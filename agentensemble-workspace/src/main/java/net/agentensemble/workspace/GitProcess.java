package net.agentensemble.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private helper for executing git commands as subprocesses.
 *
 * <p>Uses {@link ProcessBuilder} with virtual-thread stdout/stderr draining to avoid
 * deadlocks on large output. Follows the same pattern as {@code ProcessAgentTool}.
 */
final class GitProcess {

    private static final Logger LOG = LoggerFactory.getLogger(GitProcess.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private GitProcess() {}

    /**
     * Result of a git command execution.
     *
     * @param exitCode the process exit code
     * @param stdout the captured standard output (trimmed)
     * @param stderr the captured standard error (trimmed)
     */
    record Result(int exitCode, String stdout, String stderr) {

        /**
         * Whether the command completed successfully (exit code 0).
         *
         * @return {@code true} if exit code is 0
         */
        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Run a git command with the default timeout.
     *
     * @param workingDirectory the directory to run the command in
     * @param args git subcommand and arguments (e.g. {@code "worktree", "add", ...})
     * @return the command result
     * @throws WorkspaceException if the process cannot be started or is interrupted
     */
    static Result run(Path workingDirectory, String... args) {
        return run(workingDirectory, DEFAULT_TIMEOUT, args);
    }

    /**
     * Run a git command with a custom timeout.
     *
     * @param workingDirectory the directory to run the command in
     * @param timeout maximum time to wait for the command to complete
     * @param args git subcommand and arguments (e.g. {@code "worktree", "add", ...})
     * @return the command result
     * @throws WorkspaceException if the process cannot be started or is interrupted
     */
    static Result run(Path workingDirectory, Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        LOG.debug("Running git command in {}: {}", workingDirectory, command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);

        try {
            Process process = pb.start();

            // Drain stdout and stderr concurrently on virtual threads to avoid pipe-buffer
            // deadlocks when the process produces more output than the OS buffer (~64 KB).
            ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();
            Thread stdoutDrain = Thread.ofVirtual().start(() -> {
                try {
                    process.getInputStream().transferTo(stdoutCapture);
                } catch (IOException e) {
                    LOG.debug("Exception draining stdout from git subprocess", e);
                }
            });
            Thread stderrDrain = Thread.ofVirtual().start(() -> {
                try {
                    process.getErrorStream().transferTo(stderrCapture);
                } catch (IOException e) {
                    LOG.debug("Exception draining stderr from git subprocess", e);
                }
            });

            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                stdoutDrain.join();
                stderrDrain.join();
                throw new WorkspaceException(
                        "Git command timed out after " + timeout.toSeconds() + " seconds: " + command);
            }

            stdoutDrain.join();
            stderrDrain.join();

            String stdout = stdoutCapture.toString(StandardCharsets.UTF_8).trim();
            String stderr = stderrCapture.toString(StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();

            LOG.debug("Git command exited with code {}: {}", exitCode, command);
            return new Result(exitCode, stdout, stderr);

        } catch (IOException e) {
            throw new WorkspaceException("Failed to start git process: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException("Git command interrupted: " + command, e);
        }
    }
}
