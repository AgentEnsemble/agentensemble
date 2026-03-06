package net.agentensemble.web.protocol;

/**
 * Sent when a delegation fails. Mirrors {@code DelegationFailedEvent}.
 *
 * @param delegationId        correlation ID
 * @param delegatingAgentRole role of the agent that initiated the delegation
 * @param workerRole          role of the intended target agent
 * @param reason              human-readable failure reason
 */
public record DelegationFailedMessage(String delegationId, String delegatingAgentRole, String workerRole, String reason)
        implements ServerMessage {}
