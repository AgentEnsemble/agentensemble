package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Server-to-client acknowledgment that a task request has been accepted for processing.
 *
 * <p>Sent immediately upon receipt of a {@link TaskRequestMessage}, before execution begins.
 *
 * @param requestId           the correlation key matching the original request; must not be null
 * @param queuePosition       position in the processing queue (0 = executing now); may be null
 * @param estimatedCompletion ISO-8601 duration estimate for completion; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAcceptedMessage(String requestId, Integer queuePosition, String estimatedCompletion)
        implements ServerMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId} is null
     */
    public TaskAcceptedMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
    }
}
