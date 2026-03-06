package net.agentensemble.tools.process;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that executes an external subprocess and returns its output.
 *
 * <p>Enables cross-language tool implementations: any program that speaks the
 * AgentEnsemble subprocess protocol can be invoked as an agent tool.
 *
 * <h2>AgentEnsemble Subprocess Protocol</h2>
 *
 * <p>The protocol is a simple JSON-over-stdio contract:
 *
 * <ol>
 *   <li><strong>Input</strong>: the framework writes a single-line JSON object to the process
 *       stdin: {@code {"input": "..."}}, followed by EOF (stdin is closed).</li>
 *   <li><strong>Success output</strong>: the process writes a JSON object to stdout:
 *       {@code {"output": "...", "success": true}}, optionally with a structured payload:
 *       {@code {"output": "...", "success": true, "structured": {...}}}.</li>
 *   <li><strong>Failure output</strong>: {@code {"error": "...", "success": false}}.</li>
 *   <li><strong>Exit code 0</strong>: result is parsed from stdout as described above.</li>
 *   <li><strong>Non-zero exit code</strong>: treated as a failure; stderr content is captured
 *       as the error message.</li>
 *   <li><strong>Timeout</strong>: configurable; the process is killed if it exceeds the limit.</li>
 * </ol>
 *
 * <h2>Approval Gate</h2>
 *
 * <p>When {@link Builder#requireApproval(boolean) requireApproval(true)} is set, a human
 * reviewer must approve the command before execution:
 *
 * <pre>
 * var tool = ProcessAgentTool.builder()
 *     .name("shell")
 *     .description("Runs shell commands")
 *     .command("sh", "-c")
 *     .requireApproval(true)
 *     .build();
 * </pre>
 *
 * <p>The reviewer sees the command and input, and may:
 * <ul>
 *   <li>Continue -- execute as-is</li>
 *   <li>Edit -- replace the input sent to the subprocess</li>
 *   <li>Exit early -- return failure without executing the process</li>
 * </ul>
 *
 * <p>A {@link net.agentensemble.review.ReviewHandler} must be configured on the ensemble
 * when {@code requireApproval(true)} is set; otherwise an {@link IllegalStateException}
 * is thrown at execution time.
 *
 * <h2>Example Python tool following the protocol</h2>
 *
 * <pre>
 * import sys, json
 *
 * data = json.loads(sys.stdin.read())
 * result = process(data["input"])
 * print(json.dumps({"output": result, "success": True}))
 * </pre>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * var tool = ProcessAgentTool.builder()
 *     .name("sentiment_analysis")
 *     .description("Analyzes text sentiment")
 *     .command("python3", "/path/to/sentiment.py")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * </pre>
 */
public final class ProcessAgentTool extends AbstractAgentTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String toolName;
    private final String toolDescription;
    private final List<String> command;
    private final Duration timeout;
    private final boolean requireApproval;

    private ProcessAgentTool(Builder builder) {
        this.toolName = builder.name;
        this.toolDescription = builder.description;
        this.command = Collections.unmodifiableList(new ArrayList<>(builder.command));
        this.timeout = builder.timeout;
        this.requireApproval = builder.requireApproval;
    }

    /** Returns a new builder for configuring a {@code ProcessAgentTool}. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return toolDescription;
    }

    @Override
    protected ToolResult doExecute(String input) {
        String effectiveInput = input != null ? input : "";

        if (requireApproval) {
            if (rawReviewHandler() == null) {
                throw new ToolConfigurationException("Tool '"
                        + name()
                        + "' requires approval but no ReviewHandler is configured on the ensemble. "
                        + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            String commandStr = String.join(" ", command);
            ReviewDecision decision = requestApproval(
                    "Execute command: " + commandStr + "\nInput: " + truncateForApproval(effectiveInput));
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("Command execution rejected by reviewer: " + commandStr);
            }
            if (decision instanceof ReviewDecision.Edit edit) {
                log().debug("Reviewer edited input for command '{}': replacing input with revised content", commandStr);
                effectiveInput = edit.revisedOutput();
            }
        }

        try {
            return executeProcess(effectiveInput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Process execution was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to start process: " + e.getMessage());
        }
    }

    private ToolResult executeProcess(String input) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // Write input JSON to stdin and close it.
        // If the process exits before reading (e.g., echo), writing may throw an IOException
        // with "Stream closed" or "Broken pipe". This is not a fatal error; continue to read
        // the process output.
        String inputJson = buildInputJson(input);
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(inputJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log().debug(
                            "Process stdin closed before writing completed (process may not read stdin): {}",
                            e.getMessage());
        }

        // Drain stdout and stderr concurrently on virtual threads before waiting.
        // Without concurrent draining a process that writes more than the OS pipe
        // buffer (~64 KB on Linux) will block on write and waitFor() will deadlock.
        ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();
        Thread stdoutDrain = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(stdoutCapture);
            } catch (IOException ignored) {
            }
        });
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(stderrCapture);
            } catch (IOException ignored) {
            }
        });

        // Wait for completion with timeout
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            stdoutDrain.join();
            stderrDrain.join();
            return ToolResult.failure("Process timed out after " + timeout.toSeconds() + " seconds: " + command.get(0));
        }

        stdoutDrain.join();
        stderrDrain.join();
        int exitCode = process.exitValue();
        String stdout = stdoutCapture.toString(StandardCharsets.UTF_8).trim();
        String stderr = stderrCapture.toString(StandardCharsets.UTF_8).trim();

        if (exitCode != 0) {
            String errorMsg = !stderr.isBlank() ? stderr : "Process exited with code " + exitCode;
            log().warn("Process {} exited with code {}: {}", command.get(0), exitCode, errorMsg);
            return ToolResult.failure(errorMsg);
        }

        return parseProcessOutput(stdout);
    }

    /**
     * Parse the process stdout as an AgentEnsemble subprocess protocol response.
     *
     * <p>If the output is not valid JSON or does not follow the protocol, the raw stdout is
     * returned as a plain-text success result.
     */
    private ToolResult parseProcessOutput(String stdout) {
        if (stdout.isBlank()) {
            return ToolResult.success("");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(stdout);
            JsonNode successNode = node.get("success");
            boolean success = successNode == null || successNode.asBoolean(true);

            if (!success) {
                JsonNode errorNode = node.get("error");
                String error = errorNode != null ? errorNode.asText() : "Process returned success=false";
                return ToolResult.failure(error);
            }

            JsonNode outputNode = node.get("output");
            String output = outputNode != null ? outputNode.asText() : stdout;

            JsonNode structuredNode = node.get("structured");
            if (structuredNode != null && !structuredNode.isNull()) {
                return ToolResult.success(output, structuredNode);
            }
            return ToolResult.success(output);

        } catch (Exception e) {
            // Not JSON or malformed -- return raw stdout as plain-text success
            log().debug("Process output is not AgentEnsemble protocol JSON, returning raw: {}", e.getMessage());
            return ToolResult.success(stdout);
        }
    }

    private static String buildInputJson(String input) throws IOException {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("input", input != null ? input : "");
        return OBJECT_MAPPER.writeValueAsString(node);
    }

    private static String truncateForApproval(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    // ========================
    // Builder
    // ========================

    /** Builder for {@link ProcessAgentTool}. */
    public static final class Builder {

        private String name;
        private String description;
        private List<String> command;
        private Duration timeout = DEFAULT_TIMEOUT;
        private boolean requireApproval = false;

        private Builder() {}

        /**
         * Set the tool name used by the LLM to identify this tool.
         *
         * @param name tool name; must not be null or blank
         * @return this builder
         */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            return this;
        }

        /**
         * Set the tool description shown to the LLM.
         *
         * @param description tool description; must not be null
         * @return this builder
         */
        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description must not be null");
            return this;
        }

        /**
         * Set the command to execute. The first element is the program; subsequent elements
         * are arguments.
         *
         * @param command the command and arguments; must not be null or empty
         * @return this builder
         */
        public Builder command(String... command) {
            Objects.requireNonNull(command, "command must not be null");
            if (command.length == 0) {
                throw new IllegalArgumentException("command must not be empty");
            }
            this.command = Arrays.asList(command);
            return this;
        }

        /**
         * Set the command to execute from a list.
         *
         * @param command the command and arguments; must not be null or empty
         * @return this builder
         */
        public Builder command(List<String> command) {
            Objects.requireNonNull(command, "command must not be null");
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            this.command = new ArrayList<>(command);
            return this;
        }

        /**
         * Set the process execution timeout.
         *
         * @param timeout the maximum time to wait; must be positive; default is 30 seconds
         * @return this builder
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
         * Require human approval before executing the subprocess.
         *
         * <p>When {@code true}, the ensemble's configured
         * {@link net.agentensemble.review.ReviewHandler} is invoked before the process is
         * started. The reviewer sees the command and input, and may approve, edit the
         * input, or reject the execution.
         *
         * <p>If {@code requireApproval(true)} is set but no {@code ReviewHandler} is
         * configured on the ensemble, an {@link IllegalStateException} is thrown at
         * execution time (fail-fast -- see issue #126).
         *
         * <p>Default: {@code false}.
         *
         * @param requireApproval {@code true} to require approval before process execution
         * @return this builder
         */
        public Builder requireApproval(boolean requireApproval) {
            this.requireApproval = requireApproval;
            return this;
        }

        /**
         * Build the {@link ProcessAgentTool}.
         *
         * @return a new ProcessAgentTool
         * @throws IllegalStateException if name, description, or command are not set
         */
        public ProcessAgentTool build() {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("name must be set and non-blank");
            }
            if (description == null) {
                throw new IllegalStateException("description must be set");
            }
            if (command == null || command.isEmpty()) {
                throw new IllegalStateException("command must be set");
            }
            return new ProcessAgentTool(this);
        }
    }
}
