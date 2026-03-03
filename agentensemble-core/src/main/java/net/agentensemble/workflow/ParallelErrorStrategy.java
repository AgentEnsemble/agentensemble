package net.agentensemble.workflow;

/**
 * Defines how {@link ParallelWorkflowExecutor} responds when a task fails during
 * concurrent execution.
 *
 * Configure via {@code Ensemble.builder().parallelErrorStrategy(...)} when using
 * {@code Workflow.PARALLEL}.
 */
public enum ParallelErrorStrategy {

    /**
     * On the first task failure, stop scheduling any new tasks and, after the
     * parallel run terminates, throw a
     * {@link net.agentensemble.exception.TaskExecutionException}.
     *
     * Tasks that have already completed are reported in the exception's
     * {@code completedTaskOutputs}. Tasks that are already in progress when the
     * first failure is detected are allowed to finish normally; they are not
     * cancelled or interrupted by this strategy.
     *
     * This is the default strategy. It mirrors the behaviour of
     * {@code Workflow.SEQUENTIAL} where the first failure stops starting further
     * tasks, and helps avoid wasted LLM API calls on tasks whose results will be
     * discarded.
     */
    FAIL_FAST,

    /**
     * When a task fails, continue executing all other independent tasks.
     * Dependent tasks (those that declare the failed task in their context list)
     * are skipped automatically.
     *
     * When the run finishes, if any tasks failed a
     * {@link net.agentensemble.exception.ParallelExecutionException} is thrown,
     * carrying both the successful outputs and a map of failed task descriptions
     * to their causes. If all tasks succeed, the run completes normally.
     *
     * Use this strategy when partial results are valuable even if some tasks fail,
     * or when tasks are independent enough that a single failure should not
     * invalidate the entire pipeline.
     */
    CONTINUE_ON_ERROR
}
