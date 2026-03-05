package net.agentensemble.memory;

import java.time.Duration;

/**
 * Callback interface for receiving notifications about memory operations performed during
 * a single agent task execution.
 *
 * <p>Implementations are wired into {@link MemoryContext} via
 * {@link MemoryContext#setOperationListener(MemoryOperationListener)} at the start of each
 * task and cleared in a {@code finally} block after the task completes. This keeps the
 * listener lifecycle scoped to a single task, matching the lifetime of a
 * {@code TaskTraceAccumulator}.
 *
 * <p>The primary consumer is {@code TaskTraceAccumulator},
 * which forwards each event to the corresponding counter so that
 * {@link net.agentensemble.metrics.MemoryOperationCounts} is fully populated in the task trace.
 *
 * <p>This interface is activated when {@link net.agentensemble.trace.CaptureMode#STANDARD}
 * or higher is in effect. At {@link net.agentensemble.trace.CaptureMode#OFF}, no listener
 * is registered, and {@link MemoryContext} incurs no listener overhead.
 *
 * <p>All methods have empty default implementations so that partial implementations can be
 * created for testing or custom use cases without implementing every method.
 */
public interface MemoryOperationListener {

    /**
     * Called when a task output is written to short-term memory.
     * Fired once per {@link MemoryContext#record(net.agentensemble.task.TaskOutput)} call
     * when short-term memory is enabled.
     */
    default void onStmWrite() {}

    /**
     * Called when a task output is stored in long-term memory.
     * Fired once per {@link MemoryContext#record(net.agentensemble.task.TaskOutput)} call
     * when long-term memory is configured.
     */
    default void onLtmStore() {}

    /**
     * Called when long-term memory is queried for relevant entries.
     * Fired once per {@link MemoryContext#queryLongTerm(String)} call when long-term memory
     * is configured.
     *
     * @param duration wall-clock time spent on the retrieval query
     */
    default void onLtmRetrieval(Duration duration) {}

    /**
     * Called when entity memory is accessed.
     * Fired once per {@link MemoryContext#getEntityFacts()} call when entity memory is configured
     * and non-empty.
     *
     * @param duration wall-clock time spent on the entity lookup
     */
    default void onEntityLookup(Duration duration) {}
}
