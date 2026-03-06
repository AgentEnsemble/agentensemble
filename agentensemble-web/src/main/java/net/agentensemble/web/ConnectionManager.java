package net.agentensemble.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.agentensemble.web.protocol.HelloMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks connected WebSocket sessions and provides broadcast and targeted-send operations.
 *
 * <p>Thread-safe: all operations use a {@link ConcurrentHashMap} for the session registry.
 * Broadcast iterates the map concurrently; sessions closed between iteration start and the
 * actual send are detected via {@link WsSession#isOpen()} and skipped.
 *
 * <p>Also manages pending review futures: when a {@link WebReviewHandler} registers a review,
 * the future is stored here so that {@link #resolveReview} can complete it when the browser
 * sends a decision, and so that disconnection can cancel pending reviews.
 */
class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final ConcurrentHashMap<String, WsSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingReviews = new ConcurrentHashMap<>();
    private final MessageSerializer serializer;

    /**
     * Ordered log of all messages broadcast during the current run, used to send a
     * late-join snapshot via the {@code hello} message. Thread-safe: append via
     * CopyOnWriteArrayList; snapshot is obtained by iterating the list at connect time.
     */
    private final CopyOnWriteArrayList<String> snapshotMessages = new CopyOnWriteArrayList<>();

    /** Ensemble ID from the most recent {@code ensemble_started} message, for hello. */
    private volatile String currentEnsembleId = null;

    /** Ensemble start time from the most recent {@code ensemble_started} message, for hello. */
    private volatile Instant ensembleStartedAt = null;

    ConnectionManager(MessageSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Called when a new WebSocket client connects. Adds the session and immediately sends a
     * {@code hello} message containing the current partial execution snapshot (if any), so
     * that late-joining browsers can reconstruct the in-progress display without waiting for
     * the next live event.
     *
     * <p>The snapshot is a JSON array of all messages broadcast since the run started. The
     * browser-side {@code liveReducer} can replay this array to restore state.
     *
     * @param session the newly connected session
     */
    void onConnect(WsSession session) {
        sessions.put(session.id(), session);
        log.debug("WebSocket client connected: {}", session.id());

        // Build a snapshot JSON array from all messages broadcast so far. Taking a
        // copy of the CopyOnWriteArrayList is atomic and safe without external locking.
        JsonNode snapshotNode = buildSnapshotNode();
        HelloMessage hello = new HelloMessage(currentEnsembleId, ensembleStartedAt, snapshotNode);
        String helloJson = serializer.toJson(hello);
        session.send(helloJson);
    }

    /**
     * Called when a WebSocket client disconnects. Removes the session and, if this was the
     * <em>last</em> connected session, resolves any pending review futures with an empty string
     * so that blocked JVM threads are not stuck indefinitely.
     *
     * <p>Reviews are only canceled when <em>all</em> browsers disconnect: while at least one
     * browser remains connected it can still deliver a decision, so the review stays active.
     *
     * @param sessionId the ID of the disconnected session
     */
    void onDisconnect(String sessionId) {
        sessions.remove(sessionId);
        log.debug("WebSocket client disconnected: {}", sessionId);

        // Only cancel pending reviews when the last browser disconnects.
        // While other clients remain connected they can still deliver a review decision.
        if (sessions.isEmpty() && !pendingReviews.isEmpty()) {
            pendingReviews.forEach((reviewId, future) -> {
                log.debug("Resolving pending review {} because all clients disconnected", reviewId);
                future.complete("");
            });
            pendingReviews.clear();
        }
    }

    /**
     * Broadcasts a JSON message to all currently connected sessions. Closed sessions are skipped
     * and logged at DEBUG level.
     *
     * @param json the JSON text to broadcast; must not be null
     */
    void broadcast(String json) {
        sessions.forEach((id, session) -> {
            if (session.isOpen()) {
                session.send(json);
            } else {
                log.debug("Skipping closed session {} during broadcast", id);
            }
        });
    }

    /**
     * Sends a JSON message to a single session identified by its ID. If the session is not
     * found or is closed, the message is silently dropped.
     *
     * @param sessionId the target session ID
     * @param json      the JSON text to send; must not be null
     */
    void send(String sessionId, String json) {
        WsSession session = sessions.get(sessionId);
        if (session == null) {
            log.debug("send() called for unknown session {}, ignoring", sessionId);
            return;
        }
        if (session.isOpen()) {
            session.send(json);
        }
    }

    /**
     * Appends a JSON message to the late-join snapshot log. Called by
     * {@link WebSocketStreamingListener} after each broadcast so that late-joining
     * clients receive all past events in the {@code hello} message.
     *
     * <p>Thread-safe: {@link CopyOnWriteArrayList#add} is atomic.
     *
     * @param messageJson the serialized JSON message that was just broadcast; must not be null
     */
    void appendToSnapshot(String messageJson) {
        snapshotMessages.add(messageJson);
    }

    /**
     * Records ensemble metadata for the {@code hello} message sent to late-joining clients.
     * Called by {@link net.agentensemble.web.WebDashboard} when it receives the
     * {@code onEnsembleStarted} lifecycle hook, immediately before the first task begins.
     *
     * <p>Also clears any snapshot accumulated from a previous run so that late-joining
     * clients see only the current run's events.
     *
     * @param ensembleId the UUID identifying this run
     * @param startedAt  when this run began
     */
    void noteEnsembleStarted(String ensembleId, Instant startedAt) {
        snapshotMessages.clear();
        this.currentEnsembleId = ensembleId;
        this.ensembleStartedAt = startedAt;
    }

    /**
     * Registers a pending review future that will be resolved when the browser sends a
     * {@code review_decision} message with the matching {@code reviewId}.
     *
     * @param reviewId the review correlation ID
     * @param future   the future to complete when a decision arrives or a session disconnects
     */
    void registerPendingReview(String reviewId, CompletableFuture<String> future) {
        pendingReviews.put(reviewId, future);
    }

    /**
     * Resolves a pending review by completing its future with the given value. The future is
     * removed from the registry to prevent double-resolution.
     *
     * @param reviewId the review correlation ID
     * @param value    the value to complete the future with
     */
    void resolveReview(String reviewId, String value) {
        CompletableFuture<String> future = pendingReviews.remove(reviewId);
        if (future != null) {
            future.complete(value);
        }
    }

    /**
     * Returns the number of currently registered sessions (including sessions that may have
     * closed since their last keepalive).
     */
    int sessionCount() {
        return sessions.size();
    }

    // ========================
    // Private helpers
    // ========================

    /**
     * Builds a {@link JsonNode} JSON array from the current snapshot message log.
     * Returns {@code null} when no messages have been recorded yet (empty snapshot).
     *
     * <p>The CopyOnWriteArrayList iterator provides a consistent snapshot of the list
     * state at the time of this call, safe under concurrent appends.
     */
    private JsonNode buildSnapshotNode() {
        List<String> snapshot = new ArrayList<>(snapshotMessages);
        if (snapshot.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < snapshot.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(snapshot.get(i));
        }
        sb.append(']');
        return serializer.toJsonNode(sb.toString());
    }
}
