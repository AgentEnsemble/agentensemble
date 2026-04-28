package net.agentensemble.workflow.graph;

/**
 * Behaviour when a {@link Graph} hits its {@code maxSteps} cap without reaching
 * {@link Graph#END}.
 *
 * <p>Mirrors {@link net.agentensemble.workflow.loop.MaxIterationsAction} for consistency
 * across the Loop and Graph constructs.
 */
public enum MaxStepsAction {

    /**
     * Return the last visited state's output as the graph's result; the ensemble continues.
     * Default. Matches the common case of "we ran out of steps -- ship what we have."
     */
    RETURN_LAST,

    /**
     * Throw {@link net.agentensemble.exception.MaxGraphStepsExceededException}, aborting the
     * ensemble. Use when failure to reach a terminal state is itself an error.
     */
    THROW,

    /**
     * Return the last visited state's output and set a flag on
     * {@link net.agentensemble.ensemble.EnsembleOutput#wasGraphTerminatedByMaxSteps()}
     * so downstream code can detect that the graph did not converge to a terminal.
     */
    RETURN_WITH_FLAG
}
