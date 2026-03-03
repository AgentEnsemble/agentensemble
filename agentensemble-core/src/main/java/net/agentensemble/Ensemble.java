package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.config.TemplateResolver;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.workflow.HierarchicalWorkflowExecutor;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.ParallelWorkflowExecutor;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import net.agentensemble.workflow.Workflow;
import net.agentensemble.workflow.WorkflowExecutor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An ensemble of agents collaborating on a sequence of tasks.
 *
 * The ensemble orchestrates task execution according to the configured workflow
 * strategy, passing context between tasks as declared in each task's context list.
 *
 * Example:
 * <pre>
 * EnsembleOutput result = Ensemble.builder()
 *     .agent(researcher)
 *     .agent(writer)
 *     .task(researchTask)
 *     .task(writeTask)
 *     .workflow(Workflow.SEQUENTIAL)
 *     .build()
 *     .run();
 * </pre>
 */
@Builder
@Getter
public class Ensemble {

    private static final Logger log = LoggerFactory.getLogger(Ensemble.class);

    /** All agents participating in this ensemble. */
    @Singular
    private final List<Agent> agents;

    /** All tasks to execute. */
    @Singular
    private final List<Task> tasks;

    /** How tasks are executed. Default: SEQUENTIAL. */
    @Builder.Default
    private final Workflow workflow = Workflow.SEQUENTIAL;

    /**
     * Optional LLM for the Manager agent in hierarchical workflow.
     * If not set, defaults to the first registered agent's LLM.
     */
    private final ChatModel managerLlm;

    /**
     * Maximum number of tool call iterations for the Manager agent in hierarchical workflow.
     * Default: 20. Must be greater than zero.
     */
    @Builder.Default
    private final int managerMaxIterations = 20;

    /** When true, elevates execution logging to INFO level. */
    @Builder.Default
    private final boolean verbose = false;

    /**
     * Optional memory configuration.
     * When set, short-term, long-term, and/or entity memory are enabled.
     * Default: null (no memory).
     */
    private final EnsembleMemory memory;

    /**
     * Maximum delegation depth for agent-to-agent delegation.
     * Prevents infinite recursion when agents have {@code allowDelegation = true}.
     * Only relevant when at least one agent has {@code allowDelegation = true}.
     * Default: 3. Must be greater than zero.
     */
    @Builder.Default
    private final int maxDelegationDepth = 3;

    /**
     * Error handling strategy for parallel workflow execution.
     * Only relevant when {@code workflow = Workflow.PARALLEL}.
     * Default: {@link ParallelErrorStrategy#FAIL_FAST}.
     */
    @Builder.Default
    private final ParallelErrorStrategy parallelErrorStrategy = ParallelErrorStrategy.FAIL_FAST;

    /**
     * Execute the ensemble's tasks with no input variables.
     *
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run() {
        return run(Map.of());
    }

    /**
     * Execute the ensemble's tasks with template variable substitution.
     *
     * Variables like {topic} in task descriptions and expected outputs are
     * replaced with values from the inputs map before execution.
     *
     * @param inputs map of variable names to replacement values
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run(Map<String, String> inputs) {
        String ensembleId = UUID.randomUUID().toString();
        MDC.put("ensemble.id", ensembleId);

        try {
            log.info("Ensemble run started | Workflow: {} | Tasks: {} | Agents: {}",
                    workflow, tasks.size(), agents.size());
            log.debug("Input variables: {}", inputs);

            // Step 1: Validate configuration
            validate();

            // Step 2: Resolve template variables in task descriptions and expected outputs
            List<Task> resolvedTasks = resolveTasks(inputs);

            // Step 3: Create memory context for this run (no-op when memory is not configured)
            MemoryContext memoryContext = memory != null
                    ? MemoryContext.from(memory)
                    : MemoryContext.disabled();

            if (memoryContext.isActive()) {
                log.info("Memory enabled | shortTerm={} longTerm={} entityMemory={}",
                        memoryContext.hasShortTerm(),
                        memoryContext.hasLongTerm(),
                        memoryContext.hasEntityMemory());
            }

            // Step 4: Select and execute WorkflowExecutor
            WorkflowExecutor executor = selectExecutor();
            EnsembleOutput output = executor.execute(resolvedTasks, verbose, memoryContext);

            log.info("Ensemble run completed | Duration: {} | Tasks: {} | Tool calls: {}",
                    output.getTotalDuration(), output.getTaskOutputs().size(), output.getTotalToolCalls());

            return output;

        } catch (ValidationException e) {
            log.warn("Ensemble validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Ensemble run failed", e);
            throw e;
        } finally {
            MDC.remove("ensemble.id");
        }
    }

    /**
     * Resolve template variables in task descriptions and expected outputs.
     *
     * Two-pass approach: first resolve descriptions/expectedOutputs and build an
     * original-to-resolved mapping, then rewrite context lists so they reference the
     * resolved Task instances. Without the second pass, context lookups in
     * SequentialWorkflowExecutor would fail when a task with a template variable is
     * also referenced by another task's context list (value equality would not match
     * the original and resolved copies).
     */
    private List<Task> resolveTasks(Map<String, String> inputs) {
        // Pass 1: resolve description and expectedOutput; build original -> resolved map.
        // Use identity-based map because Task uses value equality and two pre-resolution
        // tasks with different descriptions must be treated as distinct keys.
        Map<Task, Task> originalToResolved = new IdentityHashMap<>();
        for (Task task : tasks) {
            Task resolved = task.toBuilder()
                    .description(TemplateResolver.resolve(task.getDescription(), inputs))
                    .expectedOutput(TemplateResolver.resolve(task.getExpectedOutput(), inputs))
                    .build();
            originalToResolved.put(task, resolved);
        }

        // Pass 2: rewrite context lists to reference resolved instances.
        // IMPORTANT: update originalToResolved as each final task is created so that
        // later tasks in this pass (e.g., task D depending on task B) pick up the
        // fully-rewritten version (with correct context) rather than the pass-1 draft.
        // Without this update, a diamond A->B->D / A->C->D would produce D with stale
        // context references not present in the result list, breaking DAG lookup.
        List<Task> result = new ArrayList<>(tasks.size());
        for (Task original : tasks) {
            Task resolvedBase = originalToResolved.get(original);
            List<Task> originalContext = original.getContext();
            if (originalContext.isEmpty()) {
                result.add(resolvedBase);
                // resolvedBase == originalToResolved.get(original) already; no update needed
            } else {
                List<Task> resolvedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    resolvedContext.add(originalToResolved.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask = resolvedBase.toBuilder().context(resolvedContext).build();
                // Update the mapping so subsequent tasks in this pass see the final version
                originalToResolved.put(original, finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    private WorkflowExecutor selectExecutor() {
        return switch (workflow) {
            case SEQUENTIAL -> new SequentialWorkflowExecutor(agents, maxDelegationDepth);
            case HIERARCHICAL -> new HierarchicalWorkflowExecutor(
                    resolveManagerLlm(), agents, managerMaxIterations, maxDelegationDepth);
            case PARALLEL -> new ParallelWorkflowExecutor(agents, maxDelegationDepth,
                    parallelErrorStrategy);
        };
    }

    private ChatModel resolveManagerLlm() {
        return managerLlm != null ? managerLlm : agents.get(0).getLlm();
    }

    // ========================
    // Validation
    // ========================

    private void validate() {
        validateTasksNotEmpty();
        validateAgentsNotEmpty();
        validateMaxDelegationDepth();
        validateManagerMaxIterations();
        validateParallelErrorStrategy();
        validateAgentMembership();
        validateHierarchicalRoles();
        validateNoCircularContextDependencies();
        validateContextOrdering();
        warnUnusedAgents();
    }

    private void validateTasksNotEmpty() {
        if (tasks == null || tasks.isEmpty()) {
            throw new ValidationException("Ensemble must have at least one task");
        }
    }

    private void validateAgentsNotEmpty() {
        if (agents == null || agents.isEmpty()) {
            throw new ValidationException("Ensemble must have at least one agent");
        }
    }

    private void validateMaxDelegationDepth() {
        if (maxDelegationDepth <= 0) {
            throw new ValidationException(
                    "Ensemble maxDelegationDepth must be > 0, got: " + maxDelegationDepth);
        }
    }

    private void validateManagerMaxIterations() {
        if (workflow == Workflow.HIERARCHICAL && managerMaxIterations <= 0) {
            throw new ValidationException(
                    "Ensemble managerMaxIterations must be > 0 for HIERARCHICAL workflow, got: "
                    + managerMaxIterations);
        }
    }

    private void validateParallelErrorStrategy() {
        if (workflow == Workflow.PARALLEL && parallelErrorStrategy == null) {
            throw new ValidationException(
                    "Ensemble parallelErrorStrategy must not be null for PARALLEL workflow");
        }
    }

    private void validateAgentMembership() {
        // Use identity-based lookup per design spec (docs/design/03-domain-model.md):
        // two Agent objects with identical field values must be treated as distinct agents.
        Set<Agent> registeredAgents = Collections.newSetFromMap(new IdentityHashMap<>());
        registeredAgents.addAll(agents);

        for (Task task : tasks) {
            if (!registeredAgents.contains(task.getAgent())) {
                throw new ValidationException(
                        "Task '" + task.getDescription()
                        + "' references agent '" + task.getAgent().getRole()
                        + "' which is not in the ensemble's agent list");
            }
        }
    }

    /**
     * For HIERARCHICAL workflow: validate that no registered agent uses the reserved
     * "Manager" role (which is auto-assigned to the virtual manager agent), and that
     * all agent roles are unique (case-insensitively) to avoid ambiguous delegation.
     */
    private void validateHierarchicalRoles() {
        if (workflow != Workflow.HIERARCHICAL) {
            return;
        }
        Set<String> seenRoles = new HashSet<>();
        for (Agent agent : agents) {
            String role = agent.getRole();
            if ("Manager".equalsIgnoreCase(role)) {
                throw new ValidationException(
                        "Agent role 'Manager' is reserved for the virtual manager in HIERARCHICAL "
                        + "workflow. Choose a different role for agent '" + role + "'.");
            }
            if (!seenRoles.add(role.toLowerCase(Locale.ROOT))) {
                throw new ValidationException(
                        "Duplicate agent role '" + role + "' detected in HIERARCHICAL workflow. "
                        + "All agent roles must be unique (case-insensitive) to avoid ambiguous delegation.");
            }
        }
    }

    private void validateNoCircularContextDependencies() {
        // Build adjacency map: task -> tasks it depends on (its context)
        Map<Task, List<Task>> graph = new HashMap<>();
        for (Task task : tasks) {
            graph.put(task, task.getContext());
        }
        // Also include tasks only referenced in context (not in the tasks list)
        for (Task task : tasks) {
            for (Task ctx : task.getContext()) {
                graph.putIfAbsent(ctx, ctx.getContext());
            }
        }

        // DFS cycle detection
        Set<Task> visited = new HashSet<>();
        Set<Task> inStack = new HashSet<>();
        for (Task task : graph.keySet()) {
            if (!visited.contains(task)) {
                detectCycle(task, graph, visited, inStack);
            }
        }
    }

    private void detectCycle(Task task, Map<Task, List<Task>> graph,
            Set<Task> visited, Set<Task> inStack) {
        visited.add(task);
        inStack.add(task);

        List<Task> dependencies = graph.getOrDefault(task, List.of());
        for (Task dep : dependencies) {
            if (!visited.contains(dep)) {
                detectCycle(dep, graph, visited, inStack);
            } else if (inStack.contains(dep)) {
                throw new ValidationException(
                        "Circular context dependency detected involving task: '"
                        + task.getDescription() + "'");
            }
        }

        inStack.remove(task);
    }

    private void validateContextOrdering() {
        // Hierarchical and parallel workflows do not require sequential task ordering:
        // HIERARCHICAL: the Manager agent decides execution order at runtime.
        // PARALLEL: the dependency graph (from context declarations) drives execution order.
        // In both cases, the user-supplied task list order is irrelevant to execution.
        if (workflow == Workflow.HIERARCHICAL || workflow == Workflow.PARALLEL) {
            return;
        }

        // Use identity-based membership to be consistent with resolveTasks() and
        // validateAgentMembership(): two Task objects with identical field values must
        // be treated as distinct tasks. A value-equal but identity-distinct context task
        // would pass equals()-based validation but then fail remapping in resolveTasks().
        Set<Task> executedSoFar = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Task> ensureTaskSet = Collections.newSetFromMap(new IdentityHashMap<>());
        ensureTaskSet.addAll(tasks);

        for (Task task : tasks) {
            for (Task contextTask : task.getContext()) {
                if (!executedSoFar.contains(contextTask)) {
                    boolean inEnsembleTasks = ensureTaskSet.contains(contextTask);
                    String message = inEnsembleTasks
                            ? "Task '" + task.getDescription()
                              + "' references context task '" + contextTask.getDescription()
                              + "' which appears later in the task list. "
                              + "Context tasks must be executed before the tasks that reference them."
                            : "Task '" + task.getDescription()
                              + "' references context task '" + contextTask.getDescription()
                              + "' which is not in the ensemble's task list. "
                              + "All context tasks must be included in the ensemble before they can be referenced.";
                    throw new ValidationException(message);
                }
            }
            executedSoFar.add(task);
        }
    }

    private void warnUnusedAgents() {
        Set<Agent> usedAgents = new HashSet<>();
        for (Task task : tasks) {
            usedAgents.add(task.getAgent());
        }
        for (Agent agent : agents) {
            if (!usedAgents.contains(agent)) {
                log.warn("Agent '{}' is registered with the ensemble but not assigned to any task",
                        agent.getRole());
            }
        }
    }
}
