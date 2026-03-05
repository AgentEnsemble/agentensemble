package net.agentensemble.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.agentensemble.delegation.DelegationRequest;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.delegation.policy.DelegationPolicyContext;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.exception.ConstraintViolationException;
import net.agentensemble.task.TaskOutput;

/**
 * Stateful enforcer for {@link HierarchicalConstraints} in a hierarchical workflow run.
 *
 * <p>This class is created by {@link HierarchicalWorkflowExecutor} for each run when
 * {@link HierarchicalConstraints} are configured on the {@link net.agentensemble.Ensemble}. It
 * is not intended for direct use by application code.
 *
 * <h2>Pre-delegation enforcement (DelegationPolicy)</h2>
 * <p>Implements {@link DelegationPolicy} and is prepended to the delegation policy chain. Before
 * each delegation attempt the enforcer checks in order:
 * <ol>
 *   <li>{@code allowedWorkers}: if non-empty, rejects any target not in the set</li>
 *   <li>{@code globalMaxDelegations}: if &gt; 0, rejects when total approved attempts reach the
 *       cap</li>
 *   <li>{@code maxCallsPerWorker}: rejects when per-worker approved attempts reach the cap</li>
 *   <li>{@code requiredStages}: rejects if any prior-stage worker has not yet completed</li>
 * </ol>
 * <p>Cap counts are incremented atomically at approval time (not at completion), so an attempt
 * that was allowed but whose worker subsequently fails still consumes a slot.
 *
 * <h2>Post-completion tracking</h2>
 * <p>{@link #recordDelegation(String)} is called by {@link HierarchicalWorkflowExecutor} after
 * each successful worker completion (via the {@code DelegationCompletedEvent} listener). It
 * maintains the {@code completedWorkers} set used by stage-ordering checks and
 * post-execution validation.
 *
 * <h2>Post-execution validation</h2>
 * <p>{@link #validatePostExecution()} is called by {@link HierarchicalWorkflowExecutor} after the
 * Manager agent finishes. It checks that every role in {@code requiredWorkers} appears in
 * {@code completedWorkers}. If any are missing, it throws {@link ConstraintViolationException}
 * with all violations listed.
 *
 * <h2>Thread safety</h2>
 * <p>{@link #evaluate} and {@link #recordDelegation} are {@code synchronized} to ensure
 * atomicity of check-and-increment operations when the manager agent issues concurrent tool
 * calls. {@link #validatePostExecution} is expected to be called after all delegations have
 * completed and does not require external synchronization.
 */
class HierarchicalConstraintEnforcer implements DelegationPolicy {

    private final HierarchicalConstraints constraints;

    /**
     * Tracks approved delegation attempts per worker role (incremented in evaluate when allowed).
     * Used to enforce per-worker caps.
     */
    private final Map<String, Integer> approvedAttempts = new HashMap<>();

    /**
     * Tracks total approved delegation attempts across all workers (incremented in evaluate).
     * Used to enforce the global cap.
     */
    private int totalApprovedAttempts = 0;

    /**
     * Tracks worker roles that have completed at least one successful delegation.
     * Updated via recordDelegation(). Used for stage ordering and required-worker checks.
     */
    private final Set<String> completedWorkers = new HashSet<>();

    HierarchicalConstraintEnforcer(HierarchicalConstraints constraints) {
        this.constraints = constraints;
    }

    /**
     * Evaluates all pre-delegation constraints in order. Returns
     * {@link DelegationPolicyResult#allow()} if all pass, or
     * {@link DelegationPolicyResult#reject(String)} with a human-readable reason if any fail.
     *
     * <p>Approved attempts are counted atomically inside this method while the lock is held,
     * preventing races with concurrent tool calls.
     *
     * @param request    the delegation request; {@code request.getAgentRole()} identifies the
     *                   target worker
     * @param policyCtx  contextual information about the delegation pipeline
     * @return allow or reject result; never null
     */
    @Override
    public synchronized DelegationPolicyResult evaluate(DelegationRequest request, DelegationPolicyContext policyCtx) {

        String role = request.getAgentRole();

        // 1. allowedWorkers check: if the set is non-empty the target must be in it
        Set<String> allowed = constraints.getAllowedWorkers();
        if (!allowed.isEmpty() && !allowed.contains(role)) {
            return DelegationPolicyResult.reject(
                    "Worker '" + role + "' is not in the allowed workers list. Allowed: " + allowed);
        }

        // 2. globalMaxDelegations check: 0 means unlimited
        int globalCap = constraints.getGlobalMaxDelegations();
        if (globalCap > 0 && totalApprovedAttempts >= globalCap) {
            return DelegationPolicyResult.reject("global delegation cap of " + globalCap
                    + " reached. No further delegations are permitted in this run.");
        }

        // 3. maxCallsPerWorker check
        Integer workerCap = constraints.getMaxCallsPerWorker().get(role);
        if (workerCap != null) {
            int currentCount = approvedAttempts.getOrDefault(role, 0);
            if (currentCount >= workerCap) {
                return DelegationPolicyResult.reject(
                        "Worker '" + role + "' has reached its per-worker delegation cap of " + workerCap + ".");
            }
        }

        // 4. requiredStages ordering check
        List<List<String>> stages = constraints.getRequiredStages();
        if (!stages.isEmpty()) {
            int roleStageIndex = findStageIndex(stages, role);
            if (roleStageIndex > 0) {
                // All workers in every prior stage must have completed
                for (int i = 0; i < roleStageIndex; i++) {
                    List<String> priorStage = stages.get(i);
                    for (String priorRole : priorStage) {
                        if (!completedWorkers.contains(priorRole)) {
                            return DelegationPolicyResult.reject("Worker '" + role
                                    + "' cannot be delegated to yet: stage " + i + " worker '"
                                    + priorRole
                                    + "' has not yet completed. All workers in earlier stages "
                                    + "must complete before this stage can begin.");
                        }
                    }
                }
            }
        }

        // All checks passed -- atomically reserve the slot
        totalApprovedAttempts++;
        approvedAttempts.merge(role, 1, Integer::sum);

        return DelegationPolicyResult.allow();
    }

    /**
     * Records a successful worker delegation completion. Called by
     * {@link HierarchicalWorkflowExecutor} via the {@code DelegationCompletedEvent} listener
     * after each worker finishes.
     *
     * <p>Adds the worker role to the {@code completedWorkers} set used by stage-ordering checks
     * and post-execution required-worker validation.
     *
     * @param workerRole the role of the worker that completed; must not be null
     */
    public synchronized void recordDelegation(String workerRole) {
        completedWorkers.add(workerRole);
    }

    /**
     * Validates post-execution constraints. Called by {@link HierarchicalWorkflowExecutor} after
     * the Manager agent finishes.
     *
     * <p>Checks that every role in {@code requiredWorkers} appears in {@code completedWorkers}.
     * If any are missing, throws {@link ConstraintViolationException} with all violations.
     *
     * <p>Callers that catch {@link ConstraintViolationException} here may reconstruct it with
     * partial task outputs before re-throwing to callers.
     *
     * @param completedTaskOutputs the task outputs from workers that completed successfully;
     *                             used to construct the exception so callers can inspect partial
     *                             results
     * @throws ConstraintViolationException if any required workers were not called
     */
    public void validatePostExecution(List<TaskOutput> completedTaskOutputs) {
        Set<String> required = constraints.getRequiredWorkers();
        if (required.isEmpty()) {
            return;
        }

        List<String> violations = new ArrayList<>();
        for (String requiredRole : required) {
            if (!completedWorkers.contains(requiredRole)) {
                violations.add("Required worker '" + requiredRole + "' was not delegated to during this run.");
            }
        }

        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations, completedTaskOutputs);
        }
    }

    /**
     * Overload without task outputs for use in tests and the enforcer unit tests.
     *
     * @throws ConstraintViolationException if any required workers were not called
     */
    public void validatePostExecution() {
        validatePostExecution(List.of());
    }

    // ========================
    // Internal helpers
    // ========================

    /**
     * Returns the stage index (0-based) of the given role in the stages list, or -1 if the role
     * does not appear in any stage.
     */
    private static int findStageIndex(List<List<String>> stages, String role) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).contains(role)) {
                return i;
            }
        }
        return -1;
    }
}
