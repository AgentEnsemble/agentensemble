package net.agentensemble.network.transport.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.network.transport.RequestQueue;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueIngress}.
 */
class QueueIngressTest {

    private QueueIngress ingress;

    @AfterEach
    void tearDown() {
        if (ingress != null) {
            ingress.stop();
        }
    }

    // ========================
    // name
    // ========================

    @Test
    void name_returnsQueuePrefixed() {
        ingress = new QueueIngress(RequestQueue.inMemory(), "kitchen");
        assertThat(ingress.name()).isEqualTo("queue:kitchen");
    }

    // ========================
    // start / poll
    // ========================

    @Test
    void start_pollsQueueAndForwardsToSink() throws Exception {
        RequestQueue queue = RequestQueue.inMemory();
        ingress = new QueueIngress(queue, "test-q");

        CountDownLatch latch = new CountDownLatch(1);
        List<WorkRequest> received = Collections.synchronizedList(new ArrayList<>());

        ingress.start(req -> {
            received.add(req);
            latch.countDown();
        });

        // Enqueue after start so the poller picks it up
        queue.enqueue("test-q", workRequest("req-1", "do-work"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-1");
    }

    @Test
    void start_nullSink_throwsNPE() {
        ingress = new QueueIngress(RequestQueue.inMemory(), "test-q");
        assertThatThrownBy(() -> ingress.start(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // stop
    // ========================

    @Test
    void stop_stopsPolling() throws Exception {
        RequestQueue queue = RequestQueue.inMemory();
        ingress = new QueueIngress(queue, "test-q");

        CountDownLatch firstReceived = new CountDownLatch(1);
        List<WorkRequest> received = Collections.synchronizedList(new ArrayList<>());

        ingress.start(req -> {
            received.add(req);
            firstReceived.countDown();
        });

        queue.enqueue("test-q", workRequest("req-1", "task"));
        assertThat(firstReceived.await(5, TimeUnit.SECONDS)).isTrue();

        ingress.stop();

        // The polling thread may be mid-poll with a 1-second timeout. Wait long enough
        // for it to finish and observe the stop flag before enqueuing more items.
        Thread.sleep(1500);

        // Enqueue more items after stop
        queue.enqueue("test-q", workRequest("req-2", "task"));

        // Wait a bit to confirm no more items arrive at the sink
        Thread.sleep(500);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-1");
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullQueue_throwsNPE() {
        assertThatThrownBy(() -> new QueueIngress(null, "q")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> new QueueIngress(RequestQueue.inMemory(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null, null);
    }
}
