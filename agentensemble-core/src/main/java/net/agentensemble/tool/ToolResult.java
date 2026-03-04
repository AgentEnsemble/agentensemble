package net.agentensemble.tool;

/**
 * The result of a tool execution.
 *
 * <p>Use the factory methods {@link #success(String)}, {@link #success(String, Object)},
 * and {@link #failure(String)} to create instances.
 *
 * <p>Every result carries a plain-text {@link #getOutput() output} string that is fed back
 * to the LLM. Optionally, a {@link #getStructuredOutput() structuredOutput} object may be
 * attached for programmatic access by listeners and downstream components (e.g., via
 * {@link net.agentensemble.callback.ToolCallEvent}).
 */
public final class ToolResult {

    private final String output;
    private final boolean success;
    private final String errorMessage;
    private final Object structuredOutput;

    private ToolResult(String output, boolean success, String errorMessage, Object structuredOutput) {
        this.output = output;
        this.success = success;
        this.errorMessage = errorMessage;
        this.structuredOutput = structuredOutput;
    }

    /**
     * Create a successful tool result with a plain-text output.
     *
     * @param output the text output from the tool; null is treated as empty string
     * @return a successful ToolResult with no structured output
     */
    public static ToolResult success(String output) {
        return new ToolResult(output != null ? output : "", true, null, null);
    }

    /**
     * Create a successful tool result with both a plain-text output and a typed
     * structured payload.
     *
     * <p>The {@code output} string is fed to the LLM unchanged. The {@code structuredOutput}
     * is available to {@link net.agentensemble.callback.EnsembleListener} implementations
     * via {@link net.agentensemble.callback.ToolCallEvent#structuredResult()} for
     * programmatic use.
     *
     * @param output           the text output from the tool; null is treated as empty string
     * @param structuredOutput an optional typed payload for programmatic consumers; may be null
     * @return a successful ToolResult
     */
    public static ToolResult success(String output, Object structuredOutput) {
        return new ToolResult(output != null ? output : "", true, null, structuredOutput);
    }

    /**
     * Create a failure tool result.
     *
     * @param errorMessage description of what went wrong; null is normalized to a default message
     * @return a failed ToolResult
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(
                "", false, errorMessage != null ? errorMessage : "Tool execution failed with no message", null);
    }

    /**
     * The text output from the tool, fed to the LLM as the tool response.
     * Empty string if the tool failed.
     */
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

    /**
     * Optional typed payload for programmatic consumers (e.g., listeners).
     * This object is not sent to the LLM; only {@link #getOutput()} is.
     *
     * <p>May be null if the tool did not produce structured output, or if this is
     * a failure result.
     *
     * @return the structured output, or null
     */
    public Object getStructuredOutput() {
        return structuredOutput;
    }

    /**
     * Return the structured output cast to the specified type.
     *
     * @param type the expected type
     * @param <T>  the expected type parameter
     * @return the structured output cast to {@code T}, or null if not present
     * @throws ClassCastException if the structured output cannot be cast to {@code T}
     */
    @SuppressWarnings("unchecked")
    public <T> T getStructuredOutput(Class<T> type) {
        return (T) structuredOutput;
    }
}
