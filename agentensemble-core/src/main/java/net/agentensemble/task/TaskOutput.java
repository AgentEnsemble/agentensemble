package net.agentensemble.task;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * The result produced by an agent executing a single task.
 *
 * TaskOutput instances are immutable and carry the complete result alongside
 * tracing metadata (which agent produced it, when, and how many tool calls
 * were made).
 *
 * When the task was configured with {@code outputType}, the parsed Java object
 * is available via {@link #getParsedOutput(Class)}. The raw LLM response is
 * always available via {@link #getRaw()}.
 */
@Builder
@Value
public class TaskOutput {

    /** The complete text output from the agent. */
    @NonNull String raw;

    /** The original task description (for traceability). */
    @NonNull String taskDescription;

    /** The role of the agent that produced this output. */
    @NonNull String agentRole;

    /** When this task completed (UTC). */
    @NonNull Instant completedAt;

    /** How long the task took to execute. */
    @NonNull Duration duration;

    /** Number of tool invocations during execution. */
    int toolCallCount;

    /**
     * The parsed Java object when the task was configured with
     * {@code outputType}. {@code null} when no structured output was requested
     * or parsing was not performed.
     */
    Object parsedOutput;

    /**
     * The Java class that was used for structured output parsing.
     * {@code null} when no structured output was requested.
     */
    Class<?> outputType;

    /**
     * Return the parsed output as the given type.
     *
     * @param type the expected class; must match the type the task was built with
     * @param <T>  the target type
     * @return the parsed object cast to {@code T}
     * @throws IllegalStateException if no parsed output is available, or if
     *                               the stored object is not an instance of {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> T getParsedOutput(Class<T> type) {
        if (parsedOutput == null) {
            throw new IllegalStateException(
                    "No parsed output available. "
                    + "Ensure the task was built with outputType(...) "
                    + "and that parsing succeeded.");
        }
        if (!type.isInstance(parsedOutput)) {
            throw new IllegalStateException(
                    "Parsed output is of type " + parsedOutput.getClass().getName()
                    + " but requested type " + type.getName() + " does not match.");
        }
        return type.cast(parsedOutput);
    }
}
