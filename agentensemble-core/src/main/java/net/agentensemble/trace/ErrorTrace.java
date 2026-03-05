package net.agentensemble.trace;

import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Captures an error that occurred during an ensemble run.
 *
 * <p>When a task fails, an {@code ErrorTrace} is added to
 * {@link ExecutionTrace} {@code getErrors()}, preserving the context needed for post-mortem
 * diagnosis without requiring the exception to still be in scope.
 */
@Value
@Builder(toBuilder = true)
public class ErrorTrace {

    /** The role of the agent whose task failed. */
    @NonNull
    String agentRole;

    /** The description of the task that failed. */
    @NonNull
    String taskDescription;

    /** Fully qualified class name of the exception that was thrown. */
    @NonNull
    String errorType;

    /** The exception message, or an empty string if none was provided. */
    @NonNull
    String message;

    /**
     * The exception's stack trace as a single string, suitable for log output.
     * May be omitted (empty string) when the full stack trace is not needed.
     */
    @Builder.Default
    String stackTrace = "";

    /** When the error occurred. */
    @NonNull
    Instant occurredAt;
}
