package net.agentensemble.delegation.policy;

import net.agentensemble.delegation.DelegationRequest;

/**
 * Pluggable hook that intercepts delegation attempts before the worker agent executes.
 *
 * <p>A {@code DelegationPolicy} runs after all built-in guards (self-delegation check,
 * depth limit, unknown-agent check) have passed. Policies can:
 * <ul>
 *   <li><strong>Allow</strong> -- permit the delegation to proceed unchanged</li>
 *   <li><strong>Reject</strong> -- block the delegation; the worker is never invoked and the
 *       calling agent receives an error message describing the rejection reason</li>
 *   <li><strong>Modify</strong> -- replace the {@link DelegationRequest} (for example to inject
 *       missing scope defaults) and continue evaluation of subsequent policies with the
 *       updated request</li>
 * </ul>
 *
 * <p>Policies are evaluated in the order they were registered. If any policy returns
 * {@link DelegationPolicyResult#reject(String)}, evaluation stops immediately and no
 * subsequent policies are evaluated. If a policy returns
 * {@link DelegationPolicyResult#modify(DelegationRequest)}, the modified request is used
 * for all subsequent policy evaluations and for the final worker invocation.
 *
 * <p>Register policies on the {@code Ensemble} builder:
 * <pre>
 * Ensemble.builder()
 *     .delegationPolicy((request, ctx) -> {
 *         if ("UNKNOWN".equals(request.scope().get("project_key"))) {
 *             return DelegationPolicyResult.reject("project_key must not be UNKNOWN");
 *         }
 *         return DelegationPolicyResult.allow();
 *     })
 *     .delegationPolicy((request, ctx) -> {
 *         if (!"Analyst".equals(request.agentRole())) {
 *             return DelegationPolicyResult.allow();
 *         }
 *         if (request.scope().get("region") == null) {
 *             DelegationRequest enriched = request.toBuilder()
 *                 .scope(Map.of("region", "us-east-1"))
 *                 .build();
 *             return DelegationPolicyResult.modify(enriched);
 *         }
 *         return DelegationPolicyResult.allow();
 *     })
 *     .build();
 * </pre>
 *
 * <p>This is a {@link FunctionalInterface}; lambdas and method references are accepted
 * wherever a {@code DelegationPolicy} is expected.
 *
 * <p>Thread safety: policy implementations must be thread-safe when the ensemble uses
 * parallel task execution. Stateless lambdas are always thread-safe.
 */
@FunctionalInterface
public interface DelegationPolicy {

    /**
     * Evaluate the delegation request and return a decision.
     *
     * <p>This method is called once per registered policy, in registration order, for each
     * delegation attempt. The {@code request} parameter reflects any modifications made by
     * earlier policies in the chain (via {@link DelegationPolicyResult#modify}).
     *
     * @param request the current delegation request, incorporating any modifications made
     *                by earlier policies in the evaluation chain; never null
     * @param context contextual information about the current delegation chain state;
     *                never null
     * @return a non-null {@link DelegationPolicyResult} indicating whether to allow,
     *         reject, or modify the delegation
     */
    DelegationPolicyResult evaluate(DelegationRequest request, DelegationPolicyContext context);
}
