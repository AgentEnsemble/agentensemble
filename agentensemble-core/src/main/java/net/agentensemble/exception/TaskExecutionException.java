package net.agentensemble.exception;

import net.agentensemble.task.TaskOutput;

import java.util.List;

/**
 * Thrown when a task fails during execution.
 *
 * Carries context about the failure including all task outputs from tasks that
 * completed successfully before the failure. This allows partial recovery of work.
 */
public class TaskExecutionException extends AgentEnsembleException {

    private final String taskDescription;
    private final String agentRole;
    private final List<TaskOutput> completedTaskOutputs;

    public TaskExecutionException(String message, String taskDescription, String agentRole,
            List<TaskOutput> completedTaskOutputs) {
        super(message);
        this.taskDescription = taskDescription;
        this.agentRole = agentRole;
        this.completedTaskOutputs = List.copyOf(completedTaskOutputs);
    }

    public TaskExecutionException(String message, String taskDescription, String agentRole,
            List<TaskOutput> completedTaskOutputs, Throwable cause) {
        super(message, cause);
        this.taskDescription = taskDescription;
        this.agentRole = agentRole;
        this.completedTaskOutputs = List.copyOf(completedTaskOutputs);
    }

    /**
     * The description of the task that failed.
     */
    public String getTaskDescription() {
        return taskDescription;
    }

    /**
     * The role of the agent that was executing the task when it failed.
     */
    public String getAgentRole() {
        return agentRole;
    }

    /**
     * Outputs from all tasks that completed successfully before this failure.
     * Useful for partial result recovery.
     */
    public List<TaskOutput> getCompletedTaskOutputs() {
        return completedTaskOutputs;
    }
}
