package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Broadcast by a {@code LiveEventHub} when a new producer attaches to the ingress endpoint.
 *
 * @param producer the joining producer's identity and metadata
 * @param joinedAt when the hub registered the producer
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProducerJoinedMessage(ProducerInfo producer, Instant joinedAt) implements ServerMessage {}
