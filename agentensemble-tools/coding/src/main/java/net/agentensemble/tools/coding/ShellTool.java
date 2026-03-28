package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that executes shell commands within a sandboxed workspace directory.
 *
 * <p>Uses {@code sh -c} on Unix-like systems and {@code cmd /c} on Windows. The shell is
 * detected at construction time. Output is truncated at a configurable limit to prevent
 * overwhelming the LLM context.
 *
 * <pre>
 * ShellTool tool = ShellTool.builder(Path.of("/workspace/project"))
 *     .requireApproval(true)
 *     .timeout(Duration.ofSeconds(60))
 *     .build();
 * </pre>
 */
public final class ShellTool extends AbstractTypedAgentTool<ShellInput> {

    static final int DEFAULT_MAX_OUTPUT_LENGTH = 10_000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final SandboxValidator sandbox;
    private final boolean requireApproval;
    private final Duration timeout;
    private final int maxOutputLength;
    private final List<String> shellPrefix;

    private ShellTool(Builder builder) {
        this.sandbox = builder.sandbox;
        this.requireApproval = builder.requireApproval;
        this.timeout = builder.timeout;
        this.maxOutputLength = builder.maxOutputLength;
        this.shellPrefix = detectShellPrefix();
    }

    private static List<String> detectShellPrefix() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return List.of("cmd", "/c");
        }
        return List.of("sh", "-c");
    }

    /**
     * Returns a new builder for configuring a {@code ShellTool}.
     *
     * @param baseDir the directory to sandbox execution within; must be an existing directory
     * @return a new Builder
     * @throws NullPointerException     if baseDir is null
     * @throws IllegalArgumentException if baseDir does not exist or is not a directory
     */
    public static Builder builder(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new Builder(new SandboxValidator(baseDir));
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Executes a shell command in the workspace directory. "
                + "Output is captured and returned. "
                + "Long output is truncated at " + maxOutputLength + " characters.";
    }

    @Override
    public Class<ShellInput> inputType() {
        return ShellInput.class;
    }

    @Override
    public ToolResult execute(ShellInput input) {
        if (input.command() == null || input.command().isBlank()) {
            return ToolResult.failure("Command must not be blank");
        }

        Path workingDir = sandbox.baseDir();
        if (input.workingDir() != null && !input.workingDir().isBlank()) {
            Path resolved = sandbox.resolveAndValidate(input.workingDir().trim());
            if (resolved == null) {
                return ToolResult.failure("Access denied: working directory is outside the workspace");
            }
            if (!Files.isDirectory(resolved)) {
                return ToolResult.failure("Working directory is not a directory: " + input.workingDir());
            }
            workingDir = resolved;
        }

        if (requireApproval) {
            if (rawReviewHandler() == null) {
                throw new ToolConfigurationException("Tool '"
                        + name()
                        + "' requires approval but no ReviewHandler is configured on the ensemble. "
                        + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            ReviewDecision decision =
                    requestApproval("Execute shell command: " + input.command() + "\nWorking directory: " + workingDir);
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("Shell command rejected by reviewer: " + input.command());
            }
        }

        Duration effectiveTimeout = timeout;
        if (input.timeoutSeconds() != null && input.timeoutSeconds() > 0) {
            effectiveTimeout = Duration.ofSeconds(input.timeoutSeconds());
        }

        try {
            List<String> command = new ArrayList<>(shellPrefix);
            command.add(input.command());

            SubprocessRunner.SubprocessResult result =
                    SubprocessRunner.run(command, workingDir, effectiveTimeout, log());

            if (result.exitCode() == -1) {
                return ToolResult.failure("Command timed out after " + effectiveTimeout.toSeconds() + " seconds");
            }

            String output = result.stdout();
            if (!result.stderr().isBlank()) {
                output = output.isBlank() ? result.stderr() : output + "\n" + result.stderr();
            }

            output = truncateOutput(output);

            if (result.exitCode() != 0) {
                String errorMsg = !output.isBlank() ? output : "Command exited with code " + result.exitCode();
                return ToolResult.failure(errorMsg);
            }
            return ToolResult.success(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Command execution was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to execute command: " + e.getMessage());
        }
    }

    private String truncateOutput(String output) {
        if (output.length() > maxOutputLength) {
            return output.substring(0, maxOutputLength) + "\n... (output truncated at " + maxOutputLength
                    + " characters)";
        }
        return output;
    }

    /** Builder for {@link ShellTool}. */
    public static final class Builder {

        private final SandboxValidator sandbox;
        private boolean requireApproval = true;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxOutputLength = DEFAULT_MAX_OUTPUT_LENGTH;

        private Builder(SandboxValidator sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Require human approval before executing the command.
         * Default: {@code true}.
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Set the default command execution timeout.
         * May be overridden per-call via {@link ShellInput#timeoutSeconds()}.
         * Default: 60 seconds.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Set the maximum output length before truncation.
         * Default: {@value ShellTool#DEFAULT_MAX_OUTPUT_LENGTH} characters.
         */
        public Builder maxOutputLength(int maxOutputLength) {
            if (maxOutputLength <= 0) {
                throw new IllegalArgumentException("maxOutputLength must be positive");
            }
            this.maxOutputLength = maxOutputLength;
            return this;
        }

        /** Build the {@link ShellTool}. */
        public ShellTool build() {
            return new ShellTool(this);
        }
    }
}
