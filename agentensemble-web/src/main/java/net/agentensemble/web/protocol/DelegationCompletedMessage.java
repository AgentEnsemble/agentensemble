package net.agentensemble.web.protocol;

/**
 * Sent when a delegation completes successfully. Mirrors {@code DelegationCompletedEvent}.
 *
 * @param delegationId        correlation ID; matches the corresponding started event
 * @param delegatingAgentRole role of the agent that initiated the delegation
 * @param workerRole          role of the agent that executed the delegated task
 * @param durationMs          elapsed time from delegation start to completion in milliseconds
 */
public record DelegationCompletedMessage(
        String delegationId, String delegatingAgentRole, String workerRole, long durationMs) implements ServerMessage {}
