package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Server-to-client message containing the result of a tool invocation.
 *
 * <p>The {@code status} field indicates the outcome:
 * <ul>
 *   <li>{@code "COMPLETED"} -- tool executed successfully; {@code result} contains the output</li>
 *   <li>{@code "FAILED"} -- tool failed; {@code error} contains the error message</li>
 * </ul>
 *
 * @param requestId  the correlation key matching the original request; must not be null
 * @param status     outcome status ("COMPLETED" or "FAILED"); must not be null
 * @param result     tool output on success; null on failure
 * @param error      error message on failure; null on success
 * @param durationMs execution duration in milliseconds; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResponseMessage(String requestId, String status, String result, String error, Long durationMs)
        implements ServerMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId} or {@code status} is null
     */
    public ToolResponseMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
