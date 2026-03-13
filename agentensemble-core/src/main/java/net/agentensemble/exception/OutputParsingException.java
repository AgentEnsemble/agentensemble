package net.agentensemble.exception;

import java.util.List;

/**
 * Thrown when structured output parsing fails for a task after all retries are exhausted.
 *
 * This exception is raised by {@code AgentExecutor} when a task has
 * {@code outputType} set and the LLM's raw response cannot be parsed into
 * the target type, even after all configured retry attempts.
 *
 * Use {@link #getRawOutput()} to inspect the last raw response from the LLM,
 * {@link #getParseErrors()} for the error detail from each attempt, and
 * {@link #getAttemptCount()} for the total number of attempts made
 * (1 initial + N retries).
 */
public class OutputParsingException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final String rawOutput;
    private final Class<?> outputType;
    private final List<String> parseErrors;
    private final int attemptCount;

    public OutputParsingException(
            String message, String rawOutput, Class<?> outputType, List<String> parseErrors, int attemptCount) {
        super(message);
        this.rawOutput = rawOutput;
        this.outputType = outputType;
        this.parseErrors = List.copyOf(parseErrors);
        this.attemptCount = attemptCount;
    }

    /** The raw LLM response from the last parse attempt. */
    public String getRawOutput() {
        return rawOutput;
    }

    /** The Java class that the output was expected to deserialize into. */
    public Class<?> getOutputType() {
        return outputType;
    }

    /**
     * The parse error message from each attempt, in order.
     * The list is immutable. Size equals {@link #getAttemptCount()}.
     */
    public List<String> getParseErrors() {
        return parseErrors;
    }

    /**
     * The total number of parse attempts made (1 initial + N retries).
     * Equals {@code task.maxOutputRetries + 1}.
     */
    public int getAttemptCount() {
        return attemptCount;
    }
}
