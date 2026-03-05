package net.agentensemble.trace;

/**
 * Outcome of a single tool invocation captured in a {@link ToolCallTrace}.
 */
public enum ToolCallOutcome {

    /** The tool executed and returned a successful {@link net.agentensemble.tool.ToolResult}. */
    SUCCESS,

    /** The tool executed and returned a failed {@link net.agentensemble.tool.ToolResult}. */
    FAILURE,

    /** The tool threw an uncaught exception during execution. */
    ERROR,

    /**
     * The tool call was not executed because the agent exceeded its maximum iteration limit.
     * A stop message was injected instead of the actual tool result.
     */
    SKIPPED_MAX_ITERATIONS
}
