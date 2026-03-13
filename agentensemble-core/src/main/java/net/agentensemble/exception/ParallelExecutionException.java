package net.agentensemble.exception;

import java.util.List;
import java.util.Map;
import net.agentensemble.task.TaskOutput;

/**
 * Thrown by {@code ParallelWorkflowExecutor} when one or more tasks fail during
 * a {@code Workflow.PARALLEL} run with {@code ParallelErrorStrategy.CONTINUE_ON_ERROR}.
 *
 * Unlike {@link TaskExecutionException} (which signals a single failure that halted
 * the run), this exception represents a partial-success outcome: some tasks completed
 * successfully while others failed. Both the successful outputs and the failure causes
 * are available to callers.
 *
 * Example handling:
 * <pre>
 * try {
 *     EnsembleOutput output = ensemble.run();
 * } catch (ParallelExecutionException e) {
 *     System.out.println("Completed tasks: " + e.getCompletedTaskOutputs().size());
 *     e.getFailedTaskCauses().forEach((desc, cause) ->
 *         System.err.println("FAILED: " + desc + " -> " + cause.getMessage()));
 * }
 * </pre>
 */
public class ParallelExecutionException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final List<TaskOutput> completedTaskOutputs;
    private final Map<String, Throwable> failedTaskCauses;

    /**
     * Construct a new ParallelExecutionException.
     *
     * @param message              summary message describing the partial failure
     * @param completedTaskOutputs outputs from tasks that completed successfully; must not be null
     * @param failedTaskCauses     map of failed task description to the exception that caused
     *                             the failure; must not be null and must not be empty
     */
    public ParallelExecutionException(
            String message, List<TaskOutput> completedTaskOutputs, Map<String, Throwable> failedTaskCauses) {
        super(message);
        this.completedTaskOutputs = List.copyOf(completedTaskOutputs);
        this.failedTaskCauses = Map.copyOf(failedTaskCauses);
    }

    /**
     * Return the outputs of all tasks that completed successfully before or during the failure.
     *
     * The list is in completion order. These outputs may be used for partial result recovery.
     *
     * @return immutable list of successful task outputs; never null, may be empty
     */
    public List<TaskOutput> getCompletedTaskOutputs() {
        return completedTaskOutputs;
    }

    /**
     * Return a map of failed task descriptions to the exceptions that caused their failure.
     *
     * Keys are the task description strings (after template variable resolution).
     * Values are the causing exceptions ({@link AgentExecutionException},
     * {@link MaxIterationsExceededException}, or other runtime exceptions).
     *
     * @return immutable map of task description to failure cause; never null, never empty
     */
    public Map<String, Throwable> getFailedTaskCauses() {
        return failedTaskCauses;
    }

    /**
     * Return the number of tasks that failed.
     *
     * @return count of failed tasks
     */
    public int getFailedCount() {
        return failedTaskCauses.size();
    }

    /**
     * Return the number of tasks that completed successfully.
     *
     * @return count of successful tasks
     */
    public int getCompletedCount() {
        return completedTaskOutputs.size();
    }
}
