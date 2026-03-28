package net.agentensemble.web.protocol;

/**
 * Server-to-client acknowledgement that a directive has been received and stored.
 *
 * @param directiveId the unique ID assigned to the stored directive
 * @param status      the status of the operation (e.g. "accepted")
 */
public record DirectiveAckMessage(String directiveId, String status) implements ServerMessage {}
