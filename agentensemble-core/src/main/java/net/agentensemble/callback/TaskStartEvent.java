package net.agentensemble.callback;

/**
 * Event fired immediately before an agent begins executing a task.
 *
 * @param taskDescription the description of the task being started
 * @param agentRole       the role of the agent executing the task
 * @param taskIndex       the 1-based index of this task in the run
 * @param totalTasks      the total number of tasks in the run
 */
public record TaskStartEvent(String taskDescription, String agentRole, int taskIndex, int totalTasks) {}
