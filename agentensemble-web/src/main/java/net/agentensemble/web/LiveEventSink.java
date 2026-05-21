package net.agentensemble.web;

import java.time.Instant;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;

/**
 * Sink for live wire-protocol events produced by {@link WebSocketStreamingListener}.
 *
 * <p>Decouples the listener from a particular delivery mechanism. In embedded mode the local
 * {@link ConnectionManager} fulfils this contract, broadcasting to in-process WebSocket sessions
 * and accumulating into the late-join snapshot. In publisher mode (see {@code agentensemble-web-hub})
 * a remote-shipping adapter implements this interface to wrap each event in a
 * {@link net.agentensemble.web.protocol.LiveEventEnvelope} and forward it to a
 * {@code LiveEventHub}.
 *
 * <h2>Method semantics</h2>
 * <ul>
 *   <li>{@link #accept(String)} fans out a JSON message to live consumers. The listener calls
 *       this for both persistent events (followed by {@link #appendToSnapshot(String)}) and
 *       ephemeral events such as token streams (skipping snapshot append).</li>
 *   <li>{@link #appendToSnapshot(String)} records a previously-broadcast message for late-join
 *       replay. Implementations may store the JSON for a configured retention window or no-op
 *       when storage is handled elsewhere (e.g. a publisher whose hub owns the snapshot).</li>
 *   <li>{@link #noteEnsembleStarted(String, Instant)} opens a new per-run snapshot bucket.
 *       Called once per {@code ensemble_started} event before the corresponding broadcast.</li>
 *   <li>{@link #clearIterationSnapshots()} discards in-flight iteration state at run boundaries.</li>
 *   <li>{@link #recordIterationStarted}/{@link #recordIterationCompleted} populate the per-task
 *       LLM iteration ring buffer used to hydrate browser conversation panels on late-join.</li>
 * </ul>
 *
 * <p>All methods are invoked from listener virtual threads and must be safe for concurrent calls.
 *
 * <p>Adapter implementations are free to no-op any method whose state is owned upstream.
 */
public interface LiveEventSink {

    /**
     * Fan out a JSON-encoded {@link net.agentensemble.web.protocol.ServerMessage} to live
     * consumers (WebSocket sessions, remote hub, SSE callbacks). Does not persist the message;
     * callers issue {@link #appendToSnapshot(String)} separately for events that should be
     * replayed on late-join.
     *
     * @param json the serialized message; must not be null
     */
    void accept(String json);

    /**
     * Persist a previously-broadcast message into the current run's late-join snapshot.
     * Implementations that do not own snapshot storage (e.g. publishers shipping to a remote
     * hub) may no-op.
     *
     * @param json the serialized message; must not be null
     */
    void appendToSnapshot(String json);

    /**
     * Open a new per-run snapshot bucket so that subsequent
     * {@link #appendToSnapshot(String)} calls accumulate into a fresh list.
     *
     * @param ensembleId the run's unique identifier
     * @param startedAt  the run's start instant
     */
    void noteEnsembleStarted(String ensembleId, Instant startedAt);

    /**
     * Discard any in-flight iteration buffer state. Called at run boundaries so a new run does
     * not inherit conversation state from the previous run.
     */
    void clearIterationSnapshots();

    /**
     * Record the {@code llm_iteration_started} half of an iteration snapshot pair for the given
     * task key. The matching {@link #recordIterationCompleted} call pairs and stores the entry
     * in the per-task ring buffer.
     *
     * @param key the task key ({@code agentRole + ":" + taskDescription})
     * @param msg the iteration-started message; must not be null
     */
    void recordIterationStarted(String key, LlmIterationStartedMessage msg);

    /**
     * Pair the pending iteration-started message for the given task key with the supplied
     * completed message and store it in the per-task ring buffer.
     *
     * @param key the task key ({@code agentRole + ":" + taskDescription})
     * @param msg the iteration-completed message; must not be null
     */
    void recordIterationCompleted(String key, LlmIterationCompletedMessage msg);
}
