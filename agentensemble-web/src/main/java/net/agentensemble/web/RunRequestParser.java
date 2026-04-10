package net.agentensemble.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.execution.RunOptions;
import net.agentensemble.tool.AgentTool;

/**
 * Converts API run requests into {@link RunConfiguration} values ready for execution.
 *
 * <p>Supports three levels of parameterization:
 * <ul>
 *   <li><strong>Level 1</strong> -- run the pre-configured template ensemble with input variable
 *       substitution. The template ensemble's tasks run as-is; {@code {variable}} placeholders in
 *       task descriptions are resolved by the {@code TemplateResolver} inside
 *       {@code Ensemble.run(inputs, options)}. Use {@link #buildFromTemplate}.</li>
 *   <li><strong>Level 2</strong> -- per-task overrides at runtime (description, expectedOutput,
 *       model, maxIterations, additionalContext, tools add/remove). Use
 *       {@link #buildFromTemplateWithOverrides}.</li>
 *   <li><strong>Level 3</strong> -- fully dynamic task creation from a JSON task list. Use
 *       {@link #buildFromDynamicTasks}.</li>
 * </ul>
 *
 * <p>Instances are stateless and safe for concurrent use.
 */
public final class RunRequestParser {

    private static final int DESCRIPTION_PREFIX_LENGTH = 50;

    private final ToolCatalog toolCatalog;
    private final ModelCatalog modelCatalog;

    /**
     * Creates a parser with the given catalog references.
     *
     * <p>Catalogs may be {@code null} when Level 1 only is needed. Level 2/3 tool and model
     * resolution requires non-null catalogs; attempting those operations with a null catalog
     * throws {@link IllegalStateException}.
     *
     * @param toolCatalog  the registered tool allowlist; may be null
     * @param modelCatalog the registered model allowlist; may be null
     */
    public RunRequestParser(ToolCatalog toolCatalog, ModelCatalog modelCatalog) {
        this.toolCatalog = toolCatalog;
        this.modelCatalog = modelCatalog;
    }

    // ========================
    // Level 1
    // ========================

    /**
     * Level 1: builds a run configuration from a template ensemble and input variables.
     *
     * <p>The template's tasks are run unchanged; {@code {variable}} placeholders are
     * resolved by the ensemble's own {@code TemplateResolver} at run time.
     *
     * @param template the pre-configured template ensemble; must not be null
     * @param inputs   the input variables to substitute; may be null (treated as empty)
     * @param options  per-run execution overrides; may be null (uses ensemble defaults)
     * @return a {@link RunConfiguration} ready for execution via {@link RunManager}
     * @throws NullPointerException if {@code template} is null
     */
    public RunConfiguration buildFromTemplate(Ensemble template, Map<String, String> inputs, RunOptions options) {
        Objects.requireNonNull(template, "template ensemble must not be null");
        Map<String, String> effectiveInputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        RunOptions effectiveOptions = options != null ? options : RunOptions.DEFAULT;
        return new RunConfiguration(template, null, effectiveInputs, effectiveOptions);
    }

    // ========================
    // Level 2
    // ========================

    /**
     * Level 2: builds a run configuration from a template ensemble with per-task overrides.
     *
     * <p>The template's task list is copied and each task named in {@code taskOverrides} is
     * replaced with a modified copy produced via {@link Task#toBuilder()}. The original task
     * objects are never mutated.
     *
     * <p>Override key matching (first match wins):
     * <ol>
     *   <li>Exact task {@code name} match (case-insensitive).</li>
     *   <li>Description prefix match -- the first {@value #DESCRIPTION_PREFIX_LENGTH} characters
     *       of the task description compared case-insensitively to the override key.</li>
     * </ol>
     *
     * <p>Supported override fields:
     * <ul>
     *   <li>{@code description} -- replaces task description (String); supports template vars.</li>
     *   <li>{@code expectedOutput} -- replaces task expected output (String).</li>
     *   <li>{@code model} -- model alias resolved from {@link ModelCatalog}; replaces
     *       {@code chatLanguageModel}.</li>
     *   <li>{@code maxIterations} -- integer (or parseable String).</li>
     *   <li>{@code additionalContext} -- String appended to the task description.</li>
     *   <li>{@code tools} -- {@code Map} with optional {@code add} and {@code remove} keys,
     *       each a {@code List<String>} of tool names resolved from {@link ToolCatalog}.</li>
     * </ul>
     *
     * @param template      the pre-configured template ensemble; must not be null
     * @param inputs        the input variables; may be null
     * @param options       per-run execution overrides; may be null
     * @param taskOverrides map of task identifier to override fields; may be null or empty
     * @return a {@link RunConfiguration} with the overridden task list
     * @throws NullPointerException     if {@code template} is null
     * @throws IllegalArgumentException if an override key matches no task, or a tool/model alias
     *                                  cannot be resolved
     * @throws IllegalStateException    if a tool or model is referenced but the corresponding
     *                                  catalog is not configured
     */
    public RunConfiguration buildFromTemplateWithOverrides(
            Ensemble template,
            Map<String, String> inputs,
            RunOptions options,
            Map<String, Map<String, Object>> taskOverrides) {
        Objects.requireNonNull(template, "template ensemble must not be null");
        Map<String, String> effectiveInputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        RunOptions effectiveOptions = options != null ? options : RunOptions.DEFAULT;

        List<Task> tasks = new ArrayList<>(template.getTasks());
        Map<String, Map<String, Object>> effectiveOverrides = (taskOverrides != null) ? taskOverrides : Map.of();

        for (Map.Entry<String, Map<String, Object>> entry : effectiveOverrides.entrySet()) {
            String overrideKey = entry.getKey();
            Map<String, Object> fields = entry.getValue();

            int taskIndex = findTaskIndex(tasks, overrideKey);
            if (taskIndex < 0) {
                throw new IllegalArgumentException("No task found matching override key: '"
                        + overrideKey
                        + "'. "
                        + "Override keys must match a task name (exact, case-insensitive) "
                        + "or the first "
                        + DESCRIPTION_PREFIX_LENGTH
                        + " characters of a task description (case-insensitive).");
            }

            Task original = tasks.get(taskIndex);
            Task overridden = applyOverrides(original, fields);
            tasks.set(taskIndex, overridden);
        }

        return new RunConfiguration(template, List.copyOf(tasks), effectiveInputs, effectiveOptions);
    }

    // ========================
    // Level 3
    // ========================

    /**
     * Level 3: builds a run configuration from a fully dynamic task list defined in JSON.
     *
     * <p>Tasks are defined as maps with the following fields (all except {@code description}
     * are optional):
     * <ul>
     *   <li>{@code name} -- logical name for context references and capability listing.</li>
     *   <li>{@code description} -- <strong>required</strong>; what the agent should do.</li>
     *   <li>{@code expectedOutput} -- what the output should look like; defaults to
     *       {@link Task#DEFAULT_EXPECTED_OUTPUT}.</li>
     *   <li>{@code tools} -- {@code List<String>} of tool names resolved from
     *       {@link ToolCatalog}.</li>
     *   <li>{@code model} -- model alias resolved from {@link ModelCatalog}.</li>
     *   <li>{@code maxIterations} -- integer (or parseable String).</li>
     *   <li>{@code context} -- {@code List<String>} of references to predecessor tasks.
     *       Each reference is {@code $name} (by task name) or {@code $N} (by 0-based index).
     *       Circular references are detected and rejected.</li>
     *   <li>{@code outputSchema} -- JSON Schema {@code Map} injected into the task's
     *       {@code expectedOutput} as a structured output instruction.</li>
     *   <li>{@code additionalContext} -- {@code String} appended to the task description.</li>
     * </ul>
     *
     * @param template the template ensemble providing default model and settings; must not be null
     * @param taskDefs the task definitions; must not be null or empty
     * @param inputs   the input variables; may be null
     * @param options  per-run execution overrides; may be null
     * @return a {@link RunConfiguration} with the dynamically built task list
     * @throws NullPointerException     if {@code template} is null
     * @throws IllegalArgumentException if {@code taskDefs} is null/empty, a required field is
     *                                  missing, a context reference is unresolvable, a circular
     *                                  dependency is detected, or a tool/model alias is unknown
     * @throws IllegalStateException    if a tool or model is referenced but the catalog is null
     */
    @SuppressWarnings("unchecked")
    public RunConfiguration buildFromDynamicTasks(
            Ensemble template, List<Map<String, Object>> taskDefs, Map<String, String> inputs, RunOptions options) {
        Objects.requireNonNull(template, "template ensemble must not be null");
        if (taskDefs == null || taskDefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Dynamic tasks list must not be null or empty; provide at least one task definition.");
        }

        Map<String, String> effectiveInputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        RunOptions effectiveOptions = options != null ? options : RunOptions.DEFAULT;

        int n = taskDefs.size();

        // Step 1: Register task names for $name reference resolution
        Map<String, Integer> nameToIndex = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Object nameObj = taskDefs.get(i).get("name");
            if (nameObj instanceof String name && !name.isBlank()) {
                nameToIndex.put(name, i);
            }
        }

        // Step 2: Parse context references into dependency index lists
        // deps.get(i) = list of indices that task i depends on
        List<List<Integer>> deps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> def = taskDefs.get(i);
            List<String> contextRefs = (List<String>) def.getOrDefault("context", List.of());
            List<Integer> taskDeps = new ArrayList<>();
            for (String ref : contextRefs) {
                taskDeps.add(resolveContextRef(ref, nameToIndex, n));
            }
            deps.add(taskDeps);
        }

        // Step 3: Topological sort (Kahn's algorithm) -- detects cycles and gives build order
        int[] buildOrder = topologicalSort(deps, n);

        // Step 4: Build tasks in topological order; accumulate built objects for context resolution
        Task[] builtTasks = new Task[n];
        for (int idx : buildOrder) {
            Map<String, Object> def = taskDefs.get(idx);
            List<Task> contextTasks = new ArrayList<>();
            for (int depIdx : deps.get(idx)) {
                contextTasks.add(builtTasks[depIdx]);
            }
            builtTasks[idx] = buildDynamicTask(def, contextTasks);
        }

        // Step 5: Collect tasks in original declaration order
        List<Task> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(builtTasks[i]);
        }

        return new RunConfiguration(template, List.copyOf(result), effectiveInputs, effectiveOptions);
    }

    /**
     * Resolves a context reference string ({@code $name} or {@code $N}) to an index in the
     * task list.
     *
     * @throws IllegalArgumentException if the reference is unknown or out of bounds
     */
    private static int resolveContextRef(String ref, Map<String, Integer> nameToIndex, int taskCount) {
        if (!ref.startsWith("$")) {
            throw new IllegalArgumentException("Context reference must start with '$'; got: '" + ref + "'.");
        }

        String identifier = ref.substring(1);

        // Try parsing as integer index ($0, $1, $2, ...)
        try {
            int idx = Integer.parseInt(identifier);
            if (idx < 0 || idx >= taskCount) {
                throw new IllegalArgumentException("Context reference '"
                        + ref
                        + "' is out of bounds; task list has "
                        + taskCount
                        + " task(s) (valid indices: 0.."
                        + (taskCount - 1)
                        + ").");
            }
            return idx;
        } catch (NumberFormatException e) {
            // Not a numeric index -- treat as name reference
        }

        // Resolve by name ($researcher, $writer, etc.)
        Integer idx = nameToIndex.get(identifier);
        if (idx == null) {
            throw new IllegalArgumentException("Unknown context reference '$"
                    + identifier
                    + "'. No task with name '"
                    + identifier
                    + "' was found in the task list.");
        }
        return idx;
    }

    /**
     * Topological sort using Kahn's algorithm.
     *
     * @param deps      deps.get(i) = list of task indices that task i depends on
     * @param n         number of tasks
     * @return array of task indices in a valid processing order (dependencies first)
     * @throws IllegalArgumentException if a circular dependency is detected
     */
    private static int[] topologicalSort(List<List<Integer>> deps, int n) {
        // Build reverse adjacency list: rdeps.get(j) = list of tasks that depend on j
        List<List<Integer>> rdeps = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rdeps.add(new ArrayList<>());
        }
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++) {
            inDegree[i] = deps.get(i).size();
            for (int dep : deps.get(i)) {
                rdeps.get(dep).add(i);
            }
        }

        // Initialize queue with tasks that have no dependencies
        Queue<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                queue.offer(i);
            }
        }

        int[] order = new int[n];
        int count = 0;
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            order[count++] = idx;
            for (int dependent : rdeps.get(idx)) {
                if (--inDegree[dependent] == 0) {
                    queue.offer(dependent);
                }
            }
        }

        if (count < n) {
            throw new IllegalArgumentException(
                    "A circular dependency was detected in the dynamic task context references. "
                            + "Ensure no task's context references form a cycle.");
        }

        return order;
    }

    /**
     * Builds a single {@link Task} from a definition map, wiring in the resolved context tasks.
     *
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    @SuppressWarnings("unchecked")
    private Task buildDynamicTask(Map<String, Object> def, List<Task> contextTasks) {
        // description is required
        Object descObj = def.get("description");
        if (!(descObj instanceof String desc) || desc.isBlank()) {
            throw new IllegalArgumentException("Dynamic task definition is missing required field 'description'. "
                    + "Each task must specify a non-blank description.");
        }

        // Handle additionalContext: append to description
        Object addCtxObj = def.get("additionalContext");
        if (addCtxObj instanceof String addCtx && !addCtx.isBlank()) {
            desc = desc + "\n\n" + addCtx;
        }

        // expectedOutput with optional outputSchema injection
        String expectedOutput =
                def.containsKey("expectedOutput") ? (String) def.get("expectedOutput") : Task.DEFAULT_EXPECTED_OUTPUT;

        Object schemaObj = def.get("outputSchema");
        if (schemaObj instanceof Map<?, ?> schemaMap) {
            String schemaJson = serializeSchema(schemaMap);
            expectedOutput = expectedOutput + "\n\nRespond with JSON matching this schema:\n" + schemaJson;
        }

        Task.TaskBuilder builder = Task.builder().description(desc).expectedOutput(expectedOutput);

        // name (optional)
        Object nameObj = def.get("name");
        if (nameObj instanceof String name && !name.isBlank()) {
            builder.name(name);
        }

        // context
        if (!contextTasks.isEmpty()) {
            builder.context(contextTasks);
        }

        // tools
        Object toolsObj = def.get("tools");
        if (toolsObj instanceof List<?> toolNames) {
            List<Object> resolvedTools = new ArrayList<>();
            for (Object t : toolNames) {
                resolvedTools.add(resolveTool((String) t));
            }
            builder.tools(resolvedTools);
        }

        // model
        Object modelObj = def.get("model");
        if (modelObj instanceof String modelAlias) {
            builder.chatLanguageModel(resolveModel(modelAlias));
        }

        // maxIterations
        Object maxIterObj = def.get("maxIterations");
        if (maxIterObj != null) {
            builder.maxIterations(toInt(maxIterObj, "maxIterations"));
        }

        return builder.build();
    }

    /** Serializes a schema map to compact JSON. Falls back to {@code toString()} on error. */
    private static String serializeSchema(Map<?, ?> schema) {
        try {
            return new ObjectMapper().writeValueAsString(schema);
        } catch (Exception e) {
            return schema.toString();
        }
    }

    /**
     * Returns the tool catalog, or {@code null} if not configured.
     */
    public ToolCatalog getToolCatalog() {
        return toolCatalog;
    }

    /**
     * Returns the model catalog, or {@code null} if not configured.
     */
    public ModelCatalog getModelCatalog() {
        return modelCatalog;
    }

    // ========================
    // Internal helpers
    // ========================

    /**
     * Finds the index of the first task in {@code tasks} that matches {@code key}.
     *
     * <p>Matching order:
     * <ol>
     *   <li>Exact task name (case-insensitive).</li>
     *   <li>Description prefix (first {@value #DESCRIPTION_PREFIX_LENGTH} chars,
     *       case-insensitive).</li>
     * </ol>
     *
     * @return the index of the matching task, or {@code -1} if not found
     */
    private static int findTaskIndex(List<Task> tasks, String key) {
        String lowerKey = key.toLowerCase();

        // Pass 1: exact name match (case-insensitive)
        for (int i = 0; i < tasks.size(); i++) {
            String name = tasks.get(i).getName();
            if (name != null && name.toLowerCase().equals(lowerKey)) {
                return i;
            }
        }

        // Pass 2: description prefix match (case-insensitive, first 50 chars)
        for (int i = 0; i < tasks.size(); i++) {
            String desc = tasks.get(i).getDescription();
            if (desc != null) {
                String prefix = desc.substring(0, Math.min(DESCRIPTION_PREFIX_LENGTH, desc.length()))
                        .toLowerCase();
                if (prefix.equals(lowerKey)) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Applies the given override fields to {@code task} and returns a new modified copy.
     * The original task is never mutated.
     */
    @SuppressWarnings("unchecked")
    private Task applyOverrides(Task task, Map<String, Object> fields) {
        Task.TaskBuilder builder = task.toBuilder();

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            switch (field) {
                case "description" -> builder.description((String) value);
                case "expectedOutput" -> builder.expectedOutput((String) value);
                case "maxIterations" -> builder.maxIterations(toInt(value, "maxIterations"));
                case "model" -> builder.chatLanguageModel(resolveModel((String) value));
                case "additionalContext" -> {
                    String ctx = (String) value;
                    String newDesc = task.getDescription() + "\n\n" + ctx;
                    builder.description(newDesc);
                }
                case "tools" -> {
                    Map<String, Object> toolsMap = (Map<String, Object>) value;
                    List<Object> currentTools = new ArrayList<>(task.getTools());

                    if (toolsMap.containsKey("add")) {
                        List<String> toAdd = (List<String>) toolsMap.get("add");
                        for (String toolName : toAdd) {
                            currentTools.add(resolveTool(toolName));
                        }
                    }

                    if (toolsMap.containsKey("remove")) {
                        List<String> toRemove = (List<String>) toolsMap.get("remove");
                        for (String toolName : toRemove) {
                            AgentTool resolved = resolveTool(toolName);
                            currentTools.removeIf(t -> t == resolved);
                        }
                    }

                    builder.tools(List.copyOf(currentTools));
                }
                default -> {
                    // Unknown override fields are silently ignored for forward compatibility
                }
            }
        }

        return builder.build();
    }

    /**
     * Converts a value to {@code int}. Accepts {@link Integer}, {@link Long}, or a
     * {@link String} that is parseable as an integer.
     */
    private static int toInt(Object value, String fieldName) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            return l.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Override field '" + fieldName + "' must be an integer; got: " + s);
            }
        }
        throw new IllegalArgumentException("Override field '" + fieldName + "' must be an integer; got: "
                + value.getClass().getSimpleName());
    }

    /**
     * Resolves a model alias from the model catalog.
     *
     * @throws IllegalStateException    if no model catalog is configured
     * @throws IllegalArgumentException if the alias is not found in the catalog
     */
    private ChatModel resolveModel(String alias) {
        if (modelCatalog == null) {
            throw new IllegalStateException("ModelCatalog is not configured. Configure a ModelCatalog on WebDashboard "
                    + "to use model overrides in API run requests.");
        }
        return modelCatalog
                .find(alias)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model alias: '"
                        + alias
                        + "'. Available models: "
                        + modelCatalog.list().stream()
                                .map(ModelCatalog.ModelInfo::alias)
                                .toList()));
    }

    /**
     * Resolves a tool name from the tool catalog.
     *
     * @throws IllegalStateException    if no tool catalog is configured
     * @throws IllegalArgumentException if the tool name is not found in the catalog
     */
    private AgentTool resolveTool(String toolName) {
        if (toolCatalog == null) {
            throw new IllegalStateException("ToolCatalog is not configured. Configure a ToolCatalog on WebDashboard "
                    + "to use tool references in API run requests.");
        }
        return toolCatalog
                .find(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: '"
                        + toolName
                        + "'. Available tools: "
                        + toolCatalog.list().stream()
                                .map(ToolCatalog.ToolInfo::name)
                                .toList()));
    }

    // ========================
    // RunConfiguration
    // ========================

    /**
     * A validated, ready-to-execute run configuration produced by {@link RunRequestParser}.
     *
     * @param template      the template ensemble to run (always non-null)
     * @param overrideTasks when non-null, replaces the template's task list at execution time;
     *                      null for Level 1 runs (template tasks used as-is)
     * @param inputs        the resolved input variables (never null, may be empty)
     * @param options       the effective run options (never null)
     */
    public record RunConfiguration(
            Ensemble template, List<Task> overrideTasks, Map<String, String> inputs, RunOptions options) {}
}
