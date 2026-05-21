package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Broadcast by a {@code LiveEventHub} when a producer disconnects from the ingress endpoint or
 * is evicted by retention policy. The hub keeps the producer's snapshot until eviction so that a
 * reconnecting publisher with the same {@code producerId} resumes from the same state.
 *
 * @param producerId the leaving producer's ID
 * @param leftAt     when the hub detected the departure
 * @param reason     optional human-readable reason (e.g. {@code "disconnected"}, {@code "evicted"})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProducerLeftMessage(String producerId, Instant leftAt, String reason) implements ServerMessage {}
