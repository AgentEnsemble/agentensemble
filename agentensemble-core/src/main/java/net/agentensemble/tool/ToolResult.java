package net.agentensemble.tool;

/**
 * The result of a tool execution.
 *
 * Use the factory methods {@link #success(String)} and {@link #failure(String)}
 * to create instances.
 */
public final class ToolResult {

    private final String output;
    private final boolean success;
    private final String errorMessage;

    private ToolResult(String output, boolean success, String errorMessage) {
        this.output = output;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /**
     * Create a successful tool result.
     *
     * @param output the text output from the tool; null is treated as empty string
     * @return a successful ToolResult
     */
    public static ToolResult success(String output) {
        return new ToolResult(output != null ? output : "", true, null);
    }

    /**
     * Create a failure tool result.
     *
     * @param errorMessage description of what went wrong
     * @return a failed ToolResult
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult("", false, errorMessage);
    }

    /** The text output from the tool. Empty string if the tool failed. */
    public String getOutput() {
        return output;
    }

    /** Whether the tool executed successfully. */
    public boolean isSuccess() {
        return success;
    }

    /** Error message when {@link #isSuccess()} is false. Null on success. */
    public String getErrorMessage() {
        return errorMessage;
    }
}
