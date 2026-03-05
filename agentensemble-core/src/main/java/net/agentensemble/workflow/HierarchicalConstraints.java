package net.agentensemble.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * Optional guardrails for a {@link Workflow#HIERARCHICAL} ensemble run.
 *
 * <p>When attached to an {@link net.agentensemble.Ensemble} via
 * {@code Ensemble.builder().hierarchicalConstraints(...)}, the framework creates a
 * {@code HierarchicalConstraintEnforcer} that enforces each constraint before and after
 * the Manager agent's delegation run.
 *
 * <h2>Pre-delegation enforcement</h2>
 * <p>The enforcer implements {@link net.agentensemble.delegation.policy.DelegationPolicy} and
 * is prepended to the policy chain. Before each delegation the enforcer checks:
 * <ul>
 *   <li>{@code allowedWorkers} -- if non-empty, the target worker must be in the set</li>
 *   <li>{@code maxCallsPerWorker} -- per-worker delegation cap</li>
 *   <li>{@code globalMaxDelegations} -- global delegation cap across all workers</li>
 *   <li>{@code requiredStages} -- stage ordering; all workers in stage N must have
 *       completed before any worker in stage N+1 can be delegated to</li>
 * </ul>
 * <p>A violated pre-delegation check returns a
 * {@link net.agentensemble.delegation.policy.DelegationPolicyResult#reject(String)} result;
 * the Manager receives the rejection reason and can adjust its delegation plan.
 *
 * <h2>Post-execution enforcement</h2>
 * <p>After the Manager finishes, the enforcer validates {@code requiredWorkers}: every
 * listed role must have been delegated to at least once. If any required worker was not called,
 * a {@link net.agentensemble.exception.ConstraintViolationException} is thrown carrying all
 * violations and the task outputs that completed successfully.
 *
 * <h2>Example</h2>
 * <pre>
 * HierarchicalConstraints constraints = HierarchicalConstraints.builder()
 *     .requiredWorker("Researcher")
 *     .allowedWorker("Researcher")
 *     .allowedWorker("Analyst")
 *     .maxCallsPerWorker("Analyst", 2)
 *     .globalMaxDelegations(5)
 *     .requiredStage(List.of("Researcher"))
 *     .requiredStage(List.of("Analyst"))
 *     .build();
 *
 * EnsembleOutput result = Ensemble.builder()
 *     .workflow(Workflow.HIERARCHICAL)
 *     .agent(researcher)
 *     .agent(analyst)
 *     .task(task)
 *     .hierarchicalConstraints(constraints)
 *     .build()
 *     .run();
 * </pre>
 */
@Value
@Builder
public class HierarchicalConstraints {

    /**
     * Worker roles that MUST be delegated to at least once during the run.
     *
     * <p>If any required worker is not called by the time the Manager finishes, a
     * {@link net.agentensemble.exception.ConstraintViolationException} is thrown. Default: empty
     * (no required workers).
     *
     * <p>Use the builder's {@code requiredWorker(String)} method to add individual roles or
     * {@code requiredWorkers(Collection)} for bulk addition.
     */
    @Singular
    Set<String> requiredWorkers;

    /**
     * Restricts which worker roles the Manager may delegate to.
     *
     * <p>When non-empty, any delegation to a role not in this set is rejected before the worker
     * executes. When empty, all registered workers are allowed. Default: empty (all allowed).
     *
     * <p>Use the builder's {@code allowedWorker(String)} method to add individual roles or
     * {@code allowedWorkers(Collection)} for bulk addition.
     */
    @Singular("allowedWorker")
    Set<String> allowedWorkers;

    /**
     * Per-worker delegation caps. Delegations to a worker beyond its cap are rejected.
     *
     * <p>Workers not listed here have no individual cap. The cap counts delegation attempts that
     * passed all other checks (not just successful completions): an attempt that was allowed by
     * the enforcer but whose worker agent subsequently fails still counts against the cap. Default:
     * empty (no per-worker caps).
     *
     * <p>Use the builder's {@code maxCallsPerWorker(String key, Integer value)} method to add
     * individual entries or {@code maxCallsPerWorker(Map)} for bulk addition.
     */
    @Singular("maxCallsPerWorker")
    Map<String, Integer> maxCallsPerWorker;

    /**
     * Global cap on total delegations across all workers during a single run.
     *
     * <p>When 0 (the default), there is no global cap. When positive, total delegation attempts
     * (across all workers, counting attempts that passed all other checks) are capped at this
     * value. Must be {@code >= 0}. Default: 0 (unlimited).
     */
    @Builder.Default
    int globalMaxDelegations = 0;

    /**
     * Ordered stage groups for enforcing delegation sequencing.
     *
     * <p>Each inner list is a group of worker roles that form a stage. All roles in stage N must
     * have been delegated to and completed successfully at least once before any role in stage
     * N+1 can be delegated to. Workers not appearing in any stage are unconstrained by stage
     * ordering.
     *
     * <p>Default: empty (no stage ordering enforced).
     *
     * <p>Use the builder's {@code requiredStage(List)} method to add individual stages or
     * {@code requiredStages(Collection)} for bulk addition. Stage order is preserved (insertion
     * order).
     */
    @Singular("requiredStage")
    List<List<String>> requiredStages;
}
