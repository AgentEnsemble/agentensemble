package net.agentensemble.workflow.loop;

/**
 * Behaviour when a {@link Loop} hits its {@code maxIterations} cap without the
 * {@link LoopPredicate} firing.
 */
public enum MaxIterationsAction {

    /**
     * Return the last iteration's outputs as the loop's result; the ensemble continues.
     * This is the default and matches the common case of "we tried our best -- ship what we have."
     */
    RETURN_LAST,

    /**
     * Throw a {@link MaxLoopIterationsExceededException}, aborting the ensemble.
     * Use when failure to converge is itself an error (e.g. retry-until-valid where
     * never-valid means the upstream pipeline must stop).
     */
    THROW,

    /**
     * Return the last iteration's outputs and set a flag on
     * {@link net.agentensemble.ensemble.EnsembleOutput} so downstream tasks (or callers)
     * can detect that the loop did not converge.
     */
    RETURN_WITH_FLAG
}
