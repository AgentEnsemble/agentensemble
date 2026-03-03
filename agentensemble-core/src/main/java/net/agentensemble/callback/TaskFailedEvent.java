package net.agentensemble.callback;

import java.time.Duration;

/**
 * Event fired when an agent fails to complete a task.
 *
 * This event is fired before the exception propagates to the workflow executor,
 * allowing listeners to observe failure information (e.g. for alerting or metrics)
 * without needing to catch the thrown exception.
 *
 * @param taskDescription the description of the task that failed
 * @param agentRole       the role of the agent that was executing the task
 * @param cause           the exception that caused the failure
 * @param duration        elapsed time from task start to failure
 * @param taskIndex       1-based index of this task within the current workflow run
 * @param totalTasks      total number of tasks in the current workflow run
 */
public record TaskFailedEvent(
        String taskDescription, String agentRole, Throwable cause, Duration duration, int taskIndex, int totalTasks) {}
