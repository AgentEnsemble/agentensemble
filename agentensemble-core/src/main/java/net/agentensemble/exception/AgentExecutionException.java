package net.agentensemble.exception;

/**
 * Thrown when an agent fails during execution due to LLM errors or infrastructure failures.
 *
 * Examples: LLM timeout, authentication failure, rate limiting, network error,
 * or an unparseable LLM response. Tool execution errors are NOT represented here
 * (they are caught and fed back to the LLM).
 */
public class AgentExecutionException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final String agentRole;
    private final String taskDescription;

    public AgentExecutionException(String message, String agentRole, String taskDescription, Throwable cause) {
        super(message, cause);
        this.agentRole = agentRole;
        this.taskDescription = taskDescription;
    }

    /**
     * The role of the agent that failed.
     */
    public String getAgentRole() {
        return agentRole;
    }

    /**
     * The description of the task the agent was working on when it failed.
     */
    public String getTaskDescription() {
        return taskDescription;
    }
}
