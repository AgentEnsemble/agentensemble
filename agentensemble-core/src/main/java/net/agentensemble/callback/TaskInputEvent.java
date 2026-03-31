package net.agentensemble.callback;

import java.util.List;

/**
 * Fired after task context is assembled but before the first LLM call, capturing
 * the full "agent input" -- everything the agent was given to work with for a task.
 *
 * <p>This event provides a first-class view of the assembled agent context that the
 * viz dashboard can display as a task-level input summary. It is distinct from
 * {@link TaskStartEvent} (which fires at the workflow level before context assembly)
 * and {@link LlmIterationStartedEvent} (which fires per-iteration with the full
 * message buffer).
 *
 * @param taskIndex        1-based index of the task; 0 when unavailable
 * @param taskDescription  the task description
 * @param expectedOutput   the expected output specification; may be null
 * @param agentRole        the role of the agent executing the task
 * @param agentGoal        the agent's goal; may be null for synthesized agents
 * @param agentBackground  the agent's background/backstory; may be null
 * @param toolNames        names of tools available to the agent; empty list when no tools
 * @param assembledContext  the full user prompt string assembled from upstream outputs,
 *                          memory, directives, and task context; this is the complete
 *                          input that will be sent to the LLM
 */
public record TaskInputEvent(
        int taskIndex,
        String taskDescription,
        String expectedOutput,
        String agentRole,
        String agentGoal,
        String agentBackground,
        List<String> toolNames,
        String assembledContext) {}
