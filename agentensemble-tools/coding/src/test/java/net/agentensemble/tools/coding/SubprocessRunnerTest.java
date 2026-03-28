package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SubprocessRunnerTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SubprocessRunnerTest.class);

    private static boolean shellAvailable;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void checkShellAvailability() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "echo test").start();
            shellAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            shellAvailable = false;
        }
    }

    // --- successful execution ---

    @Test
    void run_echoCommand_capturesStdout() throws IOException, InterruptedException {
        assumeTrue(shellAvailable, "Shell not available");

        var result =
                SubprocessRunner.run(List.of("sh", "-c", "echo hello world"), tempDir, Duration.ofSeconds(10), LOG);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("hello world");
    }

    @Test
    void run_stderrCapture_capturesStderr() throws IOException, InterruptedException {
        assumeTrue(shellAvailable, "Shell not available");

        var result = SubprocessRunner.run(List.of("sh", "-c", "echo error >&2"), tempDir, Duration.ofSeconds(10), LOG);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stderr()).isEqualTo("error");
    }

    // --- non-zero exit ---

    @Test
    void run_nonZeroExit_returnsExitCode() throws IOException, InterruptedException {
        assumeTrue(shellAvailable, "Shell not available");

        var result = SubprocessRunner.run(List.of("sh", "-c", "exit 42"), tempDir, Duration.ofSeconds(10), LOG);

        assertThat(result.exitCode()).isEqualTo(42);
    }

    // --- timeout ---

    @Test
    void run_timeout_returnsNegativeOneExitCode() throws IOException, InterruptedException {
        assumeTrue(shellAvailable, "Shell not available");

        var result = SubprocessRunner.run(List.of("sh", "-c", "sleep 60"), tempDir, Duration.ofMillis(200), LOG);

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderr()).containsIgnoringCase("timed out");
    }

    // --- invalid command ---

    @Test
    void run_nonExistentCommand_throwsIOException() {
        assertThatThrownBy(() ->
                        SubprocessRunner.run(List.of("nonexistent-command-xyz"), tempDir, Duration.ofSeconds(5), LOG))
                .isInstanceOf(IOException.class);
    }

    // --- working directory ---

    @Test
    void run_usesWorkingDirectory() throws IOException, InterruptedException {
        assumeTrue(shellAvailable, "Shell not available");

        var result = SubprocessRunner.run(List.of("sh", "-c", "pwd"), tempDir, Duration.ofSeconds(10), LOG);

        assertThat(result.exitCode()).isEqualTo(0);
        // The output should contain the temp directory path
        assertThat(Path.of(result.stdout()).toRealPath()).isEqualTo(tempDir.toRealPath());
    }
}
