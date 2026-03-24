package net.agentensemble.mapreduce;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First-fit-decreasing (FFD) bin-packing for adaptive map-reduce reduction.
 *
 * <p>Groups {@link TaskOutput} instances into bins where the combined token count of each
 * bin does not exceed a configurable {@code targetTokenBudget}. The FFD approximation
 * achieves good bin utilisation by sorting items in descending token order before
 * assignment.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Sort outputs by token count, descending (using provider count or heuristic).</li>
 *   <li>For each output, assign it to the first existing bin with sufficient remaining
 *       capacity. If no bin has capacity, open a new bin.</li>
 *   <li>An output whose token count alone exceeds {@code targetTokenBudget} is placed
 *       in its own bin with a WARN logged.</li>
 * </ol>
 *
 * <p>This class is stateless. All state is local to each {@link #pack} invocation.
 */
final class MapReduceBinPacker {

    private static final Logger log = LoggerFactory.getLogger(MapReduceBinPacker.class);

    private MapReduceBinPacker() {}

    /**
     * Pack the given outputs into bins where each bin's total token count does not exceed
     * {@code targetTokenBudget}. Uses the default token estimator.
     *
     * @param outputs           the task outputs to pack; must not be null
     * @param targetTokenBudget the maximum combined token count per bin; must be &gt; 0
     * @return an immutable list of bins, where each bin is an immutable list of outputs.
     *         Never {@code null}; empty when {@code outputs} is empty.
     */
    static List<List<TaskOutput>> pack(List<TaskOutput> outputs, int targetTokenBudget) {
        return pack(outputs, targetTokenBudget, MapReduceTokenEstimator.defaultEstimator());
    }

    /**
     * Pack the given outputs into bins where each bin's total token count does not exceed
     * {@code targetTokenBudget}, using the provided estimator for token count resolution.
     *
     * <p>The estimator uses the three-tier strategy (provider count, custom function,
     * heuristic fallback) to ensure consistent token estimates between the adaptive
     * reduction decision loop and the bin-packing assignment.
     *
     * @param outputs           the task outputs to pack; must not be null
     * @param targetTokenBudget the maximum combined token count per bin; must be &gt; 0
     * @param estimator         the token estimator to use; must not be null
     * @return an immutable list of bins, where each bin is an immutable list of outputs.
     *         Never {@code null}; empty when {@code outputs} is empty.
     */
    static List<List<TaskOutput>> pack(
            List<TaskOutput> outputs, int targetTokenBudget, MapReduceTokenEstimator estimator) {
        if (outputs == null || outputs.isEmpty()) {
            return List.of();
        }

        // Build (output, tokenCount) pairs using the estimator for consistent estimates
        List<OutputWithTokens> items = new ArrayList<>(outputs.size());
        for (TaskOutput output : outputs) {
            int tokens = estimator.estimate(output);
            items.add(new OutputWithTokens(output, tokens));
        }

        // Sort descending by token count (FFD)
        items.sort(Comparator.comparingInt(OutputWithTokens::tokenCount).reversed());

        // Assign outputs to bins using first-fit strategy
        List<List<TaskOutput>> bins = new ArrayList<>();
        List<Long> binUsed = new ArrayList<>(); // remaining capacity (actually: used so far)

        for (OutputWithTokens item : items) {
            if (item.tokenCount() > targetTokenBudget) {
                // Oversized item: own bin, with a warning
                if (log.isWarnEnabled()) {
                    log.warn(
                            "MapReduce: single output from agent [{}] exceeds targetTokenBudget "
                                    + "({} > {}). Proceeding with a single-item reduce group. "
                                    + "Consider increasing targetTokenBudget or using outputType to "
                                    + "produce more compact structured output.",
                            item.output().getAgentRole(),
                            item.tokenCount(),
                            targetTokenBudget);
                }
                List<TaskOutput> bin = new ArrayList<>();
                bin.add(item.output());
                bins.add(bin);
                binUsed.add((long) item.tokenCount());
                continue;
            }

            // Find first bin with enough remaining capacity
            boolean placed = false;
            for (int i = 0; i < bins.size(); i++) {
                long used = binUsed.get(i);
                if (used + item.tokenCount() <= targetTokenBudget) {
                    bins.get(i).add(item.output());
                    binUsed.set(i, used + item.tokenCount());
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                // Open a new bin
                List<TaskOutput> newBin = new ArrayList<>();
                newBin.add(item.output());
                bins.add(newBin);
                binUsed.add((long) item.tokenCount());
            }
        }

        // Convert to immutable lists
        List<List<TaskOutput>> result = new ArrayList<>(bins.size());
        for (List<TaskOutput> bin : bins) {
            result.add(List.copyOf(bin));
        }
        return List.copyOf(result);
    }

    /**
     * Internal record pairing a task output with its pre-computed token count.
     */
    private record OutputWithTokens(TaskOutput output, int tokenCount) {}
}
