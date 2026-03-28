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

class TestRunnerToolTest {

    @TempDir
    Path tempDir;

    private TestRunnerTool tool;

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
        tool = TestRunnerTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsTestRunner() {
        assertThat(tool.name()).isEqualTo("test_runner");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- Gradle output parsing ---

    @Test
    void parseTestOutput_gradleFormat_parsesCorrectly() {
        String output = "6 tests completed, 2 failed\n\n"
                + "FAILED com.example.FooTest > testFoo()\n"
                + "  expected: true but was: false\n";

        TestResult result = tool.parseTestOutput(output, false);

        assertThat(result.success()).isFalse();
        assertThat(result.passed()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.failures()).isNotEmpty();
    }

    @Test
    void parseTestOutput_gradleAllPassing_parsesCorrectly() {
        String output = "10 tests completed, 0 failed";

        TestResult result = tool.parseTestOutput(output, true);

        assertThat(result.success()).isTrue();
        assertThat(result.passed()).isEqualTo(10);
        assertThat(result.failed()).isEqualTo(0);
    }

    // --- Maven Surefire output parsing ---

    @Test
    void parseTestOutput_mavenFormat_parsesCorrectly() {
        String output = "Tests run: 10, Failures: 2, Errors: 1, Skipped: 3";

        TestResult result = tool.parseTestOutput(output, false);

        assertThat(result.success()).isFalse();
        assertThat(result.passed()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    void parseTestOutput_mavenAllPassing_parsesCorrectly() {
        String output = "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0";

        TestResult result = tool.parseTestOutput(output, true);

        assertThat(result.success()).isTrue();
        assertThat(result.passed()).isEqualTo(5);
        assertThat(result.failed()).isEqualTo(0);
    }

    // --- npm/Jest output parsing ---

    @Test
    void parseTestOutput_npmFormat_parsesCorrectly() {
        String output = "Tests:  3 passed, 1 failed";

        TestResult result = tool.parseTestOutput(output, false);

        assertThat(result.success()).isFalse();
        assertThat(result.passed()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void parseTestOutput_npmAllPassing_parsesCorrectly() {
        String output = "Tests:  5 passed";

        TestResult result = tool.parseTestOutput(output, true);

        assertThat(result.success()).isTrue();
        assertThat(result.passed()).isEqualTo(5);
        assertThat(result.failed()).isEqualTo(0);
    }

    // --- failure detail extraction ---

    @Test
    void parseTestOutput_withStackTrace_extractsDetails() {
        String output = "3 tests completed, 1 failed\n\n"
                + "FAILED com.example.FooTest > testBar()\n"
                + "  org.opentest4j.AssertionFailedError: expected true\n"
                + "  at com.example.FooTest.testBar(FooTest.java:42)\n"
                + "  at java.base/java.lang.reflect.Method.invoke(Method.java:580)\n";

        TestResult result = tool.parseTestOutput(output, false);

        assertThat(result.failures()).isNotEmpty();
        // The FAILURE_MARKER matches "failed" in the summary line
        TestFailure failure = result.failures().stream()
                .filter(f -> f.testName().contains("FooTest"))
                .findFirst()
                .orElse(result.failures().getFirst());
        assertThat(failure.testName()).isNotBlank();
    }

    @Test
    void parseTestOutput_gradleSingleTest_parsesCorrectly() {
        String output = "1 test completed, 0 failed";

        TestResult result = tool.parseTestOutput(output, true);

        assertThat(result.success()).isTrue();
        assertThat(result.passed()).isEqualTo(1);
    }

    // --- fallback parsing ---

    @Test
    void parseTestOutput_unknownFormat_usesExitCode() {
        String output = "some custom test framework output";

        TestResult successResult = tool.parseTestOutput(output, true);
        assertThat(successResult.success()).isTrue();

        TestResult failResult = tool.parseTestOutput(output, false);
        assertThat(failResult.success()).isFalse();
    }

    // --- end-to-end execution ---

    @Test
    void execute_successfulTestRun_returnsSuccess() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute("{\"command\": \"echo '10 tests completed, 0 failed'\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredOutput()).isNotNull();

        JsonNode structured = (JsonNode) result.getStructuredOutput();
        assertThat(structured.get("success").asBoolean()).isTrue();
        assertThat(structured.get("passed").asInt()).isEqualTo(10);
    }

    @Test
    void execute_failedTestRun_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");

        var result = tool.execute(
                "{\"command\": \"echo '3 tests completed, 1 failed' && echo 'FAILED FooTest' && exit 1\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_withTestFilter_appendsFilter() {
        assumeTrue(shellAvailable, "Shell not available");

        // The filter should be appended to the command
        var result = tool.execute("{\"command\": \"echo\", \"testFilter\": \"Tests: 1 passed\"}");

        assertThat(result.isSuccess()).isTrue();
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
        var result = tool.execute("{\"command\": \"echo\", \"workingDir\": \"../outside\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- timeout ---

    @Test
    void execute_timeout_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");
        var timedTool =
                TestRunnerTool.builder(tempDir).timeout(Duration.ofMillis(200)).build();

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
        assertThatThrownBy(() -> TestRunnerTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> TestRunnerTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_negativeTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TestRunnerTool.builder(tempDir).timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TestRunnerTool.builder(tempDir.resolve("nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_workingDirNotDirectory_returnsFailure() throws IOException {
        Files.writeString(tempDir.resolve("file.txt"), "content");

        var result = tool.execute("{\"command\": \"echo\", \"workingDir\": \"file.txt\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a directory");
    }
}
