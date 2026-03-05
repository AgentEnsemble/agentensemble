package net.agentensemble.tool;

import java.time.Duration;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;

/**
 * A built-in {@link AgentTool} that pauses the ReAct loop to collect human input.
 *
 * <p>When the agent invokes this tool, the framework presents the agent's question to
 * the ensemble's configured {@link ReviewHandler} as a
 * {@link ReviewTiming#DURING_EXECUTION} review request. The human's text response is
 * returned to the agent as the tool result, resuming the loop.
 *
 * <p>Declare on the task:
 * <pre>
 * Task task = Task.builder()
 *     .description("Research AI trends and check with the user if the direction is correct")
 *     .expectedOutput("A research report approved by the user")
 *     .tools(HumanInputTool.of())
 *     .build();
 * </pre>
 *
 * <p>The ensemble must also have a {@link ReviewHandler} configured:
 * <pre>
 * Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .reviewHandler(ReviewHandler.console())
 *     .task(task)
 *     .build()
 *     .run();
 * </pre>
 *
 * <p>When the reviewer chooses {@link ReviewDecision.ExitEarly}, an
 * {@link ExitEarlyException} is thrown, propagating through the agent loop and
 * caught by the workflow executor, which assembles partial results.
 *
 * <p>When the reviewer chooses {@link ReviewDecision.Continue}, an empty acknowledgement
 * string is returned to the agent. When the reviewer chooses
 * {@link ReviewDecision.Edit}, the revised text is returned to the agent as the
 * human's response.
 *
 * <p>The default timeout applied when none is configured on the task's {@code review} or
 * when the {@link ReviewHandler} does not enforce one is determined by
 * {@link Review#DEFAULT_TIMEOUT}.
 */
public final class HumanInputTool extends AbstractAgentTool {

    /** The default tool name recognised by agents. */
    static final String TOOL_NAME = "human_input";

    /** The description shown to the LLM to help it decide when to use this tool. */
    static final String TOOL_DESCRIPTION = "Pauses execution to collect a response from a human reviewer or operator. "
            + "Use this tool when you need clarification, approval, or additional information "
            + "that only a human can provide. "
            + "Input: the question or information you want to present to the human. "
            + "Returns the human's text response.";

    /** The ReviewHandler injected by the execution engine before first use. */
    private volatile ReviewHandler reviewHandler;

    /** The timeout duration for human input. Defaults to Review.DEFAULT_TIMEOUT. */
    private final Duration timeout;

    /** The on-timeout action when no response is received. Defaults to CONTINUE. */
    private final OnTimeoutAction onTimeoutAction;

    private HumanInputTool(Duration timeout, OnTimeoutAction onTimeoutAction) {
        this.timeout = timeout != null ? timeout : Review.DEFAULT_TIMEOUT;
        this.onTimeoutAction = onTimeoutAction != null ? onTimeoutAction : OnTimeoutAction.CONTINUE;
    }

    /**
     * Create a {@code HumanInputTool} with the default timeout (5 minutes) and
     * on-timeout action ({@link OnTimeoutAction#CONTINUE}).
     *
     * <p>Declare on a task to enable human input during execution:
     * <pre>
     * Task.builder()
     *     .description("Research AI trends and clarify direction with the user")
     *     .tools(HumanInputTool.of())
     *     .build();
     * </pre>
     *
     * @return a new HumanInputTool
     */
    public static HumanInputTool of() {
        return new HumanInputTool(Review.DEFAULT_TIMEOUT, OnTimeoutAction.CONTINUE);
    }

    /**
     * Create a {@code HumanInputTool} with a custom timeout and on-timeout action.
     *
     * @param timeout         how long to wait for a human response
     * @param onTimeoutAction what to do when the timeout expires
     * @return a new HumanInputTool
     */
    public static HumanInputTool of(Duration timeout, OnTimeoutAction onTimeoutAction) {
        return new HumanInputTool(timeout, onTimeoutAction);
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    @Override
    protected ToolResult doExecute(String input) {
        ReviewHandler handler = this.reviewHandler;

        if (handler == null) {
            log().warn("HumanInputTool invoked but no ReviewHandler is configured on the ensemble. "
                    + "Returning empty response and continuing. "
                    + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            return ToolResult.success("No reviewer is available. Please proceed with your best judgment.");
        }

        ReviewRequest request = ReviewRequest.of(
                input, // agent's question as the task description
                "", // no output yet during execution
                ReviewTiming.DURING_EXECUTION,
                timeout,
                onTimeoutAction,
                null);

        ReviewDecision decision = handler.review(request);

        if (decision instanceof ReviewDecision.ExitEarly) {
            throw new ExitEarlyException("Human reviewer requested early exit via HumanInputTool");
        } else if (decision instanceof ReviewDecision.Edit edit) {
            return ToolResult.success(edit.revisedOutput());
        } else {
            // Continue -- return an acknowledgement so the agent knows the human is present
            return ToolResult.success("Understood. Please continue.");
        }
    }

    // ========================
    // Framework injection -- package-private
    // ========================

    /**
     * Inject the {@link ReviewHandler} provided by the execution engine.
     *
     * <p>Called by the workflow executor before the task begins execution.
     * This method is public to allow access from workflow executor classes in different
     * packages, but is not part of the user-facing API.
     *
     * @param handler the review handler to use; may be null (no handler configured)
     */
    public void injectReviewHandler(ReviewHandler handler) {
        this.reviewHandler = handler;
    }
}
