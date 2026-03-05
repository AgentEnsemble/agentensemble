package net.agentensemble.exception;

import java.util.List;
import net.agentensemble.task.TaskOutput;

/**
 * Thrown when a {@link net.agentensemble.workflow.HierarchicalConstraints} configuration is
 * violated after the hierarchical workflow manager completes.
 *
 * <p>This exception is thrown by
 * {@link net.agentensemble.workflow.HierarchicalConstraintEnforcer#validatePostExecution()} when
 * one or more post-execution constraint checks fail -- for example, when a required worker was
 * never delegated to during the run.
 *
 * <p>Pre-delegation constraint violations (disallowed worker, per-worker cap, global cap, stage
 * ordering) are enforced as
 * {@link net.agentensemble.delegation.policy.DelegationPolicyResult#reject(String)} policy
 * decisions and do not throw this exception; instead the manager receives an error message from
 * the delegation tool and can adjust its delegation strategy.
 *
 * <p>The {@link #getViolations()} method returns a human-readable list of unsatisfied constraint
 * descriptions for diagnostic purposes. The {@link #getCompletedTaskOutputs()} method returns
 * the task outputs that were produced before the constraint was detected, allowing callers to
 * inspect partial results.
 */
public class ConstraintViolationException extends AgentEnsembleException {

    private final List<String> violations;
    private final List<TaskOutput> completedTaskOutputs;

    /**
     * Creates a ConstraintViolationException with a list of violations and no completed outputs.
     *
     * @param violations human-readable descriptions of all unsatisfied constraints; must not be
     *                   null or empty
     */
    public ConstraintViolationException(List<String> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
        this.completedTaskOutputs = List.of();
    }

    /**
     * Creates a ConstraintViolationException with violations and the task outputs that completed
     * before the violation was detected.
     *
     * @param violations            human-readable descriptions of all unsatisfied constraints;
     *                              must not be null or empty
     * @param completedTaskOutputs  task outputs produced before the constraint was detected;
     *                              may be empty but must not be null
     */
    public ConstraintViolationException(List<String> violations, List<TaskOutput> completedTaskOutputs) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
        this.completedTaskOutputs = List.copyOf(completedTaskOutputs);
    }

    /**
     * Creates a ConstraintViolationException with violations and a causal exception.
     *
     * @param violations human-readable descriptions of all unsatisfied constraints; must not be
     *                   null or empty
     * @param cause      the cause of this exception; may be null
     */
    public ConstraintViolationException(List<String> violations, Throwable cause) {
        super(buildMessage(violations), cause);
        this.violations = List.copyOf(violations);
        this.completedTaskOutputs = List.of();
    }

    /**
     * Returns an immutable list of human-readable descriptions of all unsatisfied constraints.
     *
     * @return immutable list of violation descriptions; never null or empty
     */
    public List<String> getViolations() {
        return violations;
    }

    /**
     * Returns an immutable list of task outputs produced before the constraint violation was
     * detected.
     *
     * <p>In hierarchical workflow, these are the worker outputs from delegations that completed
     * successfully before post-execution constraint validation ran. The list may be empty when
     * no workers were successfully called or when this exception was constructed without outputs.
     *
     * @return immutable list of completed task outputs; never null; may be empty
     */
    public List<TaskOutput> getCompletedTaskOutputs() {
        return completedTaskOutputs;
    }

    private static String buildMessage(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            return "Hierarchical constraints violated";
        }
        if (violations.size() == 1) {
            return "Hierarchical constraint violated: " + violations.get(0);
        }
        return "Hierarchical constraints violated (" + violations.size() + "): " + String.join("; ", violations);
    }
}
