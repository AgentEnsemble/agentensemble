package net.agentensemble.review;

import java.time.Duration;

/**
 * Carries the context for a single review gate invocation.
 *
 * <p>A {@link ReviewRequest} is built by the execution engine and passed to the
 * configured {@link ReviewHandler}. It contains enough context for the handler to
 * present a meaningful review prompt to the human reviewer.
 *
 * <p>Example -- before-execution review:
 * <pre>
 * ReviewRequest request = ReviewRequest.of(
 *     "Research AI trends in 2025",  // taskDescription
 *     null,                          // no output yet
 *     ReviewTiming.BEFORE_EXECUTION,
 *     Duration.ofMinutes(5));
 * </pre>
 *
 * <p>Example -- after-execution review:
 * <pre>
 * ReviewRequest request = ReviewRequest.of(
 *     "Research AI trends in 2025",
 *     "The AI landscape in 2025 has seen ...",
 *     ReviewTiming.AFTER_EXECUTION,
 *     Duration.ofMinutes(5));
 * </pre>
 */
public final class ReviewRequest {

    private final String taskDescription;
    private final String taskOutput;
    private final ReviewTiming timing;
    private final Duration timeout;
    private final OnTimeoutAction onTimeoutAction;
    private final String prompt;
    private final String requiredRole;

    private ReviewRequest(
            String taskDescription,
            String taskOutput,
            ReviewTiming timing,
            Duration timeout,
            OnTimeoutAction onTimeoutAction,
            String prompt,
            String requiredRole) {
        if (taskDescription == null) {
            throw new IllegalArgumentException("taskDescription must not be null");
        }
        if (timing == null) {
            throw new IllegalArgumentException("timing must not be null");
        }
        this.taskDescription = taskDescription;
        this.taskOutput = taskOutput != null ? taskOutput : "";
        this.timing = timing;
        this.timeout = timeout;
        this.onTimeoutAction = onTimeoutAction != null ? onTimeoutAction : OnTimeoutAction.EXIT_EARLY;
        this.prompt = prompt;
        this.requiredRole = requiredRole;
    }

    /**
     * Create a {@link ReviewRequest} with the four required fields and default timeout
     * action ({@link OnTimeoutAction#EXIT_EARLY}).
     *
     * @param taskDescription the description of the task being reviewed; must not be null
     * @param taskOutput      the current task output; may be null or empty for
     *                        {@link ReviewTiming#BEFORE_EXECUTION}
     * @param timing          when this review is occurring; must not be null
     * @param timeout         how long to wait before timing out; may be null for no timeout
     * @return a new ReviewRequest
     */
    public static ReviewRequest of(String taskDescription, String taskOutput, ReviewTiming timing, Duration timeout) {
        return new ReviewRequest(taskDescription, taskOutput, timing, timeout, OnTimeoutAction.EXIT_EARLY, null, null);
    }

    /**
     * Create a {@link ReviewRequest} with all fields specified.
     *
     * @param taskDescription  the description of the task being reviewed; must not be null
     * @param taskOutput       the current task output; may be null or empty for
     *                         {@link ReviewTiming#BEFORE_EXECUTION}
     * @param timing           when this review is occurring; must not be null
     * @param timeout          how long to wait before timing out; may be null for no timeout
     * @param onTimeoutAction  what to do when the timeout expires; defaults to
     *                         {@link OnTimeoutAction#EXIT_EARLY} when null
     * @param prompt           optional custom display message shown by the handler; may be null
     * @return a new ReviewRequest
     */
    public static ReviewRequest of(
            String taskDescription,
            String taskOutput,
            ReviewTiming timing,
            Duration timeout,
            OnTimeoutAction onTimeoutAction,
            String prompt) {
        return new ReviewRequest(taskDescription, taskOutput, timing, timeout, onTimeoutAction, prompt, null);
    }

    /**
     * Create a {@link ReviewRequest} with all fields specified, including an optional
     * required role for role-based gated reviews.
     *
     * @param taskDescription  the description of the task being reviewed; must not be null
     * @param taskOutput       the current task output; may be null or empty
     * @param timing           when this review is occurring; must not be null
     * @param timeout          how long to wait before timing out; may be null for no timeout
     * @param onTimeoutAction  what to do when the timeout expires; defaults to EXIT_EARLY when null
     * @param prompt           optional custom display message; may be null
     * @param requiredRole     optional role required to approve; may be null
     * @return a new ReviewRequest
     */
    public static ReviewRequest of(
            String taskDescription,
            String taskOutput,
            ReviewTiming timing,
            Duration timeout,
            OnTimeoutAction onTimeoutAction,
            String prompt,
            String requiredRole) {
        return new ReviewRequest(taskDescription, taskOutput, timing, timeout, onTimeoutAction, prompt, requiredRole);
    }

    /**
     * The description of the task being reviewed.
     *
     * @return the task description; never null
     */
    public String taskDescription() {
        return taskDescription;
    }

    /**
     * The current task output.
     *
     * <p>Empty string when the review timing is {@link ReviewTiming#BEFORE_EXECUTION}
     * because execution has not started yet.
     *
     * @return the task output; never null, may be empty
     */
    public String taskOutput() {
        return taskOutput;
    }

    /**
     * When in the task lifecycle this review is occurring.
     *
     * @return the review timing; never null
     */
    public ReviewTiming timing() {
        return timing;
    }

    /**
     * How long the {@link ReviewHandler} should wait for a human response before
     * executing the configured {@link #onTimeoutAction()}.
     *
     * @return the timeout duration, or {@code null} when there is no timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * The action to take when the review times out.
     *
     * @return the on-timeout action; never null, defaults to {@link OnTimeoutAction#EXIT_EARLY}
     */
    public OnTimeoutAction onTimeoutAction() {
        return onTimeoutAction;
    }

    /**
     * An optional custom message displayed to the reviewer by the {@link ReviewHandler}.
     *
     * <p>When set, the handler should display this message above or below the standard
     * task information. Useful for providing domain-specific context or instructions.
     *
     * @return the custom prompt, or {@code null} when none was configured
     */
    public String prompt() {
        return prompt;
    }

    /**
     * The optional role that a human must have to approve this review.
     *
     * <p>When non-null, only humans connected with this role can approve the review.
     * Dashboard clients without the matching role should display the review as read-only.
     *
     * @return the required role, or {@code null} when any human can approve
     */
    public String requiredRole() {
        return requiredRole;
    }
}
