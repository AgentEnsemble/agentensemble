package net.agentensemble.web.protocol;

/**
 * Sent by the browser as a keepalive. The server responds with a {@link PongMessage}.
 */
public record PingMessage() implements ClientMessage {}
