package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Server-to-client message containing the result of a task execution.
 *
 * <p>The {@code status} field indicates the outcome:
 * <ul>
 *   <li>{@code "COMPLETED"} -- task finished successfully; {@code result} contains the output</li>
 *   <li>{@code "FAILED"} -- task failed; {@code error} contains the error message</li>
 *   <li>{@code "REJECTED"} -- task was not accepted (e.g., ensemble is draining);
 *       {@code error} contains the reason</li>
 * </ul>
 *
 * @param requestId  the correlation key matching the original request; must not be null
 * @param status     outcome status ("COMPLETED", "FAILED", "REJECTED"); must not be null
 * @param result     task output on success; null on failure/rejection
 * @param error      error message on failure/rejection; null on success
 * @param durationMs execution duration in milliseconds; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResponseMessage(String requestId, String status, String result, String error, Long durationMs)
        implements ServerMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId} or {@code status} is null
     */
    public TaskResponseMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
