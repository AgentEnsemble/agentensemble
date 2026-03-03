package net.agentensemble.callback;

import java.time.Duration;
import net.agentensemble.task.TaskOutput;

/**
 * Event fired immediately after an agent successfully completes a task.
 *
 * @param taskDescription the description of the completed task
 * @param agentRole       the role of the agent that executed the task
 * @param taskOutput      the full output produced by the agent
 * @param duration        how long the task took to execute
 * @param taskIndex       the 1-based index of this task in the run
 * @param totalTasks      the total number of tasks in the run
 */
public record TaskCompleteEvent(
        String taskDescription,
        String agentRole,
        TaskOutput taskOutput,
        Duration duration,
        int taskIndex,
        int totalTasks) {}
