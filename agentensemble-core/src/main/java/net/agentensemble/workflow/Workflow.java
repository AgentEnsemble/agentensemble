package net.agentensemble.workflow;

/**
 * Defines how tasks within an ensemble are executed.
 */
public enum Workflow {

    /**
     * Tasks execute one after another in list order.
     * Output from earlier tasks is passed as context to later tasks
     * that declare those tasks in their context list.
     */
    SEQUENTIAL,

    /**
     * A manager agent automatically delegates tasks to worker agents
     * based on their roles and goals. The manager synthesizes the final output.
     */
    HIERARCHICAL,

    /**
     * Tasks execute concurrently using Java 21 virtual threads, with the dependency
     * graph (derived from each task's {@code context} list) determining which tasks
     * may run in parallel and which must wait for prerequisites.
     *
     * Tasks with no unmet dependencies start immediately. As each task completes,
     * any dependent tasks whose all dependencies are satisfied are submitted for
     * concurrent execution. This naturally handles mixed sequential and parallel
     * patterns without any explicit configuration beyond declaring {@code context}
     * dependencies on the {@link net.agentensemble.Task}.
     *
     * Error handling is configurable via
     * {@code Ensemble.builder().parallelErrorStrategy(...)}.
     */
    PARALLEL
}
