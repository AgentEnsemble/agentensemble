package net.agentensemble.trace;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Serializable snapshot of a single chat message captured during a ReAct iteration.
 *
 * <p>Converts LangChain4j {@code ChatMessage} subtypes into a trace-friendly value object
 * that can be serialized to JSON without requiring LangChain4j on the consumer side.
 * Instances are captured in {@code LlmInteraction.getMessages()} when
 * {@link CaptureMode#STANDARD} or higher is active.
 *
 * <p>The {@code role} field maps to conventional chat roles:
 * <ul>
 *   <li>{@code "system"} -- system prompt ({@code SystemMessage})</li>
 *   <li>{@code "user"} -- user turn ({@code UserMessage})</li>
 *   <li>{@code "assistant"} -- LLM response ({@code AiMessage})</li>
 *   <li>{@code "tool"} -- tool execution result ({@code ToolExecutionResultMessage})</li>
 * </ul>
 *
 * <p>For {@code "assistant"} messages that contain tool call requests, {@code getContent()}
 * is {@code null} and {@code getToolCalls()} is populated with one entry per requested tool.
 * Each tool call entry has at minimum a {@code "name"} key and an {@code "arguments"} key.
 *
 * <p>For {@code "tool"} messages, {@code getToolName()} identifies which tool produced
 * the result, and {@code getContent()} is the result text.
 */
@Value
@Builder
public class CapturedMessage {

    /**
     * Chat role for this message.
     * One of: {@code "system"}, {@code "user"}, {@code "assistant"}, {@code "tool"}.
     */
    @NonNull
    String role;

    /**
     * Text content of the message.
     * {@code null} for assistant messages that only contain tool call requests.
     */
    String content;

    /**
     * Tool calls requested in this message.
     * Non-empty only for {@code "assistant"} messages with tool execution requests.
     * Each entry contains at least {@code "name"} and {@code "arguments"} keys.
     */
    @Singular("toolCall")
    List<Map<String, Object>> toolCalls;

    /**
     * Name of the tool whose result is carried in this message.
     * Non-null only for {@code "tool"} role messages.
     */
    String toolName;

    // ========================
    // Static factory methods
    // ========================

    /**
     * Convert a LangChain4j {@link ChatMessage} into a {@link CapturedMessage}.
     *
     * <p>Handles all four concrete message types used in the AgentEnsemble ReAct loop.
     * Unknown subtypes are represented as a {@code "user"} role message with the
     * object's {@code toString()} as content.
     *
     * @param msg the LangChain4j message to convert; must not be {@code null}
     * @return an equivalent {@link CapturedMessage}
     */
    public static CapturedMessage from(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) {
            return CapturedMessage.builder().role("system").content(sm.text()).build();
        }
        if (msg instanceof UserMessage um) {
            return CapturedMessage.builder()
                    .role("user")
                    .content(um.singleText())
                    .build();
        }
        if (msg instanceof AiMessage am) {
            if (am.hasToolExecutionRequests()) {
                CapturedMessageBuilder builder = CapturedMessage.builder().role("assistant");
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("name", req.name());
                    toolCall.put("arguments", req.arguments() != null ? req.arguments() : "{}");
                    builder.toolCall(toolCall);
                }
                return builder.build();
            }
            return CapturedMessage.builder()
                    .role("assistant")
                    .content(am.text())
                    .build();
        }
        if (msg instanceof ToolExecutionResultMessage tr) {
            return CapturedMessage.builder()
                    .role("tool")
                    .toolName(tr.toolName())
                    .content(tr.text())
                    .build();
        }
        // Fallback for any unknown subtype
        return CapturedMessage.builder().role("user").content(msg.toString()).build();
    }

    /**
     * Convert a list of LangChain4j {@link ChatMessage} objects into a list of
     * {@link CapturedMessage} snapshots.
     *
     * @param messages the messages to convert; must not be {@code null}
     * @return an immutable list of converted messages, in the same order
     */
    public static List<CapturedMessage> fromAll(List<? extends ChatMessage> messages) {
        List<CapturedMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            result.add(from(msg));
        }
        return List.copyOf(result);
    }
}
