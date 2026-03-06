package net.agentensemble.web.protocol;

/**
 * Sent after each tool execution within an agent's ReAct loop. Mirrors {@code ToolCallEvent}.
 *
 * @param agentRole        role of the agent that invoked the tool
 * @param taskIndex        1-based index of the task being executed when the tool was called;
 *                         {@code 0} when the task index is unavailable from the event
 * @param toolName         name of the tool that was called
 * @param durationMs       elapsed time for the tool execution in milliseconds
 * @param outcome          execution outcome: {@code SUCCESS} or {@code FAILURE}
 * @param toolArguments    arguments passed to the tool as a JSON string; {@code null} when
 *                         the tool was called with no arguments or the value is unavailable
 * @param toolResult       the text result returned by the tool; {@code null} when the tool
 *                         produced no result or threw before a result could be produced
 * @param structuredResult optional structured output from the tool; {@code null} when not
 *                         applicable or when the tool does not produce structured output
 */
public record ToolCalledMessage(
        String agentRole,
        int taskIndex,
        String toolName,
        long durationMs,
        String outcome,
        String toolArguments,
        String toolResult,
        Object structuredResult)
        implements ServerMessage {}
