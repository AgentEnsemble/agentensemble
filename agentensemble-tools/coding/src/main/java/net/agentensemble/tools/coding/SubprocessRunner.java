package net.agentensemble.tools.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Shared subprocess execution utility for coding tools.
 *
 * <p>Extracts the ProcessBuilder + virtual-thread stdout/stderr draining + timeout
 * pattern from {@code ProcessAgentTool} to avoid duplication across tools.
 */
final class SubprocessRunner {

    /** Result of a subprocess execution. */
    record SubprocessResult(int exitCode, String stdout, String stderr) {}

    private SubprocessRunner() {}

    /**
     * Executes a command as a subprocess, drains stdout/stderr concurrently on virtual
     * threads, and waits up to the given timeout.
     *
     * @param command    the command and arguments
     * @param workingDir the working directory for the process
     * @param timeout    maximum time to wait for the process
     * @param log        logger for debug messages
     * @return the subprocess result containing exit code, stdout, and stderr
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    static SubprocessResult run(List<String> command, Path workingDir, Duration timeout, Logger log)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Close stdin immediately -- coding tools do not send input to subprocesses.
        try (OutputStream stdin = process.getOutputStream()) {
            // no-op; just close stdin
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Process stdin closed early: {}", e.getMessage());
            }
        }

        // Drain stdout and stderr concurrently on virtual threads to prevent deadlock.
        ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();
        Thread stdoutDrain = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(stdoutCapture);
            } catch (IOException e) {
                log.debug("Exception draining stdout from subprocess", e);
            }
        });
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(stderrCapture);
            } catch (IOException e) {
                log.debug("Exception draining stderr from subprocess", e);
            }
        });

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            stdoutDrain.join();
            stderrDrain.join();
            return new SubprocessResult(
                    -1,
                    stdoutCapture.toString(StandardCharsets.UTF_8).trim(),
                    "Process timed out after " + timeout.toSeconds() + " seconds");
        }

        stdoutDrain.join();
        stderrDrain.join();
        int exitCode = process.exitValue();
        String stdout = stdoutCapture.toString(StandardCharsets.UTF_8).trim();
        String stderr = stderrCapture.toString(StandardCharsets.UTF_8).trim();
        return new SubprocessResult(exitCode, stdout, stderr);
    }
}
