package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.workflow.HierarchicalConstraints;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.Workflow;

/**
 * Validates the configuration of an {@link Ensemble} before execution.
 *
 * <p>Package-private. Called by {@link Ensemble#run(java.util.Map)} before the
 * workflow executor is selected and invoked.
 *
 * <p>In v2, agents are no longer required on the ensemble -- they may be synthesized
 * at runtime. Validation ensures each task has an LLM source (explicit agent, task-level
 * {@code chatLanguageModel}, or ensemble-level {@code chatLanguageModel}).
 *
 * <p>When {@code workflow} is {@code null}, the effective workflow is inferred from the
 * task context declarations before workflow-specific validation runs:
 * <ul>
 *   <li>Any context dependency between ensemble tasks -> PARALLEL</li>
 *   <li>No such dependency -> SEQUENTIAL</li>
 * </ul>
 */
class EnsembleValidator {

    private final List<Task> tasks;
    private final ChatModel ensembleLlm;
    private final RateLimit rateLimit;
    private final Workflow workflow;
    private final int maxDelegationDepth;
    private final int managerMaxIterations;
    private final ParallelErrorStrategy parallelErrorStrategy;
    private final HierarchicalConstraints hierarchicalConstraints;

    EnsembleValidator(Ensemble ensemble) {
        this.tasks = ensemble.getTasks();
        this.ensembleLlm = ensemble.getChatLanguageModel();
        this.rateLimit = ensemble.getRateLimit();
        this.workflow = ensemble.getWorkflow();
        this.maxDelegationDepth = ensemble.getMaxDelegationDepth();
        this.managerMaxIterations = ensemble.getManagerMaxIterations();
        this.parallelErrorStrategy = ensemble.getParallelErrorStrategy();
        this.hierarchicalConstraints = ensemble.getHierarchicalConstraints();
    }

    void validate() {
        validateTasksNotEmpty();
        validateRateLimitConfiguration();
        validateTasksHaveLlm();
        validateMaxDelegationDepth();
        // Workflow-specific validations use the resolved (possibly inferred) workflow
        Workflow effective = resolveWorkflow();
        validateManagerMaxIterations(effective);
        validateParallelErrorStrategy(effective);
        validateHierarchicalRoles(effective);
        validateHierarchicalConstraints(effective);
        validateNoCircularContextDependencies();
        validateContextOrdering(effective);
    }

    private void validateTasksNotEmpty() {
        if (tasks == null || tasks.isEmpty()) {
            throw new ValidationException("Ensemble must have at least one task");
        }
    }

    /**
     * Validate that every task has an LLM source, in this priority order:
     * <ol>
     *   <li>Task has an explicit agent (which carries its own LLM)</li>
     *   <li>Task has a {@code chatLanguageModel} set directly</li>
     *   <li>The ensemble has a {@code chatLanguageModel} (used for synthesis)</li>
     * </ol>
     *
     * <p>If none of the above are present for a task, a {@link ValidationException} is thrown.
     */
    private void validateTasksHaveLlm() {
        for (Task task : tasks) {
            boolean hasLlm =
                    (task.getAgent() != null) || (task.getChatLanguageModel() != null) || (ensembleLlm != null);
            if (!hasLlm) {
                throw new ValidationException("Task '" + task.getDescription() + "' has no LLM available. "
                        + "Provide an explicit agent, a task-level chatLanguageModel, "
                        + "or an ensemble-level chatLanguageModel.");
            }
        }
    }

    /**
     * Validate that the ensemble-level {@code rateLimit} is not set without a corresponding
     * {@code chatLanguageModel}. When {@code rateLimit} is configured but {@code chatLanguageModel}
     * is null, the rate limit is silently ignored at runtime, which is almost certainly a
     * misconfiguration.
     */
    private void validateRateLimitConfiguration() {
        if (rateLimit != null && ensembleLlm == null) {
            throw new ValidationException("Ensemble rateLimit is configured but chatLanguageModel is null. "
                    + "An ensemble-level chatLanguageModel is required when rateLimit is set.");
        }
    }

    private void validateMaxDelegationDepth() {
        if (maxDelegationDepth <= 0) {
            throw new ValidationException("Ensemble maxDelegationDepth must be > 0, got: " + maxDelegationDepth);
        }
    }

    private void validateManagerMaxIterations(Workflow effective) {
        if (effective == Workflow.HIERARCHICAL && managerMaxIterations <= 0) {
            throw new ValidationException("Ensemble managerMaxIterations must be > 0 for HIERARCHICAL workflow, got: "
                    + managerMaxIterations);
        }
    }

    private void validateParallelErrorStrategy(Workflow effective) {
        if (effective == Workflow.PARALLEL && parallelErrorStrategy == null) {
            throw new ValidationException("Ensemble parallelErrorStrategy must not be null for PARALLEL workflow");
        }
    }

    /**
     * For HIERARCHICAL workflow: validate that no task's explicit agent uses the reserved
     * "Manager" role, and that all explicit-agent roles are unique (case-insensitively).
     *
     * <p>Tasks without an explicit agent are excluded from this check since their agents
     * are synthesized at runtime and will not conflict with the Manager role.
     */
    private void validateHierarchicalRoles(Workflow effective) {
        if (effective != Workflow.HIERARCHICAL) {
            return;
        }
        Set<String> seenRoles = new HashSet<>();
        for (Task task : tasks) {
            if (task.getAgent() == null) {
                continue; // synthesized agents are checked at runtime
            }
            String role = task.getAgent().getRole();
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

    /**
     * Validates the optional {@link HierarchicalConstraints} configuration against the
     * explicitly registered agents (derived from tasks that have an explicit agent set).
     *
     * <p>Only runs when {@code effective == HIERARCHICAL} and constraints are non-null.
     */
    private void validateHierarchicalConstraints(Workflow effective) {
        if (effective != Workflow.HIERARCHICAL || hierarchicalConstraints == null) {
            return;
        }

        Set<String> registeredRoles = new HashSet<>();
        for (Task task : tasks) {
            if (task.getAgent() != null) {
                registeredRoles.add(task.getAgent().getRole());
            }
        }

        Set<String> requiredWorkers = hierarchicalConstraints.getRequiredWorkers();
        Set<String> allowedWorkers = hierarchicalConstraints.getAllowedWorkers();

        for (String role : requiredWorkers) {
            if (!registeredRoles.contains(role)) {
                throw new ValidationException("HierarchicalConstraints.requiredWorkers contains role '" + role
                        + "' which is not in the ensemble's registered agents.");
            }
        }

        for (String role : allowedWorkers) {
            if (!registeredRoles.contains(role)) {
                throw new ValidationException("HierarchicalConstraints.allowedWorkers contains role '" + role
                        + "' which is not in the ensemble's registered agents.");
            }
        }

        if (!allowedWorkers.isEmpty()) {
            for (String requiredRole : requiredWorkers) {
                if (!allowedWorkers.contains(requiredRole)) {
                    throw new ValidationException(
                            "HierarchicalConstraints.requiredWorkers contains role '" + requiredRole
                                    + "' which is not present in allowedWorkers. Required workers must be "
                                    + "a subset of allowedWorkers when allowedWorkers is non-empty.");
                }
            }
        }

        for (Map.Entry<String, Integer> entry :
                hierarchicalConstraints.getMaxCallsPerWorker().entrySet()) {
            String role = entry.getKey();
            Integer cap = entry.getValue();
            if (!registeredRoles.contains(role)) {
                throw new ValidationException("HierarchicalConstraints.maxCallsPerWorker contains role '" + role
                        + "' which is not in the ensemble's registered agents.");
            }
            if (cap == null || cap <= 0) {
                throw new ValidationException("HierarchicalConstraints.maxCallsPerWorker value for role '" + role
                        + "' must be > 0, got: " + cap);
            }
        }

        if (hierarchicalConstraints.getGlobalMaxDelegations() < 0) {
            throw new ValidationException("HierarchicalConstraints.globalMaxDelegations must be >= 0, got: "
                    + hierarchicalConstraints.getGlobalMaxDelegations());
        }

        List<List<String>> stages = hierarchicalConstraints.getRequiredStages();
        Map<String, Integer> roleFirstStageIndex = new HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            for (String role : stages.get(i)) {
                if (!registeredRoles.contains(role)) {
                    throw new ValidationException("HierarchicalConstraints.requiredStages[" + i + "] contains role '"
                            + role + "' which is not in the ensemble's registered agents.");
                }
                Integer existingIndex = roleFirstStageIndex.get(role);
                if (existingIndex != null && existingIndex != i) {
                    throw new ValidationException("HierarchicalConstraints.requiredStages contains role '" + role
                            + "' in multiple stages (at indices " + existingIndex + " and " + i
                            + "). Each role may only appear in a single stage.");
                }
                roleFirstStageIndex.putIfAbsent(role, i);
            }
        }
    }

    private void validateNoCircularContextDependencies() {
        Map<Task, List<Task>> graph = new HashMap<>();
        for (Task task : tasks) {
            graph.put(task, task.getContext());
        }
        for (Task task : tasks) {
            for (Task ctx : task.getContext()) {
                graph.putIfAbsent(ctx, ctx.getContext());
            }
        }

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

    /**
     * For SEQUENTIAL effective workflow: validate that context tasks appear before the
     * tasks that reference them in the task list.
     *
     * <p>Skipped for HIERARCHICAL and PARALLEL (including inferred PARALLEL), since
     * those workflows use the DAG to determine execution order and do not require
     * declaration-order constraints.
     */
    private void validateContextOrdering(Workflow effective) {
        if (effective == Workflow.HIERARCHICAL || effective == Workflow.PARALLEL) {
            return;
        }

        // Use identity-based membership to be consistent with resolveTasks().
        java.util.Set<Task> executedSoFar = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Task> ensureTaskSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
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

    /**
     * Infer the effective workflow from the original task list, for use in validation
     * before template resolution.
     *
     * <p>When {@link #workflow} is explicitly set, returns it unchanged. Otherwise uses
     * the same inference logic as {@link Ensemble#resolveWorkflow}: if any task has a
     * context dependency on another task in this ensemble, infer PARALLEL; else SEQUENTIAL.
     *
     * @return the effective workflow for this ensemble run
     */
    private Workflow resolveWorkflow() {
        if (workflow != null) {
            return workflow;
        }
        Set<Task> taskSet = Collections.newSetFromMap(new IdentityHashMap<>());
        taskSet.addAll(tasks);
        for (Task task : tasks) {
            for (Task dep : task.getContext()) {
                if (taskSet.contains(dep)) {
                    return Workflow.PARALLEL;
                }
            }
        }
        return Workflow.SEQUENTIAL;
    }
}
