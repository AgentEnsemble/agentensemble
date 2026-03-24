package net.agentensemble.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *
 * <h2>Multi-run snapshot storage</h2>
 * <p>Maintains a per-run snapshot: one inner list of messages per ensemble run. When a new run
 * starts, a new inner list is opened while all prior inner lists are retained (up to
 * {@code maxRetainedRuns}). The flattened concatenation of all inner lists is sent to
 * late-joining browsers in the {@code hello} message so they can replay the full history.
 * When the number of retained runs exceeds the cap, the oldest run's inner list is evicted.
 */
class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    /** Default maximum number of completed runs to retain in the snapshot. */
    static final int DEFAULT_MAX_RETAINED_RUNS = 10;

    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingReviews = new ConcurrentHashMap<>();
    private final MessageSerializer serializer;

    /**
     * Maximum number of runs to retain in the snapshot. When a new run starts and the total
     * would exceed this cap, the oldest run's messages are evicted.
     */
    private final int maxRetainedRuns;

    /**
     * Per-run snapshot storage. Each inner list holds all messages broadcast during one run.
     * The latest run's list is last. Protected by {@code synchronized(runSnapshots)} for
     * structural modifications (add/remove of inner lists). Each inner list is instantiated
     * as a {@code CopyOnWriteArrayList} (see {@link #noteEnsembleStarted}) so that concurrent
     * appends within a single run are thread-safe without holding the outer lock.
     */
    private final List<List<String>> runSnapshots = new ArrayList<>();

    /**
     * The current run's message list. Updated atomically (volatile) when a new run starts.
     * Always instantiated as a thread-safe list (see {@link #noteEnsembleStarted}).
     * Null until the first {@link #noteEnsembleStarted} call.
     */
    private volatile List<String> currentRunMessages = null;

    /** Ensemble ID from the most recent {@code ensemble_started} message, for hello. */
    private volatile String currentEnsembleId = null;

    /** Ensemble start time from the most recent {@code ensemble_started} message, for hello. */
    private volatile Instant ensembleStartedAt = null;

    /**
     * Create a {@code ConnectionManager} with the default run retention cap of
     * {@value #DEFAULT_MAX_RETAINED_RUNS}.
     *
     * @param serializer the message serializer; must not be null
     */
    ConnectionManager(MessageSerializer serializer) {
        this(serializer, DEFAULT_MAX_RETAINED_RUNS);
    }

    /**
     * Create a {@code ConnectionManager} with an explicit run retention cap.
     *
     * @param serializer       the message serializer; must not be null
     * @param maxRetainedRuns  maximum number of completed runs to retain in the late-join snapshot;
     *                         must be &ge; 1
     * @throws NullPointerException     when {@code serializer} is null
     * @throws IllegalArgumentException when {@code maxRetainedRuns} is less than 1
     */
    ConnectionManager(MessageSerializer serializer, int maxRetainedRuns) {
        if (serializer == null) {
            throw new NullPointerException("serializer must not be null");
        }
        if (maxRetainedRuns < 1) {
            throw new IllegalArgumentException("maxRetainedRuns must be >= 1; got: " + maxRetainedRuns);
        }
        this.serializer = serializer;
        this.maxRetainedRuns = maxRetainedRuns;
    }

    /**
     * Called when a new WebSocket client connects. Adds the session and immediately sends a
     * {@code hello} message containing the current partial execution snapshot (if any), so
     * that late-joining browsers can reconstruct the in-progress display without waiting for
     * the next live event.
     *
     * <p>The snapshot is a JSON array of all messages broadcast across all retained runs
     * (flattened in chronological order). The browser-side {@code liveReducer} replays this
     * array to restore state, including building the {@code completedRuns} list for multi-run
     * timelines.
     *
     * @param session the newly connected session
     */
    void onConnect(WsSession session) {
        sessions.put(session.id(), session);
        if (log.isDebugEnabled()) {
            log.debug("WebSocket client connected: {}", session.id());
        }

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
     * Appends a JSON message to the current run's snapshot log. Called by
     * {@link WebSocketStreamingListener} after each broadcast so that late-joining
     * clients receive all past events in the {@code hello} message.
     *
     * <p>Thread-safe: the current run's {@link CopyOnWriteArrayList#add} is atomic.
     * If no run has started yet (i.e. {@link #noteEnsembleStarted} has not been called),
     * the message is silently dropped -- pre-run messages carry no meaning for late-join
     * replay and must not be stored.
     *
     * @param messageJson the serialized JSON message that was just broadcast; must not be null
     */
    void appendToSnapshot(String messageJson) {
        List<String> current = currentRunMessages;
        if (current == null) {
            // noteEnsembleStarted has not been called yet; silently drop the message.
            return;
        }
        current.add(messageJson);
    }

    /**
     * Records ensemble metadata for the {@code hello} message sent to late-joining clients
     * and opens a new per-run snapshot inner list.
     *
     * <p>Unlike the previous single-run implementation, this method does <em>not</em> clear
     * the existing snapshot. Instead it opens a new inner list for the new run while
     * retaining all prior runs' messages. When the number of retained runs exceeds
     * {@link #maxRetainedRuns}, the oldest run's inner list is evicted.
     *
     * @param ensembleId the UUID identifying this run
     * @param startedAt  when this run began
     */
    void noteEnsembleStarted(String ensembleId, Instant startedAt) {
        this.currentEnsembleId = ensembleId;
        this.ensembleStartedAt = startedAt;

        CopyOnWriteArrayList<String> newRunList = new CopyOnWriteArrayList<>();
        synchronized (runSnapshots) {
            runSnapshots.add(newRunList);
            // Evict the oldest run when we exceed the cap. The new run's list was just added,
            // so if size > cap the first element is the oldest run to evict.
            while (runSnapshots.size() > maxRetainedRuns) {
                runSnapshots.remove(0);
            }
        }
        // Volatile write: all threads that subsequently call appendToSnapshot() will see
        // the new current list.
        this.currentRunMessages = newRunList;
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
        } else {
            log.debug(
                    "resolveReview called for unknown reviewId '{}'; ignoring (likely a late decision after timeout)",
                    reviewId);
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
     * Builds a {@link JsonNode} JSON array from all retained run snapshots, flattened in
     * chronological order (oldest run first, newest run last). Returns {@code null} when no
     * messages have been recorded yet (empty snapshot).
     *
     * <p>Acquires {@code runSnapshots} lock only to take a stable copy of the outer list;
     * each inner list's iterator provides a consistent snapshot without external locking.
     */
    private JsonNode buildSnapshotNode() {
        List<List<String>> runsCopy;
        synchronized (runSnapshots) {
            if (runSnapshots.isEmpty()) {
                return null;
            }
            runsCopy = new ArrayList<>(runSnapshots);
        }

        // Flatten: collect all messages from all runs in order
        List<String> all = new ArrayList<>();
        for (List<String> runList : runsCopy) {
            all.addAll(runList);
        }

        if (all.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(all.get(i));
        }
        sb.append(']');
        return serializer.toJsonNode(sb.toString());
    }
}
