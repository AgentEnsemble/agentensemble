package net.agentensemble.executor;

import java.util.function.Consumer;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.LlmIterationCompletedEvent;
import net.agentensemble.callback.LlmIterationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EnsembleListener} that translates ensemble lifecycle events into heartbeat
 * notifications for external workflow orchestrators.
 *
 * <p>Register this listener by passing a heartbeat callback to
 * {@link TaskExecutor#execute(TaskRequest, Consumer)} or
 * {@link EnsembleExecutor#execute(EnsembleRequest, Consumer)}. For Temporal:
 *
 * <pre>
 * // In your Temporal Activity implementation:
 * public TaskResult execute(TaskRequest request) {
 *     return executor.execute(request, Activity.getExecutionContext()::heartbeat);
 * }
 *
 * // Or construct explicitly for custom routing:
 * executor.execute(request, detail -> {
 *     Activity.getExecutionContext().heartbeat(detail);
 *     metrics.recordHeartbeat(detail.eventType());
 * });
 * </pre>
 *
 * <p>The following events are translated to heartbeat calls, each carrying a
 * {@link HeartbeatDetail}:
 * <ul>
 *   <li>{@code onTaskStart} -- event type {@code "task_started"}</li>
 *   <li>{@code onTaskComplete} -- event type {@code "task_completed"}</li>
 *   <li>{@code onTaskFailed} -- event type {@code "task_failed"}</li>
 *   <li>{@code onToolCall} -- event type {@code "tool_call"}</li>
 *   <li>{@code onLlmIterationStarted} -- event type {@code "iteration_started"}</li>
 *   <li>{@code onLlmIterationCompleted} -- event type {@code "iteration_completed"}</li>
 * </ul>
 *
 * <p>Thread safety: in a parallel ensemble workflow, listener methods may be called
 * concurrently. The consumer callback must be thread-safe. Temporal's
 * {@code ActivityExecutionContext::heartbeat} is thread-safe.
 *
 * <p>Exception safety: exceptions thrown by the consumer are caught and logged but
 * do not abort the ensemble execution, consistent with the {@link EnsembleListener} contract.
 */
public class HeartbeatEnsembleListener implements EnsembleListener {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatEnsembleListener.class);

    private final Consumer<Object> heartbeatConsumer;

    /**
     * Constructs a listener that forwards lifecycle events to the given consumer.
     *
     * @param heartbeatConsumer the callback invoked with a {@link HeartbeatDetail} on each event;
     *                          must not be null
     * @throws IllegalArgumentException if heartbeatConsumer is null
     */
    public HeartbeatEnsembleListener(Consumer<Object> heartbeatConsumer) {
        if (heartbeatConsumer == null) {
            throw new IllegalArgumentException("heartbeatConsumer must not be null");
        }
        this.heartbeatConsumer = heartbeatConsumer;
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        heartbeat(new HeartbeatDetail("task_started", event.taskDescription(), event.taskIndex(), null));
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        heartbeat(new HeartbeatDetail("task_completed", event.taskDescription(), event.taskIndex(), null));
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        String description = event.taskDescription();
        if (event.cause() != null && event.cause().getMessage() != null) {
            description = description + ": " + event.cause().getMessage();
        }
        heartbeat(new HeartbeatDetail("task_failed", description, event.taskIndex(), null));
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        heartbeat(new HeartbeatDetail("tool_call", event.toolName(), event.taskIndex(), null));
    }

    @Override
    public void onLlmIterationStarted(LlmIterationStartedEvent event) {
        heartbeat(new HeartbeatDetail("iteration_started", event.agentRole(), null, event.iterationIndex()));
    }

    @Override
    public void onLlmIterationCompleted(LlmIterationCompletedEvent event) {
        heartbeat(new HeartbeatDetail("iteration_completed", event.agentRole(), null, event.iterationIndex()));
    }

    // ========================
    // Helpers
    // ========================

    private void heartbeat(HeartbeatDetail detail) {
        try {
            heartbeatConsumer.accept(detail);
        } catch (Exception e) {
            // Consistent with EnsembleListener contract: exceptions must not abort execution.
            if (log.isWarnEnabled()) {
                log.warn(
                        "Heartbeat consumer threw an exception for event '{}' -- ignoring to preserve execution",
                        detail.eventType(),
                        e);
            }
        }
    }
}
