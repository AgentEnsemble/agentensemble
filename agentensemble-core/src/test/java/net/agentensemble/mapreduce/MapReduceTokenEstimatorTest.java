package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapReduceTokenEstimator}.
 *
 * <p>Tests cover the three-tier estimation strategy:
 * <ol>
 *   <li>Provider-supplied output token count (when != -1)</li>
 *   <li>Custom estimator function (when provided)</li>
 *   <li>Heuristic fallback ({@code rawOutput.length() / 4}) when provider returns -1</li>
 * </ol>
 */
class MapReduceTokenEstimatorTest {

    // ========================
    // Provider count (tier 1)
    // ========================

    @Test
    void estimate_providerCountKnown_returnsProviderCount() {
        TaskOutput output = stubOutput("hello world", 42L);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(42);
    }

    @Test
    void estimate_providerCountZero_returnsZero() {
        TaskOutput output = stubOutput("some text", 0L);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(0);
    }

    @Test
    void estimate_providerCountLarge_returnsExactCount() {
        TaskOutput output = stubOutput("short text", 128_000L);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(128_000);
    }

    // ========================
    // Custom estimator (tier 2)
    // ========================

    @Test
    void estimate_customEstimator_usedWhenProviderCountIsMinusOne() {
        // Provider returns -1, custom estimator should be used
        TaskOutput output = stubOutputUnknownTokens("hello world");
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.withCustomEstimator(text -> 99);

        assertThat(estimator.estimate(output)).isEqualTo(99);
    }

    @Test
    void estimate_customEstimator_notUsedWhenProviderCountIsKnown() {
        // Provider count is 42, custom estimator should NOT be used (provider wins)
        TaskOutput output = stubOutput("hello world", 42L);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.withCustomEstimator(text -> 999);

        assertThat(estimator.estimate(output)).isEqualTo(42);
    }

    @Test
    void estimate_customEstimatorReceivesRawText() {
        String raw = "the quick brown fox jumps over the lazy dog";
        TaskOutput output = stubOutputUnknownTokens(raw);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.withCustomEstimator(String::length);

        assertThat(estimator.estimate(output)).isEqualTo(raw.length());
    }

    // ========================
    // Heuristic fallback (tier 3)
    // ========================

    @Test
    void estimate_heuristicFallback_providerMinusOne_noCustomEstimator() {
        // Heuristic: length / 4
        String raw = "abcdefgh"; // 8 chars -> 8/4 = 2 tokens
        TaskOutput output = stubOutputUnknownTokens(raw);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(2);
    }

    @Test
    void estimate_heuristicFallback_emptyString_returnsZero() {
        TaskOutput output = stubOutputUnknownTokens("");
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(0);
    }

    @Test
    void estimate_heuristicFallback_3chars_returnsZero() {
        // 3 / 4 = 0 (integer division)
        TaskOutput output = stubOutputUnknownTokens("abc");
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(0);
    }

    @Test
    void estimate_heuristicFallback_4chars_returnsOne() {
        TaskOutput output = stubOutputUnknownTokens("abcd");
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(1);
    }

    @Test
    void estimate_heuristicFallback_400chars_returns100() {
        String raw = "a".repeat(400);
        TaskOutput output = stubOutputUnknownTokens(raw);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.defaultEstimator();

        assertThat(estimator.estimate(output)).isEqualTo(100);
    }

    // ========================
    // Custom estimator overrides heuristic
    // ========================

    @Test
    void estimate_customEstimator_overridesHeuristic() {
        String raw = "abcdefgh"; // heuristic would give 2, custom gives 50
        TaskOutput output = stubOutputUnknownTokens(raw);
        MapReduceTokenEstimator estimator = MapReduceTokenEstimator.withCustomEstimator(text -> 50);

        assertThat(estimator.estimate(output)).isEqualTo(50);
    }

    // ========================
    // Helpers
    // ========================

    /** Builds a TaskOutput with a known provider output token count. */
    private static TaskOutput stubOutput(String raw, long outputTokens) {
        TaskMetrics metrics = TaskMetrics.builder()
                .outputTokens(outputTokens)
                .inputTokens(outputTokens)
                .totalTokens(outputTokens * 2)
                .build();
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("stub task")
                .agentRole("Stub Agent")
                .completedAt(Instant.now())
                .duration(Duration.ZERO)
                .metrics(metrics)
                .build();
    }

    /** Builds a TaskOutput where the provider did not return a token count (outputTokens = -1). */
    private static TaskOutput stubOutputUnknownTokens(String raw) {
        // TaskMetrics.EMPTY has outputTokens = -1
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("stub task")
                .agentRole("Stub Agent")
                .completedAt(Instant.now())
                .duration(Duration.ZERO)
                .build();
    }
}
