package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.config.TemplateResolver;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.workflow.HierarchicalWorkflowExecutor;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

        } catch (Exception e) {
            log.error("Ensemble run failed: {}", e.getMessage());
            throw e;
        } finally {
            MDC.remove("ensemble.id");
        }
    }

    private List<Task> resolveTasks(Map<String, String> inputs) {
        return tasks.stream()
                .map(task -> task.toBuilder()
                        .description(TemplateResolver.resolve(task.getDescription(), inputs))
                        .expectedOutput(TemplateResolver.resolve(task.getExpectedOutput(), inputs))
                        .build())
                .toList();
    }

    private WorkflowExecutor selectExecutor() {
        return switch (workflow) {
            case SEQUENTIAL -> new SequentialWorkflowExecutor(agents, maxDelegationDepth);
            case HIERARCHICAL -> new HierarchicalWorkflowExecutor(
                    resolveManagerLlm(), agents, managerMaxIterations, maxDelegationDepth);
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
        validateAgentMembership();
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

    private void validateAgentMembership() {
        // Build identity set of registered agents
        Set<Agent> registeredAgents = new HashSet<>(agents);

        for (Task task : tasks) {
            if (!registeredAgents.contains(task.getAgent())) {
                throw new ValidationException(
                        "Task '" + task.getDescription()
                        + "' references agent '" + task.getAgent().getRole()
                        + "' which is not in the ensemble's agent list");
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
        // Hierarchical workflow: the Manager agent decides execution order at runtime.
        // Context ordering is not validated -- the manager handles task sequencing.
        if (workflow == Workflow.HIERARCHICAL) {
            return;
        }

        // For sequential workflow: each context task must appear earlier in the tasks list
        List<Task> executedSoFar = new ArrayList<>();
        for (Task task : tasks) {
            for (Task contextTask : task.getContext()) {
                if (!executedSoFar.contains(contextTask)) {
                    throw new ValidationException(
                            "Task '" + task.getDescription()
                            + "' references context task '" + contextTask.getDescription()
                            + "' which appears later in the task list. "
                            + "Context tasks must be executed before the tasks that reference them.");
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
