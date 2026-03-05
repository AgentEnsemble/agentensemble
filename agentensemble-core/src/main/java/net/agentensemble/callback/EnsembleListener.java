package net.agentensemble.callback;

/**
 * Listener interface for observing ensemble execution lifecycle events.
 *
 * Implement this interface to receive notifications when tasks start, complete,
 * fail, when tools are called during agent execution, or when delegation handoffs
 * occur between agents. All methods have default no-op implementations so
 * implementors only need to override the events they care about.
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
 *     .onDelegationStarted(event -> log.info("Delegating to {}", event.workerRole()))
 *     .onDelegationCompleted(event -> metrics.record(event.duration()))
 *     .onDelegationFailed(event -> alerts.notify(event.failureReason()))
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

    /**
     * Called immediately before a delegation is handed off to a worker agent.
     *
     * <p>This method is called for both peer delegation ({@code AgentDelegationTool}) and
     * hierarchical delegation ({@code DelegateTaskTool}). It is only fired for delegations
     * that pass all guards and registered policy evaluations.
     *
     * <p>Use {@link DelegationStartedEvent#delegationId()} to correlate with the matching
     * {@link #onDelegationCompleted} or {@link #onDelegationFailed} callback.
     *
     * @param event the delegation started event
     */
    default void onDelegationStarted(DelegationStartedEvent event) {}

    /**
     * Called immediately after a delegation completes successfully.
     *
     * <p>The {@link DelegationCompletedEvent#delegationId()} matches the value from the
     * corresponding {@link DelegationStartedEvent}.
     *
     * @param event the delegation completed event
     */
    default void onDelegationCompleted(DelegationCompletedEvent event) {}

    /**
     * Called when a delegation fails, whether due to a guard violation, policy rejection,
     * or worker agent exception.
     *
     * <p>Guard violations and policy rejections do not have a matching
     * {@link DelegationStartedEvent} because the worker is never invoked in those cases.
     * Worker exceptions do have a matching start event.
     *
     * @param event the delegation failed event
     */
    default void onDelegationFailed(DelegationFailedEvent event) {}
}
