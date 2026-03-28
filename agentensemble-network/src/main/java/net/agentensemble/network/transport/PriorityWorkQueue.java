package net.agentensemble.network.transport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import net.agentensemble.web.protocol.Priority;
import net.agentensemble.web.protocol.WorkRequest;

/**
 * A {@link RequestQueue} that orders work requests by priority with configurable aging.
 *
 * <p>Requests are dequeued in priority order ({@link Priority#CRITICAL} first,
 * {@link Priority#LOW} last). Within the same effective priority, FIFO ordering applies.
 *
 * <h2>Priority aging</h2>
 *
 * <p>To prevent starvation, low-priority requests are promoted over time. The
 * {@link AgingPolicy} configures how frequently promotions occur. For example, with a
 * 30-minute interval, a {@code LOW} request becomes {@code NORMAL} after 30 minutes,
 * {@code HIGH} after 60 minutes, and {@code CRITICAL} after 90 minutes.
 *
 * <p>Aging is computed lazily at dequeue time -- no background threads are used.
 *
 * <h2>Queue status</h2>
 *
 * <p>Use {@link #queueStatus(String, String)} to obtain the queue position and estimated
 * completion time for a specific request, suitable for populating a {@code task_accepted}
 * response.
 *
 * <h2>Metrics</h2>
 *
 * <p>An optional {@link QueueMetrics} callback is invoked after each enqueue and dequeue
 * operation with the current queue depth per priority level.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All operations are thread-safe. Internal state is guarded by a {@link ReentrantLock}
 * per queue name, and blocking dequeue uses the lock's {@link Condition}.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * RequestQueue queue = RequestQueue.priority(AgingPolicy.every(Duration.ofMinutes(30)));
 * queue.enqueue("kitchen", workRequest);
 * WorkRequest next = queue.dequeue("kitchen", Duration.ofSeconds(30));
 * </pre>
 *
 * @see AgingPolicy
 * @see QueueMetrics
 * @see QueueStatus
 * @see RequestQueue#priority(AgingPolicy)
 */
public class PriorityWorkQueue implements RequestQueue {

    private static final Duration DEFAULT_AVG_PROCESSING_TIME = Duration.ofSeconds(30);
    private static final Priority[] PRIORITIES = Priority.values();

    private final AgingPolicy agingPolicy;
    private final Clock clock;
    private final QueueMetrics metrics;
    private final Duration averageProcessingTime;
    private final ConcurrentHashMap<String, PriorityBuckets> queues = new ConcurrentHashMap<>();

    /**
     * Create a priority work queue with full configuration.
     *
     * @param agingPolicy           aging configuration; must not be null
     * @param clock                 clock for timestamps (inject a fixed clock for testing); must not be null
     * @param metrics               queue depth callback; must not be null
     * @param averageProcessingTime estimated time per request for ETA calculation; must not be null
     */
    PriorityWorkQueue(AgingPolicy agingPolicy, Clock clock, QueueMetrics metrics, Duration averageProcessingTime) {
        this.agingPolicy = Objects.requireNonNull(agingPolicy, "agingPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.averageProcessingTime =
                Objects.requireNonNull(averageProcessingTime, "averageProcessingTime must not be null");
    }

    /**
     * Create a priority work queue with default clock, no-op metrics, and default processing time.
     *
     * @param agingPolicy aging configuration; must not be null
     */
    PriorityWorkQueue(AgingPolicy agingPolicy) {
        this(agingPolicy, Clock.systemUTC(), QueueMetrics.noOp(), DEFAULT_AVG_PROCESSING_TIME);
    }

    /** Create a priority work queue with aging disabled and all defaults. */
    PriorityWorkQueue() {
        this(AgingPolicy.none());
    }

    @Override
    public void enqueue(String queueName, WorkRequest request) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(request, "request must not be null");

        PriorityBuckets buckets = bucketsFor(queueName);
        buckets.lock.lock();
        try {
            Priority priority = request.priority();
            QueueEntry entry = new QueueEntry(request, clock.instant(), priority);
            buckets.buckets[priority.ordinal()].addLast(entry);
            buckets.notEmpty.signal();
        } finally {
            buckets.lock.unlock();
        }

        reportMetrics(queueName, buckets);
    }

    @Override
    public WorkRequest dequeue(String queueName, Duration timeout) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        PriorityBuckets buckets = bucketsFor(queueName);
        long remainingNanos = timeout.toNanos();
        WorkRequest result = null;

        buckets.lock.lock();
        try {
            while (true) {
                QueueEntry best = findBestEntry(buckets);
                if (best != null) {
                    result = best.request;
                    break;
                }

                if (remainingNanos <= 0) {
                    break;
                }

                try {
                    remainingNanos = buckets.notEmpty.awaitNanos(remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            buckets.lock.unlock();
        }

        if (result != null) {
            reportMetrics(queueName, buckets);
        }

        return result;
    }

    @Override
    public void acknowledge(String queueName, String requestId) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        // No-op for in-memory: messages are removed on dequeue.
    }

    /**
     * Get the queue position and estimated completion time for a specific request.
     *
     * <p>The queue position counts how many requests are ahead of the given request in
     * effective priority order. Position 0 means the request is next to be dequeued.
     *
     * @param queueName the name of the queue; must not be null
     * @param requestId the request ID to look up; must not be null
     * @return the queue status, or {@code null} if the request is not found in the queue
     */
    public QueueStatus queueStatus(String queueName, String requestId) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");

        PriorityBuckets buckets = queues.get(queueName);
        if (buckets == null) {
            return null;
        }

        buckets.lock.lock();
        try {
            return computeQueueStatus(buckets, requestId);
        } finally {
            buckets.lock.unlock();
        }
    }

    // ========================
    // Internal data structures
    // ========================

    /**
     * Wrapper around a work request that tracks when it was enqueued and its original priority.
     */
    private record QueueEntry(WorkRequest request, Instant enqueuedAt, Priority originalPriority) {}

    /**
     * Per-queue-name priority buckets with a shared lock and condition.
     */
    private static final class PriorityBuckets {
        final ReentrantLock lock = new ReentrantLock();
        final Condition notEmpty = lock.newCondition();

        @SuppressWarnings("unchecked")
        final LinkedList<QueueEntry>[] buckets = new LinkedList[PRIORITIES.length];

        PriorityBuckets() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LinkedList<>();
            }
        }
    }

    // ========================
    // Private helpers
    // ========================

    private PriorityBuckets bucketsFor(String queueName) {
        return queues.computeIfAbsent(queueName, k -> new PriorityBuckets());
    }

    /**
     * Find and remove the highest-effective-priority entry across all buckets.
     *
     * <p>Checks the head (oldest) entry in each non-empty bucket, computes its effective
     * priority after aging, and picks the one with the highest effective priority. Ties
     * are broken by earliest enqueue time (FIFO across promoted entries).
     *
     * <p>Must be called while holding {@code buckets.lock}.
     *
     * @return the removed entry, or {@code null} if all buckets are empty
     */
    private QueueEntry findBestEntry(PriorityBuckets buckets) {
        Instant now = clock.instant();
        int bestEffectiveOrdinal = Integer.MAX_VALUE;
        Instant bestEnqueueTime = null;
        int bestBucketIndex = -1;

        for (int i = 0; i < PRIORITIES.length; i++) {
            LinkedList<QueueEntry> bucket = buckets.buckets[i];
            if (bucket.isEmpty()) {
                continue;
            }

            QueueEntry head = bucket.peekFirst();
            int effectiveOrdinal = effectiveOrdinal(head, now);

            if (effectiveOrdinal < bestEffectiveOrdinal
                    || (effectiveOrdinal == bestEffectiveOrdinal && head.enqueuedAt.isBefore(bestEnqueueTime))) {
                bestEffectiveOrdinal = effectiveOrdinal;
                bestEnqueueTime = head.enqueuedAt;
                bestBucketIndex = i;
            }
        }

        if (bestBucketIndex < 0) {
            return null;
        }

        return buckets.buckets[bestBucketIndex].removeFirst();
    }

    /**
     * Compute the effective priority ordinal of an entry after aging.
     *
     * <p>Each elapsed {@link AgingPolicy#promotionInterval()} reduces the ordinal by one
     * (moving toward {@link Priority#CRITICAL}). The result is clamped to 0 (CRITICAL).
     * Uses nanosecond precision to avoid division by zero with sub-millisecond intervals.
     * Negative elapsed time (clock moved backwards) is treated as zero.
     */
    private int effectiveOrdinal(QueueEntry entry, Instant now) {
        long elapsedNanos = Duration.between(entry.enqueuedAt, now).toNanos();
        if (elapsedNanos < 0L) {
            elapsedNanos = 0L;
        }

        long intervalNanos = agingPolicy.promotionInterval().toNanos();
        if (intervalNanos <= 0L) {
            return entry.originalPriority.ordinal();
        }

        long rawPromotions = elapsedNanos / intervalNanos;
        int originalOrdinal = entry.originalPriority.ordinal();
        long cappedPromotions = Math.min(rawPromotions, (long) originalOrdinal);

        return originalOrdinal - (int) cappedPromotions;
    }

    /**
     * Compute queue status for a specific request.
     *
     * <p>Counts entries that would be dequeued before the target request based on effective
     * priority ordering. Must be called while holding {@code buckets.lock}.
     */
    private QueueStatus computeQueueStatus(PriorityBuckets buckets, String requestId) {
        Instant now = clock.instant();

        // Build a flat list of all entries with their effective priorities.
        record Ranked(QueueEntry entry, int effectiveOrdinal) {}
        java.util.List<Ranked> allEntries = new java.util.ArrayList<>();

        for (int i = 0; i < PRIORITIES.length; i++) {
            for (QueueEntry entry : buckets.buckets[i]) {
                allEntries.add(new Ranked(entry, effectiveOrdinal(entry, now)));
            }
        }

        // Sort by effective priority (lowest ordinal first), then by enqueue time (oldest first).
        allEntries.sort((a, b) -> {
            int cmp = Integer.compare(a.effectiveOrdinal, b.effectiveOrdinal);
            if (cmp != 0) {
                return cmp;
            }
            return a.entry.enqueuedAt.compareTo(b.entry.enqueuedAt);
        });

        // Find the target request and count entries ahead of it.
        for (int i = 0; i < allEntries.size(); i++) {
            if (allEntries.get(i).entry.request.requestId().equals(requestId)) {
                Duration eta = averageProcessingTime.multipliedBy(i + 1);
                return new QueueStatus(i, eta);
            }
        }

        return null; // Request not found.
    }

    /** Report queue depth metrics for all priority levels. Must NOT hold the lock. */
    private void reportMetrics(String queueName, PriorityBuckets buckets) {
        int[] depths = new int[PRIORITIES.length];

        buckets.lock.lock();
        try {
            for (int i = 0; i < PRIORITIES.length; i++) {
                depths[i] = buckets.buckets[i].size();
            }
        } finally {
            buckets.lock.unlock();
        }

        for (int i = 0; i < PRIORITIES.length; i++) {
            metrics.recordQueueDepth(queueName, PRIORITIES[i], depths[i]);
        }
    }
}
