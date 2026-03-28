package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * Server-to-client response containing capabilities matching a {@link CapabilityQueryMessage}.
 *
 * @param requestId    correlation key matching the original query
 * @param ensemble     the ensemble that provides these capabilities
 * @param capabilities the matching capabilities
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityResponseMessage(String requestId, String ensemble, List<SharedCapabilityInfo> capabilities)
        implements ServerMessage {

    public CapabilityResponseMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(ensemble, "ensemble must not be null");
        if (capabilities == null) {
            capabilities = List.of();
        } else {
            capabilities = List.copyOf(capabilities);
        }
    }
}
