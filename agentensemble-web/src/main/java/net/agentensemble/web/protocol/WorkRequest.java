package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.util.Objects;

/**
 * Standardized envelope for all cross-ensemble work requests.
 *
 * <p>Every cross-ensemble message -- whether a task delegation or a tool invocation -- uses
 * this envelope. The {@code requestId} serves as both a correlation key (to match responses
 * to requests) and an idempotency key (to prevent duplicate processing).
 *
 * @param requestId   correlation and idempotency key; must not be null
 * @param from        name of the requesting ensemble; must not be null
 * @param task        name of the shared task or tool to execute; must not be null
 * @param context     natural language input/context for the request; may be null
 * @param priority    request priority; defaults to {@link Priority#NORMAL} if null
 * @param deadline    caller's SLA ("I need this within..."); may be null (no deadline)
 * @param delivery    how and where to return the result; defaults to
 *                    {@link DeliveryMethod#WEBSOCKET} if null
 * @param traceContext W3C trace context for distributed tracing; may be null
 * @param cachePolicy  caching policy; may be null (implementation-specific default)
 * @param cacheKey    optional cache key for result caching; may be null
 *
 * @see Priority
 * @see DeliverySpec
 * @see TraceContext
 * @see CachePolicy
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkRequest(
        String requestId,
        String from,
        String task,
        String context,
        Priority priority,
        Duration deadline,
        DeliverySpec delivery,
        TraceContext traceContext,
        CachePolicy cachePolicy,
        String cacheKey) {

    /**
     * Compact constructor with validation and defaults.
     *
     * @throws NullPointerException if {@code requestId}, {@code from}, or {@code task} is null
     */
    public WorkRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (priority == null) {
            priority = Priority.NORMAL;
        }
        if (delivery == null) {
            delivery = new DeliverySpec(DeliveryMethod.WEBSOCKET, null);
        }
    }
}
