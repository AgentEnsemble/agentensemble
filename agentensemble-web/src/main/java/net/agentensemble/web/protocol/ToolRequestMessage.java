package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Client-to-server message requesting execution of a shared tool on the receiving ensemble.
 *
 * <p>The server responds with a {@link ToolResponseMessage} when execution completes.
 *
 * @param requestId    correlation key; must not be null
 * @param from         name of the requesting ensemble; must not be null
 * @param tool         name of the shared tool to invoke; must not be null
 * @param input        input string to pass to the tool; may be null
 * @param traceContext W3C trace context for distributed tracing; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolRequestMessage(String requestId, String from, String tool, String input, TraceContext traceContext)
        implements ClientMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId}, {@code from}, or {@code tool} is null
     */
    public ToolRequestMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
    }
}
