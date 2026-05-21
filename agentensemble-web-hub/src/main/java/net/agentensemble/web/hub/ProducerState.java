package net.agentensemble.web.hub;

import java.time.Instant;
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

    private volatile ProducerInfo info;
    private volatile Instant lastSeenAt;
    private final AtomicLong lastSequence = new AtomicLong(-1L);
    private final ConnectionManager snapshot;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<String> pendingReviewIds = ConcurrentHashMap.newKeySet();

    ProducerState(ProducerInfo info, ConnectionManager snapshot) {
        this.info = info;
        this.snapshot = snapshot;
        this.lastSeenAt = Instant.now();
    }

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
