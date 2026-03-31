package net.agentensemble.web.protocol;

import java.time.Instant;
import java.util.List;

/**
 * Sent after task context is assembled but before the first LLM call. Mirrors
 * {@code TaskInputEvent} and captures the full "agent input" for the viz dashboard.
 *
 * @param taskIndex        1-based task index; 0 when unavailable
 * @param taskDescription  the task description
 * @param expectedOutput   the expected output specification; may be null
 * @param agentRole        the role of the agent executing the task
 * @param agentGoal        the agent's goal; may be null
 * @param agentBackground  the agent's background; may be null
 * @param toolNames        names of tools available to the agent
 * @param assembledContext  the full user prompt assembled for the LLM
 * @param sentAt           timestamp when the event was created
 */
public record TaskInputMessage(
        int taskIndex,
        String taskDescription,
        String expectedOutput,
        String agentRole,
        String agentGoal,
        String agentBackground,
        List<String> toolNames,
        String assembledContext,
        Instant sentAt)
        implements ServerMessage {}
