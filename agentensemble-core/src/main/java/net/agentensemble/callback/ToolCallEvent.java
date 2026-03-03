package net.agentensemble.callback;

import java.time.Duration;

/**
 * Event fired after each individual tool invocation within an agent's ReAct loop.
 *
 * @param toolName      the name of the tool that was called
 * @param toolArguments the raw JSON arguments passed to the tool
 * @param toolResult    the result returned by the tool
 * @param agentRole     the role of the agent that invoked the tool
 * @param duration      how long the tool call took to execute
 */
public record ToolCallEvent(
        String toolName, String toolArguments, String toolResult, String agentRole, Duration duration) {}
