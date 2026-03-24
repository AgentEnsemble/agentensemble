package net.agentensemble.web;

import java.time.Instant;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.web.protocol.DelegationCompletedMessage;
import net.agentensemble.web.protocol.DelegationFailedMessage;
import net.agentensemble.web.protocol.DelegationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.TaskCompletedMessage;
import net.agentensemble.web.protocol.TaskFailedMessage;
import net.agentensemble.web.protocol.TaskStartedMessage;
import net.agentensemble.web.protocol.TokenMessage;
import net.agentensemble.web.protocol.ToolCalledMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EnsembleListener} that translates execution lifecycle events into wire-protocol
 * messages and broadcasts them to all connected WebSocket dashboard clients.
 *
 * <p>An instance is created by {@link WebDashboard} at build time and exposed via
 * {@link WebDashboard#streamingListener()}. It is registered with the ensemble via
 * {@code Ensemble.builder().webDashboard(dashboard)} or directly via
 * {@code Ensemble.builder().listener(dashboard.streamingListener())}.
 *
 * <p>Thread safety: all methods delegate to {@link ConnectionManager#broadcast}, which
 * internally uses a {@link java.util.concurrent.ConcurrentHashMap}. Safe for concurrent
 * invocation from parallel workflow virtual threads.
 */
public final class WebSocketStreamingListener implements EnsembleListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketStreamingListener.class);

    private final ConnectionManager connectionManager;
    private final MessageSerializer serializer;

    /**
     * Package-private constructor; instantiated exclusively by {@link WebDashboard}.
     *
     * @param connectionManager the session registry and broadcast hub
     * @param serializer        the JSON serializer for protocol messages
     */
    WebSocketStreamingListener(ConnectionManager connectionManager, MessageSerializer serializer) {
        this.connectionManager = connectionManager;
        this.serializer = serializer;
    }

    // ========================
    // Task lifecycle
    // ========================

    @Override
    public void onTaskStart(TaskStartEvent event) {
        broadcast(new TaskStartedMessage(
                event.taskIndex(), event.totalTasks(), event.taskDescription(), event.agentRole(), Instant.now()));
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        int toolCallCount = extractToolCallCount(event);
        broadcast(new TaskCompletedMessage(
                event.taskIndex(),
                event.totalTasks(),
                event.taskDescription(),
                event.agentRole(),
                Instant.now(),
                event.duration().toMillis(),
                -1L, // token counts are not surfaced via EnsembleListener events; -1 = unknown
                toolCallCount));
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        String reason = event.cause() != null ? event.cause().getMessage() : "unknown error";
        if (reason == null) {
            reason = event.cause().getClass().getSimpleName();
        }
        broadcast(new TaskFailedMessage(
                event.taskIndex(), event.taskDescription(), event.agentRole(), Instant.now(), reason));
    }

    // ========================
    // Tool calls
    // ========================

    @Override
    public void onToolCall(ToolCallEvent event) {
        broadcast(new ToolCalledMessage(
                event.agentRole(),
                0, // taskIndex is not surfaced by ToolCallEvent
                event.toolName(),
                event.duration().toMillis(),
                null, // outcome is not surfaced by ToolCallEvent; null means unknown
                event.toolArguments(),
                event.toolResult(),
                event.structuredResult()));
    }

    // ========================
    // Delegation lifecycle
    // ========================

    @Override
    public void onDelegationStarted(DelegationStartedEvent event) {
        broadcast(new DelegationStartedMessage(
                event.delegationId(), event.delegatingAgentRole(), event.workerRole(), event.taskDescription()));
    }

    @Override
    public void onDelegationCompleted(DelegationCompletedEvent event) {
        broadcast(new DelegationCompletedMessage(
                event.delegationId(),
                event.delegatingAgentRole(),
                event.workerRole(),
                event.duration().toMillis()));
    }

    @Override
    public void onDelegationFailed(DelegationFailedEvent event) {
        broadcast(new DelegationFailedMessage(
                event.delegationId(), event.delegatingAgentRole(), event.workerRole(), event.failureReason()));
    }

    // ========================
    // Streaming tokens
    // ========================

    /**
     * Broadcasts a {@link TokenMessage} for each token received during streaming.
     *
     * <p>Token messages are broadcast to all connected clients but are <em>not</em> appended
     * to the late-join snapshot. They are ephemeral: a late-joining client should reconstruct
     * the final agent output from the {@link TaskCompletedMessage} rather than from replayed
     * token stream.
     */
    @Override
    public void onToken(TokenEvent event) {
        broadcastEphemeral(new TokenMessage(event.token(), event.agentRole(), event.taskDescription(), Instant.now()));
    }

    // ========================
    // Private helpers
    // ========================

    private void broadcast(Object message) {
        try {
            String json = serializer.toJson(message);
            connectionManager.broadcast(json);
            // Append to the late-join snapshot so clients that connect mid-run receive
            // all past events in the hello message and can reconstruct the current state.
            connectionManager.appendToSnapshot(json);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to serialize and broadcast protocol message: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Broadcast a message to all connected clients without appending to the late-join snapshot.
     *
     * <p>Used for ephemeral messages (e.g. streaming tokens) that do not need to be replayed
     * to clients that join mid-run.
     */
    private void broadcastEphemeral(Object message) {
        try {
            String json = serializer.toJson(message);
            connectionManager.broadcast(json);
            // Intentionally not calling appendToSnapshot -- tokens are ephemeral
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to serialize and broadcast ephemeral protocol message: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Extracts the number of tool calls from the task trace attached to the event.
     *
     * <p>Tool call counts are summed across all LLM interactions in the trace.
     * Returns 0 when the trace is null or has no LLM interactions.
     */
    private static int extractToolCallCount(TaskCompleteEvent event) {
        if (event.taskOutput() == null) {
            return 0;
        }
        net.agentensemble.trace.TaskTrace trace = event.taskOutput().getTrace();
        if (trace == null || trace.getLlmInteractions() == null) {
            return 0;
        }
        int count = 0;
        for (net.agentensemble.trace.LlmInteraction interaction : trace.getLlmInteractions()) {
            if (interaction != null && interaction.getToolCalls() != null) {
                count += interaction.getToolCalls().size();
            }
        }
        return count;
    }
}
