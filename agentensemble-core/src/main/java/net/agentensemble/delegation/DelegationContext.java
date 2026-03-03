package net.agentensemble.delegation;

import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.memory.MemoryContext;

/**
 * Immutable runtime state for an agent delegation chain.
 *
 * Tracks the current delegation depth, maximum allowed depth, the peer agents
 * available for delegation, and the shared execution infrastructure. A new
 * {@code DelegationContext} is created at the start of each ensemble run by the
 * workflow executor and threaded through {@link AgentExecutor} calls. When an
 * agent delegates to a peer, {@link #descend()} produces a child context with
 * depth incremented by one, preserving all other fields.
 *
 * This class is immutable, but it holds references to other runtime components
 * (such as {@link MemoryContext} and {@link AgentExecutor}) whose thread-safety
 * characteristics must be considered separately. {@link MemoryContext} in particular
 * is documented as not thread-safe, so the overall delegation runtime state should
 * not be shared across threads unless all referenced components are thread-safe.
 *
 * Example:
 * <pre>
 * DelegationContext ctx = DelegationContext.create(
 *     ensemble.getAgents(), 3, memoryContext, agentExecutor, verbose);
 *
 * // In AgentDelegationTool:
 * DelegationContext childCtx = ctx.descend();
 * agentExecutor.execute(task, List.of(), verbose, memoryContext, childCtx);
 * </pre>
 */
public final class DelegationContext {

    private final List<Agent> peerAgents;
    private final int maxDepth;
    private final int currentDepth;
    private final MemoryContext memoryContext;
    private final AgentExecutor agentExecutor;
    private final boolean verbose;

    private DelegationContext(
            List<Agent> peerAgents,
            int maxDepth,
            int currentDepth,
            MemoryContext memoryContext,
            AgentExecutor agentExecutor,
            boolean verbose) {
        this.peerAgents = peerAgents;
        this.maxDepth = maxDepth;
        this.currentDepth = currentDepth;
        this.memoryContext = memoryContext;
        this.agentExecutor = agentExecutor;
        this.verbose = verbose;
    }

    /**
     * Create a root delegation context (depth = 0).
     *
     * @param peerAgents    agents available for delegation; must not be null
     * @param maxDepth      maximum delegation depth allowed; must be greater than zero
     * @param memoryContext runtime memory state for this run; must not be null
     * @param agentExecutor executor used to run delegated tasks; must not be null
     * @param verbose       whether to enable verbose logging for delegated tasks
     * @return a new root-level delegation context
     * @throws IllegalArgumentException if any required argument is null or maxDepth is not positive
     */
    public static DelegationContext create(
            List<Agent> peerAgents,
            int maxDepth,
            MemoryContext memoryContext,
            AgentExecutor agentExecutor,
            boolean verbose) {
        if (peerAgents == null) {
            throw new IllegalArgumentException("peerAgents must not be null");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be > 0, got: " + maxDepth);
        }
        if (memoryContext == null) {
            throw new IllegalArgumentException("memoryContext must not be null");
        }
        if (agentExecutor == null) {
            throw new IllegalArgumentException("agentExecutor must not be null");
        }
        return new DelegationContext(List.copyOf(peerAgents), maxDepth, 0, memoryContext, agentExecutor, verbose);
    }

    /**
     * Return a child context with {@code currentDepth + 1}, all other fields preserved.
     *
     * Call this before executing a delegated task so the child agent receives a context
     * that correctly reflects its position in the delegation chain.
     *
     * @return a new {@code DelegationContext} with depth incremented by one
     */
    public DelegationContext descend() {
        return new DelegationContext(peerAgents, maxDepth, currentDepth + 1, memoryContext, agentExecutor, verbose);
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

    /** @return the shared memory context for this run */
    public MemoryContext getMemoryContext() {
        return memoryContext;
    }

    /** @return the agent executor used to run delegated tasks */
    public AgentExecutor getAgentExecutor() {
        return agentExecutor;
    }

    /** @return true if verbose logging is enabled */
    public boolean isVerbose() {
        return verbose;
    }
}
