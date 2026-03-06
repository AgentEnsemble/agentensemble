package net.agentensemble.web.protocol;

/**
 * Sent when a review gate timeout expires, before the timeout action is applied.
 *
 * @param reviewId the ID of the review that timed out
 * @param action   the action being applied: CONTINUE, EXIT_EARLY, or FAIL
 */
public record ReviewTimedOutMessage(String reviewId, String action) implements ServerMessage {}
