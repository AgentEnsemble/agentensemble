package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Standardized envelope for all cross-ensemble work responses.
 *
 * <p>Every cross-ensemble response -- whether from a task delegation or a tool invocation --
 * uses this envelope. The {@code requestId} correlates the response to the original
 * {@link WorkRequest}.
 *
 * <p>This is a transport-layer data carrier, not a wire protocol message. It does not
 * implement {@link ServerMessage}.
 *
 * @param requestId  correlation key matching the original request; must not be null
 * @param status     outcome status ("COMPLETED", "FAILED", "REJECTED"); must not be null
 * @param result     output on success; null on failure/rejection
 * @param error      error message on failure/rejection; null on success
 * @param durationMs execution duration in milliseconds; may be null
 *
 * @see WorkRequest
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkResponse(String requestId, String status, String result, String error, Long durationMs) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId} or {@code status} is null
     */
    public WorkResponse {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
