package net.agentensemble.delegation;

/**
 * Outcome status of a completed {@link DelegationResponse}.
 */
public enum DelegationStatus {

    /**
     * The delegated task completed successfully and the worker produced a usable output.
     */
    SUCCESS,

    /**
     * The delegated task failed; {@link DelegationResponse#errors()} will contain details.
     */
    FAILURE,

    /**
     * The delegated task completed but the output is incomplete or may require follow-up.
     * For example, a depth-limit guard was triggered mid-delegation.
     */
    PARTIAL
}
