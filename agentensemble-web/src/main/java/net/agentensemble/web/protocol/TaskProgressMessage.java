package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Server-to-client streaming progress update during task execution.
 *
 * <p>Optional: ensembles may send zero or more progress messages between the
 * {@link TaskAcceptedMessage} and the final {@link TaskResponseMessage}.
 *
 * @param requestId       the correlation key matching the original request; must not be null
 * @param status          current execution status (e.g., "RUNNING", "SYNTHESIZING"); may be null
 * @param message         human-readable progress message; may be null
 * @param percentComplete completion percentage (0-100); may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskProgressMessage(String requestId, String status, String message, Integer percentComplete)
        implements ServerMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId} is null
     */
    public TaskProgressMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
    }
}
