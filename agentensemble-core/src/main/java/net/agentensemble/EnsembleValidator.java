package net.agentensemble;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates the configuration of an {@link Ensemble} before execution.
 *
 * Package-private. Called by {@link Ensemble#run(java.util.Map)} before the
 * workflow executor is selected and invoked.
 */
class EnsembleValidator {

    private static final Logger log = LoggerFactory.getLogger(EnsembleValidator.class);

    private final List<Agent> agents;
    private final List<Task> tasks;
    private final Workflow workflow;
    private final int maxDelegationDepth;
    private final int managerMaxIterations;
    private final ParallelErrorStrategy parallelErrorStrategy;

    EnsembleValidator(Ensemble ensemble) {
        this.agents = ensemble.getAgents();
        this.tasks = ensemble.getTasks();
        this.workflow = ensemble.getWorkflow();
        this.maxDelegationDepth = ensemble.getMaxDelegationDepth();
        this.managerMaxIterations = ensemble.getManagerMaxIterations();
        this.parallelErrorStrategy = ensemble.getParallelErrorStrategy();
    }

    void validate() {
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
            throw new ValidationException("Ensemble maxDelegationDepth must be > 0, got: " + maxDelegationDepth);
        }
    }

    private void validateManagerMaxIterations() {
        if (workflow == Workflow.HIERARCHICAL && managerMaxIterations <= 0) {
            throw new ValidationException("Ensemble managerMaxIterations must be > 0 for HIERARCHICAL workflow, got: "
                    + managerMaxIterations);
        }
    }

    private void validateParallelErrorStrategy() {
        if (workflow == Workflow.PARALLEL && parallelErrorStrategy == null) {
            throw new ValidationException("Ensemble parallelErrorStrategy must not be null for PARALLEL workflow");
        }
    }

    private void validateAgentMembership() {
        // Use identity-based lookup per design spec (docs/design/03-domain-model.md):
        // two Agent objects with identical field values must be treated as distinct agents.
        Set<Agent> registeredAgents = Collections.newSetFromMap(new IdentityHashMap<>());
        registeredAgents.addAll(agents);

        for (Task task : tasks) {
            if (!registeredAgents.contains(task.getAgent())) {
                throw new ValidationException("Task '" + task.getDescription()
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
                throw new ValidationException("Duplicate agent role '" + role + "' detected in HIERARCHICAL workflow. "
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

    private void detectCycle(Task task, Map<Task, List<Task>> graph, Set<Task> visited, Set<Task> inStack) {
        visited.add(task);
        inStack.add(task);

        List<Task> dependencies = graph.getOrDefault(task, List.of());
        for (Task dep : dependencies) {
            if (!visited.contains(dep)) {
                detectCycle(dep, graph, visited, inStack);
            } else if (inStack.contains(dep)) {
                throw new ValidationException(
                        "Circular context dependency detected involving task: '" + task.getDescription() + "'");
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
                log.warn("Agent '{}' is registered with the ensemble but not assigned to any task", agent.getRole());
            }
        }
    }
}
