package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.Priority;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PriorityWorkQueue}.
 */
class PriorityWorkQueueTest {

    private static final Instant BASE_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration AGING_INTERVAL = Duration.ofMinutes(30);

    // ========================
    // Factory
    // ========================

    @Test
    void priority_factoryReturnsInstance() {
        assertThat(RequestQueue.priority()).isNotNull().isInstanceOf(PriorityWorkQueue.class);
    }

    @Test
    void priority_factoryWithAgingPolicy_returnsInstance() {
        assertThat(RequestQueue.priority(AgingPolicy.every(Duration.ofMinutes(10))))
                .isNotNull()
                .isInstanceOf(PriorityWorkQueue.class);
    }

    // ========================
    // Priority ordering
    // ========================

    @Test
    void dequeue_returnsCriticalBeforeHigh() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("high-1", Priority.HIGH));
        queue.enqueue("q", workRequest("critical-1", Priority.CRITICAL));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("critical-1");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("high-1");
    }

    @Test
    void dequeue_returnsHighBeforeNormal() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("normal-1", Priority.NORMAL));
        queue.enqueue("q", workRequest("high-1", Priority.HIGH));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("high-1");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("normal-1");
    }

    @Test
    void dequeue_returnsNormalBeforeLow() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("low-1", Priority.LOW));
        queue.enqueue("q", workRequest("normal-1", Priority.NORMAL));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("normal-1");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low-1");
    }

    @Test
    void dequeue_fullPriorityOrdering() {
        PriorityWorkQueue queue = defaultQueue();

        // Enqueue in reverse priority order
        queue.enqueue("q", workRequest("low", Priority.LOW));
        queue.enqueue("q", workRequest("normal", Priority.NORMAL));
        queue.enqueue("q", workRequest("high", Priority.HIGH));
        queue.enqueue("q", workRequest("critical", Priority.CRITICAL));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("critical");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("high");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("normal");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low");
    }

    // ========================
    // FIFO within same priority
    // ========================

    @Test
    void dequeue_samePriority_fifoOrder() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("first", Priority.NORMAL));
        queue.enqueue("q", workRequest("second", Priority.NORMAL));
        queue.enqueue("q", workRequest("third", Priority.NORMAL));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("first");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("second");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("third");
    }

    @Test
    void dequeue_samePriority_fifoPerLevel() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("high-1", Priority.HIGH));
        queue.enqueue("q", workRequest("high-2", Priority.HIGH));
        queue.enqueue("q", workRequest("low-1", Priority.LOW));
        queue.enqueue("q", workRequest("low-2", Priority.LOW));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("high-1");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("high-2");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low-1");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low-2");
    }

    // ========================
    // Aging
    // ========================

    @Test
    void dequeue_aging_promotesLowToNormal() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        // Enqueue a LOW request at t=0
        queue.enqueue("q", workRequest("old-low", Priority.LOW));

        // Advance clock by one aging interval
        clock.advance(AGING_INTERVAL);

        // Enqueue a fresh NORMAL request at t=30min
        queue.enqueue("q", workRequest("fresh-normal", Priority.NORMAL));

        // The aged LOW (now effectively NORMAL) should come first because it's older
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("old-low");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("fresh-normal");
    }

    @Test
    void dequeue_aging_promotesNormalToHigh() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        queue.enqueue("q", workRequest("old-normal", Priority.NORMAL));

        clock.advance(AGING_INTERVAL);

        queue.enqueue("q", workRequest("fresh-high", Priority.HIGH));

        // Aged NORMAL (now effectively HIGH) beats fresh HIGH because it's older
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("old-normal");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("fresh-high");
    }

    @Test
    void dequeue_aging_multiplePromotions() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        // Enqueue LOW at t=0
        queue.enqueue("q", workRequest("old-low", Priority.LOW));

        // Advance by 2 intervals: LOW -> NORMAL -> HIGH
        clock.advance(AGING_INTERVAL.multipliedBy(2));

        // Enqueue a fresh HIGH
        queue.enqueue("q", workRequest("fresh-high", Priority.HIGH));

        // Aged LOW (now effectively HIGH) beats fresh HIGH because it's older
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("old-low");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("fresh-high");
    }

    @Test
    void dequeue_aging_capsAtCritical() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        queue.enqueue("q", workRequest("old-low", Priority.LOW));

        // Advance by 10 intervals -- far more than needed to reach CRITICAL
        clock.advance(AGING_INTERVAL.multipliedBy(10));

        queue.enqueue("q", workRequest("fresh-critical", Priority.CRITICAL));

        // Aged LOW (capped at CRITICAL) should still be first since it's older
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("old-low");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("fresh-critical");
    }

    @Test
    void dequeue_aging_disabled() {
        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(),
                Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                QueueMetrics.noOp(),
                Duration.ofSeconds(30));

        queue.enqueue("q", workRequest("low", Priority.LOW));
        queue.enqueue("q", workRequest("normal", Priority.NORMAL));

        // Even though LOW was enqueued first, it stays LOW -- NORMAL comes first
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("normal");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low");
    }

    @Test
    void dequeue_aging_fifoAcrossPromotedEntries() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        // Enqueue two LOW requests
        queue.enqueue("q", workRequest("low-first", Priority.LOW));
        queue.enqueue("q", workRequest("low-second", Priority.LOW));

        // Advance by one interval: both are now effectively NORMAL
        clock.advance(AGING_INTERVAL);

        // Both are at the same effective priority -- FIFO applies, older first
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low-first");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("low-second");
    }

    @Test
    void dequeue_aging_promotedLowBeatsUnpromotedLow() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        queue.enqueue("q", workRequest("old-low", Priority.LOW));
        clock.advance(AGING_INTERVAL); // old-low is now effectively NORMAL

        queue.enqueue("q", workRequest("fresh-low", Priority.LOW));

        // old-low (effectively NORMAL) beats fresh-low (still LOW)
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("old-low");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("fresh-low");
    }

    // ========================
    // Blocking / timeout
    // ========================

    @Test
    void dequeue_emptyQueue_returnsNullAfterTimeout() {
        PriorityWorkQueue queue = defaultQueue();
        WorkRequest result = queue.dequeue("empty", Duration.ofMillis(50));
        assertThat(result).isNull();
    }

    @Test
    void dequeue_blocksUntilEnqueue() throws Exception {
        PriorityWorkQueue queue = defaultQueue();
        AtomicReference<WorkRequest> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            received.set(queue.dequeue("q", Duration.ofSeconds(5)));
            done.countDown();
        });
        consumer.start();

        // Give the consumer thread time to block
        Thread.sleep(50);

        queue.enqueue("q", workRequest("delayed", Priority.NORMAL));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get()).isNotNull();
        assertThat(received.get().requestId()).isEqualTo("delayed");
    }

    // ========================
    // Queue status
    // ========================

    @Test
    void queueStatus_returnsCorrectPosition() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("first", Priority.CRITICAL));
        queue.enqueue("q", workRequest("second", Priority.HIGH));
        queue.enqueue("q", workRequest("third", Priority.NORMAL));

        QueueStatus status = queue.queueStatus("q", "third");
        assertThat(status).isNotNull();
        assertThat(status.queuePosition()).isEqualTo(2);
    }

    @Test
    void queueStatus_positionZeroWhenFirst() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("q", workRequest("only", Priority.CRITICAL));

        QueueStatus status = queue.queueStatus("q", "only");
        assertThat(status).isNotNull();
        assertThat(status.queuePosition()).isZero();
    }

    @Test
    void queueStatus_unknownRequestId_returnsNull() {
        PriorityWorkQueue queue = defaultQueue();
        queue.enqueue("q", workRequest("exists", Priority.NORMAL));

        assertThat(queue.queueStatus("q", "does-not-exist")).isNull();
    }

    @Test
    void queueStatus_unknownQueue_returnsNull() {
        PriorityWorkQueue queue = defaultQueue();
        assertThat(queue.queueStatus("no-such-queue", "req-1")).isNull();
    }

    @Test
    void queueStatus_estimatedCompletion_proportionalToPosition() {
        Duration avgTime = Duration.ofSeconds(30);
        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(), Clock.fixed(BASE_TIME, ZoneOffset.UTC), QueueMetrics.noOp(), avgTime);

        queue.enqueue("q", workRequest("first", Priority.HIGH));
        queue.enqueue("q", workRequest("second", Priority.HIGH));
        queue.enqueue("q", workRequest("third", Priority.NORMAL));

        QueueStatus status = queue.queueStatus("q", "third");
        assertThat(status).isNotNull();
        // Position 2 means 2 items ahead, ETA = (position + 1) * avgTime = 3 * 30s = 90s
        assertThat(status.estimatedCompletion()).isEqualTo(avgTime.multipliedBy(3));
    }

    @Test
    void queueStatus_reflectsAgingOrder() {
        MutableClock clock = new MutableClock(BASE_TIME);
        PriorityWorkQueue queue = queueWithClock(clock);

        // Enqueue LOW, then advance clock so it ages to NORMAL-equivalent
        queue.enqueue("q", workRequest("aged-low", Priority.LOW));
        clock.advance(AGING_INTERVAL);

        // Enqueue a fresh NORMAL -- both are effectively NORMAL, but aged-low is older
        queue.enqueue("q", workRequest("fresh-normal", Priority.NORMAL));

        // aged-low should be position 0 (dequeued first), fresh-normal position 1
        assertThat(queue.queueStatus("q", "aged-low").queuePosition()).isZero();
        assertThat(queue.queueStatus("q", "fresh-normal").queuePosition()).isEqualTo(1);
    }

    // ========================
    // Metrics
    // ========================

    @Test
    void enqueue_callsMetricsCallback() {
        List<MetricsCall> calls = new CopyOnWriteArrayList<>();
        QueueMetrics metrics = (queueName, priority, depth) -> calls.add(new MetricsCall(queueName, priority, depth));

        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(), Clock.fixed(BASE_TIME, ZoneOffset.UTC), metrics, Duration.ofSeconds(30));

        queue.enqueue("kitchen", workRequest("req-1", Priority.HIGH));

        // Should have reported depths for all 4 priority levels
        assertThat(calls).hasSize(4);
        assertThat(calls).anyMatch(c -> c.priority == Priority.HIGH && c.depth == 1 && c.queueName.equals("kitchen"));
        assertThat(calls).filteredOn(c -> c.priority != Priority.HIGH).allMatch(c -> c.depth == 0);
    }

    @Test
    void dequeue_callsMetricsCallback() {
        List<MetricsCall> calls = new CopyOnWriteArrayList<>();
        QueueMetrics metrics = (queueName, priority, depth) -> calls.add(new MetricsCall(queueName, priority, depth));

        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(), Clock.fixed(BASE_TIME, ZoneOffset.UTC), metrics, Duration.ofSeconds(30));

        queue.enqueue("kitchen", workRequest("req-1", Priority.NORMAL));
        calls.clear();

        queue.dequeue("kitchen", Duration.ofSeconds(1));

        // After dequeue, NORMAL depth should be 0
        assertThat(calls).hasSize(4);
        assertThat(calls).allMatch(c -> c.depth == 0);
    }

    // ========================
    // Multi-queue isolation
    // ========================

    @Test
    void enqueue_multipleQueues_independent() {
        PriorityWorkQueue queue = defaultQueue();

        queue.enqueue("kitchen", workRequest("k-1", Priority.NORMAL));
        queue.enqueue("maintenance", workRequest("m-1", Priority.NORMAL));

        WorkRequest fromKitchen = queue.dequeue("kitchen", Duration.ofSeconds(1));
        WorkRequest fromMaint = queue.dequeue("maintenance", Duration.ofSeconds(1));

        assertThat(fromKitchen.requestId()).isEqualTo("k-1");
        assertThat(fromMaint.requestId()).isEqualTo("m-1");

        assertThat(queue.dequeue("kitchen", Duration.ofMillis(10))).isNull();
    }

    // ========================
    // Acknowledge
    // ========================

    @Test
    void acknowledge_noOp_doesNotThrow() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatNoException().isThrownBy(() -> queue.acknowledge("q", "req-1"));
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void enqueue_nullQueueName_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.enqueue(null, workRequest("req-1", Priority.NORMAL)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void enqueue_nullRequest_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.enqueue("q", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullQueueName_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.dequeue(null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullTimeout_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.dequeue("q", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acknowledge_nullQueueName_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.acknowledge(null, "req-1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acknowledge_nullRequestId_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.acknowledge("q", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void queueStatus_nullQueueName_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.queueStatus(null, "req-1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void queueStatus_nullRequestId_throwsNPE() {
        PriorityWorkQueue queue = defaultQueue();
        assertThatThrownBy(() -> queue.queueStatus("q", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Capacity limits
    // ========================

    @Test
    void enqueue_atCapacity_throwsQueueFullException() {
        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(),
                Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                QueueMetrics.noOp(),
                Duration.ofSeconds(30),
                2);

        queue.enqueue("q", workRequest("req-1", Priority.NORMAL));
        queue.enqueue("q", workRequest("req-2", Priority.HIGH));

        assertThatThrownBy(() -> queue.enqueue("q", workRequest("req-3", Priority.CRITICAL)))
                .isInstanceOf(QueueFullException.class)
                .hasMessageContaining("capacity (2)");
    }

    @Test
    void enqueue_afterDequeue_succeedsAgain() {
        PriorityWorkQueue queue = new PriorityWorkQueue(
                AgingPolicy.none(),
                Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                QueueMetrics.noOp(),
                Duration.ofSeconds(30),
                1);

        queue.enqueue("q", workRequest("req-1", Priority.NORMAL));
        queue.dequeue("q", Duration.ofSeconds(1));

        // Capacity freed: should succeed
        queue.enqueue("q", workRequest("req-2", Priority.NORMAL));
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("req-2");
    }

    @Test
    void capacityFactory_createsCorrectly() {
        PriorityWorkQueue queue = RequestQueue.priority(AgingPolicy.none(), 50);
        assertThat(queue.maxCapacity()).isEqualTo(50);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_enqueueDequeue_threadSafe() throws Exception {
        PriorityWorkQueue queue = defaultQueue();
        int messageCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Producers -- mix priorities
            for (int i = 0; i < messageCount; i++) {
                int idx = i;
                Priority priority = Priority.values()[idx % Priority.values().length];
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    queue.enqueue("concurrent", workRequest("req-" + idx, priority));
                });
            }

            // Consumers
            for (int i = 0; i < messageCount; i++) {
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    WorkRequest req = queue.dequeue("concurrent", Duration.ofSeconds(5));
                    if (req != null) {
                        received.add(req.requestId());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received).hasSize(messageCount);
    }

    // ========================
    // Helpers
    // ========================

    private static PriorityWorkQueue defaultQueue() {
        return new PriorityWorkQueue(
                AgingPolicy.none(),
                Clock.fixed(BASE_TIME, ZoneOffset.UTC),
                QueueMetrics.noOp(),
                Duration.ofSeconds(30));
    }

    private static PriorityWorkQueue queueWithClock(MutableClock clock) {
        return new PriorityWorkQueue(
                AgingPolicy.every(AGING_INTERVAL), clock, QueueMetrics.noOp(), Duration.ofSeconds(30));
    }

    private static WorkRequest workRequest(String requestId, Priority priority) {
        return new WorkRequest(requestId, "test-ensemble", "task", null, priority, null, null, null, null, null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record MetricsCall(String queueName, Priority priority, int depth) {}

    /**
     * A mutable clock for deterministic testing of time-dependent behavior.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this; // zone is irrelevant for Instant
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
