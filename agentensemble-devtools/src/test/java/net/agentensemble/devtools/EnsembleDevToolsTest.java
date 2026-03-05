package net.agentensemble.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.devtools.dag.DagModel;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link EnsembleDevTools}.
 *
 * <p>Tests cover the facade methods for DAG export and trace export, verifying
 * that files are written with correct names and valid content.
 */
class EnsembleDevToolsTest {

    @TempDir
    Path tempDir;

    private Ensemble ensemble;
    private EnsembleOutput outputWithTrace;

    @BeforeEach
    void setUp() {
        ChatModel stub = new NoOpChatModel();
        Agent agent = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(stub)
                .build();
        Task task = Task.builder()
                .description("Research AI")
                .expectedOutput("A report")
                .agent(agent)
                .build();
        ensemble = Ensemble.builder().task(task).workflow(Workflow.SEQUENTIAL).build();

        ExecutionTrace trace = ExecutionTrace.builder()
                .ensembleId("test-id")
                .workflow("SEQUENTIAL")
                .captureMode(CaptureMode.OFF)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .totalDuration(Duration.ofSeconds(5))
                .metrics(ExecutionMetrics.EMPTY)
                .build();

        outputWithTrace = EnsembleOutput.builder()
                .raw("Research output")
                .taskOutputs(List.of())
                .totalDuration(Duration.ofSeconds(5))
                .totalToolCalls(0)
                .metrics(ExecutionMetrics.EMPTY)
                .trace(trace)
                .build();
    }

    // ========================
    // buildDag
    // ========================

    @Test
    void buildDag_returnsNonNullDagModel() {
        DagModel dag = EnsembleDevTools.buildDag(ensemble);
        assertThat(dag).isNotNull();
        assertThat(dag.getWorkflow()).isEqualTo("SEQUENTIAL");
    }

    @Test
    void buildDag_nullEnsemble_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> EnsembleDevTools.buildDag(null));
    }

    // ========================
    // exportDag
    // ========================

    @Test
    void exportDag_writesFileToDirectory() {
        Path result = EnsembleDevTools.exportDag(ensemble, tempDir);

        assertThat(result).exists();
        assertThat(result.getFileName().toString()).endsWith(".dag.json");
        assertThat(result.getFileName().toString()).startsWith("ensemble-dag-");
    }

    @Test
    void exportDag_writtenFileContainsValidJson() throws IOException {
        Path result = EnsembleDevTools.exportDag(ensemble, tempDir);

        String content = Files.readString(result);
        assertThat(content).contains("\"type\" : \"dag\"");
        assertThat(content).contains("\"workflow\" : \"SEQUENTIAL\"");
        assertThat(content).contains("\"schemaVersion\"");
    }

    @Test
    void exportDag_createsOutputDirectoryIfMissing() {
        Path subDir = tempDir.resolve("subdir").resolve("nested");
        assertThat(subDir).doesNotExist();

        Path result = EnsembleDevTools.exportDag(ensemble, subDir);

        assertThat(subDir).exists();
        assertThat(result).exists();
    }

    @Test
    void exportDag_nullEnsemble_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> EnsembleDevTools.exportDag(null, tempDir));
    }

    @Test
    void exportDag_nullOutputDir_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EnsembleDevTools.exportDag(ensemble, null))
                .withMessageContaining("outputDir");
    }

    // ========================
    // exportTrace
    // ========================

    @Test
    void exportTrace_writesFileToDirectory() {
        Path result = EnsembleDevTools.exportTrace(outputWithTrace, tempDir);

        assertThat(result).exists();
        assertThat(result.getFileName().toString()).endsWith(".trace.json");
        assertThat(result.getFileName().toString()).startsWith("ensemble-trace-");
    }

    @Test
    void exportTrace_writtenFileContainsValidJson() throws IOException {
        Path result = EnsembleDevTools.exportTrace(outputWithTrace, tempDir);

        String content = Files.readString(result);
        assertThat(content).contains("\"ensembleId\"");
        assertThat(content).contains("\"workflow\"");
        assertThat(content).contains("\"schemaVersion\"");
    }

    @Test
    void exportTrace_nullOutput_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> EnsembleDevTools.exportTrace(null, tempDir));
    }

    @Test
    void exportTrace_nullOutputDir_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EnsembleDevTools.exportTrace(outputWithTrace, null))
                .withMessageContaining("outputDir");
    }

    @Test
    void exportTrace_outputWithNoTrace_throwsIllegalArgument() {
        EnsembleOutput noTrace = EnsembleOutput.builder()
                .raw("output")
                .taskOutputs(List.of())
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .metrics(ExecutionMetrics.EMPTY)
                .trace(null)
                .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EnsembleDevTools.exportTrace(noTrace, tempDir))
                .withMessageContaining("no execution trace attached");
    }

    // ========================
    // export (combined)
    // ========================

    @Test
    void export_writesBothFiles() {
        EnsembleDevTools.ExportResult result = EnsembleDevTools.export(ensemble, outputWithTrace, tempDir);

        assertThat(result.dagPath()).exists();
        assertThat(result.tracePath()).exists();
        assertThat(result.dagPath().getFileName().toString()).endsWith(".dag.json");
        assertThat(result.tracePath().getFileName().toString()).endsWith(".trace.json");
    }

    @Test
    void export_describeReturnsFormattedString() {
        EnsembleDevTools.ExportResult result = EnsembleDevTools.export(ensemble, outputWithTrace, tempDir);
        String description = result.describe();

        assertThat(description).contains("DAG exported to:");
        assertThat(description).contains("Trace exported to:");
    }

    // ========================
    // Stub
    // ========================

    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in devtools tests");
        }
    }
}
