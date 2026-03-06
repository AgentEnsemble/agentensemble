package net.agentensemble.web;

import java.time.Instant;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.web.protocol.DelegationCompletedMessage;
import net.agentensemble.web.protocol.DelegationFailedMessage;
import net.agentensemble.web.protocol.DelegationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.TaskCompletedMessage;
import net.agentensemble.web.protocol.TaskFailedMessage;
import net.agentensemble.web.protocol.TaskStartedMessage;
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
                0, // token counts are not yet surfaced via EnsembleListener events
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
                0, // taskIndex is not part of ToolCallEvent
                event.toolName(),
                event.duration().toMillis(),
                "success"));
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
    // Private helpers
    // ========================

    private void broadcast(Object message) {
        try {
            String json = serializer.toJson(message);
            connectionManager.broadcast(json);
        } catch (Exception e) {
            log.warn("Failed to serialize and broadcast protocol message: {}", e.getMessage(), e);
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
