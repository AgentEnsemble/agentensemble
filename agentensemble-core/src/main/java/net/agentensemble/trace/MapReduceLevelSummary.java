package net.agentensemble.trace;

import java.time.Duration;
import lombok.Builder;
import lombok.Value;

/**
 * Summary of a single level in an adaptive map-reduce execution.
 *
 * <p>Collected in {@link ExecutionTrace} when an ensemble was executed
 * via an adaptive {@link net.agentensemble.mapreduce.MapReduceEnsemble}. Each entry
 * corresponds to one {@link net.agentensemble.Ensemble#run()} call in the adaptive loop:
 * the map phase (level 0), any intermediate reduce levels (level 1+), and the final reduce.
 *
 * <p>The {@code workflow} field is always {@code "PARALLEL"} since each level runs as
 * an independent parallel ensemble.
 */
@Value
@Builder
public class MapReduceLevelSummary {

    /**
     * Zero-based index of this level.
     *
     * <p>Level 0 = map phase. Level 1+ = intermediate reduce levels.
     * The final reduce level has the highest index.
     */
    int level;

    /**
     * Number of tasks that ran at this level.
     *
     * <p>For the map phase this equals the number of input items. For reduce levels
     * this equals the number of bins produced by the bin-packing algorithm at the
     * previous level.
     */
    int taskCount;

    /** Total wall-clock duration of this level's {@link net.agentensemble.Ensemble#run()} call. */
    Duration duration;

    /**
     * Workflow type used at this level.
     *
     * <p>Always {@code "PARALLEL"} for adaptive map-reduce levels.
     */
    String workflow;
}
