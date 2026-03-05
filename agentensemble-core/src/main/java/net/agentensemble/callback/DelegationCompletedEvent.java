package net.agentensemble.callback;

import java.time.Duration;
import net.agentensemble.delegation.DelegationResponse;

/**
 * Event fired immediately after a delegation completes successfully.
 *
 * <p>This event is emitted from both peer-delegation ({@code AgentDelegationTool}) and
 * hierarchical-delegation ({@code DelegateTaskTool}) paths.
 *
 * <p>The {@link #delegationId()} correlates with the {@link DelegationStartedEvent} that
 * was fired at the start of the same delegation.
 *
 * @param delegationId         unique correlation ID for this delegation; matches the
 *                             corresponding {@link DelegationStartedEvent#delegationId()}
 * @param delegatingAgentRole  role of the agent that initiated the delegation
 * @param workerRole           role of the agent that executed the delegated task
 * @param response             the full typed delegation response including raw output,
 *                             parsed output, and any metadata
 * @param duration             elapsed time from delegation start to completion
 */
public record DelegationCompletedEvent(
        String delegationId,
        String delegatingAgentRole,
        String workerRole,
        DelegationResponse response,
        Duration duration) {}
