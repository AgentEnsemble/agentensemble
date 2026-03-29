package net.agentensemble.callback;

import java.time.Duration;
import java.util.List;

/**
 * Fired after the LLM responds in each ReAct iteration.
 * Contains the response type, text/tool requests, token usage, and latency.
 */
public record LlmIterationCompletedEvent(
        String agentRole,
        String taskDescription,
        int iterationIndex,
        String responseType,
        String responseText,
        List<ToolCallRequest> toolRequests,
        long inputTokens,
        long outputTokens,
        Duration latency) {
    /** A tool call request from the LLM response. */
    public record ToolCallRequest(String name, String arguments) {}
}
