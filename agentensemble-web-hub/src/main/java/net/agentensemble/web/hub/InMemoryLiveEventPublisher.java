package net.agentensemble.web.hub;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.publisher.AbstractLiveEventPublisher;

/**
 * In-JVM {@link net.agentensemble.web.publisher.LiveEventPublisher} that forwards envelopes directly to a {@link LiveEventHub}
 * reference. Used by tests, by same-JVM hub configurations (one process hosts both publisher
 * dashboards and the hub), and as the reference implementation that exercises every code path
 * without a network transport.
 *
 * <p>Review fan-in is fully supported: a publisher registers itself with the hub on
 * {@link #start()} and the hub invokes {@link #deliverReviewDecision(ReviewDecisionForwardMessage)}
 * when a browser submits a decision.
 *
 * <p>Lifecycle: idempotent {@link #start()}/{@link #stop()}. After {@code stop()}, further
 * publish calls are silently dropped.
 */
public final class InMemoryLiveEventPublisher extends AbstractLiveEventPublisher {

    private final LiveEventHub hub;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Consumer<ReviewDecisionForwardMessage>> reviewSubscriber = new AtomicReference<>();

    /**
     * Construct an in-memory publisher bound to a specific {@link LiveEventHub}. The publisher
     * is not started; call {@link #start()} before wiring it into a {@code WebDashboard}.
     *
     * @param hub        the destination hub; must not be null
     * @param info       this publisher's identity; must not be null
     * @param serializer the serializer to convert outgoing payloads to {@link com.fasterxml.jackson.databind.JsonNode}
     */
    public InMemoryLiveEventPublisher(LiveEventHub hub, ProducerInfo info, MessageSerializer serializer) {
        super(info, serializer);
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
    }

    @Override
    public void start() {
        if (started.compareAndSet(false, true)) {
            hub.registerPublisher(this);
        }
    }

    @Override
    public void stop() {
        if (started.compareAndSet(true, false)) {
            hub.unregisterPublisher(info().producerId(), "stopped");
        }
    }

    @Override
    public boolean isConnected() {
        return started.get();
    }

    @Override
    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {
        reviewSubscriber.set(subscriber);
    }

    @Override
    protected void publishEnvelope(LiveEventEnvelope envelope) {
        if (!started.get()) {
            return;
        }
        hub.ingest(envelope);
    }

    /**
     * Invoked by the hub when a browser-submitted review decision targets this publisher. The
     * default flow has the publisher's {@code RemoteReviewHandler} register itself via
     * {@link #subscribeToReviewDecisions(Consumer)}; this method bridges the hub's reverse
     * channel to that subscriber.
     *
     * <p>Package-private but invoked across packages via the hub-friendship contract.
     *
     * @param decision the hub's decision-forward payload; must not be null
     */
    public void deliverReviewDecision(ReviewDecisionForwardMessage decision) {
        Consumer<ReviewDecisionForwardMessage> s = reviewSubscriber.get();
        if (s != null) {
            s.accept(decision);
        }
    }
}
