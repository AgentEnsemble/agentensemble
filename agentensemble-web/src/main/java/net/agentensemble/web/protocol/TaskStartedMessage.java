package net.agentensemble.web.protocol;

import java.time.Instant;

/**
 * Sent when an agent begins executing a task. Mirrors {@code TaskStartEvent}.
 *
 * @param taskIndex       1-based position of this task in the workflow
 * @param totalTasks      total tasks in the run
 * @param taskDescription description of the task
 * @param agentRole       role of the executing agent
 * @param startedAt       when execution started
 */
public record TaskStartedMessage(
        int taskIndex, int totalTasks, String taskDescription, String agentRole, Instant startedAt)
        implements ServerMessage {}
