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
import net.agentensemble.delegation.DelegationResponse;
import net.agentensemble.delegation.DelegationStatus;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.LangChain4jToolAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

    // ========================
    // DelegationResponse tests
    // ========================

    @Test
    void testGetDelegationResponses_emptyBeforeDelegation() {
        assertThat(tool.getDelegationResponses()).isEmpty();
    }

    @Test
    void testDelegateTask_successfulDelegation_producesDelegationResponse() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research result"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses()).hasSize(1);
    }

    @Test
    void testDelegateTask_successfulDelegation_responseHasSuccessStatus() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research result"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void testDelegateTask_successfulDelegation_responseContainsRawOutput() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("AI insights"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses().get(0).rawOutput()).isEqualTo("AI insights");
    }

    @Test
    void testDelegateTask_successfulDelegation_responseContainsWorkerRole() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses().get(0).workerRole()).isEqualTo("Researcher");
    }

    @Test
    void testDelegateTask_successfulDelegation_responseHasNonNullTaskId() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses().get(0).taskId()).isNotNull().isNotBlank();
    }

    @Test
    void testDelegateTask_successfulDelegation_responseHasNonNegativeDuration() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

        tool.delegateTask("Researcher", "Research AI trends");

        assertThat(tool.getDelegationResponses().get(0).duration()).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void testDelegateTask_unknownRole_producesFailureDelegationResponse() {
        tool.delegateTask("NoSuchAgent", "some task");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        assertThat(tool.getDelegationResponses().get(0).errors()).isNotEmpty();
    }

    @Test
    void testDelegateTask_unknownRole_failureResponseRawOutputIsNull() {
        tool.delegateTask("NoSuchAgent", "some task");

        assertThat(tool.getDelegationResponses().get(0).rawOutput()).isNull();
    }

    @Test
    void testDelegateTask_multipleDelegations_allResponsesAccumulated() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research"));
        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("writing"));

        tool.delegateTask("Researcher", "Research task");
        tool.delegateTask("Writer", "Writing task");

        assertThat(tool.getDelegationResponses()).hasSize(2);
        assertThat(tool.getDelegationResponses().get(0).rawOutput()).isEqualTo("research");
        assertThat(tool.getDelegationResponses().get(1).rawOutput()).isEqualTo("writing");
    }

    @Test
    void testGetDelegationResponses_isImmutable() {
        List<DelegationResponse> responses = tool.getDelegationResponses();
        assertThatThrownBy(() -> responses.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testDelegateTask_responseTaskId_isUniquePerDelegation() {
        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("r1"))
                .thenReturn(textResponse("r2"));

        tool.delegateTask("Researcher", "Task 1");
        tool.delegateTask("Researcher", "Task 2");

        String id1 = tool.getDelegationResponses().get(0).taskId();
        String id2 = tool.getDelegationResponses().get(1).taskId();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void testDelegateTask_executorThrows_recordsFailureResponseAndRethrows() {
        // Cover the catch(Exception e) path in delegateTask()
        AgentExecutor throwingExecutor = mock(AgentExecutor.class);
        Mockito.when(throwingExecutor.execute(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("executor failure"));

        DelegationContext delegCtx = net.agentensemble.delegation.DelegationContext.create(
                List.of(researcher), 3, ExecutionContext.disabled(), throwingExecutor);
        DelegateTaskTool localTool =
                new DelegateTaskTool(List.of(researcher), throwingExecutor, ExecutionContext.disabled(), delegCtx);

        assertThatThrownBy(() -> localTool.delegateTask("Researcher", "Research something"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("executor failure");

        assertThat(localTool.getDelegationResponses()).hasSize(1);
        assertThat(localTool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        assertThat(localTool.getDelegationResponses().get(0).errors()).containsExactly("executor failure");
        assertThat(localTool.getDelegationResponses().get(0).rawOutput()).isNull();
        assertThat(localTool.getDelegatedOutputs()).isEmpty();
    }
}
