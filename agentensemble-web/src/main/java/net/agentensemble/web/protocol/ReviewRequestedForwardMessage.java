package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Hub &harr; publisher internal message used to route review gate state for distributed
 * dashboards. Travels on the publisher's ingress WebSocket; not part of the browser-facing
 * {@code ServerMessage} hierarchy.
 *
 * <p>When a publisher's {@code RemoteReviewHandler} fires, it sends one of these to the hub so
 * the hub can broadcast a {@link ReviewRequestedMessage} to browsers with producer attribution.
 * The corresponding {@link ReviewDecisionForwardMessage} returns from the hub to the originating
 * publisher when a browser submits its decision.
 *
 * @param producerId the publisher's ID; the hub uses it for routing the decision back
 * @param reviewId   correlation ID for this review gate; matches the
 *                   {@link ReviewRequestedMessage#reviewId()} broadcast to browsers
 * @param payload    JSON of the underlying {@link ReviewRequestedMessage} as broadcast to browsers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewRequestedForwardMessage(String producerId, String reviewId, String payload) {}
