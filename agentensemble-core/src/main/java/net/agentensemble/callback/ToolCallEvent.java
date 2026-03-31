package net.agentensemble.callback;

import java.time.Duration;

/**
 * Event fired after each tool execution within an agent's ReAct loop.
 *
 * <p>In addition to the plain-text {@link #toolResult()} that was returned to the LLM,
 * a {@link #structuredResult()} may be present when the tool returned a
 * {@link net.agentensemble.tool.ToolResult} created via
 * {@link net.agentensemble.tool.ToolResult#success(String, Object)}. Listeners can use
 * this for programmatic processing (e.g., metrics, auditing) without re-parsing the text.
 *
 * @param toolName         the name of the tool that was called
 * @param toolArguments    the JSON string of arguments passed to the tool
 * @param toolResult       the plain-text result returned by the tool (fed to the LLM)
 * @param structuredResult the optional typed structured payload from the tool; may be null
 * @param agentRole        the role of the agent that invoked the tool
 * @param duration         elapsed time for the tool execution
 * @param taskIndex        1-based index of the task being executed when the tool was called;
 *                         {@code 0} when the task index is unavailable (e.g., delegation)
 * @param outcome          execution outcome: {@code "SUCCESS"} when the tool completed
 *                         normally, {@code "FAILURE"} when the tool returned an error result
 *                         or threw an exception; may be {@code null} in legacy contexts
 */
public record ToolCallEvent(
        String toolName,
        String toolArguments,
        String toolResult,
        Object structuredResult,
        String agentRole,
        Duration duration,
        int taskIndex,
        String outcome) {

    /** Outcome value indicating the tool executed successfully. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** Outcome value indicating the tool returned an error or threw. */
    public static final String OUTCOME_FAILURE = "FAILURE";

    /**
     * Backwards-compatible constructor matching the original 6-argument signature.
     *
     * <p>Delegates to the canonical constructor with a default {@code taskIndex} of {@code 0}
     * (meaning "unknown") and a {@code null} {@code outcome}, preserving legacy behavior.
     */
    public ToolCallEvent(
            String toolName,
            String toolArguments,
            String toolResult,
            Object structuredResult,
            String agentRole,
            Duration duration) {
        this(toolName, toolArguments, toolResult, structuredResult, agentRole, duration, 0, null);
    }
}
