package net.agentensemble.workflow.loop;

/**
 * Decides whether a {@link Loop} should stop after the iteration that just completed.
 *
 * <p>Returning {@code true} stops the loop and triggers the configured
 * {@link LoopOutputMode}. Returning {@code false} continues to the next iteration
 * (subject to {@code maxIterations}).
 */
@FunctionalInterface
public interface LoopPredicate {

    /**
     * @param ctx state of the loop after the just-completed iteration
     * @return {@code true} to stop the loop, {@code false} to continue
     */
    boolean shouldStop(LoopIterationContext ctx);
}
