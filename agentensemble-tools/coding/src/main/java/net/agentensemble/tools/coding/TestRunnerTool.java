package net.agentensemble.tools.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that runs tests and parses the output into structured results.
 *
 * <p>Recognizes test output patterns from JUnit/Gradle, Maven Surefire, and npm/Jest.
 * Falls back to exit-code-based success/failure when no pattern matches.
 *
 * <p>Produces both a human-readable summary and a structured {@link TestResult}
 * as the tool's structured output.
 *
 * <pre>
 * TestRunnerTool tool = TestRunnerTool.of(Path.of("/workspace/project"));
 * </pre>
 */
public final class TestRunnerTool extends AbstractTypedAgentTool<TestRunnerInput> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    // Gradle 8+: "6 tests completed, 2 failed"
    private static final Pattern GRADLE_PATTERN = Pattern.compile("(\\d+)\\s+tests?\\s+completed,\\s+(\\d+)\\s+failed");

    // Maven Surefire: "Tests run: 10, Failures: 2, Errors: 1, Skipped: 3"
    private static final Pattern MAVEN_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    // npm/Jest: "Tests:  3 passed, 1 failed, 4 total" or "Tests: 4 passed, 4 total"
    private static final Pattern NPM_PATTERN = Pattern.compile("Tests:\\s+(\\d+)\\s+passed(?:,\\s+(\\d+)\\s+failed)?");

    // Generic failure marker
    private static final Pattern FAILURE_MARKER =
            Pattern.compile("(?i)FAILED|FAIL\\b|AssertionError|AssertionFailedError");

    private final SandboxValidator sandbox;
    private final Duration timeout;
    private final List<String> shellPrefix;

    private TestRunnerTool(SandboxValidator sandbox, Duration timeout) {
        this.sandbox = sandbox;
        this.timeout = timeout;
        String os = System.getProperty("os.name", "").toLowerCase();
        this.shellPrefix = os.contains("win") ? List.of("cmd", "/c") : List.of("sh", "-c");
    }

    /**
     * Creates a TestRunnerTool sandboxed to the given base directory.
     *
     * @param baseDir the workspace root directory
     * @return a new TestRunnerTool
     */
    public static TestRunnerTool of(Path baseDir) {
        Objects.requireNonNull(baseDir, "baseDir must not be null");
        return new TestRunnerTool(new SandboxValidator(baseDir), DEFAULT_TIMEOUT);
    }

    /**
     * Returns a new builder for configuring a {@code TestRunnerTool}.
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
        return "test_runner";
    }

    @Override
    public String description() {
        return "Runs tests and parses the results. "
                + "Recognizes JUnit/Gradle, Maven Surefire, and npm/Jest output. "
                + "Returns structured test results with pass/fail counts and failure details.";
    }

    @Override
    public Class<TestRunnerInput> inputType() {
        return TestRunnerInput.class;
    }

    @Override
    public ToolResult execute(TestRunnerInput input) {
        if (input.command() == null || input.command().isBlank()) {
            return ToolResult.failure("Test command must not be blank");
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

        String fullCommand = input.command();
        if (input.testFilter() != null && !input.testFilter().isBlank()) {
            fullCommand = fullCommand + " " + input.testFilter();
        }

        try {
            List<String> command = new ArrayList<>(shellPrefix);
            command.add(fullCommand);

            SubprocessRunner.SubprocessResult result = SubprocessRunner.run(command, workingDir, timeout, log());

            String output = result.stdout();
            if (!result.stderr().isBlank()) {
                output = output.isBlank() ? result.stderr() : output + "\n" + result.stderr();
            }

            if (result.exitCode() == -1) {
                return ToolResult.failure("Tests timed out after " + timeout.toSeconds() + " seconds");
            }

            boolean exitSuccess = result.exitCode() == 0;
            TestResult testResult = parseTestOutput(output, exitSuccess);
            ObjectNode structured = toStructuredOutput(testResult);

            // Always return structured output so listeners/agents can consume
            // test counts and failure details. structured.success=false signals failure.
            String summary = buildSummary(testResult, output);
            return ToolResult.success(summary, structured);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Test execution was interrupted");
        } catch (IOException e) {
            return ToolResult.failure("Failed to run tests: " + e.getMessage());
        }
    }

    TestResult parseTestOutput(String output, boolean exitSuccess) {
        // Try Gradle pattern
        Matcher gradleMatcher = GRADLE_PATTERN.matcher(output);
        if (gradleMatcher.find()) {
            int total = Integer.parseInt(gradleMatcher.group(1));
            int failed = Integer.parseInt(gradleMatcher.group(2));
            int passed = total - failed;
            List<TestFailure> failures = extractFailures(output);
            return new TestResult(failed == 0 && exitSuccess, passed, failed, 0, failures);
        }

        // Try Maven Surefire pattern
        Matcher mavenMatcher = MAVEN_PATTERN.matcher(output);
        if (mavenMatcher.find()) {
            int total = Integer.parseInt(mavenMatcher.group(1));
            int failures = Integer.parseInt(mavenMatcher.group(2));
            int errors = Integer.parseInt(mavenMatcher.group(3));
            int skipped = Integer.parseInt(mavenMatcher.group(4));
            int failed = failures + errors;
            int passed = total - failed - skipped;
            List<TestFailure> failureDetails = extractFailures(output);
            return new TestResult(failed == 0 && exitSuccess, passed, failed, skipped, failureDetails);
        }

        // Try npm/Jest pattern
        Matcher npmMatcher = NPM_PATTERN.matcher(output);
        if (npmMatcher.find()) {
            int passed = Integer.parseInt(npmMatcher.group(1));
            int failed = npmMatcher.group(2) != null ? Integer.parseInt(npmMatcher.group(2)) : 0;
            List<TestFailure> failures = extractFailures(output);
            return new TestResult(failed == 0 && exitSuccess, passed, failed, 0, failures);
        }

        // Fallback: use exit code
        List<TestFailure> failures = exitSuccess ? Collections.emptyList() : extractFailures(output);
        return new TestResult(exitSuccess, 0, exitSuccess ? 0 : 1, 0, failures);
    }

    private List<TestFailure> extractFailures(String output) {
        List<TestFailure> failures = new ArrayList<>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = FAILURE_MARKER.matcher(lines[i]);
            if (m.find()) {
                String testName = lines[i].trim();
                String message = "";
                String stackTrace = "";

                // Try to capture the next few lines as message/stack
                if (i + 1 < lines.length) {
                    message = lines[i + 1].trim();
                }
                if (i + 2 < lines.length && lines[i + 2].trim().startsWith("at ")) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = i + 2; j < Math.min(i + 7, lines.length); j++) {
                        if (lines[j].trim().startsWith("at ") || lines[j].trim().startsWith("Caused by:")) {
                            sb.append(lines[j].trim()).append("\n");
                        }
                    }
                    stackTrace = sb.toString().trim();
                }

                failures.add(new TestFailure(testName, message, stackTrace));
            }
        }
        return failures;
    }

    private static final int MAX_RAW_OUTPUT_LENGTH = 10_000;

    private String buildSummary(TestResult testResult, String rawOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tests: ")
                .append(testResult.passed())
                .append(" passed, ")
                .append(testResult.failed())
                .append(" failed");
        if (testResult.skipped() > 0) {
            sb.append(", ").append(testResult.skipped()).append(" skipped");
        }
        sb.append(" | ").append(testResult.success() ? "PASSED" : "FAILED");

        if (!testResult.failures().isEmpty()) {
            sb.append("\n\nFailures:");
            for (TestFailure f : testResult.failures()) {
                sb.append("\n  - ").append(f.testName());
                if (!f.message().isBlank()) {
                    sb.append("\n    ").append(f.message());
                }
            }
        }

        // Append raw output for context, truncated to prevent overwhelming the LLM
        sb.append("\n\n--- Raw Output ---\n");
        if (rawOutput.length() > MAX_RAW_OUTPUT_LENGTH) {
            sb.append(rawOutput, 0, MAX_RAW_OUTPUT_LENGTH);
            sb.append("\n... (output truncated at ")
                    .append(MAX_RAW_OUTPUT_LENGTH)
                    .append(" characters)");
        } else {
            sb.append(rawOutput);
        }
        return sb.toString();
    }

    private ObjectNode toStructuredOutput(TestResult testResult) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("success", testResult.success());
        node.put("passed", testResult.passed());
        node.put("failed", testResult.failed());
        node.put("skipped", testResult.skipped());
        ArrayNode failuresArray = node.putArray("failures");
        for (TestFailure f : testResult.failures()) {
            ObjectNode failure = OBJECT_MAPPER.createObjectNode();
            failure.put("testName", f.testName());
            failure.put("message", f.message());
            failure.put("stackTrace", f.stackTrace());
            failuresArray.add(failure);
        }
        return node;
    }

    /** Builder for {@link TestRunnerTool}. */
    public static final class Builder {

        private final SandboxValidator sandbox;
        private Duration timeout = DEFAULT_TIMEOUT;

        private Builder(SandboxValidator sandbox) {
            this.sandbox = sandbox;
        }

        /**
         * Set the test command timeout. Default: 300 seconds.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** Build the {@link TestRunnerTool}. */
        public TestRunnerTool build() {
            return new TestRunnerTool(sandbox, timeout);
        }
    }
}
