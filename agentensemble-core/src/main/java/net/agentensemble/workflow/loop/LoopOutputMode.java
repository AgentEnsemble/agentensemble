package net.agentensemble.workflow.loop;

/**
 * How a {@link Loop} exposes its body's outputs to the rest of the ensemble after the loop finishes.
 */
public enum LoopOutputMode {

    /**
     * For each body task name, expose the last iteration's {@code TaskOutput}.
     * Default. Matches reflection-loop semantics ("publish the approved draft").
     */
    LAST_ITERATION,

    /**
     * Expose only the last body task's last-iteration {@code TaskOutput}.
     * Useful when downstream only cares about the final stage of the loop body
     * (e.g. writer-then-critic where downstream only wants the writer's text).
     */
    FINAL_TASK_ONLY,

    /**
     * Expose a synthesized {@code TaskOutput} per body task whose raw text is the
     * concatenation of all iterations. The full per-iteration history is also
     * available via {@code EnsembleOutput.getLoopHistory(loopName)}.
     */
    ALL_ITERATIONS
}
