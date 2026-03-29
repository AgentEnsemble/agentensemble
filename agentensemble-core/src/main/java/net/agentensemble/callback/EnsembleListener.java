package net.agentensemble.callback;

import java.time.Duration;

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
 *     .onToken(event -> uiBuffer.append(event.token()))
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
     * Called at the start of an ensemble run, after configuration is resolved but before
     * any tasks execute.
     *
     * @param ensembleId  unique identifier for this ensemble run
     * @param workflow    the workflow strategy name (e.g., "SEQUENTIAL", "PARALLEL")
     * @param totalTasks  total number of tasks in this run
     */
    default void onEnsembleStarted(String ensembleId, String workflow, int totalTasks) {}

    /**
     * Called at the end of an ensemble run, after all tasks have completed (or failed).
     *
     * @param ensembleId    unique identifier for this ensemble run
     * @param totalDuration total elapsed time for the run
     * @param exitReason    the reason the ensemble run exited (e.g., "COMPLETED", "FAILED")
     */
    default void onEnsembleCompleted(String ensembleId, Duration totalDuration, String exitReason) {}

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

    /**
     * Called after a task's reflection analysis completes and the result is stored.
     *
     * <p>Reflection runs after all reviews pass — on accepted output — and produces
     * improvement notes for the task's instructions that will be injected into future
     * prompts. Only fired when a task has {@code .reflect(true)} or
     * {@code .reflect(ReflectionConfig)} configured.
     *
     * @param event the task reflected event
     */
    default void onTaskReflected(TaskReflectedEvent event) {}

    /**
     * Called for each token received during streaming generation of the final agent response.
     *
     * <p>This method is only invoked when a {@code dev.langchain4j.model.chat.StreamingChatModel}
     * is resolved for the agent. Resolution order (first non-null wins):
     * {@code Agent.streamingLlm} &gt; {@code Task.streamingChatLanguageModel} &gt;
     * {@code Ensemble.streamingChatLanguageModel}.
     *
     * <p>Token events are fired during the direct LLM-to-answer path only. Tool-loop
     * iterations remain non-streaming because the full response must be inspected to
     * detect tool-call requests.
     *
     * <p>Thread safety: in a parallel workflow, this method may be called concurrently
     * from multiple virtual threads for different agents. Listener implementations must
     * be thread-safe.
     *
     * @param event the token event
     */
    default void onToken(TokenEvent event) {}

    /**
     * Called at the beginning of each ReAct iteration, just before the LLM is called.
     *
     * <p>Contains the full message buffer being sent to the LLM. Useful for observing
     * the conversation state at each step of the agent's reasoning loop.
     *
     * @param event the LLM iteration started event
     */
    default void onLlmIterationStarted(LlmIterationStartedEvent event) {}

    /**
     * Called after the LLM responds in each ReAct iteration.
     *
     * <p>Contains the response type (TOOL_CALLS or FINAL_ANSWER), text or tool requests,
     * token usage, and latency. Useful for monitoring LLM performance and reasoning steps.
     *
     * @param event the LLM iteration completed event
     */
    default void onLlmIterationCompleted(LlmIterationCompletedEvent event) {}

    /**
     * Called when a coding tool modifies a file in the workspace.
     *
     * @param event details of the file change
     */
    default void onFileChanged(FileChangedEvent event) {}

    /**
     * Returns a trace ID associated with this listener, or {@code null} if not applicable.
     *
     * <p>This method is used by the framework to populate {@code ExecutionTrace.traceId}
     * when an OpenTelemetry-based listener is registered.
     *
     * @return the W3C trace ID hex string, or {@code null}
     */
    default String getTraceId() {
        return null;
    }
}
