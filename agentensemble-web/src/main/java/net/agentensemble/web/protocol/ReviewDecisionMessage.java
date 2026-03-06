package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sent by the browser in response to a {@link ReviewRequestedMessage}.
 *
 * @param reviewId      the ID from the corresponding {@link ReviewRequestedMessage}
 * @param decision      the reviewer's choice: {@code CONTINUE}, {@code EDIT}, or {@code EXIT_EARLY}
 * @param revisedOutput the revised output text when {@code decision} is {@code EDIT}; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewDecisionMessage(String reviewId, String decision, String revisedOutput) implements ClientMessage {}
