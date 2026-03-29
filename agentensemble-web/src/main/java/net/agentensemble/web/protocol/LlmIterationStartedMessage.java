package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Wire message sent when an LLM iteration starts (before calling the model).
 * Contains the message buffer being sent to the LLM.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmIterationStartedMessage(
        String agentRole,
        String taskDescription,
        int iterationIndex,
        List<MessageDto> messages,
        Integer totalMessageCount)
        implements ServerMessage {

    /** Serializable DTO for a single chat message in the conversation. */
    public record MessageDto(String role, String content, List<ToolCallDto> toolCalls, String toolName) {}

    /** Serializable DTO for a tool call in an assistant message. */
    public record ToolCallDto(String name, String arguments) {}
}
