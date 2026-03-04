package net.agentensemble.tool;

import java.time.Duration;
import java.util.Map;

/**
 * Pluggable metrics interface for recording tool execution measurements.
 *
 * <p>Implementations bridge to the metrics backend of choice (e.g., Micrometer,
 * Prometheus, custom). The default implementation is {@link NoOpToolMetrics}, which
 * discards all measurements with zero overhead.
 *
 * <p>The framework automatically records three standard metrics for every
 * {@link AbstractAgentTool} execution:
 *
 * <ul>
 *   <li>{@code incrementSuccess(toolName, agentRole)} -- on successful execution
 *   <li>{@code incrementFailure(toolName, agentRole)} -- on failed execution (tool returned failure)
 *   <li>{@code incrementError(toolName, agentRole)} -- on exception thrown during execution
 *   <li>{@code recordDuration(toolName, agentRole, duration)} -- on every execution
 * </ul>
 *
 * <p>Tool implementations may record additional custom metrics via the {@code record*}
 * methods, obtained from {@link AbstractAgentTool#metrics()}.
 *
 * <p>Implementations must be thread-safe -- metrics may be recorded concurrently
 * from multiple virtual threads when the parallel tool executor is in use.
 */
public interface ToolMetrics {

    /**
     * Increment the success counter for the named tool invoked by the named agent.
     *
     * @param toolName  the name of the tool (from {@link AgentTool#name()})
     * @param agentRole the role of the agent that invoked the tool
     */
    void incrementSuccess(String toolName, String agentRole);

    /**
     * Increment the failure counter for the named tool invoked by the named agent.
     * A failure is a result where {@link ToolResult#isSuccess()} returns false,
     * as opposed to an exception (which is counted by {@link #incrementError}).
     *
     * @param toolName  the name of the tool
     * @param agentRole the role of the agent that invoked the tool
     */
    void incrementFailure(String toolName, String agentRole);

    /**
     * Increment the error counter for the named tool invoked by the named agent.
     * An error is an unexpected exception thrown during tool execution.
     *
     * @param toolName  the name of the tool
     * @param agentRole the role of the agent that invoked the tool
     */
    void incrementError(String toolName, String agentRole);

    /**
     * Record the execution duration for the named tool invoked by the named agent.
     *
     * @param toolName  the name of the tool
     * @param agentRole the role of the agent that invoked the tool
     * @param duration  the time elapsed during tool execution
     */
    void recordDuration(String toolName, String agentRole, Duration duration);

    /**
     * Record an arbitrary named counter increment, optionally tagged.
     * Use this for custom tool-specific metrics (e.g., cache hits, retries).
     *
     * @param metricName the metric name
     * @param toolName   the name of the tool recording the metric
     * @param tags       optional key-value tags; may be null or empty
     */
    void incrementCounter(String metricName, String toolName, Map<String, String> tags);

    /**
     * Record an arbitrary named numeric value, optionally tagged.
     * Use this for custom tool-specific measurements (e.g., result size, latency breakdown).
     *
     * @param metricName the metric name
     * @param toolName   the name of the tool recording the metric
     * @param value      the numeric value to record
     * @param tags       optional key-value tags; may be null or empty
     */
    void recordValue(String metricName, String toolName, double value, Map<String, String> tags);
}
