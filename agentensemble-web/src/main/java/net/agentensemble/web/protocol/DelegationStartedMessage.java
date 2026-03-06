package net.agentensemble.web.protocol;

/**
 * Sent when a delegation handoff begins. Mirrors {@code DelegationStartedEvent}.
 *
 * @param delegationId        correlation ID; matches the subsequent completed or failed event
 * @param delegatingAgentRole role of the agent initiating the delegation
 * @param workerRole          role of the agent that will execute the delegated task
 * @param taskDescription     description of the delegated subtask
 */
public record DelegationStartedMessage(
        String delegationId, String delegatingAgentRole, String workerRole, String taskDescription)
        implements ServerMessage {}
