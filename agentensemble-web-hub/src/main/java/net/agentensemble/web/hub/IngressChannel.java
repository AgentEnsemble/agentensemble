package net.agentensemble.web.hub;

import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
// Channel implementations live in this package; IngressChannel itself does not need to import
// LiveEventPublisher.

/**
 * Outbound side of a producer's hub connection. The hub uses this to forward browser-submitted
 * review decisions back to the originating publisher, whether that publisher lives in the same
 * JVM (in-memory) or connects over the wire (WebSocket ingress).
 *
 * <p>Package-private; implemented by {@link InMemoryLiveEventPublisher} adapters and by the WS
 * ingress handler in {@link LiveEventHub}.
 */
interface IngressChannel {

    /**
     * Identity of the producer at the other end of this channel.
     */
    ProducerInfo info();

    /**
     * Forward a hub-originated review decision back to the publisher so its blocked
     * {@code RemoteReviewHandler} future can complete.
     *
     * @param decision the decision payload; must not be null
     */
    void deliverReviewDecision(ReviewDecisionForwardMessage decision);

    /**
     * Returns true when the channel is still capable of delivering messages back to the
     * publisher (i.e. the WS session is open, or the in-memory publisher is started).
     */
    boolean isOpen();
}
