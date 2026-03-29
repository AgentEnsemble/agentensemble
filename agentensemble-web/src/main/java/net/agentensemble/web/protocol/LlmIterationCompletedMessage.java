package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire message sent when the LLM responds in a ReAct iteration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmIterationCompletedMessage(
        String agentRole,
        String taskDescription,
        int iterationIndex,
        String responseType,
        String responseText,
        List<ToolRequestDto> toolRequests,
        long inputTokens,
        long outputTokens,
        long latencyMs)
        implements ServerMessage {

    /** Serializable DTO for a tool call request from the LLM. */
    public record ToolRequestDto(String name, String arguments) {}
}
