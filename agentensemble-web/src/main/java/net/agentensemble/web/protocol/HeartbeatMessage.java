package net.agentensemble.web.protocol;

/**
 * Sent every 15 seconds to keep WebSocket connections alive.
 *
 * @param serverTimeMs current server epoch time in milliseconds
 */
public record HeartbeatMessage(long serverTimeMs) implements ServerMessage {}
