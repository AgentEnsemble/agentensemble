package net.agentensemble.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CapturedMessage conversion from LangChain4j message types.
 */
class CapturedMessageTest {

    // ========================
    // SystemMessage
    // ========================

    @Test
    void from_systemMessage_roleIsSystem() {
        CapturedMessage msg = CapturedMessage.from(new SystemMessage("You are a researcher."));

        assertThat(msg.getRole()).isEqualTo("system");
        assertThat(msg.getContent()).isEqualTo("You are a researcher.");
        assertThat(msg.getToolCalls()).isEmpty();
        assertThat(msg.getToolName()).isNull();
    }

    // ========================
    // UserMessage
    // ========================

    @Test
    void from_userMessage_roleIsUser() {
        CapturedMessage msg = CapturedMessage.from(UserMessage.from("Research AI frameworks."));

        assertThat(msg.getRole()).isEqualTo("user");
        assertThat(msg.getContent()).isEqualTo("Research AI frameworks.");
        assertThat(msg.getToolCalls()).isEmpty();
        assertThat(msg.getToolName()).isNull();
    }

    // ========================
    // AiMessage -- final answer
    // ========================

    @Test
    void from_aiMessage_finalAnswer_roleIsAssistant() {
        CapturedMessage msg = CapturedMessage.from(AiMessage.from("Here is my research."));

        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isEqualTo("Here is my research.");
        assertThat(msg.getToolCalls()).isEmpty();
        assertThat(msg.getToolName()).isNull();
    }

    // ========================
    // AiMessage -- tool call request
    // ========================

    @Test
    void from_aiMessage_withToolCall_roleIsAssistantWithToolCalls() {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("web_search")
                .arguments("{\"query\":\"AI frameworks\"}")
                .build();
        AiMessage aiMessage = AiMessage.from(toolRequest);

        CapturedMessage msg = CapturedMessage.from(aiMessage);

        assertThat(msg.getRole()).isEqualTo("assistant");
        assertThat(msg.getContent()).isNull();
        assertThat(msg.getToolCalls()).hasSize(1);
        assertThat(msg.getToolCalls().get(0)).containsEntry("name", "web_search");
        assertThat(msg.getToolCalls().get(0)).containsEntry("arguments", "{\"query\":\"AI frameworks\"}");
    }

    @Test
    void from_aiMessage_withMultipleToolCalls_allCaptured() {
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("call-1")
                .name("web_search")
                .arguments("{\"query\":\"AI\"}")
                .build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("call-2")
                .name("calculator")
                .arguments("{\"expression\":\"2+2\"}")
                .build();
        AiMessage aiMessage = AiMessage.from(List.of(req1, req2));

        CapturedMessage msg = CapturedMessage.from(aiMessage);

        assertThat(msg.getToolCalls()).hasSize(2);
        assertThat(msg.getToolCalls().get(0).get("name")).isEqualTo("web_search");
        assertThat(msg.getToolCalls().get(1).get("name")).isEqualTo("calculator");
    }

    @Test
    void from_aiMessage_withNullToolArguments_defaultsToEmptyJson() {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("no_args_tool")
                .arguments(null)
                .build();
        AiMessage aiMessage = AiMessage.from(toolRequest);

        CapturedMessage msg = CapturedMessage.from(aiMessage);

        assertThat(msg.getToolCalls()).hasSize(1);
        assertThat(msg.getToolCalls().get(0)).containsEntry("arguments", "{}");
    }

    // ========================
    // ToolExecutionResultMessage
    // ========================

    @Test
    void from_toolResultMessage_roleIsToolWithToolName() {
        ToolExecutionResultMessage resultMsg =
                new ToolExecutionResultMessage("call-1", "web_search", "Search results...");

        CapturedMessage msg = CapturedMessage.from(resultMsg);

        assertThat(msg.getRole()).isEqualTo("tool");
        assertThat(msg.getToolName()).isEqualTo("web_search");
        assertThat(msg.getContent()).isEqualTo("Search results...");
        assertThat(msg.getToolCalls()).isEmpty();
    }

    // ========================
    // fromAll
    // ========================

    @Test
    void fromAll_convertsAllMessagesInOrder() {
        List<CapturedMessage> messages = CapturedMessage.fromAll(
                List.of(new SystemMessage("sys"), UserMessage.from("user"), AiMessage.from("answer")));

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getRole()).isEqualTo("system");
        assertThat(messages.get(1).getRole()).isEqualTo("user");
        assertThat(messages.get(2).getRole()).isEqualTo("assistant");
    }

    @Test
    void fromAll_emptyList_returnsEmptyList() {
        assertThat(CapturedMessage.fromAll(List.of())).isEmpty();
    }

    @Test
    void fromAll_resultIsImmutable() {
        List<CapturedMessage> messages = CapturedMessage.fromAll(List.of(new SystemMessage("sys")));
        // Should be an unmodifiable list (UnsupportedOperationException expected)
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> messages.add(null));
    }
}
