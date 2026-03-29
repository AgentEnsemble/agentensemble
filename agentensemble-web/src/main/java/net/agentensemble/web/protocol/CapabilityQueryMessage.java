package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Client-to-server request to discover capabilities on the network.
 *
 * <p>Sent by an ensemble to query for available capabilities by name, tag, or capabilityType.
 * The server responds with a {@link CapabilityResponseMessage} containing matching capabilities.
 *
 * @param requestId      correlation key for matching responses
 * @param from           the requesting ensemble name
 * @param toolName       optional exact tool name to find; null means any
 * @param tag            optional tag filter; null means any
 * @param capabilityType optional type filter ({@code "TASK"} or {@code "TOOL"}); null means any
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityQueryMessage(String requestId, String from, String toolName, String tag, String capabilityType)
        implements ClientMessage {

    public CapabilityQueryMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(from, "from must not be null");
    }
}
