package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.LangChain4jToolAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegateTaskToolTest {

    private ChatModel researcherModel;
    private ChatModel writerModel;
    private Agent researcher;
    private Agent writer;
    private DelegateTaskTool tool;

    @BeforeEach
    void setUp() {
        researcherModel = mock(ChatModel.class);
        writerModel = mock(ChatModel.class);
        researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(researcherModel)
                .build();
        writer = Agent.builder()
                .role("Writer")
                .goal("Write content")
                .llm(writerModel)
                .build();

        AgentExecutor executor = new AgentExecutor();
        DelegationContext delegationContext =
                DelegationContext.create(List.of(researcher, writer), 3, ExecutionContext.disabled(), executor);
        tool = new DelegateTaskTool(
                List.of(researcher, writer), executor, ExecutionContext.disabled(), delegationContext);
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    // ========================
    // delegateTask direct method tests
    // ========================

    @Test
    void testDelegateTask_withValidRole_returnsWorkerOutput() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("AI trends found"));

        String result = tool.delegateTask("Researcher", "Research AI trends");

        assertThat(result).isEqualTo("AI trends found");
    }

    @Test
    void testDelegateTask_routesToCorrectAgent() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research result"));
        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("written content"));

        String researchResult = tool.delegateTask("Researcher", "Research something");
        String writeResult = tool.delegateTask("Writer", "Write something");

        assertThat(researchResult).isEqualTo("research result");
        assertThat(writeResult).isEqualTo("written content");
    }

    @Test
    void testDelegateTask_roleMatchIsCaseInsensitive() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        String result = tool.delegateTask("researcher", "Research something");

        assertThat(result).isEqualTo("result");
    }

    @Test
    void testDelegateTask_roleMatchTrimsWhitespace() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        String result = tool.delegateTask("  Researcher  ", "Research something");

        assertThat(result).isEqualTo("result");
    }

    @Test
    void testDelegateTask_withUnknownRole_returnsErrorMessage() {
        String result = tool.delegateTask("Unknown Agent", "some task");

        assertThat(result).contains("No agent found with role 'Unknown Agent'");
    }

    @Test
    void testDelegateTask_withUnknownRole_listsAvailableRoles() {
        String result = tool.delegateTask("Nonexistent", "some task");

        assertThat(result).contains("Researcher");
        assertThat(result).contains("Writer");
    }

    // ========================
    // getDelegatedOutputs tests
    // ========================

    @Test
    void testGetDelegatedOutputs_beforeAnyDelegation_isEmpty() {
        assertThat(tool.getDelegatedOutputs()).isEmpty();
    }

    @Test
    void testGetDelegatedOutputs_afterOneDelegation_containsOneOutput() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("output text"));

        tool.delegateTask("Researcher", "some task");

        assertThat(tool.getDelegatedOutputs()).hasSize(1);
        assertThat(tool.getDelegatedOutputs().get(0).getRaw()).isEqualTo("output text");
    }

    @Test
    void testGetDelegatedOutputs_afterMultipleDelegations_returnsAll() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research"));
        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("writing"));

        tool.delegateTask("Researcher", "research task");
        tool.delegateTask("Writer", "writing task");

        assertThat(tool.getDelegatedOutputs()).hasSize(2);
    }

    @Test
    void testGetDelegatedOutputs_isImmutable() {
        assertThatThrownBy(() -> tool.getDelegatedOutputs().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testGetDelegatedOutputs_withUnknownRole_doesNotAddOutput() {
        tool.delegateTask("Nobody", "some task");

        assertThat(tool.getDelegatedOutputs()).isEmpty();
    }

    @Test
    void testGetDelegatedOutputs_outputHasCorrectAgentRole() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        tool.delegateTask("Researcher", "some task");

        TaskOutput output = tool.getDelegatedOutputs().get(0);
        assertThat(output.getAgentRole()).isEqualTo("Researcher");
    }

    @Test
    void testGetDelegatedOutputs_outputHasCorrectTaskDescription() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        tool.delegateTask("Researcher", "Research AI trends carefully");

        TaskOutput output = tool.getDelegatedOutputs().get(0);
        assertThat(output.getTaskDescription()).isEqualTo("Research AI trends carefully");
    }

    // ========================
    // @Tool annotation integration test
    // ========================

    @Test
    void testDelegateTask_canBeInvokedViaToolAdapter() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("adapter result"));

        // Verify it works via the JSON-based tool adapter (as called by AgentExecutor)
        String result = LangChain4jToolAdapter.executeAnnotatedTool(
                tool, "delegateTask", "{\"agentRole\": \"Researcher\", \"taskDescription\": \"Research AI trends\"}");

        assertThat(result).isEqualTo("adapter result");
    }

    @Test
    void testDelegateTask_toolAnnotationIsPresent() throws NoSuchMethodException {
        var method = DelegateTaskTool.class.getMethod("delegateTask", String.class, String.class);
        assertThat(method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                .isTrue();
    }

    @Test
    void testDelegateTask_hasCorrectNumberOfParameters() throws NoSuchMethodException {
        var method = DelegateTaskTool.class.getMethod("delegateTask", String.class, String.class);
        assertThat(method.getParameterCount()).isEqualTo(2);
    }
}
