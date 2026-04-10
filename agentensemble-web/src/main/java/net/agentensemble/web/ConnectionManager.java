package net.agentensemble.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.HelloMessage;
import net.agentensemble.web.protocol.IterationSnapshot;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
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

    /** Default maximum number of LLM iteration snapshots retained per task. */
    static final int DEFAULT_MAX_SNAPSHOT_ITERATIONS = 5;

    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingReviews = new ConcurrentHashMap<>();

    /**
     * Metadata for pending reviews, keyed by reviewId.
     * Used by {@link #listPendingReviews(String)} to return review details to REST callers.
     */
    private final ConcurrentHashMap<String, PendingReviewInfo> pendingReviewMetadata = new ConcurrentHashMap<>();

    private final MessageSerializer serializer;

    /**
     * Optional subscription manager for per-session event filtering.
     * When null, all events are delivered to all sessions (default / backwards-compatible behaviour).
     */
    private volatile SubscriptionManager subscriptionManager;

    /**
     * Callbacks registered by SSE clients. Each callback receives a copy of every JSON string
     * broadcast to WebSocket sessions, allowing SSE handlers to stream live events.
     * Keyed by an opaque callback ID assigned at registration time.
     */
    private final ConcurrentHashMap<String, Consumer<String>> broadcastCallbacks = new ConcurrentHashMap<>();

    /**
     * Maximum number of runs to retain in the snapshot. When a new run starts and the total
     * would exceed this cap, the oldest run's messages are evicted.
     */
    private final int maxRetainedRuns;

    /**
     * Maximum number of LLM iteration snapshots to retain per task in the ring buffer.
     * When an iteration completes and the buffer for that task exceeds this cap, the oldest
     * entry is evicted. Configured via {@link WebDashboard.Builder#maxSnapshotIterations(int)}.
     */
    private final int maxSnapshotIterations;

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

    /**
     * Pending iteration-started messages, keyed by {@code agentRole + ":" + taskDescription}.
     * When {@link #recordIterationCompleted} is called, the pending entry is removed and
     * paired with the completed message to form an {@link IterationSnapshot}.
     */
    private final ConcurrentHashMap<String, LlmIterationStartedMessage> pendingIterationStarts =
            new ConcurrentHashMap<>();

    /**
     * Per-task ring buffer of completed {@link IterationSnapshot} pairs.
     * Keyed by {@code agentRole + \":\" + taskDescription}. Each deque is capped at
     * {@link #maxSnapshotIterations} entries; when exceeded, the oldest entry is evicted.
     * Backed by a {@link ConcurrentHashMap}; structural modifications are performed via
     * concurrent map operations rather than {@code synchronized(iterationSnapshots)}.
     */
    private final ConcurrentHashMap<String, Deque<IterationSnapshot>> iterationSnapshots = new ConcurrentHashMap<>();

    /** Ensemble ID from the most recent {@code ensemble_started} message, for hello. */
    private volatile String currentEnsembleId = null;

    /** Ensemble start time from the most recent {@code ensemble_started} message, for hello. */
    private volatile Instant ensembleStartedAt = null;

    /**
     * Create a {@code ConnectionManager} with the default run retention cap of
     * {@value #DEFAULT_MAX_RETAINED_RUNS} and iteration snapshot cap of
     * {@value #DEFAULT_MAX_SNAPSHOT_ITERATIONS}.
     *
     * @param serializer the message serializer; must not be null
     */
    ConnectionManager(MessageSerializer serializer) {
        this(serializer, DEFAULT_MAX_RETAINED_RUNS, DEFAULT_MAX_SNAPSHOT_ITERATIONS);
    }

    /**
     * Create a {@code ConnectionManager} with an explicit run retention cap and the default
     * iteration snapshot cap.
     *
     * @param serializer       the message serializer; must not be null
     * @param maxRetainedRuns  maximum number of completed runs to retain in the late-join snapshot;
     *                         must be &ge; 1
     * @throws NullPointerException     when {@code serializer} is null
     * @throws IllegalArgumentException when {@code maxRetainedRuns} is less than 1
     */
    ConnectionManager(MessageSerializer serializer, int maxRetainedRuns) {
        this(serializer, maxRetainedRuns, DEFAULT_MAX_SNAPSHOT_ITERATIONS);
    }

    /**
     * Create a {@code ConnectionManager} with explicit run retention and iteration snapshot caps.
     *
     * @param serializer              the message serializer; must not be null
     * @param maxRetainedRuns         maximum number of completed runs to retain in the late-join
     *                                snapshot; must be &ge; 1
     * @param maxSnapshotIterations   maximum number of LLM iteration snapshots to retain per task;
     *                                must be &ge; 0 (0 disables iteration snapshots)
     * @throws NullPointerException     when {@code serializer} is null
     * @throws IllegalArgumentException when {@code maxRetainedRuns} is less than 1 or
     *                                  {@code maxSnapshotIterations} is negative
     */
    ConnectionManager(MessageSerializer serializer, int maxRetainedRuns, int maxSnapshotIterations) {
        if (serializer == null) {
            throw new NullPointerException("serializer must not be null");
        }
        if (maxRetainedRuns < 1) {
            throw new IllegalArgumentException("maxRetainedRuns must be >= 1; got: " + maxRetainedRuns);
        }
        if (maxSnapshotIterations < 0) {
            throw new IllegalArgumentException("maxSnapshotIterations must be >= 0; got: " + maxSnapshotIterations);
        }
        this.serializer = serializer;
        this.maxRetainedRuns = maxRetainedRuns;
        this.maxSnapshotIterations = maxSnapshotIterations;
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
        List<IterationSnapshot> iterations = getRecentIterations();
        HelloMessage hello = new HelloMessage(currentEnsembleId, ensembleStartedAt, snapshotNode, null, iterations);
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
        // Clean up any subscription registered for this session
        SubscriptionManager sm = subscriptionManager;
        if (sm != null) {
            sm.unsubscribe(sessionId);
        }
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
     * Broadcasts a JSON message to all currently connected sessions, applying per-session
     * subscription filtering when a {@link SubscriptionManager} is configured.
     *
     * <p>System messages (heartbeat, hello) and sessions without a subscription receive all
     * events (backwards compatible). Closed sessions are skipped.
     *
     * <p>Also notifies any registered SSE broadcast callbacks so that live SSE streams
     * receive the same events as WebSocket sessions.
     *
     * @param json the JSON text to broadcast; must not be null
     */
    void broadcast(String json) {
        SubscriptionManager sm = subscriptionManager;
        if (sm == null) {
            // No subscription filtering: deliver to all open sessions (legacy behaviour)
            sessions.forEach((id, session) -> {
                if (session.isOpen()) {
                    session.send(json);
                } else {
                    log.debug("Skipping closed session {} during broadcast", id);
                }
            });
        } else {
            // Subscription-aware delivery
            String messageType = extractMessageType(json);
            sessions.forEach((id, session) -> {
                if (!session.isOpen()) {
                    log.debug("Skipping closed session {} during broadcast", id);
                    return;
                }
                if (sm.shouldDeliver(id, messageType, json)) {
                    session.send(json);
                }
            });
        }

        // Notify SSE callbacks (not subscription-filtered -- SSE clients filter themselves)
        if (!broadcastCallbacks.isEmpty()) {
            broadcastCallbacks.forEach((id, callback) -> {
                try {
                    callback.accept(json);
                } catch (Exception e) {
                    log.debug("SSE broadcast callback {} threw; it may have disconnected: {}", id, e.getMessage());
                }
            });
        }
    }

    /**
     * Sets the subscription manager used to filter per-session event delivery.
     * When {@code null}, all events are delivered to all sessions (default).
     *
     * @param manager the subscription manager; may be null to disable filtering
     */
    void setSubscriptionManager(SubscriptionManager manager) {
        this.subscriptionManager = manager;
    }

    /**
     * Registers a callback that receives a copy of every JSON string broadcast to WebSocket
     * sessions. Used by {@link SseHandler} to stream live events to SSE clients.
     *
     * @param callbackId a unique identifier for this callback (used to unregister it)
     * @param callback   the callback; must not be null
     */
    void registerBroadcastCallback(String callbackId, Consumer<String> callback) {
        broadcastCallbacks.put(callbackId, callback);
    }

    /**
     * Removes a previously registered broadcast callback.
     *
     * @param callbackId the identifier used when the callback was registered
     */
    void unregisterBroadcastCallback(String callbackId) {
        broadcastCallbacks.remove(callbackId);
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

    // ========================
    // Iteration snapshot ring buffer
    // ========================

    /**
     * Records an {@link LlmIterationStartedMessage} as a pending iteration for the given
     * task key. The message is stored until the corresponding
     * {@link #recordIterationCompleted} call arrives.
     *
     * <p>If iteration snapshots are disabled ({@code maxSnapshotIterations == 0}), this
     * method is a no-op.
     *
     * @param key the task key ({@code agentRole + ":" + taskDescription})
     * @param msg the iteration-started message
     */
    void recordIterationStarted(String key, LlmIterationStartedMessage msg) {
        if (maxSnapshotIterations == 0) {
            return;
        }
        pendingIterationStarts.put(key, msg);
    }

    /**
     * Pairs the pending iteration-started message for the given task key with the provided
     * completed message to form an {@link IterationSnapshot}, then adds it to the per-task
     * ring buffer. If the buffer exceeds {@link #maxSnapshotIterations}, the oldest entry
     * is evicted.
     *
     * <p>If no pending started message exists for the key (e.g. the listener missed the
     * start event, or snapshots are disabled), this method is a no-op.
     *
     * @param key the task key ({@code agentRole + ":" + taskDescription})
     * @param msg the iteration-completed message
     */
    void recordIterationCompleted(String key, LlmIterationCompletedMessage msg) {
        if (maxSnapshotIterations == 0) {
            return;
        }
        LlmIterationStartedMessage started = pendingIterationStarts.remove(key);
        if (started == null) {
            return;
        }
        IterationSnapshot snapshot = new IterationSnapshot(started, msg);
        iterationSnapshots.compute(key, (k, deque) -> {
            if (deque == null) {
                deque = new ArrayDeque<>();
            }
            deque.addLast(snapshot);
            while (deque.size() > maxSnapshotIterations) {
                deque.removeFirst();
            }
            return deque;
        });
    }

    /**
     * Clears all iteration snapshot buffers and pending starts. Called when a new ensemble
     * run starts so that stale iteration data from a previous run is not included in the
     * hello message for the new run.
     */
    void clearIterationSnapshots() {
        pendingIterationStarts.clear();
        iterationSnapshots.clear();
    }

    /**
     * Returns a flattened list of all recent {@link IterationSnapshot}s across all tasks.
     * The ordering across different task keys is not guaranteed (ConcurrentHashMap iteration
     * order). Returns {@code null} when no snapshots have been recorded (to allow
     * {@code @JsonInclude(NON_NULL)} to omit the field from the hello message).
     *
     * @return the recent iteration snapshots, or {@code null} if empty
     */
    List<IterationSnapshot> getRecentIterations() {
        if (iterationSnapshots.isEmpty()) {
            // Also check pending starts: if there is a pending start without a completed
            // message, include it as an incomplete snapshot so the client sees the
            // "thinking" state.
            if (pendingIterationStarts.isEmpty()) {
                return null;
            }
        }

        List<IterationSnapshot> result = new ArrayList<>();

        // Add completed snapshots from all tasks.
        // Use toArray() to avoid the ArrayDeque's fail-fast iterator, which can throw
        // ConcurrentModificationException if recordIterationCompleted() mutates the deque
        // concurrently (e.g. during a WebSocket onConnect() hello snapshot build).
        for (Map.Entry<String, Deque<IterationSnapshot>> entry : iterationSnapshots.entrySet()) {
            for (Object o : entry.getValue().toArray()) {
                result.add((IterationSnapshot) o);
            }
        }

        // Add any pending (in-progress) iterations as incomplete snapshots
        for (Map.Entry<String, LlmIterationStartedMessage> entry : pendingIterationStarts.entrySet()) {
            result.add(new IterationSnapshot(entry.getValue(), null));
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Returns the configured maximum number of iteration snapshots per task.
     * Package-private for testing.
     *
     * @return the configured cap; always &ge; 0
     */
    int getMaxSnapshotIterations() {
        return maxSnapshotIterations;
    }

    // ========================
    // Review coordination
    // ========================

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
     * Registers a pending review future along with its metadata for REST-based review discovery.
     *
     * <p>Delegates to {@link #registerPendingReview(String, CompletableFuture)} so that
     * subclasses overriding the 2-argument form (e.g. test doubles that auto-resolve futures)
     * continue to work correctly. The metadata is stored separately for the review listing
     * endpoint.
     *
     * @param reviewId the review correlation ID
     * @param future   the future to complete when a decision arrives or a session disconnects
     * @param info     metadata about the review gate; may be null to omit from listing
     */
    void registerPendingReview(String reviewId, CompletableFuture<String> future, PendingReviewInfo info) {
        // Delegate to the 2-arg overload so subclasses that override it (e.g. test helpers)
        // still receive the registration and can auto-resolve the future.
        registerPendingReview(reviewId, future);
        if (info != null) {
            pendingReviewMetadata.put(reviewId, info);
        }
    }

    /**
     * Resolves a pending review by completing its future with the given value. The future and
     * its metadata are removed from the registries to prevent double-resolution.
     *
     * @param reviewId the review correlation ID
     * @param value    the value to complete the future with
     */
    void resolveReview(String reviewId, String value) {
        CompletableFuture<String> future = pendingReviews.remove(reviewId);
        pendingReviewMetadata.remove(reviewId);
        if (future != null) {
            future.complete(value);
        } else {
            log.debug(
                    "resolveReview called for unknown reviewId '{}'; ignoring (likely a late decision after timeout)",
                    reviewId);
        }
    }

    /**
     * Returns true if the given reviewId has a pending (unresolved) review future.
     *
     * @param reviewId the review correlation ID
     * @return true if the review is still pending
     */
    boolean hasPendingReview(String reviewId) {
        return pendingReviews.containsKey(reviewId);
    }

    /**
     * Returns a snapshot of pending review metadata, optionally filtered by run ID.
     *
     * <p>Used by the {@code GET /api/reviews} REST endpoint to allow REST-only clients
     * to discover pending review gates without a WebSocket connection.
     *
     * @param runIdFilter if non-null, only reviews matching this run ID are returned;
     *                    null returns all pending reviews
     * @return an immutable list of pending review info records
     */
    List<PendingReviewInfo> listPendingReviews(String runIdFilter) {
        List<PendingReviewInfo> result = new ArrayList<>();
        pendingReviewMetadata.forEach((reviewId, info) -> {
            // Only include reviews that still have an active future (not yet resolved)
            if (!pendingReviews.containsKey(reviewId)) {
                return;
            }
            if (runIdFilter != null && !runIdFilter.equals(info.runId())) {
                return;
            }
            result.add(info);
        });
        return List.copyOf(result);
    }

    /**
     * Returns the number of currently registered sessions (including sessions that may have
     * closed since their last keepalive).
     */
    int sessionCount() {
        return sessions.size();
    }

    /**
     * Immutable metadata record for a pending review gate.
     *
     * @param reviewId        the review correlation ID
     * @param runId           the API run ID that owns this review, or null for non-API runs
     * @param taskDescription the description of the task under review
     * @param taskOutput      the task's output to be reviewed
     * @param timing          when the gate fires (e.g. {@code "AFTER_EXECUTION"})
     * @param prompt          optional human-readable review prompt
     * @param timeoutMs       how long (ms) to wait for a decision; 0 means indefinite
     * @param createdAt       when the review was opened
     * @param expiresAt       when the review will time out, or null if no timeout
     */
    record PendingReviewInfo(
            String reviewId,
            String runId,
            String taskDescription,
            String taskOutput,
            String timing,
            String prompt,
            long timeoutMs,
            Instant createdAt,
            Instant expiresAt) {}

    // ========================
    // Private helpers
    // ========================

    /**
     * Extracts the {@code "type"} field value from a JSON string without full parsing.
     * Returns an empty string if the field is absent. Used in the subscription-aware
     * {@link #broadcast(String)} to look up the message type efficiently.
     *
     * <p>Only extracts the first occurrence of {@code "type":"..."}.
     */
    private static String extractMessageType(String json) {
        if (json == null) return "";
        int idx = json.indexOf("\"type\":\"");
        if (idx < 0) return "";
        int start = idx + 8;
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }

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
