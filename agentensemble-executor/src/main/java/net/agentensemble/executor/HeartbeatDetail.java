package net.agentensemble.executor;

/**
 * Serializable heartbeat payload emitted by {@link HeartbeatEnsembleListener} during execution.
 *
 * <p>External workflow orchestrators (Temporal, AWS Step Functions, etc.) receive this object
 * as the heartbeat detail. It communicates the type of lifecycle event, a human-readable
 * description, and optional positioning information (task index and ReAct iteration index).
 *
 * <p>Event types emitted by {@link HeartbeatEnsembleListener}:
 * <ul>
 *   <li>{@code "task_started"} -- an agent has begun executing a task</li>
 *   <li>{@code "task_completed"} -- an agent successfully finished a task</li>
 *   <li>{@code "task_failed"} -- an agent failed to complete a task</li>
 *   <li>{@code "tool_call"} -- an agent invoked a tool within the ReAct loop</li>
 *   <li>{@code "iteration_started"} -- a new ReAct iteration is beginning (LLM call pending)</li>
 *   <li>{@code "iteration_completed"} -- a ReAct iteration finished (LLM response received)</li>
 * </ul>
 *
 * <p>Temporal serialization: this record is serializable by Temporal's default Jackson
 * {@code DataConverter}. The canonical constructor is used for deserialization.
 *
 * @param eventType   one of the event type strings listed above
 * @param description human-readable detail (task description, tool name, agent role, etc.)
 * @param taskIndex   1-based index of the executing task; null when not applicable
 * @param iteration   0-based ReAct iteration index; null when not applicable
 */
public record HeartbeatDetail(String eventType, String description, Integer taskIndex, Integer iteration) {}
