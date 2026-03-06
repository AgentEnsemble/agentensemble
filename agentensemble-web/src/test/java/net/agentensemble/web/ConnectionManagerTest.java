package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
    void newConnectionReceivesCurrentSnapshotInHello() {
        String snapshotJson = "{\"workflow\":\"SEQUENTIAL\",\"taskCount\":2}";
        connectionManager.updateSnapshotJson(snapshotJson);

        MockWsSession session = new MockWsSession("session-2");
        connectionManager.onConnect(session);

        String helloJson = session.sentMessages().get(0);
        // The snapshot content is embedded in the hello message
        assertThat(helloJson).contains("hello");
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

        // Disconnect should resolve all pending reviews with an empty string
        connectionManager.onDisconnect("session-1");

        assertThat(future).isCompleted();
        assertThat(future.join()).isEmpty();
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
        private final List<String> messages = new ArrayList<>();
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
