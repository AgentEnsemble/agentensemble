package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellToolTest {

    @TempDir
    Path tempDir;

    private ShellTool tool;

    private static boolean shellAvailable;

    @BeforeAll
    static void checkShellAvailability() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "echo test").start();
            shellAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            shellAvailable = false;
        }
    }

    @BeforeEach
    void setUp() {
        tool = ShellTool.builder(tempDir).requireApproval(false).build();
    }

    // --- metadata ---

    @Test
    void name_returnsShell() {
        assertThat(tool.name()).isEqualTo("shell");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful execution ---

    @Test
    void execute_echoCommand_returnsOutput() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo hello\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("hello");
    }

    @Test
    void execute_commandWithWorkingDir_usesCorrectDirectory() throws IOException {
        assumeTrue(shellAvailable, "Shell not available");
        Files.createDirectory(tempDir.resolve("subdir"));

        var result = tool.execute("{\"command\": \"pwd\", \"workingDir\": \"subdir\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("subdir");
    }

    // --- non-zero exit ---

    @Test
    void execute_nonZeroExit_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"exit 1\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- timeout ---

    @Test
    void execute_timeout_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");
        var timedTool = ShellTool.builder(tempDir)
                .requireApproval(false)
                .timeout(Duration.ofMillis(200))
                .build();

        var result = timedTool.execute("{\"command\": \"sleep 60\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("timed out");
    }

    @Test
    void execute_customTimeoutInInput_isUsed() {
        assumeTrue(shellAvailable, "Shell not available");
        // 1 second timeout via input should be enough for echo
        var result = tool.execute("{\"command\": \"echo fast\", \"timeoutSeconds\": 1}");

        assertThat(result.isSuccess()).isTrue();
    }

    // --- output truncation ---

    @Test
    void execute_longOutput_isTruncated() {
        assumeTrue(shellAvailable, "Shell not available");
        var shortOutputTool = ShellTool.builder(tempDir)
                .requireApproval(false)
                .maxOutputLength(50)
                .build();

        var result = shortOutputTool.execute("{\"command\": \"seq 1 1000\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("truncated");
    }

    // --- working directory validation ---

    @Test
    void execute_workingDirTraversal_returnsFailure() {
        var result = tool.execute("{\"command\": \"ls\", \"workingDir\": \"../outside\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_workingDirNotDirectory_returnsFailure() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "content");

        var result = tool.execute("{\"command\": \"ls\", \"workingDir\": \"file.txt\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a directory");
    }

    // --- blank/null command ---

    @Test
    void execute_blankCommand_returnsFailure() {
        var result = tool.execute("{\"command\": \"   \"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("blank");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- approval gate ---

    private ShellTool approvalToolWithHandler(ReviewHandler handler) {
        var builtTool = ShellTool.builder(tempDir).requireApproval(true).build();
        var ctx = ToolContext.of(
                builtTool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(builtTool, ctx);
        return builtTool;
    }

    @Test
    void requireApproval_handlerContinue_executesCommand() {
        assumeTrue(shellAvailable, "Shell not available");
        var approvalTool = approvalToolWithHandler(ReviewHandler.autoApprove());

        var result = approvalTool.execute("{\"command\": \"echo approved\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("approved");
    }

    @Test
    void requireApproval_handlerExitEarly_returnsFailure() {
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = approvalTool.execute("{\"command\": \"echo should not run\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
    }

    @Test
    void requireApproval_noHandler_throwsToolConfigurationException() {
        var builtTool = ShellTool.builder(tempDir).requireApproval(true).build();

        assertThatThrownBy(() -> builtTool.execute("{\"command\": \"echo test\"}"))
                .isInstanceOf(ToolConfigurationException.class)
                .hasMessageContaining("requires approval");
    }

    // --- stderr capture ---

    @Test
    void execute_stderrCaptured_inOutput() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo err >&2 && echo out\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("out");
        assertThat(result.getOutput()).contains("err");
    }

    @Test
    void execute_onlyStderrOnSuccess_returnsStderr() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo warning >&2\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("warning");
    }

    // --- builder validation ---

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> ShellTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_negativeTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ShellTool.builder(tempDir).timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_zeroMaxOutput_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ShellTool.builder(tempDir).maxOutputLength(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
