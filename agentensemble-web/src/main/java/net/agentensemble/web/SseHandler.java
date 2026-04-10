package net.agentensemble.web;

import io.javalin.http.sse.SseClient;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.RunState.TaskOutputSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code GET /api/runs/{runId}/events} Server-Sent Event (SSE) streams.
 *
 * <p>For <em>completed</em> runs, the stored {@link RunState.TaskOutputSnapshot} records are
 * serialised as simplified SSE events and the connection is closed immediately.
 *
 * <p>For <em>in-progress</em> runs, the handler registers a broadcast callback in the
 * {@link ConnectionManager} and streams live events until the run completes or the client
 * disconnects. Event type filtering is supported via the {@code ?events=type1,type2} query
 * parameter.
 *
 * <p>Reconnection: the {@code ?from=N} query parameter (0-based index into the stored
 * task-output list) is supported for completed-run replay to allow clients to resume from a
 * specific point.
 *
 * <p>Thread safety: one instance is created per dashboard and is stateless; all per-request
 * state lives in local variables and the lambda closures.
 */
final class SseHandler {

    private static final Logger log = LoggerFactory.getLogger(SseHandler.class);

    private final RunManager runManager;
    private final ConnectionManager connectionManager;

    /**
     * Creates a handler backed by the given run manager and connection manager.
     *
     * @param runManager        the run state store; must not be null
     * @param connectionManager the broadcast callback registry; must not be null
     */
    SseHandler(RunManager runManager, ConnectionManager connectionManager) {
        this.runManager = runManager;
        this.connectionManager = connectionManager;
    }

    /**
     * Handles an incoming SSE connection for the given run ID.
     *
     * <p>Called from the Javalin SSE route handler: {@code config.routes.sse(path, client -> handler.handle(client, runId))}.
     * The SSE connection remains open until this method returns.
     *
     * @param client the Javalin SSE client for writing events
     * @param runId  the run whose events to stream
     */
    void handle(SseClient client, String runId) {
        Optional<RunState> found = runManager.getRun(runId);
        if (found.isEmpty()) {
            client.sendEvent("error", "{\"error\":\"RUN_NOT_FOUND\",\"message\":\"No run with ID " + runId + "\"}");
            client.close();
            return;
        }

        RunState state = found.get();

        // Parse optional event type filter from query parameter (e.g. ?events=task_started,run_result)
        String eventsParam = client.ctx().queryParam("events");
        Set<String> eventFilter = parseEventFilter(eventsParam);

        // Parse optional reconnect offset for completed-run replay
        int fromIndex = parseFromIndex(client.ctx().queryParam("from"));

        RunState.Status status = state.getStatus();
        if (isTerminal(status)) {
            // Run is already completed: replay stored task outputs and close
            replayCompletedRun(client, state, fromIndex);
        } else {
            // Run is in progress: stream live events until run completes or client disconnects
            streamLiveRun(client, state, eventFilter);
        }
    }

    // ========================
    // Private helpers
    // ========================

    private boolean isTerminal(RunState.Status status) {
        return status == RunState.Status.COMPLETED
                || status == RunState.Status.FAILED
                || status == RunState.Status.CANCELLED;
    }

    /**
     * Replays stored task output snapshots as SSE events and closes the connection.
     * Used when the client connects to a run that has already completed.
     *
     * @param client    the SSE client to write to
     * @param state     the completed run state
     * @param fromIndex 0-based index to start replay from (for reconnection)
     */
    private void replayCompletedRun(SseClient client, RunState state, int fromIndex) {
        java.util.List<TaskOutputSnapshot> outputs = state.getTaskOutputs();
        int startIdx = Math.max(0, Math.min(fromIndex, outputs.size()));
        for (int i = startIdx; i < outputs.size(); i++) {
            TaskOutputSnapshot snap = outputs.get(i);
            try {
                String data = buildTaskOutputData(snap, i);
                client.sendEvent("task_completed", data);
            } catch (Exception e) {
                log.debug("SSE write failed for run {} task {}: {}", state.getRunId(), i, e.getMessage());
                return;
            }
        }
        // Final event indicating run status
        try {
            client.sendEvent(
                    "run_result",
                    "{\"runId\":\"" + state.getRunId() + "\",\"status\":\""
                            + state.getStatus().name() + "\"}");
        } catch (Exception e) {
            log.debug("SSE write failed for run {} final event: {}", state.getRunId(), e.getMessage());
        }
    }

    /**
     * Streams live broadcast events to the SSE client until the run completes or the client
     * disconnects. Blocks the calling thread for the duration.
     *
     * @param client      the SSE client to write to
     * @param state       the in-progress run state
     * @param eventFilter set of allowed event types; empty means deliver everything
     */
    private void streamLiveRun(SseClient client, RunState state, Set<String> eventFilter) {
        String callbackId = "sse-" + UUID.randomUUID();
        CompletableFuture<Void> done = new CompletableFuture<>();

        // Register a broadcast callback so we receive all events
        connectionManager.registerBroadcastCallback(callbackId, json -> {
            if (done.isDone()) return;
            // Apply event type filter
            if (!eventFilter.isEmpty()) {
                String type = extractMessageType(json);
                if (!eventFilter.contains(type)) return;
            }
            try {
                String type = extractMessageType(json);
                client.sendEvent(type.isEmpty() ? "event" : type, json);
            } catch (Exception e) {
                log.debug("SSE write failed for callback {}: {}", callbackId, e.getMessage());
                done.complete(null); // signal to stop
            }
        });

        // When client disconnects, signal the wait to end
        client.onClose(() -> done.complete(null));

        try {
            // Poll run status while waiting for completion
            while (!done.isDone() && !isTerminal(state.getStatus())) {
                done.get(500, TimeUnit.MILLISECONDS);
            }
            // Run completed: send final event
            if (isTerminal(state.getStatus()) && !done.isDone()) {
                try {
                    client.sendEvent(
                            "run_result",
                            "{\"runId\":\"" + state.getRunId() + "\",\"status\":\""
                                    + state.getStatus().name() + "\"}");
                } catch (Exception ignored) {
                    // Client may have disconnected
                }
            }
        } catch (java.util.concurrent.TimeoutException ignored) {
            // Normal: just woke up to check run status
        } catch (Exception e) {
            log.debug("SSE stream ended for run {}: {}", state.getRunId(), e.getMessage());
        } finally {
            connectionManager.unregisterBroadcastCallback(callbackId);
            done.complete(null); // ensure cleanup if not already done
        }
    }

    /**
     * Builds a simple JSON data string for a task output SSE event.
     */
    private static String buildTaskOutputData(TaskOutputSnapshot snap, int index) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"taskIndex\":").append(index);
        if (snap.taskDescription() != null) {
            sb.append(",\"taskDescription\":").append(jsonString(snap.taskDescription()));
        }
        if (snap.output() != null) {
            sb.append(",\"output\":").append(jsonString(snap.output()));
        }
        if (snap.durationMs() != null) {
            sb.append(",\"durationMs\":").append(snap.durationMs());
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Escapes a string value for JSON embedding.
     */
    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    /**
     * Extracts the {@code "type"} field value from a JSON string without full parsing.
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
     * Parses the {@code ?events=type1,type2} query parameter into a set.
     * Returns an empty set when the parameter is absent or equals {@code "*"} (all events).
     */
    private static Set<String> parseEventFilter(String eventsParam) {
        if (eventsParam == null || eventsParam.isBlank() || "*".equals(eventsParam)) {
            return Collections.emptySet();
        }
        Set<String> types = ConcurrentHashMap.newKeySet();
        for (String part : eventsParam.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
                types.add(trimmed);
            }
        }
        return types;
    }

    /**
     * Parses the {@code ?from=N} query parameter into a 0-based index.
     * Returns 0 on invalid or absent values.
     */
    private static int parseFromIndex(String fromParam) {
        if (fromParam == null || fromParam.isBlank()) return 0;
        try {
            int v = Integer.parseInt(fromParam);
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
