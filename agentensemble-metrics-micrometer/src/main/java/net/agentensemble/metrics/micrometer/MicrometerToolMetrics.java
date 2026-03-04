package net.agentensemble.metrics.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.agentensemble.tool.ToolMetrics;

/**
 * {@link ToolMetrics} implementation that records measurements to a Micrometer
 * {@link MeterRegistry}.
 *
 * <h2>Metrics recorded</h2>
 *
 * <ul>
 *   <li>{@code agentensemble.tool.executions} (counter, tags: tool_name, agent_role, outcome):
 *       total tool invocations, tagged by outcome ({@code success}, {@code failure},
 *       {@code error})</li>
 *   <li>{@code agentensemble.tool.duration} (timer, tags: tool_name, agent_role):
 *       execution duration per tool+agent combination</li>
 * </ul>
 *
 * <h2>Tags</h2>
 *
 * <p>All standard metrics are tagged with:
 * <ul>
 *   <li>{@code tool_name}: the name from {@link net.agentensemble.tool.AgentTool#name()}</li>
 *   <li>{@code agent_role}: the role of the agent that invoked the tool</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * MeterRegistry registry = new SimpleMeterRegistry(); // or Prometheus, etc.
 * ToolMetrics metrics = new MicrometerToolMetrics(registry);
 *
 * Ensemble.builder()
 *     .toolMetrics(metrics)
 *     .build();
 * </pre>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All Micrometer operations are thread-safe. This class is safe for concurrent
 * use from multiple virtual threads.
 */
public final class MicrometerToolMetrics implements ToolMetrics {

    /** Counter metric name for tool executions, tagged by outcome. */
    public static final String METRIC_EXECUTIONS = "agentensemble.tool.executions";

    /** Timer metric name for tool execution duration. */
    public static final String METRIC_DURATION = "agentensemble.tool.duration";

    static final String TAG_TOOL_NAME = "tool_name";
    static final String TAG_AGENT_ROLE = "agent_role";
    static final String TAG_OUTCOME = "outcome";

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_FAILURE = "failure";
    static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;

    /**
     * Create a new {@code MicrometerToolMetrics} backed by the given registry.
     *
     * @param registry the Micrometer registry to record metrics to; must not be null
     */
    public MicrometerToolMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void incrementSuccess(String toolName, String agentRole) {
        executionCounter(toolName, agentRole, OUTCOME_SUCCESS).increment();
    }

    @Override
    public void incrementFailure(String toolName, String agentRole) {
        executionCounter(toolName, agentRole, OUTCOME_FAILURE).increment();
    }

    @Override
    public void incrementError(String toolName, String agentRole) {
        executionCounter(toolName, agentRole, OUTCOME_ERROR).increment();
    }

    @Override
    public void recordDuration(String toolName, String agentRole, Duration duration) {
        Timer.builder(METRIC_DURATION)
                .description("Tool execution duration")
                .tags(TAG_TOOL_NAME, toolName, TAG_AGENT_ROLE, agentRole)
                .register(registry)
                .record(duration);
    }

    @Override
    public void incrementCounter(String metricName, String toolName, Map<String, String> tags) {
        Counter.Builder builder = Counter.builder(metricName)
                .description("Custom counter from tool: " + toolName)
                .tag(TAG_TOOL_NAME, toolName);
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                builder.tag(entry.getKey(), entry.getValue());
            }
        }
        builder.register(registry).increment();
    }

    @Override
    public void recordValue(String metricName, String toolName, double value, Map<String, String> tags) {
        List<Tag> tagList = new ArrayList<>();
        tagList.add(Tag.of(TAG_TOOL_NAME, toolName));
        if (tags != null) {
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                tagList.add(Tag.of(entry.getKey(), entry.getValue()));
            }
        }
        registry.gauge(metricName, tagList, value);
    }

    // ========================
    // Private helpers
    // ========================

    private Counter executionCounter(String toolName, String agentRole, String outcome) {
        return Counter.builder(METRIC_EXECUTIONS)
                .description("Total tool invocations by outcome")
                .tags(TAG_TOOL_NAME, toolName, TAG_AGENT_ROLE, agentRole, TAG_OUTCOME, outcome)
                .register(registry);
    }
}
