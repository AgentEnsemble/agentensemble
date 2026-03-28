package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildRunnerToolTest {

    @TempDir
    Path tempDir;

    private BuildRunnerTool tool;

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
        tool = BuildRunnerTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsBuildRunner() {
        assertThat(tool.name()).isEqualTo("build_runner");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful build ---

    @Test
    void execute_successfulBuild_returnsSuccessWithStructuredOutput() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo BUILD SUCCESSFUL\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("BUILD SUCCESSFUL");
        assertThat(result.getStructuredOutput()).isNotNull();

        JsonNode structured = (JsonNode) result.getStructuredOutput();
        assertThat(structured.get("success").asBoolean()).isTrue();
    }

    // --- failed build ---

    @Test
    void execute_failedBuild_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"exit 1\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- structured output with errors/warnings ---

    @Test
    void execute_outputWithErrors_capturesErrors() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute(
                "{\"command\": \"echo 'error: cannot find symbol' && echo 'warning: deprecated API used'\"}");

        assertThat(result.isSuccess()).isTrue();
        JsonNode structured = (JsonNode) result.getStructuredOutput();
        assertThat(structured.get("errors").size()).isGreaterThan(0);
        assertThat(structured.get("warnings").size()).isGreaterThan(0);
    }

    @Test
    void execute_cleanOutput_hasEmptyErrorsAndWarnings() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo 'all good'\"}");

        assertThat(result.isSuccess()).isTrue();
        JsonNode structured = (JsonNode) result.getStructuredOutput();
        assertThat(structured.get("errors").size()).isEqualTo(0);
        assertThat(structured.get("warnings").size()).isEqualTo(0);
    }

    // --- working directory ---

    @Test
    void execute_withWorkingDir_usesCorrectDirectory() throws IOException {
        assumeTrue(shellAvailable, "Shell not available");
        Files.createDirectory(tempDir.resolve("subproject"));

        var result = tool.execute("{\"command\": \"pwd\", \"workingDir\": \"subproject\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("subproject");
    }

    @Test
    void execute_workingDirTraversal_returnsFailure() {
        var result = tool.execute("{\"command\": \"ls\", \"workingDir\": \"../outside\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- timeout ---

    @Test
    void execute_timeout_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");
        var timedTool =
                BuildRunnerTool.builder(tempDir).timeout(Duration.ofMillis(200)).build();

        var result = timedTool.execute("{\"command\": \"sleep 60\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("timed out");
    }

    // --- blank command ---

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

    // --- factory/builder validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> BuildRunnerTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> BuildRunnerTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_negativeTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> BuildRunnerTool.builder(tempDir).timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> BuildRunnerTool.builder(tempDir.resolve("nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_workingDirNotDirectory_returnsFailure() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "content");

        var result = tool.execute("{\"command\": \"ls\", \"workingDir\": \"file.txt\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a directory");
    }
}
