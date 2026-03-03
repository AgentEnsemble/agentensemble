package net.agentensemble.callback;

/**
 * Listener interface for observing ensemble execution lifecycle events.
 *
 * <p>All methods have default no-op implementations so that implementors only need to
 * override the events they care about.
 *
 * <p>Listeners are invoked synchronously. Any exception thrown by a listener is caught
 * and logged; it will never abort execution or affect other listeners.
 *
 * <p>Register listeners via the {@code Ensemble} builder:
 *
 * <pre>
 * var ensemble = Ensemble.builder()
 *     .agents(List.of(researcher, writer))
 *     .tasks(List.of(researchTask, writeTask))
 *     .workflow(Workflow.SEQUENTIAL)
 *     .listener(myListener)
 *     .build();
 * </pre>
 *
 * Or via the instance convenience methods:
 *
 * <pre>
 * ensemble.onTaskStart(event -&gt; log.info("Starting: {}", event.taskDescription()));
 * ensemble.onTaskComplete(event -&gt; log.info("Done: {} in {}", event.taskDescription(), event.duration()));
 * ensemble.onToolCall(event -&gt; log.info("Tool: {}", event.toolName()));
 * </pre>
 */
public interface EnsembleListener {

    /**
     * Called immediately before an agent begins executing a task.
     *
     * @param event details about the task that is starting
     */
    default void onTaskStart(TaskStartEvent event) {}

    /**
     * Called immediately after an agent successfully completes a task.
     *
     * @param event details about the completed task including output and duration
     */
    default void onTaskComplete(TaskCompleteEvent event) {}

    /**
     * Called after each individual tool invocation within an agent's ReAct loop.
     *
     * @param event details about the tool call including name, arguments, result, and duration
     */
    default void onToolCall(ToolCallEvent event) {}
}
