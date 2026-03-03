package net.agentensemble.callback;

/**
 * Listener interface for observing ensemble execution lifecycle events.
 *
 * Implement this interface to receive notifications when tasks start, complete,
 * fail, or when tools are called during agent execution. All methods have default
 * no-op implementations so implementors only need to override the events they
 * care about.
 *
 * Listeners are registered via the Ensemble builder:
 * <pre>
 * // Full interface implementation
 * Ensemble.builder()
 *     .listener(new MyMetricsListener())
 *     .build();
 *
 * // Lambda convenience methods
 * Ensemble.builder()
 *     .onTaskStart(event -> log.info("Starting: {}", event.agentRole()))
 *     .onTaskComplete(event -> metrics.record(event.duration()))
 *     .onTaskFailed(event -> alerts.notify(event.cause()))
 *     .onToolCall(event -> metrics.increment("tool." + event.toolName()))
 *     .build();
 * </pre>
 *
 * Thread safety: when using a parallel workflow, listener methods may be called
 * concurrently from multiple virtual threads. Listener implementations must be
 * thread-safe.
 *
 * Exception safety: exceptions thrown by listeners are caught and logged by the
 * framework. A listener throwing an exception will not abort task execution or
 * prevent other listeners from being called.
 */
public interface EnsembleListener {

    /**
     * Called immediately before an agent begins executing a task.
     *
     * @param event the task start event
     */
    default void onTaskStart(TaskStartEvent event) {}

    /**
     * Called immediately after an agent successfully completes a task.
     *
     * @param event the task complete event
     */
    default void onTaskComplete(TaskCompleteEvent event) {}

    /**
     * Called when an agent fails to complete a task, before the exception propagates.
     *
     * @param event the task failed event
     */
    default void onTaskFailed(TaskFailedEvent event) {}

    /**
     * Called after each tool execution within an agent's ReAct loop.
     *
     * @param event the tool call event
     */
    default void onToolCall(ToolCallEvent event) {}
}
