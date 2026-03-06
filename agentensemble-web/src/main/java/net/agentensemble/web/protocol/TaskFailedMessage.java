package net.agentensemble.web.protocol;

import java.time.Instant;

/**
 * Sent when a task fails. Mirrors {@code TaskFailedEvent}.
 *
 * @param taskIndex       1-based position of this task
 * @param taskDescription description of the task
 * @param agentRole       role of the agent that was executing the task
 * @param failedAt        when the failure occurred
 * @param reason          human-readable failure reason (exception message)
 */
public record TaskFailedMessage(
        int taskIndex, String taskDescription, String agentRole, Instant failedAt, String reason)
        implements ServerMessage {}
