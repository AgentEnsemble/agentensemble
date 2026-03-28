package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sent when a review gate fires. The browser must respond with a {@link ReviewDecisionMessage}
 * before {@code timeoutMs} elapses or the {@code onTimeout} action is applied automatically.
 *
 * @param reviewId        unique ID for this review; used to correlate the browser's decision
 * @param taskDescription description of the task under review
 * @param taskOutput      the task output to review; empty string for before-execution reviews
 * @param timing          when this review is occurring: BEFORE_EXECUTION, DURING_EXECUTION,
 *                        or AFTER_EXECUTION
 * @param prompt          optional custom message to display to the reviewer; may be null
 * @param timeoutMs       milliseconds before timeout; 0 means wait indefinitely
 * @param onTimeout       action when timeout expires: CONTINUE, EXIT_EARLY, or FAIL
 * @param requiredRole    optional role that a human must have to approve; may be null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewRequestedMessage(
        String reviewId,
        String taskDescription,
        String taskOutput,
        String timing,
        String prompt,
        long timeoutMs,
        String onTimeout,
        String requiredRole)
        implements ServerMessage {}
