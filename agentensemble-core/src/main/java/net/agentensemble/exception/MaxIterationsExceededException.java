package net.agentensemble.exception;

/**
 * Thrown when an agent exceeds its maximum tool call iterations without
 * producing a final answer.
 *
 * The agent entered a tool-calling loop that did not converge. After sending
 * the maximum number of "stop" messages, execution is terminated with this
 * exception.
 *
 * Recovery options:
 * - Increase maxIterations on the Agent
 * - Simplify the task description
 * - Reduce the number or complexity of tools
 */
public class MaxIterationsExceededException extends AgentEnsembleException {

    private final String agentRole;
    private final String taskDescription;
    private final int maxIterations;
    private final int toolCallsMade;

    public MaxIterationsExceededException(String agentRole, String taskDescription,
            int maxIterations, int toolCallsMade) {
        super(String.format(
                "Agent '%s' exceeded maximum iterations (%d). Tool calls made: %d. "
                + "Task: '%s'. Consider increasing maxIterations or simplifying the task.",
                agentRole, maxIterations, toolCallsMade, taskDescription));
        this.agentRole = agentRole;
        this.taskDescription = taskDescription;
        this.maxIterations = maxIterations;
        this.toolCallsMade = toolCallsMade;
    }

    /**
     * The role of the agent that exceeded the iteration limit.
     */
    public String getAgentRole() {
        return agentRole;
    }

    /**
     * The description of the task the agent was working on.
     */
    public String getTaskDescription() {
        return taskDescription;
    }

    /**
     * The configured maximum number of iterations.
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * The actual number of tool calls made before the exception was thrown.
     */
    public int getToolCallsMade() {
        return toolCallsMade;
    }
}
