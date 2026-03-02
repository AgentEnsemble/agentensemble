package io.agentensemble;

import io.agentensemble.ensemble.EnsembleOutput;
import io.agentensemble.exception.ValidationException;
import io.agentensemble.workflow.Workflow;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** When true, elevates execution logging to INFO level. */
    @Builder.Default
    private final boolean verbose = false;

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
        validate();
        // Template resolution and WorkflowExecutor delegation are wired in Issue #12.
        throw new UnsupportedOperationException(
                "Ensemble execution not yet wired. WorkflowExecutor will be connected in Issue #12.");
    }

    // ========================
    // Validation
    // ========================

    private void validate() {
        validateTasksNotEmpty();
        validateAgentsNotEmpty();
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
