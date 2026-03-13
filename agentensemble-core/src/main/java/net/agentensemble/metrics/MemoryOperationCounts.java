package net.agentensemble.metrics;

import lombok.Builder;
import lombok.Value;

/**
 * Counts of memory operations performed during a single task or ensemble run.
 *
 * <p>All counters are zero when no memory is configured. Aggregated across tasks
 * by {@link ExecutionMetrics}.
 */
@Value
@Builder
public class MemoryOperationCounts {

    /** Number of short-term memory entries written (one per task output recorded). */
    int shortTermEntriesWritten;

    /** Number of entries written to long-term memory. */
    int longTermStores;

    /** Number of long-term memory retrieval queries executed. */
    int longTermRetrievals;

    /** Number of entity memory lookups performed. */
    int entityLookups;

    /** A zeroed instance used as the default when no memory is configured. */
    public static final MemoryOperationCounts ZERO =
            MemoryOperationCounts.builder().build();

    /**
     * Return a new instance that sums this and the other counts.
     *
     * @param other the counts to add
     * @return aggregated counts
     */
    public MemoryOperationCounts add(MemoryOperationCounts other) {
        if (other == null || other.equals(ZERO)) {
            return this;
        }
        return MemoryOperationCounts.builder()
                .shortTermEntriesWritten(this.shortTermEntriesWritten + other.shortTermEntriesWritten)
                .longTermStores(this.longTermStores + other.longTermStores)
                .longTermRetrievals(this.longTermRetrievals + other.longTermRetrievals)
                .entityLookups(this.entityLookups + other.entityLookups)
                .build();
    }
}
