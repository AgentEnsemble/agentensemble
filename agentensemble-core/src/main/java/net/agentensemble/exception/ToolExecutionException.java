package net.agentensemble.exception;

/**
 * Represents a tool infrastructure failure.
 *
 * This exception is used for internal tracking and logging. It is NOT typically
 * thrown to halt execution -- tool errors are caught, converted to error message
 * strings, and fed back to the LLM so it can retry or adjust its approach.
 *
 * If the framework itself encounters an unrecoverable tool infrastructure failure
 * (e.g., a tool system initialization error), this exception may be thrown.
 */
public class ToolExecutionException extends AgentEnsembleException {

    private final String toolName;
    private final String toolInput;

    public ToolExecutionException(String message, String toolName, String toolInput, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
        this.toolInput = toolInput;
    }

    /**
     * The name of the tool that failed.
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * The input that was passed to the tool when it failed.
     */
    public String getToolInput() {
        return toolInput;
    }
}
