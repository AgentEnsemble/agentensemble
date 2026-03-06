package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectionManager}.
 *
 * <p>All tests use {@link MockWsSession} stubs to avoid starting a real WebSocket server.
 */
class ConnectionManagerTest {

    private ConnectionManager connectionManager;
    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        connectionManager = new ConnectionManager(serializer);
    }

    @Test
    void newConnectionReceivesHelloMessage() {
        MockWsSession session = new MockWsSession("session-1");
        connectionManager.onConnect(session);

        assertThat(session.sentMessages()).hasSize(1);
        String helloJson = session.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
    }

    @Test
    void newConnectionReceivesEmptySnapshotInHelloWhenNoEventsYet() {
        MockWsSession session = new MockWsSession("session-2");
        connectionManager.onConnect(session);

        String helloJson = session.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        // No snapshotTrace field when snapshot is empty (NON_NULL omits it)
        assertThat(helloJson).doesNotContain("snapshotTrace");
    }

    @Test
    void lateJoinerReceivesSnapshotOfPastEvents() {
        // Simulate a run that has already broadcast some events
        connectionManager.noteEnsembleStarted("ens-abc", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-abc\"}");
        connectionManager.appendToSnapshot("{\"type\":\"task_started\",\"taskIndex\":1}");

        // Late-joining client
        MockWsSession lateSession = new MockWsSession("late-joiner");
        connectionManager.onConnect(lateSession);

        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("\"type\":\"hello\"");
        // Snapshot should contain the two past events as a JSON array
        assertThat(helloJson).contains("snapshotTrace");
        assertThat(helloJson).contains("ensemble_started");
        assertThat(helloJson).contains("task_started");
        // ensembleId and startedAt should be set from noteEnsembleStarted
        assertThat(helloJson).contains("\"ensembleId\":\"ens-abc\"");
    }

    @Test
    void noteEnsembleStarted_clearsOldSnapshotFromPreviousRun() {
        // Run 1: accumulate events
        connectionManager.noteEnsembleStarted("ens-run1", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run1\"}");
        connectionManager.appendToSnapshot("{\"type\":\"task_started\",\"taskIndex\":1}");

        // Run 2: new run clears old snapshot
        connectionManager.noteEnsembleStarted("ens-run2", Instant.now());
        connectionManager.appendToSnapshot("{\"type\":\"ensemble_started\",\"ensembleId\":\"ens-run2\"}");

        MockWsSession session = new MockWsSession("session-x");
        connectionManager.onConnect(session);
        String helloJson = session.sentMessages().get(0);

        // Only run-2 events should be present
        assertThat(helloJson).contains("ens-run2");
        assertThat(helloJson).doesNotContain("ens-run1");
    }

    @Test
    void snapshotGrowsIncrementallyAsEventsArrive() {
        connectionManager.noteEnsembleStarted("ens-123", Instant.now());

        // Connect a client before any events -- gets empty snapshot
        MockWsSession earlySession = new MockWsSession("early");
        connectionManager.onConnect(earlySession);
        assertThat(earlySession.sentMessages().get(0)).doesNotContain("snapshotTrace");

        // Broadcast some events
        String msg1 = "{\"type\":\"task_started\",\"taskIndex\":1}";
        String msg2 = "{\"type\":\"task_completed\",\"taskIndex\":1}";
        connectionManager.appendToSnapshot(msg1);
        connectionManager.appendToSnapshot(msg2);

        // Late-joining client gets both events in snapshot
        MockWsSession lateSession = new MockWsSession("late");
        connectionManager.onConnect(lateSession);
        String helloJson = lateSession.sentMessages().get(0);
        assertThat(helloJson).contains("task_started");
        assertThat(helloJson).contains("task_completed");
    }

    @Test
    void concurrentSnapshotAppends_doNotCorruptSnapshot() throws InterruptedException {
        connectionManager.noteEnsembleStarted("ens-concurrent", Instant.now());
        int threadCount = 10;
        int eventsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int thread = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        connectionManager.appendToSnapshot(
                                "{\"type\":\"task_started\",\"thread\":" + thread + ",\"i\":" + i + "}");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Connect after all concurrent appends; snapshot must contain all events
        MockWsSession session = new MockWsSession("after-concurrent");
        connectionManager.onConnect(session);
        String helloJson = session.sentMessages().get(0);
        // The snapshot JSON array must be well-formed (parseable) and contain all events
        assertThat(helloJson).contains("snapshotTrace");
        // Count occurrences of "task_started" -- should be threadCount * eventsPerThread
        int count = 0;
        int idx = 0;
        while ((idx = helloJson.indexOf("task_started", idx)) != -1) {
            count++;
            idx++;
        }
        assertThat(count).isEqualTo(threadCount * eventsPerThread);
    }

    @Test
    void broadcastSendsToAllConnectedSessions() {
        MockWsSession s1 = new MockWsSession("session-1");
        MockWsSession s2 = new MockWsSession("session-2");
        MockWsSession s3 = new MockWsSession("session-3");

        connectionManager.onConnect(s1);
        connectionManager.onConnect(s2);
        connectionManager.onConnect(s3);

        // Clear hello messages
        s1.clearMessages();
        s2.clearMessages();
        s3.clearMessages();

        connectionManager.broadcast("{\"type\":\"heartbeat\",\"serverTimeMs\":1000}");

        assertThat(s1.sentMessages()).hasSize(1);
        assertThat(s2.sentMessages()).hasSize(1);
        assertThat(s3.sentMessages()).hasSize(1);
        assertThat(s1.sentMessages().get(0)).contains("heartbeat");
    }

    @Test
    void broadcastSkipsClosedSessions() {
        MockWsSession s1 = new MockWsSession("session-1");
        MockWsSession s2 = new MockWsSession("session-2");

        connectionManager.onConnect(s1);
        connectionManager.onConnect(s2);

        s1.clearMessages();
        s2.clearMessages();

        s1.close(); // mark s1 as closed

        connectionManager.broadcast("{\"type\":\"heartbeat\",\"serverTimeMs\":2000}");

        assertThat(s1.sentMessages()).isEmpty(); // closed session gets nothing
        assertThat(s2.sentMessages()).hasSize(1);
    }

    @Test
    void sendToOneSessionDeliversOnlyToTarget() {
        MockWsSession s1 = new MockWsSession("session-1");
        MockWsSession s2 = new MockWsSession("session-2");

        connectionManager.onConnect(s1);
        connectionManager.onConnect(s2);

        s1.clearMessages();
        s2.clearMessages();

        connectionManager.send("session-1", "{\"type\":\"review_requested\",\"reviewId\":\"r1\"}");

        assertThat(s1.sentMessages()).hasSize(1);
        assertThat(s1.sentMessages().get(0)).contains("review_requested");
        assertThat(s2.sentMessages()).isEmpty();
    }

    @Test
    void sendToNonExistentSessionIsNoOp() {
        // Should not throw
        connectionManager.send("nonexistent-session", "{\"type\":\"pong\"}");
    }

    @Test
    void disconnectRemovesSessionFromBroadcastRecipients() {
        MockWsSession s1 = new MockWsSession("session-1");
        MockWsSession s2 = new MockWsSession("session-2");

        connectionManager.onConnect(s1);
        connectionManager.onConnect(s2);

        connectionManager.onDisconnect("session-1");

        s1.clearMessages();
        s2.clearMessages();

        connectionManager.broadcast("{\"type\":\"heartbeat\",\"serverTimeMs\":3000}");

        // s1 was disconnected, should not receive broadcast
        assertThat(s1.sentMessages()).isEmpty();
        assertThat(s2.sentMessages()).hasSize(1);
    }

    @Test
    void sessionCountReflectsConnectedSessions() {
        assertThat(connectionManager.sessionCount()).isEqualTo(0);

        MockWsSession s1 = new MockWsSession("session-1");
        MockWsSession s2 = new MockWsSession("session-2");

        connectionManager.onConnect(s1);
        assertThat(connectionManager.sessionCount()).isEqualTo(1);

        connectionManager.onConnect(s2);
        assertThat(connectionManager.sessionCount()).isEqualTo(2);

        connectionManager.onDisconnect("session-1");
        assertThat(connectionManager.sessionCount()).isEqualTo(1);
    }

    @Test
    void broadcastWithNoSessionsIsNoOp() {
        // Should not throw when there are no sessions
        connectionManager.broadcast("{\"type\":\"heartbeat\",\"serverTimeMs\":4000}");
    }

    @Test
    void multipleDisconnectsForSameSessionAreIdempotent() {
        MockWsSession s1 = new MockWsSession("session-1");
        connectionManager.onConnect(s1);
        connectionManager.onDisconnect("session-1");
        // Second disconnect should be a no-op, not throw
        connectionManager.onDisconnect("session-1");
        assertThat(connectionManager.sessionCount()).isEqualTo(0);
    }

    // ========================
    // Pending review handling
    // ========================

    @Test
    void reviewDecisionResolvesRegisteredFuture() {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        connectionManager.registerPendingReview("review-1", future);

        connectionManager.resolveReview("review-1", "CONTINUE_DECISION");

        assertThat(future).isCompleted();
        assertThat(future.join()).isEqualTo("CONTINUE_DECISION");
    }

    @Test
    void resolveForNonExistentReviewIsNoOp() {
        // Should not throw
        connectionManager.resolveReview("nonexistent-review", "CONTINUE");
    }

    @Test
    void reviewFutureRemovedAfterResolution() {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        connectionManager.registerPendingReview("review-2", future);
        connectionManager.resolveReview("review-2", "EXIT_EARLY_DECISION");

        // Resolving again should be a no-op (future is already removed)
        connectionManager.resolveReview("review-2", "CONTINUE_DECISION");

        // Future should have the first resolution
        assertThat(future.join()).isEqualTo("EXIT_EARLY_DECISION");
    }

    @Test
    void onDisconnect_withPendingReview_resolvesReviewWithEmptyString() {
        MockWsSession session = new MockWsSession("session-1");
        connectionManager.onConnect(session);

        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        connectionManager.registerPendingReview("review-3", future);
        assertThat(future.isDone()).isFalse();

        // Disconnect the only session -- reviews should be resolved because no sessions remain
        connectionManager.onDisconnect("session-1");

        assertThat(future).isCompleted();
        assertThat(future.join()).isEmpty();
    }

    @Test
    void onDisconnect_withPendingReview_andRemainingSession_doesNotResolveReview() {
        MockWsSession session1 = new MockWsSession("session-1");
        MockWsSession session2 = new MockWsSession("session-2");
        connectionManager.onConnect(session1);
        connectionManager.onConnect(session2);

        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        connectionManager.registerPendingReview("review-x", future);
        assertThat(future.isDone()).isFalse();

        // Disconnect session-1, but session-2 is still connected -- review must stay active
        connectionManager.onDisconnect("session-1");

        assertThat(future.isDone()).isFalse();
    }

    @Test
    void send_toClosedSession_dropsMessage() {
        MockWsSession session = new MockWsSession("session-1");
        connectionManager.onConnect(session);
        session.clearMessages();

        // Close the session but do NOT disconnect it from the manager
        session.close();

        // send() finds the session but isOpen() is false -- message should be dropped
        connectionManager.send("session-1", "{\"type\":\"pong\"}");

        assertThat(session.sentMessages()).isEmpty();
    }

    // ========================
    // Inner stub
    // ========================

    static final class MockWsSession implements WsSession {
        private final String id;
        // CopyOnWriteArrayList ensures send() is safe when called concurrently from
        // multiple virtual threads (e.g. in the parallel workflow concurrent test).
        private final java.util.concurrent.CopyOnWriteArrayList<String> messages =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private boolean open = true;

        MockWsSession(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void send(String message) {
            if (open) {
                messages.add(message);
            }
        }

        List<String> sentMessages() {
            return List.copyOf(messages);
        }

        void clearMessages() {
            messages.clear();
        }

        void close() {
            this.open = false;
        }
    }
}
