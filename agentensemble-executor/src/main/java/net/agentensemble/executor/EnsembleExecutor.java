package net.agentensemble.executor;

import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

/**
 * Executes an {@link EnsembleRequest} in-process by building and running a full multi-task
 * AgentEnsemble.
 *
 * <p>Use {@code EnsembleExecutor} when you want AgentEnsemble's internal orchestration engine
 * (sequential, parallel, or hierarchical) to handle a complete pipeline within a single
 * external-workflow activity. For finer-grained control -- where each AgentEnsemble task maps
 * to a separate Temporal activity -- use {@link TaskExecutor} instead.
 *
 * <h2>Template variable resolution</h2>
 *
 * <p>Each task's {@code description} and {@code expectedOutput} are pre-resolved before the task
 * is submitted to the ensemble. The resolution order for a given task is:
 * <ol>
 *   <li>Global inputs from {@code EnsembleRequest.getInputs()} (lowest precedence)</li>
 *   <li>Per-task context from {@code TaskRequest.getContext()}</li>
 *   <li>Per-task inputs from {@code TaskRequest.getInputs()} (highest precedence)</li>
 * </ol>
 * <p>Because each task's templates are resolved independently with its own merged map, per-task
 * context and inputs do not leak across tasks -- a {@code {research}} variable set on task 1
 * will not appear in task 2's resolved description unless task 2 also declares it.
 *
 * <h2>Usage in a Temporal Activity</h2>
 * <pre>
 * public class ResearchPipelineActivityImpl implements ResearchPipelineActivity {
 *
 *     private final EnsembleExecutor executor = new EnsembleExecutor(
 *         SimpleModelProvider.of(OpenAiChatModel.builder()
 *             .apiKey(System.getenv("OPENAI_API_KEY"))
 *             .modelName("gpt-4o-mini")
 *             .build())
 *     );
 *
 *     {@literal @}Override
 *     public EnsembleResult run(EnsembleRequest request) {
 *         return executor.execute(request, Activity.getExecutionContext()::heartbeat);
 *     }
 * }
 * </pre>
 *
 * <h2>Building a request</h2>
 * <pre>
 * EnsembleRequest request = EnsembleRequest.builder()
 *     .task(TaskRequest.of("Research {topic}", "A comprehensive research summary"))
 *     .task(TaskRequest.of("Write a blog post about {topic}", "A polished blog post"))
 *     .workflow("SEQUENTIAL")
 *     .inputs(Map.of("topic", "Artificial Intelligence"))
 *     .build();
 *
 * EnsembleResult result = executor.execute(request);
 * System.out.println(result.finalOutput());
 * </pre>
 *
 * <p>Thread safety: {@code EnsembleExecutor} is thread-safe when the underlying
 * {@link ModelProvider} and {@link ToolProvider} are thread-safe.
 */
public class EnsembleExecutor {

    private final ModelProvider modelProvider;
    private final ToolProvider toolProvider;

    /**
     * Creates an {@code EnsembleExecutor} that uses the given model provider. No tools are
     * available to agents; use this constructor for LLM-only pipelines.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @throws NullPointerException if modelProvider is null
     */
    public EnsembleExecutor(ModelProvider modelProvider) {
        this(modelProvider, SimpleToolProvider.empty());
    }

    /**
     * Creates an {@code EnsembleExecutor} with both a model provider and a tool provider.
     *
     * @param modelProvider provides the {@link ChatModel} for each task; must not be null
     * @param toolProvider  resolves tool instances by name from {@code AgentSpec.getToolNames()};
     *                      must not be null
     * @throws NullPointerException if either argument is null
     */
    public EnsembleExecutor(ModelProvider modelProvider, ToolProvider toolProvider) {
        this.modelProvider = Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        this.toolProvider = Objects.requireNonNull(toolProvider, "toolProvider must not be null");
    }

    /**
     * Executes the given ensemble request synchronously without heartbeating.
     *
     * <p>Equivalent to {@code execute(request, null)}.
     *
     * @param request the ensemble to run; must not be null and must contain at least one task
     * @return the ensemble result; never null
     * @throws NullPointerException     if request is null
     * @throws IllegalArgumentException if the request contains no tasks
     */
    public EnsembleResult execute(EnsembleRequest request) {
        return execute(request, null);
    }

    /**
     * Executes the given ensemble request synchronously, emitting {@link HeartbeatDetail} events
     * to the given consumer throughout execution.
     *
     * <p>For Temporal: {@code executor.execute(request, Activity.getExecutionContext()::heartbeat)}.
     * Heartbeats keep the activity alive across long-running multi-task LLM pipelines.
     *
     * @param request           the ensemble to run; must not be null and must contain at least one task
     * @param heartbeatConsumer receives {@link HeartbeatDetail} instances during execution;
     *                          null disables heartbeating
     * @return the ensemble result; never null
     * @throws NullPointerException     if request is null
     * @throws IllegalArgumentException if the request contains no tasks
     */
    public EnsembleResult execute(EnsembleRequest request, Consumer<Object> heartbeatConsumer) {
        Objects.requireNonNull(request, "request must not be null");

        if (request.getTasks() == null || request.getTasks().isEmpty()) {
            throw new IllegalArgumentException("EnsembleRequest must contain at least one task");
        }

        ChatModel defaultModel = resolveModel(request.getModelName());

        var ensembleBuilder = Ensemble.builder().chatLanguageModel(defaultModel);

        // Build each task. Templates are pre-resolved per task using a merged map of:
        //   global inputs (lowest) < per-task context < per-task inputs (highest).
        // This prevents per-task inputs from leaking into other tasks via the Ensemble's
        // single shared input map.
        for (TaskRequest taskRequest : request.getTasks()) {
            ChatModel taskModel =
                    taskRequest.getModelName() != null ? modelProvider.get(taskRequest.getModelName()) : defaultModel;

            Map<String, String> mergedInputs = buildMergedInputs(request.getInputs(), taskRequest);
            ensembleBuilder.task(buildTask(taskRequest, taskModel, mergedInputs));
        }

        // Apply workflow mode when explicitly specified.
        if (request.getWorkflow() != null) {
            ensembleBuilder.workflow(Workflow.valueOf(request.getWorkflow()));
        }

        if (heartbeatConsumer != null) {
            ensembleBuilder.listener(new HeartbeatEnsembleListener(heartbeatConsumer));
        }

        EnsembleOutput output = ensembleBuilder.build().run();

        return toEnsembleResult(output);
    }

    // ========================
    // Helpers
    // ========================

    private ChatModel resolveModel(String modelName) {
        return modelName != null ? modelProvider.get(modelName) : modelProvider.getDefault();
    }

    /**
     * Builds the per-task merged input map in precedence order:
     * global inputs (lowest) < per-task context < per-task inputs (highest).
     */
    private static Map<String, String> buildMergedInputs(Map<String, String> globalInputs, TaskRequest taskRequest) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (globalInputs != null) {
            merged.putAll(globalInputs);
        }
        if (taskRequest.getContext() != null) {
            merged.putAll(taskRequest.getContext());
        }
        if (taskRequest.getInputs() != null) {
            merged.putAll(taskRequest.getInputs());
        }
        return Map.copyOf(merged);
    }

    /**
     * Resolves {@code {variable}} placeholders in the given template string using the provided
     * variable map. Returns the template unchanged when it is null or the map is empty.
     */
    static String resolveTemplate(String template, Map<String, String> vars) {
        if (template == null || vars.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private Task buildTask(TaskRequest request, ChatModel model, Map<String, String> mergedInputs) {
        // Pre-resolve templates so per-task variables do not leak into other tasks.
        String description = resolveTemplate(request.getDescription(), mergedInputs);

        // When expectedOutput is null fall back to Task.DEFAULT_EXPECTED_OUTPUT so that
        // TaskRequest.of(String description) (single-arg factory) is always executable.
        String expectedOutput = request.getExpectedOutput() != null
                ? resolveTemplate(request.getExpectedOutput(), mergedInputs)
                : Task.DEFAULT_EXPECTED_OUTPUT;

        var taskBuilder = Task.builder().description(description).expectedOutput(expectedOutput);

        if (request.getAgent() != null) {
            taskBuilder.agent(buildAgent(request.getAgent(), model));
        }

        return taskBuilder.build();
    }

    private Agent buildAgent(AgentSpec spec, ChatModel model) {
        var agentBuilder =
                Agent.builder().role(spec.getRole()).goal(spec.getGoal()).llm(model);

        if (spec.getBackground() != null) {
            agentBuilder.background(spec.getBackground());
        }
        if (spec.getMaxIterations() != null) {
            agentBuilder.maxIterations(spec.getMaxIterations());
        }

        List<String> toolNames = spec.getToolNames();
        if (toolNames != null && !toolNames.isEmpty()) {
            agentBuilder.tools(toolProvider.get(toolNames));
        }

        return agentBuilder.build();
    }

    private static EnsembleResult toEnsembleResult(EnsembleOutput output) {
        List<String> taskOutputStrings = new ArrayList<>();
        if (output.getTaskOutputs() != null) {
            for (TaskOutput taskOutput : output.getTaskOutputs()) {
                taskOutputStrings.add(taskOutput.getRaw());
            }
        }

        long durationMs =
                output.getTotalDuration() != null ? output.getTotalDuration().toMillis() : 0L;
        String exitReason =
                output.getExitReason() != null ? output.getExitReason().name() : "COMPLETED";

        return new EnsembleResult(
                output.getRaw(), List.copyOf(taskOutputStrings), durationMs, output.getTotalToolCalls(), exitReason);
    }
}
