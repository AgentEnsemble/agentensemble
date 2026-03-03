package net.agentensemble.callback;

import java.time.Duration;
import net.agentensemble.task.TaskOutput;

/**
 * Event fired immediately after an agent successfully completes a task.
 *
 * @param taskDescription the description of the completed task
 * @param agentRole       the role of the agent that executed the task
 * @param taskOutput      the output produced by the agent
 * @param duration        elapsed time from task start to completion
 * @param taskIndex       1-based index of this task within the current workflow run
 * @param totalTasks      total number of tasks in the current workflow run
 */
public record TaskCompleteEvent(
        String taskDescription,
        String agentRole,
        TaskOutput taskOutput,
        Duration duration,
        int taskIndex,
        int totalTasks) {}
