package net.agentensemble.ensemble;

/**
 * Describes why an ensemble run terminated.
 *
 * <p>Accessible via {@code EnsembleOutput.getExitReason()} and the convenience method
 * {@code EnsembleOutput.isComplete()}:
 *
 * <pre>
 * EnsembleOutput output = ensemble.run();
 * if (!output.isComplete()) {
 *     System.out.println("Pipeline stopped early: " + output.getExitReason());
 *     System.out.println("Completed tasks: " + output.completedTasks().size());
 * }
 * </pre>
 */
public enum ExitReason {

    /**
     * All tasks in the ensemble executed and completed successfully.
     *
     * <p>This is the normal, expected exit reason for a successful run.
     * {@link EnsembleOutput#isComplete()} returns {@code true} only when this reason is set.
     */
    COMPLETED,

    /**
     * A human reviewer chose {@link net.agentensemble.review.ReviewDecision.ExitEarly}
     * at a review gate, or a {@code HumanInputTool} returned ExitEarly during execution.
     *
     * <p>The {@link EnsembleOutput} will contain only the tasks that completed before
     * the exit-early signal, and -- in the case of an after-execution review gate --
     * the task whose output triggered the exit.
     */
    USER_EXIT_EARLY,

    /**
     * A review gate timeout expired and the configured
     * {@link net.agentensemble.review.OnTimeoutAction} was {@code EXIT_EARLY}.
     *
     * <p>The {@link EnsembleOutput} will contain the tasks that completed before
     * the timeout fired. Use {@link EnsembleOutput#completedTasks()} to access them.
     */
    TIMEOUT,

    /**
     * An unrecoverable exception terminated the pipeline before all tasks could complete.
     *
     * <p>The tasks that completed before the error are available in
     * {@link EnsembleOutput#completedTasks()}. This reason is used when constructing
     * an {@link EnsembleOutput} from the partial results carried by a
     * {@link net.agentensemble.exception.TaskExecutionException}.
     */
    ERROR
}
