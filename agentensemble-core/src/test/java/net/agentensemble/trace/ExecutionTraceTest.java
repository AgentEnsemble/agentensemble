package net.agentensemble.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.agentensemble.metrics.ExecutionMetrics;
import org.junit.jupiter.api.Test;

/**
 * Tests for ExecutionTrace construction, JSON serialization, and schema versioning.
 */
class ExecutionTraceTest {

    private static ExecutionTrace minimalTrace() {
        return ExecutionTrace.builder()
                .ensembleId("test-id-123")
                .workflow("SEQUENTIAL")
                .startedAt(Instant.parse("2026-03-05T09:00:00Z"))
                .completedAt(Instant.parse("2026-03-05T09:00:12Z"))
                .totalDuration(Duration.ofSeconds(12))
                .metrics(ExecutionMetrics.EMPTY)
                .build();
    }

    @Test
    void testBuilder_setsSchemaVersionDefault() {
        ExecutionTrace trace = minimalTrace();
        assertThat(trace.getSchemaVersion()).isEqualTo(ExecutionTrace.CURRENT_SCHEMA_VERSION);
        assertThat(trace.getSchemaVersion()).isEqualTo("1.1");
    }

    @Test
    void testBuilder_ensembleIdSet() {
        ExecutionTrace trace = minimalTrace();
        assertThat(trace.getEnsembleId()).isEqualTo("test-id-123");
    }

    @Test
    void testBuilder_emptyCollections_areEmpty() {
        ExecutionTrace trace = minimalTrace();
        assertThat(trace.getTaskTraces()).isEmpty();
        assertThat(trace.getAgents()).isEmpty();
        assertThat(trace.getErrors()).isEmpty();
        assertThat(trace.getInputs()).isEmpty();
        assertThat(trace.getMetadata()).isEmpty();
    }

    @Test
    void testToJson_returnsValidJsonString() {
        ExecutionTrace trace = minimalTrace();
        String json = trace.toJson();

        assertThat(json).isNotBlank();
        assertThat(json).contains("\"ensembleId\"");
        assertThat(json).contains("\"test-id-123\"");
        assertThat(json).contains("\"schemaVersion\"");
        assertThat(json).contains("\"1.1\"");
        assertThat(json).contains("\"workflow\"");
        assertThat(json).contains("\"SEQUENTIAL\"");
    }

    @Test
    void testToJson_instantsAreISO8601Strings() {
        ExecutionTrace trace = minimalTrace();
        String json = trace.toJson();

        // Instants should be serialized as ISO-8601, not timestamps
        assertThat(json).contains("2026-03-05");
        assertThat(json).doesNotContain("\"startedAt\":[");
    }

    @Test
    void testToJson_durationsAreISO8601Strings() {
        ExecutionTrace trace = minimalTrace();
        String json = trace.toJson();

        // Duration PT12S
        assertThat(json).contains("PT12S");
    }

    @Test
    void testToJson_withTaskTrace_includesInJson() {
        Instant now = Instant.now();
        TaskTrace taskTrace = TaskTrace.builder()
                .agentRole("Researcher")
                .taskDescription("Research topic")
                .expectedOutput("Research output")
                .startedAt(now)
                .completedAt(now.plusSeconds(5))
                .duration(Duration.ofSeconds(5))
                .finalOutput("Research completed")
                .metrics(net.agentensemble.metrics.TaskMetrics.EMPTY)
                .build();

        ExecutionTrace trace = ExecutionTrace.builder()
                .ensembleId("trace-with-tasks")
                .workflow("SEQUENTIAL")
                .startedAt(now)
                .completedAt(now.plusSeconds(5))
                .totalDuration(Duration.ofSeconds(5))
                .metrics(ExecutionMetrics.EMPTY)
                .taskTrace(taskTrace)
                .build();

        String json = trace.toJson();

        assertThat(json).contains("\"Researcher\"");
        assertThat(json).contains("\"Research topic\"");
        assertThat(json).contains("\"Research completed\"");
    }

    @Test
    void testToJson_toPath_writesFile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("trace-test");
        java.nio.file.Path outputFile = dir.resolve("test-trace.json");

        ExecutionTrace trace = minimalTrace();
        trace.toJson(outputFile);

        assertThat(outputFile).exists();
        String content = java.nio.file.Files.readString(outputFile);
        assertThat(content).contains("test-id-123");

        // Cleanup
        java.nio.file.Files.deleteIfExists(outputFile);
        java.nio.file.Files.deleteIfExists(dir);
    }
}
