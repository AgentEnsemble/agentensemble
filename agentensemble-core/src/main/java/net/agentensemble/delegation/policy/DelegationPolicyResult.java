package net.agentensemble.delegation.policy;

import net.agentensemble.delegation.DelegationRequest;

/**
 * Sealed result type returned by {@link DelegationPolicy#evaluate}.
 *
 * <p>Three outcomes are possible:
 * <ul>
 *   <li>{@link Allow} -- proceed with delegation using the current request unchanged</li>
 *   <li>{@link Reject} -- block this delegation; the worker agent is never invoked and a
 *       {@link net.agentensemble.delegation.DelegationResponse} with
 *       {@link net.agentensemble.delegation.DelegationStatus#FAILURE} status is returned
 *       to the calling agent</li>
 *   <li>{@link Modify} -- replace the working {@link DelegationRequest} with a new one
 *       (for example to inject default scope values) and continue evaluation of remaining
 *       policies using the modified request</li>
 * </ul>
 *
 * <p>Use the static factory methods to create results:
 * <pre>
 * // Allow unconditionally
 * return DelegationPolicyResult.allow();
 *
 * // Reject with a reason
 * return DelegationPolicyResult.reject("project_key must not be UNKNOWN");
 *
 * // Inject defaults and continue
 * DelegationRequest enriched = request.toBuilder()
 *     .scope(Map.&lt;String, Object&gt;of("region", "us-east-1"))
 *     .build();
 * return DelegationPolicyResult.modify(enriched);
 * </pre>
 */
public sealed interface DelegationPolicyResult
        permits DelegationPolicyResult.Allow, DelegationPolicyResult.Reject, DelegationPolicyResult.Modify {

    /**
     * Signals that the delegation should proceed with the current request unchanged.
     *
     * <p>Evaluation continues with subsequent policies. If all policies return
     * {@code Allow}, the worker agent is invoked.
     */
    final class Allow implements DelegationPolicyResult {
        private static final Allow INSTANCE = new Allow();

        private Allow() {}
    }

    /**
     * Signals that the delegation should be blocked.
     *
     * <p>Worker execution is skipped immediately. A
     * {@link net.agentensemble.delegation.DelegationResponse} with
     * {@link net.agentensemble.delegation.DelegationStatus#FAILURE} status and the
     * {@link #reason()} in {@code errors} is returned to the calling agent.
     *
     * @param reason human-readable explanation for the rejection; included in the
     *               FAILURE response errors list and in the returned error message
     */
    record Reject(String reason) implements DelegationPolicyResult {}

    /**
     * Signals that the delegation may proceed, but with a different
     * {@link DelegationRequest}.
     *
     * <p>The {@link #modifiedRequest()} replaces the current request for all subsequent
     * policy evaluations and for the final worker invocation. This allows policies to
     * inject missing scope values, normalise fields, or enrich the request with context
     * before the delegation is handed off.
     *
     * @param modifiedRequest the replacement request; must not be null
     */
    record Modify(DelegationRequest modifiedRequest) implements DelegationPolicyResult {}

    // ========================
    // Factory methods
    // ========================

    /**
     * Return an {@link Allow} result, indicating the delegation may proceed.
     *
     * @return the singleton Allow instance
     */
    static Allow allow() {
        return Allow.INSTANCE;
    }

    /**
     * Return a {@link Reject} result, blocking the delegation with the supplied reason.
     *
     * @param reason human-readable explanation for the rejection; must not be null or blank
     * @return a new Reject instance
     * @throws IllegalArgumentException if reason is null or blank
     */
    static Reject reject(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("rejection reason must not be null or blank");
        }
        return new Reject(reason);
    }

    /**
     * Return a {@link Modify} result, replacing the current request and continuing evaluation.
     *
     * @param modifiedRequest the replacement request; must not be null
     * @return a new Modify instance
     * @throws IllegalArgumentException if modifiedRequest is null
     */
    static Modify modify(DelegationRequest modifiedRequest) {
        if (modifiedRequest == null) {
            throw new IllegalArgumentException("modifiedRequest must not be null");
        }
        return new Modify(modifiedRequest);
    }
}
