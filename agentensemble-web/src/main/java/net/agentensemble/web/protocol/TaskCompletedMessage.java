package net.agentensemble.web.protocol;

import java.time.Instant;

/**
 * Sent when an agent completes a task successfully. Mirrors {@code TaskCompleteEvent}.
 *
 * @param taskIndex       1-based position of this task
 * @param totalTasks      total tasks in the run
 * @param taskDescription description of the task
 * @param agentRole       role of the agent that executed the task
 * @param completedAt     when the task completed
 * @param durationMs      elapsed time in milliseconds
 * @param tokenCount      total tokens used (0 if metrics not available)
 * @param toolCallCount   number of tool calls made during this task
 */
public record TaskCompletedMessage(
        int taskIndex,
        int totalTasks,
        String taskDescription,
        String agentRole,
        Instant completedAt,
        long durationMs,
        int tokenCount,
        int toolCallCount)
        implements ServerMessage {}
