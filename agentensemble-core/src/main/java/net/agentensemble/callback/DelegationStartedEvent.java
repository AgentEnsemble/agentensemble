package net.agentensemble.callback;

import net.agentensemble.delegation.DelegationRequest;

/**
 * Event fired immediately before a delegation is handed off to a worker agent.
 *
 * <p>This event is emitted from both peer-delegation ({@code AgentDelegationTool}) and
 * hierarchical-delegation ({@code DelegateTaskTool}) paths, allowing listeners to observe
 * every manager-to-worker or agent-to-agent handoff.
 *
 * <p>The {@link #delegationId()} is the correlation key shared with the matching
 * {@link DelegationCompletedEvent} or {@link DelegationFailedEvent} and with the originating
 * {@link DelegationRequest} (accessible via {@code request().getTaskId()}).
 *
 * <p>A {@code DelegationStartedEvent} is only fired for delegations that pass all guards
 * (depth limit, self-delegation, unknown agent) and all registered
 * {@link net.agentensemble.delegation.policy.DelegationPolicy} evaluations. Guard or policy
 * failures produce a {@link DelegationFailedEvent} directly.
 *
 * @param delegationId         unique correlation ID for this delegation; matches the
 *                             originating {@code DelegationRequest.getTaskId()} and the
 *                             corresponding completed/failed event
 * @param delegatingAgentRole  role of the agent initiating the delegation
 * @param workerRole           role of the agent that will execute the delegated task
 * @param taskDescription      description of the subtask being delegated
 * @param delegationDepth      depth of this delegation in the chain (1 = first delegation,
 *                             2 = nested delegation, etc.)
 * @param request              the full typed delegation request for detailed inspection
 */
public record DelegationStartedEvent(
        String delegationId,
        String delegatingAgentRole,
        String workerRole,
        String taskDescription,
        int delegationDepth,
        DelegationRequest request) {}
