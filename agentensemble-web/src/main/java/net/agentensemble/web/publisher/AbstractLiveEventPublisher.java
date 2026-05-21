package net.agentensemble.web.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common scaffolding for {@link LiveEventPublisher} implementations: envelope construction,
 * sequence numbering, JSON serialization, and the per-call sink-method translation.
 *
 * <p>Subclasses implement {@link #publishEnvelope(LiveEventEnvelope)} for the wire side.
 *
 * <p>Iteration ring buffer and snapshot persistence are no-ops by default: the hub owns those
 * because it serves browsers. Subclasses with custom behavior may override.
 */
public abstract class AbstractLiveEventPublisher implements LiveEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AbstractLiveEventPublisher.class);

    private final ProducerInfo info;
    private final MessageSerializer serializer;
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    protected AbstractLiveEventPublisher(ProducerInfo info, MessageSerializer serializer) {
        this.info = Objects.requireNonNull(info, "info must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
    }

    @Override
    public final ProducerInfo info() {
        return info;
    }

    /**
     * Subclass hook: actually ship the envelope over the underlying transport. Called from a
     * listener virtual thread; the implementation may queue, drop, or block according to its
     * back-pressure policy.
     *
     * @param envelope the envelope to ship; never null
     */
    protected abstract void publishEnvelope(LiveEventEnvelope envelope);

    /**
     * Returns the {@link MessageSerializer} used to convert raw broadcast JSON into
     * {@link JsonNode} payloads.
     */
    protected final MessageSerializer serializer() {
        return serializer;
    }

    @Override
    public final void accept(String json) {
        LiveEventEnvelope envelope = buildEnvelope(json);
        if (envelope == null) {
            return;
        }
        try {
            publishEnvelope(envelope);
        } catch (Exception e) {
            log.warn(
                    "Publisher {} failed to ship envelope (seq={}): {}",
                    info.producerId(),
                    envelope.sequence(),
                    e.getMessage(),
                    log.isDebugEnabled() ? e : null);
        }
    }

    @Override
    public void appendToSnapshot(String json) {
        // Publishers do not own snapshot storage; the hub records each ingested envelope into
        // its per-producer snapshot. This method is intentionally a no-op.
    }

    @Override
    public void noteEnsembleStarted(String ensembleId, Instant startedAt) {
        // Same rationale as appendToSnapshot: snapshot lifecycle is the hub's responsibility.
        // The hub observes ensemble_started events in the broadcast stream and opens a new
        // per-run inner list on the producer's snapshot ConnectionManager.
    }

    @Override
    public void clearIterationSnapshots() {
        // Iteration ring buffer lives on the hub. Local clear is unnecessary.
    }

    @Override
    public void recordIterationStarted(String key, LlmIterationStartedMessage msg) {
        // Hub re-derives iteration snapshots from the broadcast envelope stream.
    }

    @Override
    public void recordIterationCompleted(String key, LlmIterationCompletedMessage msg) {
        // Hub re-derives iteration snapshots from the broadcast envelope stream.
    }

    private LiveEventEnvelope buildEnvelope(String json) {
        JsonNode payload = serializer.toJsonNode(json);
        if (payload == null) {
            log.warn(
                    "Publisher {} dropping unparseable broadcast payload (length={})",
                    info.producerId(),
                    json == null ? 0 : json.length());
            return null;
        }
        return new LiveEventEnvelope(info, sequenceCounter.incrementAndGet(), Instant.now(), payload);
    }
}
