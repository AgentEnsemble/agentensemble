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
 * @param toolName        the name of the tool that was called
 * @param toolArguments   the JSON string of arguments passed to the tool
 * @param toolResult      the plain-text result returned by the tool (fed to the LLM)
 * @param structuredResult the optional typed structured payload from the tool; may be null
 * @param agentRole       the role of the agent that invoked the tool
 * @param duration        elapsed time for the tool execution
 */
public record ToolCallEvent(
        String toolName,
        String toolArguments,
        String toolResult,
        Object structuredResult,
        String agentRole,
        Duration duration) {}
