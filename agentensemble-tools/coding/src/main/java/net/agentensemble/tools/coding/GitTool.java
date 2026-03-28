package net.agentensemble.tools.coding;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that executes git operations within a sandboxed repository directory.
 *
 * <p>Supports common git commands (status, diff, log, commit, add, branch, etc.).
 * Destructive operations (push, reset --hard, rebase, clean, force-push) trigger
 * approval when {@code requireApproval(true)} is set.
 *
 * <pre>
 * GitTool tool = GitTool.builder(Path.of("/workspace/repo"))
 *     .requireApproval(true)
 *     .build();
 * </pre>
 */
public final class GitTool extends AbstractTypedAgentTool<GitInput> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    static final Set<String> ALLOWED_COMMANDS = Set.of(
            "status",
            "diff",
            "log",
            "commit",
            "add",
            "branch",
            "stash",
            "checkout",
            "push",
            "reset",
            "rebase",
            "clean",
            "show",
            "tag",
            "merge",
            "fetch",
            "pull");

    private static final Set<String> ALWAYS_DANGEROUS_COMMANDS = Set.of("push", "rebase", "clean");

    private final SandboxValidator sandbox;
    private final boolean requireApproval;
    private final Duration timeout;

    private GitTool(SandboxValidator sandbox, boolean requireApproval, Duration timeout) {
        this.sandbox = sandbox;
        this.requireApproval = requireApproval;
        this.timeout = timeout;
    }

    /**
     * Creates a GitTool sandboxed to the given directory, without an approval gate.
     *
     * @param baseDir the git repository root directory
     * @return a new GitTool
     */
    public static GitTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new GitTool(new SandboxValidator(baseDir), false, DEFAULT_TIMEOUT);
    }

    /**
     * Returns a new builder for configuring a {@code GitTool}.
     *
     * @param baseDir the git repository root directory
     * @return a new Builder
     */
    public static Builder builder(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new Builder(new SandboxValidator(baseDir));
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public String description() {
        return "Executes git operations in the workspace repository. "
                + "Supported commands: status, diff, log, commit, add, branch, stash, checkout, show, tag, merge, fetch, pull, push, reset. "
                + "Destructive operations require approval when enabled.";
    }

    @Override
    public Class<GitInput> inputType() {
        return GitInput.class;
    }

    @Override
    public ToolResult execute(GitInput input) {
        if (input.command() == null || input.command().isBlank()) {
            return ToolResult.failure("Git command must not be blank");
        }

        String command = input.command().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(command)) {
            return ToolResult.failure("Unknown git command: " + command + ". Allowed: "
                    + String.join(", ", ALLOWED_COMMANDS.stream().sorted().toList()));
        }

        String args = input.args() != null ? input.args().trim() : "";

        if (requireApproval && isDangerous(command, args)) {
            if (rawReviewHandler() == null) {
                throw new ToolConfigurationException("Tool '"
                        + name()
                        + "' requires approval but no ReviewHandler is configured on the ensemble. "
                        + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            String desc = "Execute git " + command + (args.isBlank() ? "" : " " + args);
            ReviewDecision decision = requestApproval(desc);
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("Git operation rejected by reviewer: git " + command);
            }
        }

        try {
            List<String> gitCommand = buildGitCommand(command, args, input.message());
            SubprocessRunner.SubprocessResult result =
                    SubprocessRunner.run(gitCommand, sandbox.baseDir(), timeout, log());

            if (result.exitCode() == -1) {
                return ToolResult.failure("Git command timed out after " + timeout.toSeconds() + " seconds");
            }

            if (result.exitCode() != 0) {
                String errorMsg = !result.stderr().isBlank()
                        ? result.stderr()
                        : "git " + command + " failed with exit code " + result.exitCode();
                return ToolResult.failure(errorMsg);
            }

            String output = result.stdout();
            if (!result.stderr().isBlank() && !output.isBlank()) {
                output = output + "\n" + result.stderr();
            } else if (output.isBlank() && !result.stderr().isBlank()) {
                output = result.stderr();
            }
            if (output.isBlank()) {
                output = "git " + command + " completed successfully";
            }
            return ToolResult.success(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Git command was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to execute git command: " + e.getMessage());
        }
    }

    static boolean isDangerous(String command, String args) {
        if (ALWAYS_DANGEROUS_COMMANDS.contains(command)) {
            return true;
        }
        if ("reset".equals(command) && args.contains("--hard")) {
            return true;
        }
        if ("checkout".equals(command) && (".".equals(args.trim()) || args.contains("-- ."))) {
            return true;
        }
        if ("branch".equals(command) && args.contains("-D")) {
            return true;
        }
        // Tokenize args to check for exact --force or -f flags
        for (String arg : args.split("\\s+")) {
            if ("--force".equals(arg) || "-f".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildGitCommand(String command, String args, String message) {
        List<String> gitCommand = new ArrayList<>();
        gitCommand.add("git");
        gitCommand.add(command);

        if ("commit".equals(command) && message != null && !message.isBlank()) {
            gitCommand.add("-m");
            gitCommand.add(message);
        }

        if (!args.isBlank()) {
            // Split args by whitespace (no quote handling -- callers should use simple args)
            for (String arg : args.split("\\s+")) {
                if (!arg.isBlank()) {
                    gitCommand.add(arg);
                }
            }
        }

        return gitCommand;
    }

    /** Builder for {@link GitTool}. */
    public static final class Builder {

        private final SandboxValidator sandbox;
        private boolean requireApproval = false;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder(SandboxValidator sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Require human approval before executing dangerous git operations.
         * Default: {@code false}.
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Set the git command timeout. Default: 30 seconds.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** Build the {@link GitTool}. */
        public GitTool build() {
            return new GitTool(sandbox, requireApproval, timeout);
        }
    }
}
