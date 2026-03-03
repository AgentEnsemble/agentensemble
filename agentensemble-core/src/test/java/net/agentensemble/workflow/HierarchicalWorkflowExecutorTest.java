package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.MemoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalWorkflowExecutorTest {

    private ChatModel managerModel;
    private ChatModel workerModel;
    private Agent worker;
    private HierarchicalWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        managerModel = mock(ChatModel.class);
        workerModel = mock(ChatModel.class);
        worker = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(workerModel)
                .build();
        executor = new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3);
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("delegate-1")
                .name("delegateTask")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    private Task researchTask() {
        return Task.builder()
                .description("Research AI trends")
                .expectedOutput("Summary of AI trends")
                .agent(worker)
                .build();
    }

    // ========================
    // Output structure tests
    // ========================

    @Test
    void testExecute_noDelegation_rawIsManagerOutput() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct manager answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Direct manager answer");
    }

    @Test
    void testExecute_noDelegation_taskOutputsContainsOnlyManagerOutput() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Manager answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getAgentRole()).isEqualTo("Manager");
    }

    @Test
    void testExecute_withDelegation_taskOutputsContainsWorkerThenManager() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getAgentRole()).isEqualTo("Researcher");
        assertThat(output.getTaskOutputs().get(1).getAgentRole()).isEqualTo("Manager");
    }

    @Test
    void testExecute_managerOutputIsLastInTaskOutputs() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Manager final"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs().getLast().getRaw()).isEqualTo("Manager final");
    }

    @Test
    void testExecute_rawIsManagerFinalOutput() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker stuff"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Synthesized final answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Synthesized final answer");
    }

    // ========================
    // Tool call count tests
    // ========================

    @Test
    void testExecute_withDelegation_totalToolCallsIsAtLeastOne() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTotalToolCalls()).isGreaterThanOrEqualTo(1);
    }

    // ========================
    // Duration tests
    // ========================

    @Test
    void testExecute_totalDurationIsPositive() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTotalDuration()).isPositive();
    }

    // ========================
    // Multiple task tests
    // ========================

    @Test
    void testExecute_multipleTasks_allPassedToManagerPrompt() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Multi-task answer"));

        Agent writer = Agent.builder()
                .role("Writer")
                .goal("Write content")
                .llm(workerModel)
                .build();
        executor = new HierarchicalWorkflowExecutor(managerModel, List.of(worker, writer), 20, 3);

        Task task1 = researchTask();
        Task task2 = Task.builder()
                .description("Write the report")
                .expectedOutput("A report")
                .agent(writer)
                .build();

        EnsembleOutput output = executor.execute(List.of(task1, task2), false, MemoryContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Multi-task answer");
    }

    // ========================
    // Manager agent config tests
    // ========================

    @Test
    void testExecute_managerAgentHasManagerRole() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs().getLast().getAgentRole())
                .isEqualTo(HierarchicalWorkflowExecutor.MANAGER_ROLE);
    }

    @Test
    void testExecute_taskOutputsIsImmutable() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), false, MemoryContext.disabled());

        assertThat(output.getTaskOutputs()).isUnmodifiable();
    }
}
