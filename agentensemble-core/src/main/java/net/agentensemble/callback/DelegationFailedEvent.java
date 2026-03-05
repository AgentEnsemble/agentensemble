package net.agentensemble.callback;

import java.time.Duration;
import net.agentensemble.delegation.DelegationResponse;

/**
 * Event fired when a delegation fails to complete.
 *
 * <p>A delegation failure can originate from several sources:
 * <ul>
 *   <li><strong>Guard failure</strong> -- self-delegation, depth limit exceeded, or unknown
 *       agent role; no worker execution occurs in this case</li>
 *   <li><strong>Policy rejection</strong> -- a registered
 *       {@link net.agentensemble.delegation.policy.DelegationPolicy} returned
 *       {@link net.agentensemble.delegation.policy.DelegationPolicyResult#reject(String)};
 *       no worker execution occurs in this case</li>
 *   <li><strong>Worker exception</strong> -- the worker agent threw an exception during
 *       execution; partial output may be available in the response</li>
 * </ul>
 *
 * <p>This event is emitted from both peer-delegation ({@code AgentDelegationTool}) and
 * hierarchical-delegation ({@code DelegateTaskTool}) paths.
 *
 * <p>Guard failures and policy rejections that return an error message (rather than
 * propagating an exception) are also reported here so that all delegation outcomes are
 * observable through the listener interface.
 *
 * <p>The {@link #delegationId()} correlates with the {@link DelegationStartedEvent}
 * when the failure occurred after worker execution began, or may not have a matching
 * start event when the failure was a guard or policy rejection (since no start event is
 * fired in those cases).
 *
 * @param delegationId         unique correlation ID for this delegation; matches the
 *                             {@link net.agentensemble.delegation.DelegationRequest#getTaskId()}
 * @param delegatingAgentRole  role of the agent that initiated the delegation
 * @param workerRole           role of the intended target agent
 * @param failureReason        human-readable description of why the delegation failed;
 *                             includes the exception message or guard/policy reason
 * @param cause                the exception that caused the failure, or null when the failure
 *                             was a guard violation or policy rejection rather than an exception
 * @param response             the typed delegation response with {@code FAILURE} status,
 *                             populated error messages, and any partial output
 * @param duration             elapsed time from delegation start to failure
 */
public record DelegationFailedEvent(
        String delegationId,
        String delegatingAgentRole,
        String workerRole,
        String failureReason,
        Throwable cause,
        DelegationResponse response,
        Duration duration) {}
