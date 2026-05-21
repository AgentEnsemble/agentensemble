package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * Wraps an inner {@link ServerMessage} with producer identity and ordering metadata for
 * transmission between a publisher process and a {@code LiveEventHub}, and between the hub and
 * browser clients.
 *
 * <p>The hub re-broadcasts the same envelope to all browser sessions, allowing the browser to
 * tag every event with its originating producer and reconstruct a coherent multi-producer view.
 *
 * <p>The inner {@link #message()} payload stays a {@link JsonNode} so envelopes can carry future
 * {@link ServerMessage} subtypes that the hub doesn't know about: the hub treats the message as
 * opaque JSON for storage and fan-out. Browsers re-dispatch the inner message through the
 * existing single-producer reducer.
 *
 * @param producer    metadata identifying the publisher that produced the event
 * @param sequence    monotonic sequence number assigned by the publisher; used by the hub to
 *                    detect gaps and by browsers for stable ordering within a producer
 * @param receivedAt  when the hub received the envelope (server clock); used for chronological
 *                    ordering across producers in the late-join snapshot
 * @param message     the inner {@link ServerMessage} as a raw {@link JsonNode}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveEventEnvelope(ProducerInfo producer, long sequence, Instant receivedAt, JsonNode message)
        implements ServerMessage {

    public LiveEventEnvelope {
        Objects.requireNonNull(producer, "producer must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
