package net.agentensemble.review;

/**
 * Identifies at which point in the task lifecycle a review gate fires.
 *
 * <p>Used in {@link ReviewRequest} to tell the {@link ReviewHandler} when
 * the review is occurring so it can display appropriate context.
 */
public enum ReviewTiming {

    /**
     * Review fires before the agent begins executing the task.
     *
     * <p>At this point there is no task output yet. The {@link ReviewHandler}
     * should present the task description and prompt the user to approve
     * or cancel execution.
     *
     * <p>{@link ReviewDecision.Edit} returned at this timing is treated as
     * {@link ReviewDecision.Continue} because there is no output to edit.
     */
    BEFORE_EXECUTION,

    /**
     * Review fires during task execution when an agent invokes the
     * {@code HumanInputTool}.
     *
     * <p>The agent pauses and presents its question as the review request.
     * The human's text response is returned to the agent as the tool result,
     * resuming the ReAct loop.
     */
    DURING_EXECUTION,

    /**
     * Review fires after the agent has completed the task, before the output
     * is passed to the next task in the pipeline.
     *
     * <p>At this point the full task output is available. The human may
     * approve it as-is ({@link ReviewDecision.Continue}), replace it with
     * revised text ({@link ReviewDecision.Edit}), or stop the pipeline
     * ({@link ReviewDecision.ExitEarly}).
     */
    AFTER_EXECUTION
}
