package net.agentensemble.trace;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;
import net.agentensemble.metrics.CostEstimate;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.trace.export.ExecutionTraceExporter;

/**
 * Complete execution trace for a single ensemble run.
 *
 * <p>The top-level trace object containing every task trace, all aggregated metrics,
 * agent configuration snapshots, input variables, and optional cost estimates.
 * Available via {@code EnsembleOutput.getTrace()}.
 *
 * <h2>JSON Export</h2>
 *
 * <pre>
 * // Write to file
 * output.getTrace().toJson(Path.of("run-trace.json"));
 *
 * // Get as string
 * String json = output.getTrace().toJson();
 *
 * // Custom exporter
 * output.getTrace().export(myExporter);
 * </pre>
 *
 * <h2>Schema versioning</h2>
 *
 * <p>The {@code schemaVersion} field identifies the trace format version. Consumers
 * should check this field before parsing to ensure compatibility.
 */
@Value
@Builder(toBuilder = true)
public class ExecutionTrace {

    /** Current schema version for this trace format. */
    public static final String CURRENT_SCHEMA_VERSION = "1.1";

    private static final ObjectMapper MAPPER = buildObjectMapper();

    /**
     * Schema version identifier for this trace object.
     * Defaults to {@link #CURRENT_SCHEMA_VERSION}.
     */
    @Builder.Default
    String schemaVersion = CURRENT_SCHEMA_VERSION;

    /**
     * The capture mode that was active during this run.
     *
     * <p>Consumers can inspect this field to determine what depth of data to expect:
     * {@link CaptureMode#OFF} means no message history or parsed inputs are present;
     * {@link CaptureMode#STANDARD} means LLM message history and memory counts are populated;
     * {@link CaptureMode#FULL} means all of the above plus enriched tool I/O.
     */
    @NonNull
    @Builder.Default
    CaptureMode captureMode = CaptureMode.OFF;

    /**
     * Unique identifier for this ensemble run.
     * Matches the {@code ensemble.id} MDC value in log output.
     */
    @NonNull
    String ensembleId;

    /** The workflow strategy used for this run (e.g., {@code "SEQUENTIAL"}). */
    @NonNull
    String workflow;

    /** Wall-clock instant when the ensemble run began. */
    @NonNull
    Instant startedAt;

    /** Wall-clock instant when the ensemble run completed (or failed). */
    @NonNull
    Instant completedAt;

    /** Total elapsed time for the run ({@code completedAt - startedAt}). */
    @NonNull
    Duration totalDuration;

    /**
     * Template variable inputs that were resolved before task execution.
     * Empty when no template variables were used.
     */
    @Singular("input")
    Map<String, String> inputs;

    /**
     * Snapshot of all agent configurations registered with the ensemble.
     * Ordered by registration order.
     */
    @Singular
    List<AgentSummary> agents;

    /**
     * Ordered list of task traces.
     * For sequential and parallel workflows, order matches the task execution order.
     * For hierarchical workflows, the manager's trace appears first, followed by
     * worker task traces in the order they were delegated.
     */
    @Singular
    List<TaskTrace> taskTraces;

    /** Aggregated metrics for the entire run. */
    @NonNull
    ExecutionMetrics metrics;

    /**
     * Aggregated cost estimate for the entire run.
     * {@code null} when no {@link net.agentensemble.metrics.CostConfiguration} was configured.
     */
    CostEstimate totalCostEstimate;

    /**
     * Errors that occurred during the run.
     * Empty for successful runs. When a task fails, an {@link ErrorTrace} is appended here.
     */
    @Singular
    List<ErrorTrace> errors;

    /**
     * Per-level summaries for adaptive map-reduce runs.
     *
     * <p>Populated only when the trace was produced by an adaptive
     * {@link net.agentensemble.mapreduce.MapReduceEnsemble} run. Each entry corresponds
     * to one level of execution: level 0 = map phase, level 1+ = intermediate reduce levels,
     * the highest index = final reduce level. Empty for all other run types.
     */
    @Singular("mapReduceLevel")
    List<MapReduceLevelSummary> mapReduceLevels;

    /**
     * Optional distributed trace identifier linking this execution to an external
     * distributed trace viewer (e.g., Jaeger, Zipkin). Set when an
     * {@code OTelTracingListener} is registered with the ensemble.
     *
     * <p>{@code null} when no OpenTelemetry integration is configured.
     */
    String traceId;

    /**
     * Optional caller-supplied metadata for the entire run.
     * Usable by framework extensions or application code for custom annotations.
     */
    @Singular("metadataEntry")
    Map<String, Object> metadata;

    // ========================
    // JSON export methods
    // ========================

    /**
     * Serialize this trace to a pretty-printed JSON string.
     *
     * <p>{@link Instant} fields are encoded as ISO-8601 strings (e.g., {@code "2026-03-05T09:00:00Z"}).
     * {@link Duration} fields are encoded as ISO-8601 duration strings (e.g., {@code "PT12.345S"}).
     *
     * @return the JSON representation of this trace
     * @throws UncheckedIOException if serialization fails
     */
    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize ExecutionTrace to JSON", e);
        }
    }

    /**
     * Write this trace as pretty-printed JSON to the specified file path.
     *
     * <p>Parent directories are created if they do not already exist.
     *
     * @param outputPath the path to write the JSON file; must not be {@code null}
     * @throws UncheckedIOException if the file cannot be written or serialization fails
     */
    public void toJson(Path outputPath) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ExecutionTrace to " + outputPath, e);
        }
    }

    /**
     * Serialize this trace to TOON (Token-Oriented Object Notation) format.
     *
     * <p>Requires {@code dev.toonformat:jtoon} on the runtime classpath. If JToon is not
     * available, throws {@link IllegalStateException} with dependency instructions.
     *
     * @return a TOON string representation of this trace; never null
     * @throws IllegalStateException if JToon is not on the classpath
     */
    public String toToon() {
        net.agentensemble.format.ContextFormatter formatter =
                net.agentensemble.format.ContextFormatters.forFormat(net.agentensemble.format.ContextFormat.TOON);
        return formatter.format(this);
    }

    /**
     * Write this trace to the specified file in TOON format.
     *
     * <p>Parent directories are created automatically if they do not exist.
     * Requires {@code dev.toonformat:jtoon} on the runtime classpath.
     *
     * @param outputPath the file to write to; must not be {@code null}
     * @throws IllegalStateException if JToon is not on the classpath
     * @throws UncheckedIOException  if file I/O fails
     */
    public void toToon(Path outputPath) {
        try {
            String toon = toToon();
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, toon);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ExecutionTrace to " + outputPath, e);
        }
    }

    /**
     * Export this trace using the provided exporter.
     *
     * @param exporter the exporter to use; must not be {@code null}
     */
    public void export(ExecutionTraceExporter exporter) {
        exporter.export(this);
    }

    // ========================
    // Private helpers
    // ========================

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return mapper;
    }
}
