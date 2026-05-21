package net.agentensemble.web.hub;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.agentensemble.web.ConnectionManager;
import net.agentensemble.web.protocol.ProducerInfo;

/**
 * Hub-side state for one producer.
 *
 * <p>Tracks producer identity, last activity, last observed sequence, an
 * <em>internal-only</em> {@link ConnectionManager} that the hub uses as a per-producer snapshot
 * store (zero registered sessions; we exploit only its {@code appendToSnapshot} +
 * {@code noteEnsembleStarted} + iteration ring buffer behavior), and the set of review IDs the
 * hub has forwarded from this producer to browsers but not yet resolved.
 *
 * <p>Package-private; consumed exclusively by {@link ProducerRegistry} and {@link LiveEventHub}.
 */
final class ProducerState {

    /** Bound on retained envelope-log entries per producer; chosen large enough to cover a
     * deep run without dominating memory. The per-run inner-message snapshot in
     * {@link ConnectionManager} provides the primary retention semantics; this log is
     * strictly for deterministic ordering across producers in {@code hub_hello}. */
    private static final int ENVELOPE_LOG_CAP = 2000;

    private volatile ProducerInfo info;
    private volatile Instant lastSeenAt;
    private final AtomicLong lastSequence = new AtomicLong(-1L);
    private final ConnectionManager snapshot;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<String> pendingReviewIds = ConcurrentHashMap.newKeySet();

    /**
     * Ring of envelope JSON payloads in arrival order, paired with the receivedAt timestamp
     * stamped at ingest. Used by {@code LiveEventHub.buildFlattenedSnapshot} to produce a
     * deterministically-ordered late-join trace across all producers.
     */
    private final Deque<EnvelopeLogEntry> envelopeLog = new ArrayDeque<>();

    ProducerState(ProducerInfo info, ConnectionManager snapshot) {
        this.info = info;
        this.snapshot = snapshot;
        this.lastSeenAt = Instant.now();
    }

    /**
     * Append the given envelope JSON + receivedAt to the per-producer log. Caller is expected
     * to hold ingest-time happens-before via the registry; the deque is guarded internally so
     * concurrent reads (e.g. snapshotEnvelopes from a browser-connect path) see a consistent
     * view.
     */
    synchronized void appendEnvelope(Instant receivedAt, String envelopeJson) {
        envelopeLog.addLast(new EnvelopeLogEntry(receivedAt, envelopeJson));
        while (envelopeLog.size() > ENVELOPE_LOG_CAP) {
            envelopeLog.removeFirst();
        }
    }

    /**
     * Returns a defensive copy of the envelope log. Each entry pairs the receivedAt with the
     * serialized envelope JSON.
     */
    synchronized List<EnvelopeLogEntry> snapshotEnvelopes() {
        return new ArrayList<>(envelopeLog);
    }

    record EnvelopeLogEntry(Instant receivedAt, String envelopeJson) {}

    ProducerInfo info() {
        return info;
    }

    void updateInfo(ProducerInfo info) {
        this.info = info;
    }

    Instant lastSeenAt() {
        return lastSeenAt;
    }

    void markSeen() {
        this.lastSeenAt = Instant.now();
    }

    /**
     * Returns the previous sequence observed for this producer and atomically stores the new
     * value. The caller uses the prior value to detect gaps (e.g. dropped envelopes).
     *
     * @param incoming the sequence from the just-received envelope
     * @return the previous sequence, or -1 if none observed yet
     */
    long observeSequence(long incoming) {
        return lastSequence.getAndSet(incoming);
    }

    ConnectionManager snapshot() {
        return snapshot;
    }

    boolean isActive() {
        return active.get();
    }

    void setActive(boolean value) {
        active.set(value);
    }

    void recordPendingReview(String reviewId) {
        pendingReviewIds.add(reviewId);
    }

    void clearPendingReview(String reviewId) {
        pendingReviewIds.remove(reviewId);
    }

    boolean hasPendingReview(String reviewId) {
        return pendingReviewIds.contains(reviewId);
    }
}
