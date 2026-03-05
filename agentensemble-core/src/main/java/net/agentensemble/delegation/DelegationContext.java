package net.agentensemble.delegation;

import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.execution.ExecutionContext;

/**
 * Immutable runtime state for an agent delegation chain.
 *
 * Tracks the current delegation depth, maximum allowed depth, the peer agents
 * available for delegation, the shared execution infrastructure, and any registered
 * {@link DelegationPolicy} instances. A new {@code DelegationContext} is created at the
 * start of each ensemble run by the workflow executor and threaded through
 * {@link AgentExecutor} calls. When an agent delegates to a peer, {@link #descend()}
 * produces a child context with depth incremented by one, preserving all other fields
 * (including the full policy list).
 *
 * This class is immutable, but it holds references to other runtime components
 * (such as {@link ExecutionContext} and {@link AgentExecutor}) whose thread-safety
 * characteristics must be considered separately. {@link net.agentensemble.memory.MemoryContext}
 * in particular is documented as not thread-safe, so the overall delegation runtime
 * state should not be shared across threads unless all referenced components are thread-safe.
 *
 * Example:
 * <pre>
 * DelegationContext ctx = DelegationContext.create(
 *     ensemble.getAgents(), 3, executionContext, agentExecutor);
 *
 * // In AgentDelegationTool:
 * DelegationContext childCtx = ctx.descend();
 * agentExecutor.execute(task, List.of(), ctx.getExecutionContext(), childCtx);
 * </pre>
 */
public final class DelegationContext {

    private final List<Agent> peerAgents;
    private final int maxDepth;
    private final int currentDepth;
    private final ExecutionContext executionContext;
    private final AgentExecutor agentExecutor;
    private final List<DelegationPolicy> policies;

    private DelegationContext(
            List<Agent> peerAgents,
            int maxDepth,
            int currentDepth,
            ExecutionContext executionContext,
            AgentExecutor agentExecutor,
            List<DelegationPolicy> policies) {
        this.peerAgents = peerAgents;
        this.maxDepth = maxDepth;
        this.currentDepth = currentDepth;
        this.executionContext = executionContext;
        this.agentExecutor = agentExecutor;
        this.policies = policies;
    }

    /**
     * Create a root delegation context (depth = 0) with no delegation policies.
     *
     * @param peerAgents       agents available for delegation; must not be null
     * @param maxDepth         maximum delegation depth allowed; must be greater than zero
     * @param executionContext  execution context bundling memory, verbosity, and listeners;
     *                          must not be null
     * @param agentExecutor    executor used to run delegated tasks; must not be null
     * @return a new root-level delegation context with an empty policy list
     * @throws IllegalArgumentException if any required argument is null or maxDepth is not positive
     */
    public static DelegationContext create(
            List<Agent> peerAgents, int maxDepth, ExecutionContext executionContext, AgentExecutor agentExecutor) {
        return create(peerAgents, maxDepth, executionContext, agentExecutor, List.of());
    }

    /**
     * Create a root delegation context (depth = 0) with the specified delegation policies.
     *
     * <p>Policies are evaluated in list order for each delegation attempt. An immutable
     * defensive copy of the list is stored and propagated to all child contexts via
     * {@link #descend()}.
     *
     * @param peerAgents       agents available for delegation; must not be null
     * @param maxDepth         maximum delegation depth allowed; must be greater than zero
     * @param executionContext  execution context bundling memory, verbosity, and listeners;
     *                          must not be null
     * @param agentExecutor    executor used to run delegated tasks; must not be null
     * @param policies         delegation policies to evaluate before each worker invocation;
     *                          must not be null; may be empty
     * @return a new root-level delegation context
     * @throws IllegalArgumentException if any required argument is null or maxDepth is not positive
     */
    public static DelegationContext create(
            List<Agent> peerAgents,
            int maxDepth,
            ExecutionContext executionContext,
            AgentExecutor agentExecutor,
            List<DelegationPolicy> policies) {
        if (peerAgents == null) {
            throw new IllegalArgumentException("peerAgents must not be null");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be > 0, got: " + maxDepth);
        }
        if (executionContext == null) {
            throw new IllegalArgumentException("executionContext must not be null");
        }
        if (agentExecutor == null) {
            throw new IllegalArgumentException("agentExecutor must not be null");
        }
        if (policies == null) {
            throw new IllegalArgumentException("policies must not be null");
        }
        return new DelegationContext(
                List.copyOf(peerAgents), maxDepth, 0, executionContext, agentExecutor, List.copyOf(policies));
    }

    /**
     * Return a child context with {@code currentDepth + 1}, all other fields preserved.
     *
     * Call this before executing a delegated task so the child agent receives a context
     * that correctly reflects its position in the delegation chain. Policies are propagated
     * unchanged to the child context.
     *
     * @return a new {@code DelegationContext} with depth incremented by one
     */
    public DelegationContext descend() {
        return new DelegationContext(peerAgents, maxDepth, currentDepth + 1, executionContext, agentExecutor, policies);
    }

    /**
     * Return true if the current depth has reached or exceeded the maximum allowed depth.
     *
     * When true, the {@link AgentDelegationTool} must not delegate further and should
     * return an error message to the calling agent instead.
     *
     * @return true if delegation limit has been reached
     */
    public boolean isAtLimit() {
        return currentDepth >= maxDepth;
    }

    /** @return the list of agents available for delegation */
    public List<Agent> getPeerAgents() {
        return peerAgents;
    }

    /** @return the maximum allowed delegation depth for this run */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** @return the current delegation depth (0 = root, 1 = first delegation, etc.) */
    public int getCurrentDepth() {
        return currentDepth;
    }

    /** @return the execution context for this run */
    public ExecutionContext getExecutionContext() {
        return executionContext;
    }

    /** @return the agent executor used to run delegated tasks */
    public AgentExecutor getAgentExecutor() {
        return agentExecutor;
    }

    /**
     * Returns the immutable list of delegation policies registered for this run.
     *
     * <p>Policies are evaluated in list order for each delegation attempt. An empty list
     * means no policy evaluation is performed and all delegations proceed directly to
     * worker execution (after the built-in guards pass).
     *
     * @return immutable list of delegation policies; never null
     */
    public List<DelegationPolicy> getPolicies() {
        return policies;
    }
}
