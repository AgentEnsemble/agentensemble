package net.agentensemble.tools.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that runs build commands and parses the output into structured results.
 *
 * <p>Heuristically extracts errors and warnings from build output, producing
 * both a human-readable text result and a structured JSON payload:
 * {@code {"success": true, "errors": [], "warnings": []}}.
 *
 * <pre>
 * BuildRunnerTool tool = BuildRunnerTool.of(Path.of("/workspace/project"));
 * </pre>
 */
public final class BuildRunnerTool extends AbstractTypedAgentTool<BuildRunnerInput> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Pattern ERROR_PATTERN =
            Pattern.compile("(?i)^.*\\b(error:|ERROR|FAILURE|BUILD FAILED|FATAL)\\b.*$", Pattern.MULTILINE);
    private static final Pattern WARNING_PATTERN =
            Pattern.compile("(?i)^.*\\b(warning:|WARNING|deprecated)\\b.*$", Pattern.MULTILINE);

    private final SandboxValidator sandbox;
    private final Duration timeout;
    private final List<String> shellPrefix;

    private BuildRunnerTool(SandboxValidator sandbox, Duration timeout) {
        this.sandbox = sandbox;
        this.timeout = timeout;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.shellPrefix = os.contains("win") ? List.of("cmd", "/c") : List.of("sh", "-c");
    }

    /**
     * Creates a BuildRunnerTool sandboxed to the given base directory.
     *
     * @param baseDir the workspace root directory
     * @return a new BuildRunnerTool
     */
    public static BuildRunnerTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new BuildRunnerTool(new SandboxValidator(baseDir), DEFAULT_TIMEOUT);
    }

    /**
     * Returns a new builder for configuring a {@code BuildRunnerTool}.
     *
     * @param baseDir the workspace root directory
     * @return a new Builder
     */
    public static Builder builder(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new Builder(new SandboxValidator(baseDir));
    }

    @Override
    public String name() {
        return "build_runner";
    }

    @Override
    public String description() {
        return "Runs a build command and parses the results. "
                + "Returns both raw output and structured data with errors and warnings.";
    }

    @Override
    public Class<BuildRunnerInput> inputType() {
        return BuildRunnerInput.class;
    }

    @Override
    public ToolResult execute(BuildRunnerInput input) {
        if (input.command() == null || input.command().isBlank()) {
            return ToolResult.failure("Build command must not be blank");
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

        try {
            List<String> command = new ArrayList<>(shellPrefix);
            command.add(input.command());

            SubprocessRunner.SubprocessResult result = SubprocessRunner.run(command, workingDir, timeout, log());

            String output = result.stdout();
            if (!result.stderr().isBlank()) {
                output = output.isBlank() ? result.stderr() : output + "\n" + result.stderr();
            }

            if (result.exitCode() == -1) {
                return ToolResult.failure("Build timed out after " + timeout.toSeconds() + " seconds");
            }

            boolean success = result.exitCode() == 0;
            List<String> errors = extractMatches(output, ERROR_PATTERN);
            List<String> warnings = extractMatches(output, WARNING_PATTERN);

            ObjectNode structured = buildStructuredOutput(success, errors, warnings);

            // Always return structured output so listeners can consume errors/warnings
            // even when the build fails. structured.success=false signals failure.
            return ToolResult.success(output, structured);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Build was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to run build: " + e.getMessage());
        }
    }

    private List<String> extractMatches(String output, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            matches.add(matcher.group().trim());
        }
        return matches;
    }

    private ObjectNode buildStructuredOutput(boolean success, List<String> errors, List<String> warnings) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("success", success);
        ArrayNode errorsArray = node.putArray("errors");
        errors.forEach(errorsArray::add);
        ArrayNode warningsArray = node.putArray("warnings");
        warnings.forEach(warningsArray::add);
        return node;
    }

    /** Builder for {@link BuildRunnerTool}. */
    public static final class Builder {

        private final SandboxValidator sandbox;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder(SandboxValidator sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Set the build command timeout. Default: 120 seconds.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** Build the {@link BuildRunnerTool}. */
        public BuildRunnerTool build() {
            return new BuildRunnerTool(sandbox, timeout);
        }
    }
}
