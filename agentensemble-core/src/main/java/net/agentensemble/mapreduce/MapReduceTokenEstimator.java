package net.agentensemble.mapreduce;

import java.util.function.Function;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token count estimator for adaptive map-reduce reduction decisions.
 *
 * <p>Uses a three-tier strategy (highest priority wins):
 * <ol>
 *   <li><b>Provider token count:</b> {@code TaskOutput.getMetrics().getOutputTokens()} when
 *       the value is not {@code -1}. This is the most accurate source.</li>
 *   <li><b>Custom estimator:</b> the {@link Function}{@code <String, Integer>} provided by
 *       the user via
 *       {@link MapReduceEnsemble.Builder#tokenEstimator(Function)}, when configured.</li>
 *   <li><b>Heuristic fallback:</b> {@code rawOutput.length() / 4}. Approximates the
 *       English-language average of ~4 characters per token. Logs a WARN when used so
 *       callers are aware that estimation is approximate.</li>
 * </ol>
 *
 * <p>Instances are immutable. Obtain one via {@link #defaultEstimator()} or
 * {@link #withCustomEstimator(Function)}.
 */
final class MapReduceTokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(MapReduceTokenEstimator.class);

    /** English-language approximation: ~4 characters per token. */
    private static final int CHARS_PER_TOKEN = 4;

    private final Function<String, Integer> customEstimator;

    private MapReduceTokenEstimator(Function<String, Integer> customEstimator) {
        this.customEstimator = customEstimator;
    }

    /**
     * Returns a default estimator that uses the provider token count (tier 1) when available,
     * or falls back to the heuristic ({@code length / 4}) otherwise.
     *
     * @return a default {@code MapReduceTokenEstimator}
     */
    static MapReduceTokenEstimator defaultEstimator() {
        return new MapReduceTokenEstimator(null);
    }

    /**
     * Returns an estimator that uses the provider token count (tier 1) when available, the
     * custom estimator function (tier 2) when the provider count is unknown, and the heuristic
     * fallback (tier 3) as a last resort.
     *
     * <p>In practice, tier 3 is never reached when a custom estimator is provided because the
     * custom estimator handles the provider-unknown case. The heuristic is only invoked if the
     * custom estimator itself returns {@code null} (which is not expected for well-formed
     * implementations).
     *
     * @param customEstimator function mapping raw text to estimated token count; must not be null
     * @return a {@code MapReduceTokenEstimator} that prefers the custom function over heuristic
     */
    static MapReduceTokenEstimator withCustomEstimator(Function<String, Integer> customEstimator) {
        if (customEstimator == null) {
            throw new IllegalArgumentException("customEstimator must not be null");
        }
        return new MapReduceTokenEstimator(customEstimator);
    }

    /**
     * Estimate the output token count for the given task output.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code output.getMetrics().getOutputTokens() != -1}, return that value.</li>
     *   <li>If a custom estimator is configured, apply it to {@code output.getRaw()}.</li>
     *   <li>Heuristic fallback: {@code output.getRaw().length() / 4}. A WARN is logged.</li>
     * </ol>
     *
     * @param output the task output to estimate; must not be null
     * @return the estimated output token count (non-negative)
     */
    int estimate(TaskOutput output) {
        long providerCount = output.getMetrics().getOutputTokens();
        if (providerCount >= 0) {
            return (int) Math.min(providerCount, Integer.MAX_VALUE);
        }

        if (customEstimator != null) {
            Integer estimated = customEstimator.apply(output.getRaw());
            if (estimated != null) {
                return estimated;
            }
        }

        // Heuristic fallback
        int heuristic = output.getRaw().length() / CHARS_PER_TOKEN;
        log.warn(
                "MapReduce: provider did not return output token count for agent [{}]. "
                        + "Using heuristic estimate ({} chars / {} = {} tokens). "
                        + "Consider providing a tokenEstimator for more accurate bin-packing.",
                output.getAgentRole(),
                output.getRaw().length(),
                CHARS_PER_TOKEN,
                heuristic);
        return heuristic;
    }
}
