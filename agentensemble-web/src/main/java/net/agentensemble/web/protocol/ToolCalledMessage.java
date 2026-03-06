package net.agentensemble.web.protocol;

/**
 * Sent after each tool execution within an agent's ReAct loop. Mirrors {@code ToolCallEvent}.
 *
 * @param agentRole  role of the agent that invoked the tool
 * @param taskIndex  1-based index of the task being executed when the tool was called
 * @param toolName   name of the tool that was called
 * @param durationMs elapsed time for the tool execution in milliseconds
 * @param outcome    execution outcome: {@code SUCCESS} or {@code FAILURE}
 */
public record ToolCalledMessage(String agentRole, int taskIndex, String toolName, long durationMs, String outcome)
        implements ServerMessage {}
