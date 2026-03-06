package net.agentensemble.ensemble;

/**
 * Describes why an ensemble run terminated.
 *
 * <p>Accessible via {@code EnsembleOutput.getExitReason()}:
 *
 * <pre>
 * EnsembleOutput output = ensemble.run();
 * if (output.getExitReason() == ExitReason.USER_EXIT_EARLY) {
 *     System.out.println("Pipeline stopped early at user request");
 *     System.out.println("Completed tasks: " + output.getTaskOutputs().size());
 * }
 * </pre>
 */
public enum ExitReason {

    /**
     * All tasks in the ensemble executed and completed successfully.
     *
     * <p>This is the normal, expected exit reason for a successful run.
     */
    COMPLETED,

    /**
     * A human reviewer chose {@code ReviewDecision.ExitEarly}
     * at a review gate, or a {@code HumanInputTool} returned ExitEarly during execution.
     *
     * <p>The {@link EnsembleOutput} will contain only the tasks that completed before
     * the exit-early signal, and -- in the case of an after-execution review gate --
     * the task whose output triggered the exit.
     */
    USER_EXIT_EARLY
}
