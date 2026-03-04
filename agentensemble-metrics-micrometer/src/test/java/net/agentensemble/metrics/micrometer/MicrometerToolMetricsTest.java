package net.agentensemble.metrics.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for MicrometerToolMetrics: verifies that all ToolMetrics operations correctly
 * record measurements to the underlying MeterRegistry.
 */
class MicrometerToolMetricsTest {

    private MeterRegistry registry;
    private MicrometerToolMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerToolMetrics(registry);
    }

    // ========================
    // Construction
    // ========================

    @Test
    void constructor_nullRegistry_throwsNullPointerException() {
        assertThatThrownBy(() -> new MicrometerToolMetrics(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }

    // ========================
    // incrementSuccess
    // ========================

    @Test
    void incrementSuccess_incrementsExecutionCounter_withSuccessOutcome() {
        metrics.incrementSuccess("calculator", "Researcher");

        Counter counter = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "calculator")
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Researcher")
                .tag(MicrometerToolMetrics.TAG_OUTCOME, MicrometerToolMetrics.OUTCOME_SUCCESS)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementSuccess_calledMultipleTimes_accumulatesCount() {
        metrics.incrementSuccess("calculator", "Writer");
        metrics.incrementSuccess("calculator", "Writer");
        metrics.incrementSuccess("calculator", "Writer");

        Counter counter = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_OUTCOME, MicrometerToolMetrics.OUTCOME_SUCCESS)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);
    }

    // ========================
    // incrementFailure
    // ========================

    @Test
    void incrementFailure_incrementsExecutionCounter_withFailureOutcome() {
        metrics.incrementFailure("web_search", "Analyst");

        Counter counter = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "web_search")
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Analyst")
                .tag(MicrometerToolMetrics.TAG_OUTCOME, MicrometerToolMetrics.OUTCOME_FAILURE)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========================
    // incrementError
    // ========================

    @Test
    void incrementError_incrementsExecutionCounter_withErrorOutcome() {
        metrics.incrementError("file_read", "Processor");

        Counter counter = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "file_read")
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Processor")
                .tag(MicrometerToolMetrics.TAG_OUTCOME, MicrometerToolMetrics.OUTCOME_ERROR)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========================
    // recordDuration
    // ========================

    @Test
    void recordDuration_registersTimerWithTags() {
        metrics.recordDuration("calculator", "Researcher", Duration.ofMillis(150));

        Timer timer = registry.find(MicrometerToolMetrics.METRIC_DURATION)
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "calculator")
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Researcher")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(150.0);
    }

    @Test
    void recordDuration_multipleCalls_accumulatesInTimer() {
        metrics.recordDuration("datetime", "Writer", Duration.ofMillis(100));
        metrics.recordDuration("datetime", "Writer", Duration.ofMillis(200));
        metrics.recordDuration("datetime", "Writer", Duration.ofMillis(300));

        Timer timer = registry.find(MicrometerToolMetrics.METRIC_DURATION)
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "datetime")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3L);
    }

    // ========================
    // Different agents use same tool: separate tag combinations
    // ========================

    @Test
    void differentAgentRoles_areTrackedSeparately() {
        metrics.incrementSuccess("calculator", "Researcher");
        metrics.incrementSuccess("calculator", "Writer");

        double researcherCount = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Researcher")
                .counter()
                .count();
        double writerCount = registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS)
                .tag(MicrometerToolMetrics.TAG_AGENT_ROLE, "Writer")
                .counter()
                .count();

        assertThat(researcherCount).isEqualTo(1.0);
        assertThat(writerCount).isEqualTo(1.0);
    }

    // ========================
    // incrementCounter (custom)
    // ========================

    @Test
    void incrementCounter_withoutTags_recordsCustomCounter() {
        metrics.incrementCounter("cache.hits", "calculator", null);

        Counter counter = registry.find("cache.hits")
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "calculator")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incrementCounter_withTags_includesAllTags() {
        metrics.incrementCounter("retries", "web_search", Map.of("reason", "timeout"));

        Counter counter = registry.find("retries")
                .tag(MicrometerToolMetrics.TAG_TOOL_NAME, "web_search")
                .tag("reason", "timeout")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========================
    // recordValue (custom gauge)
    // ========================

    @Test
    void recordValue_withoutTags_registersGauge() {
        metrics.recordValue("result.size", "json_parser", 42.0, null);

        // Gauge exists in registry
        assertThat(registry.find("result.size").gauges()).isNotEmpty();
    }

    @Test
    void recordValue_withTags_registersGaugeWithTags() {
        metrics.recordValue("response.length", "http_tool", 1024.0, Map.of("endpoint", "/api"));

        assertThat(registry.find("response.length").tag("endpoint", "/api").gauges())
                .isNotEmpty();
    }
}
