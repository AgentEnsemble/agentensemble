package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.util.Objects;

/**
 * Client-to-server message requesting execution of a shared task on the receiving ensemble.
 *
 * <p>The server responds with a {@link TaskAcceptedMessage} acknowledgment followed by a
 * {@link TaskResponseMessage} when execution completes (or fails).
 *
 * @param requestId    correlation and idempotency key; must not be null
 * @param from         name of the requesting ensemble; must not be null
 * @param task         name of the shared task to execute; must not be null
 * @param context      natural language input/context for the task; may be null
 * @param priority     request priority; may be null (defaults to NORMAL)
 * @param deadline     ISO-8601 duration string for the caller's SLA; may be null
 * @param delivery     how to return the result; may be null (defaults to WEBSOCKET)
 * @param traceContext W3C trace context for distributed tracing; may be null
 * @param cachePolicy  caching policy; may be null
 * @param cacheKey     optional cache key; may be null
 * @param maxAge       maximum age for cached results; may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRequestMessage(
        String requestId,
        String from,
        String task,
        String context,
        Priority priority,
        String deadline,
        DeliverySpec delivery,
        TraceContext traceContext,
        CachePolicy cachePolicy,
        String cacheKey,
        Duration maxAge)
        implements ClientMessage {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code requestId}, {@code from}, or {@code task} is null
     */
    public TaskRequestMessage {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(task, "task must not be null");
    }
}
