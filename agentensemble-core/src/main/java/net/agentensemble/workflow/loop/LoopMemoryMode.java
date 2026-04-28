package net.agentensemble.workflow.loop;

/**
 * How a {@link Loop} treats prior-iteration outputs in shared memory across iterations.
 */
public enum LoopMemoryMode {

    /**
     * Outputs from each iteration remain in the loop's {@code MemoryScope},
     * visible to subsequent iterations. Default. Matches reflection-loop semantics:
     * the writer needs to see the prior critique to revise.
     */
    ACCUMULATE,

    /**
     * Memory scopes declared by body tasks are cleared between iterations. Use for
     * retry-until-valid where prior bad outputs would only pollute the next prompt.
     *
     * <p>Implementation note: requires the {@code ExecutionContext}'s
     * {@link net.agentensemble.memory.MemoryStore} to support {@code clear(scope)}.
     * {@link net.agentensemble.memory.MemoryStore#inMemory()} supports it; the
     * embedding-backed store does not (vector stores generally cannot delete by metadata
     * filter), and selecting this mode against an unsupported store throws an actionable
     * {@link UnsupportedOperationException} pointing to {@link #ACCUMULATE} or to switching
     * the loop's scopes to an in-memory store.
     */
    FRESH_PER_ITERATION,

    /**
     * Memory scopes are evicted to keep only the {@code memoryWindowSize} most recent
     * entries between iterations. Useful when accumulating context indefinitely would
     * grow the prompt without bound, but recent history is still informative.
     *
     * <p>Implemented via {@link net.agentensemble.memory.MemoryStore#evict(String,
     * net.agentensemble.memory.EvictionPolicy)} with
     * {@link net.agentensemble.memory.EvictionPolicy#keepLastEntries(int)}. Works with
     * any {@code MemoryStore} that implements {@code evict} -- {@code InMemoryStore}
     * does; the embedding-backed store performs eviction as a no-op (the window is
     * effectively unbounded for vector stores).
     *
     * <p>Required companion field: {@code Loop.builder().memoryWindowSize(N)} where {@code N >= 1}.
     */
    WINDOW
}
