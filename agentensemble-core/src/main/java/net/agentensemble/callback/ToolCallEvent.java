package net.agentensemble.callback;

import java.time.Duration;

/**
 * Event fired after each tool execution within an agent's ReAct loop.
 *
 * @param toolName      the name of the tool that was called
 * @param toolArguments the JSON string of arguments passed to the tool
 * @param toolResult    the result returned by the tool
 * @param agentRole     the role of the agent that invoked the tool
 * @param duration      elapsed time for the tool execution
 */
public record ToolCallEvent(
        String toolName, String toolArguments, String toolResult, String agentRole, Duration duration) {}
