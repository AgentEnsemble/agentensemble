package net.agentensemble.delegation.policy;

import java.util.List;

/**
 * Immutable context provided to {@link DelegationPolicy#evaluate} for each delegation attempt.
 *
 * <p>Contains metadata about the current delegation chain state that policies can use to
 * make allow/reject/modify decisions without needing access to the full internal
 * {@link net.agentensemble.delegation.DelegationContext}.
 *
 * @param delegatingAgentRole  role of the agent initiating this delegation
 * @param currentDepth         current delegation depth (0 = root, 1 = first delegation, etc.)
 * @param maxDepth             maximum allowed delegation depth for this run
 * @param availableWorkerRoles roles of agents available to delegate to in this context
 */
public record DelegationPolicyContext(
        String delegatingAgentRole, int currentDepth, int maxDepth, List<String> availableWorkerRoles) {}
