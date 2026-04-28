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
     * Expose only the last body task's last-iteration {@code TaskOutput} -- i.e. the
     * output of whichever task is declared last in the body, on the loop's final
     * iteration. Useful when downstream only needs the body's terminal step. Order
     * the body so the task whose output matters is last (e.g. for a body where the
     * critic is the last task, this exposes the critic's verdict; if you instead
     * want the writer's text, place the writer last in the body).
     */
    FINAL_TASK_ONLY,

    /**
     * Expose a synthesized {@code TaskOutput} per body task whose raw text is the
     * concatenation of all iterations. The full per-iteration history is also
     * available via {@code EnsembleOutput.getLoopHistory(loopName)}.
     */
    ALL_ITERATIONS
}
