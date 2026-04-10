package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the new {@link ConnectionManager} methods added in Phases 3/4/5:
 * subscription-aware broadcast, SSE broadcast callbacks, and pending review listing.
 */
class ConnectionManagerSubscriptionTest {

    private ConnectionManager manager;
    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        manager = new ConnectionManager(serializer);
    }

    // ========================
    // setSubscriptionManager
    // ========================

    @Test
    void setSubscriptionManager_null_disablesFiltering() {
        SubscriptionManager sm = new SubscriptionManager();
        manager.setSubscriptionManager(sm);
        manager.setSubscriptionManager(null); // should not throw
        // With null SM, broadcast delivers to all (no filtering)
    }

    @Test
    void setSubscriptionManager_nonNull_enablesFiltering() {
        SubscriptionManager sm = new SubscriptionManager();
        manager.setSubscriptionManager(sm); // should not throw
    }

    // ========================
    // Broadcast callbacks
    // ========================

    @Test
    void registerBroadcastCallback_callbackReceivesBroadcastEvents() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        manager.registerBroadcastCallback("cb-1", received::add);

        manager.broadcast("{\"type\":\"task_started\"}");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isEqualTo("{\"type\":\"task_started\"}");
    }

    @Test
    void registerBroadcastCallback_multipleCallbacks_allReceiveEvent() {
        CopyOnWriteArrayList<String> received1 = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> received2 = new CopyOnWriteArrayList<>();
        manager.registerBroadcastCallback("cb-1", received1::add);
        manager.registerBroadcastCallback("cb-2", received2::add);

        manager.broadcast("{\"type\":\"heartbeat\"}");

        assertThat(received1).hasSize(1);
        assertThat(received2).hasSize(1);
    }

    @Test
    void unregisterBroadcastCallback_callbackNoLongerReceivesEvents() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        manager.registerBroadcastCallback("cb-1", received::add);
        manager.unregisterBroadcastCallback("cb-1");

        manager.broadcast("{\"type\":\"heartbeat\"}");

        assertThat(received).isEmpty();
    }

    @Test
    void broadcastCallback_throwingCallback_doesNotPreventOtherCallbacks() {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        manager.registerBroadcastCallback("bad", json -> {
            throw new RuntimeException("oops");
        });
        manager.registerBroadcastCallback("good", received::add);

        // Should not throw; bad callback is caught and logged
        manager.broadcast("{\"type\":\"test\"}");

        assertThat(received).hasSize(1);
    }

    // ========================
    // Subscription-aware broadcast
    // ========================

    @Test
    void broadcast_withSubscriptionManager_filteredEventsNotDelivered() {
        SubscriptionManager sm = new SubscriptionManager();
        manager.setSubscriptionManager(sm);

        // Connect a fake session
        AtomicReference<String> lastReceived = new AtomicReference<>();
        WsSession session = new WsSession() {
            @Override
            public String id() {
                return "s1";
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void send(String msg) {
                lastReceived.set(msg);
            }
        };
        manager.onConnect(session);

        // Subscribe to only task_started events
        sm.subscribe("s1", List.of("task_started"), null);

        // Broadcast a heartbeat -- should not be delivered
        lastReceived.set(null);
        manager.broadcast("{\"type\":\"heartbeat\"}");
        assertThat(lastReceived.get()).isNull();

        // Broadcast a task_started -- should be delivered
        manager.broadcast("{\"type\":\"task_started\"}");
        assertThat(lastReceived.get()).isEqualTo("{\"type\":\"task_started\"}");
    }

    // ========================
    // hasPendingReview
    // ========================

    @Test
    void hasPendingReview_noPendingReview_returnsFalse() {
        assertThat(manager.hasPendingReview("rev-unknown")).isFalse();
    }

    @Test
    void hasPendingReview_registeredReview_returnsTrue() {
        CompletableFuture<String> future = new CompletableFuture<>();
        manager.registerPendingReview("rev-1", future);

        assertThat(manager.hasPendingReview("rev-1")).isTrue();
    }

    @Test
    void hasPendingReview_resolvedReview_returnsFalse() {
        CompletableFuture<String> future = new CompletableFuture<>();
        manager.registerPendingReview("rev-1", future);
        manager.resolveReview("rev-1", "CONTINUE");

        assertThat(manager.hasPendingReview("rev-1")).isFalse();
    }

    // ========================
    // listPendingReviews
    // ========================

    @Test
    void listPendingReviews_noReviews_returnsEmptyList() {
        assertThat(manager.listPendingReviews(null)).isEmpty();
    }

    @Test
    void listPendingReviews_withActivePendingReview_returnsInfo() {
        CompletableFuture<String> future = new CompletableFuture<>();
        ConnectionManager.PendingReviewInfo info = new ConnectionManager.PendingReviewInfo(
                "rev-1",
                "run-abc",
                "Research task",
                "output text",
                "AFTER_EXECUTION",
                "Review please",
                30000L,
                Instant.now(),
                null);
        manager.registerPendingReview("rev-1", future, info);

        List<ConnectionManager.PendingReviewInfo> reviews = manager.listPendingReviews(null);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).reviewId()).isEqualTo("rev-1");
        assertThat(reviews.get(0).runId()).isEqualTo("run-abc");
        assertThat(reviews.get(0).taskDescription()).isEqualTo("Research task");
    }

    @Test
    void listPendingReviews_withRunIdFilter_returnsOnlyMatchingReviews() {
        CompletableFuture<String> f1 = new CompletableFuture<>();
        CompletableFuture<String> f2 = new CompletableFuture<>();
        ConnectionManager.PendingReviewInfo info1 = new ConnectionManager.PendingReviewInfo(
                "rev-1", "run-abc", "Task 1", "", "AFTER_EXECUTION", null, 0L, Instant.now(), null);
        ConnectionManager.PendingReviewInfo info2 = new ConnectionManager.PendingReviewInfo(
                "rev-2", "run-xyz", "Task 2", "", "AFTER_EXECUTION", null, 0L, Instant.now(), null);
        manager.registerPendingReview("rev-1", f1, info1);
        manager.registerPendingReview("rev-2", f2, info2);

        List<ConnectionManager.PendingReviewInfo> abcReviews = manager.listPendingReviews("run-abc");
        assertThat(abcReviews).hasSize(1);
        assertThat(abcReviews.get(0).reviewId()).isEqualTo("rev-1");

        List<ConnectionManager.PendingReviewInfo> xyzReviews = manager.listPendingReviews("run-xyz");
        assertThat(xyzReviews).hasSize(1);
        assertThat(xyzReviews.get(0).reviewId()).isEqualTo("rev-2");

        List<ConnectionManager.PendingReviewInfo> allReviews = manager.listPendingReviews(null);
        assertThat(allReviews).hasSize(2);
    }

    @Test
    void listPendingReviews_resolvedReviewNotListed() {
        CompletableFuture<String> future = new CompletableFuture<>();
        ConnectionManager.PendingReviewInfo info = new ConnectionManager.PendingReviewInfo(
                "rev-1", null, "Task", "", "AFTER_EXECUTION", null, 0L, Instant.now(), null);
        manager.registerPendingReview("rev-1", future, info);
        manager.resolveReview("rev-1", "CONTINUE");

        assertThat(manager.listPendingReviews(null)).isEmpty();
    }

    @Test
    void registerPendingReview_withMetadata_noopMetadataWhenNullInfo() {
        CompletableFuture<String> future = new CompletableFuture<>();
        manager.registerPendingReview("rev-1", future, null);

        assertThat(manager.hasPendingReview("rev-1")).isTrue();
        assertThat(manager.listPendingReviews(null)).isEmpty(); // no metadata stored
    }

    // ========================
    // resolveReview removes metadata
    // ========================

    @Test
    void resolveReview_removesMetadataAlongWithFuture() {
        CompletableFuture<String> future = new CompletableFuture<>();
        ConnectionManager.PendingReviewInfo info = new ConnectionManager.PendingReviewInfo(
                "rev-1", "run-abc", "Task", "", "AFTER_EXECUTION", null, 0L, Instant.now(), null);
        manager.registerPendingReview("rev-1", future, info);

        assertThat(manager.listPendingReviews(null)).hasSize(1);

        manager.resolveReview("rev-1", "CONTINUE");

        assertThat(manager.listPendingReviews(null)).isEmpty();
        assertThat(manager.hasPendingReview("rev-1")).isFalse();
    }

    @Test
    void resolveReview_unknownId_isIdempotentNoException() {
        // Should not throw
        manager.resolveReview("rev-unknown", "value");
    }
}
