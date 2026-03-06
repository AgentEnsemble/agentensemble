package net.agentensemble.web.protocol;

/**
 * Sent by the server in response to a {@link PingMessage} from the client.
 */
public record PongMessage() implements ServerMessage {}
