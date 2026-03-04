package net.agentensemble.tool;

import java.time.Duration;
import java.util.Map;

/**
 * No-op implementation of {@link ToolMetrics} that discards all measurements.
 *
 * <p>This is the default metrics implementation used when no metrics backend is
 * configured. All methods return immediately with zero overhead.
 *
 * <p>To enable metrics, configure a {@link ToolMetrics} implementation on the
 * Ensemble builder (e.g., {@code MicrometerToolMetrics} from the
 * {@code agentensemble-metrics-micrometer} module).
 */
public final class NoOpToolMetrics implements ToolMetrics {

    /** Singleton instance. This class is stateless and thread-safe. */
    public static final NoOpToolMetrics INSTANCE = new NoOpToolMetrics();

    private NoOpToolMetrics() {}

    @Override
    public void incrementSuccess(String toolName, String agentRole) {}

    @Override
    public void incrementFailure(String toolName, String agentRole) {}

    @Override
    public void incrementError(String toolName, String agentRole) {}

    @Override
    public void recordDuration(String toolName, String agentRole, Duration duration) {}

    @Override
    public void incrementCounter(String metricName, String toolName, Map<String, String> tags) {}

    @Override
    public void recordValue(String metricName, String toolName, double value, Map<String, String> tags) {}
}
