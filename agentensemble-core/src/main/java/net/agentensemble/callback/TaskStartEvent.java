package net.agentensemble.callback;

/**
 * Event fired immediately before an agent begins executing a task.
 *
 * @param taskDescription the description of the task being started
 * @param agentRole       the role of the agent executing the task
 * @param taskIndex       1-based index of this task within the current workflow run
 * @param totalTasks      total number of tasks in the current workflow run
 */
public record TaskStartEvent(
        String taskDescription,
        String agentRole,
        int taskIndex,
        int totalTasks) {}
