package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitProcessTest {

    private static boolean gitAvailable;

    @BeforeAll
    static void checkGitAvailability() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            gitAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            gitAvailable = false;
        }
    }

    @Test
    void run_successfulCommand(@TempDir Path tempDir) {
        assumeTrue(gitAvailable, "Git not available");

        GitProcess.Result result = GitProcess.run(tempDir, "--version");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).startsWith("git version");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void run_failingCommand(@TempDir Path tempDir) {
        assumeTrue(gitAvailable, "Git not available");

        // Running git log in a non-repo directory should fail
        GitProcess.Result result = GitProcess.run(tempDir, "log");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.exitCode()).isNotEqualTo(0);
    }

    @Test
    void run_timeout(@TempDir Path tempDir) throws Exception {
        assumeTrue(gitAvailable, "Git not available");

        // Create a real git repo so the command starts successfully, then use
        // hash-object --stdin which blocks waiting for input, reliably triggering
        // the timeout path.
        exec(tempDir, "git", "init");
        assertThatThrownBy(() -> GitProcess.run(tempDir, Duration.ofMillis(1), "hash-object", "--stdin"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("timed out");
    }

    private static void exec(Path dir, String... command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command));
        }
    }

    @Test
    void result_isSuccess_trueForZeroExitCode() {
        GitProcess.Result result = new GitProcess.Result(0, "ok", "");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void result_isSuccess_falseForNonZeroExitCode() {
        GitProcess.Result result = new GitProcess.Result(1, "", "error");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void run_capturesStderr(@TempDir Path tempDir) {
        assumeTrue(gitAvailable, "Git not available");

        // An invalid git command should produce stderr
        GitProcess.Result result = GitProcess.run(tempDir, "not-a-real-command");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).isNotEmpty();
    }
}
