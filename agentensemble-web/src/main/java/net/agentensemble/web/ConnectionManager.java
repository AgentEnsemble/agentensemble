package net.agentensemble.web;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile String currentSnapshotJson = null;

    ConnectionManager(MessageSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Called when a new WebSocket client connects. Adds the session and immediately sends a
     * {@code hello} message containing the current execution snapshot (if any).
     *
     * @param session the newly connected session
     */
    void onConnect(WsSession session) {
        sessions.put(session.id(), session);
        log.debug("WebSocket client connected: {}", session.id());

        // Send hello with current snapshot for late-joining browsers
        HelloMessage hello = new HelloMessage(null, null, null);
        String helloJson = serializer.toJson(hello);
        session.send(helloJson);
    }

    /**
     * Called when a WebSocket client disconnects. Removes the session and resolves any pending
     * review futures with an empty string (the WebReviewHandler interprets this as a timeout
     * action).
     *
     * @param sessionId the ID of the disconnected session
     */
    void onDisconnect(String sessionId) {
        sessions.remove(sessionId);
        log.debug("WebSocket client disconnected: {}", sessionId);

        // Resolve any pending reviews so that blocked JVM threads are not stuck indefinitely.
        // WebReviewHandler interprets an empty-string resolution as a disconnect signal and
        // applies the configured onTimeout action.
        if (!pendingReviews.isEmpty()) {
            pendingReviews.forEach((reviewId, future) -> {
                log.debug("Resolving pending review {} due to client disconnect", reviewId);
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
     * Updates the snapshot JSON stored for late-joining clients. Called by
     * {@link WebSocketStreamingListener} after each significant event.
     *
     * @param snapshotJson the current partial execution trace as a JSON string; may be null
     */
    void updateSnapshotJson(String snapshotJson) {
        this.currentSnapshotJson = snapshotJson;
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
}
