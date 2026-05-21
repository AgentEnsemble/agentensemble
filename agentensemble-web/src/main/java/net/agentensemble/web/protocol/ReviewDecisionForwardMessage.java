package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Hub &rarr; publisher message that carries a browser-submitted review decision back to the
 * originating publisher so its blocked {@code RemoteReviewHandler} can complete its future.
 *
 * <p>Travels on the publisher's ingress WebSocket; not part of the browser-facing
 * {@code ServerMessage} hierarchy.
 *
 * @param reviewId      the matching {@link ReviewDecisionMessage#reviewId()}
 * @param decisionJson  the serialized {@link ReviewDecisionMessage} for the publisher to parse
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewDecisionForwardMessage(String reviewId, String decisionJson) {}
