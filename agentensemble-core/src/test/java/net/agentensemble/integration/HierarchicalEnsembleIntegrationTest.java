package net.agentensemble.integration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for hierarchical ensemble execution.
 * Uses mocked LLMs to avoid real network calls.
 */
class HierarchicalEnsembleIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("d-1")
                .name("delegateTask")
                .arguments("{\"agentRole\": \"" + agentRole + "\", "
                        + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(req))
                .build();
    }

    // ========================
    // Basic hierarchical flow
    // ========================

    @Test
    void testHierarchicalWorkflow_singleTask_managerSynthesizes() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research complete"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis based on research"));

        Agent researcher = Agent.builder().role("Researcher").goal("Research topics")
                .llm(workerModel).build();
        Task task = Task.builder().description("Research AI trends")
                .expectedOutput("AI trend summary").agent(researcher).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Final synthesis based on research");
        assertThat(output.getTaskOutputs()).hasSize(2); // worker + manager
    }

    @Test
    void testHierarchicalWorkflow_multipleTasks_allDelegated() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(workerModel.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("Research done"))
                .thenReturn(textResponse("Writing done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(delegateCallResponse("Writer", "Write about AI trends"))
                .thenReturn(textResponse("Comprehensive final answer"));

        Agent researcher = Agent.builder().role("Researcher").goal("Research").llm(workerModel).build();
        Agent writer = Agent.builder().role("Writer").goal("Write").llm(workerModel).build();
        Task researchTask = Task.builder().description("Research AI trends")
                .expectedOutput("Research findings").agent(researcher).build();
        Task writeTask = Task.builder().description("Write about AI trends")
                .expectedOutput("Blog post").agent(writer).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(writeTask)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Comprehensive final answer");
        assertThat(output.getTaskOutputs()).hasSize(3); // 2 workers + manager
    }

    // ========================
    // managerLlm defaults
    // ========================

    @Test
    void testHierarchicalWorkflow_noManagerLlm_usesFirstAgentLlm() {
        ChatModel sharedModel = mock(ChatModel.class);

        // First call is for a worker delegation, second is the manager's final answer.
        // With no managerLlm, the first agent's LLM (sharedModel) is used for both.
        when(sharedModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Worker output"))   // worker (same model)
                .thenReturn(textResponse("Final answer"));   // manager final

        // Simulate: manager (sharedModel) calls delegate, worker (sharedModel) responds,
        // manager (sharedModel) synthesizes. The model responds differently based on call order.
        Agent researcher = Agent.builder().role("Researcher").goal("Research")
                .llm(sharedModel).build();
        Task task = Task.builder().description("Research something")
                .expectedOutput("Result").agent(researcher).build();

        // No managerLlm set -- should default to first agent's LLM
        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .build()
                .run();

        // Verify it completed without error (LLM was auto-resolved)
        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isNotNull();
    }

    @Test
    void testHierarchicalWorkflow_withExplicitManagerLlm_usesManagerLlm() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Worker", "Do the work"))
                .thenReturn(textResponse("Manager synthesized"));

        Agent worker = Agent.builder().role("Worker").goal("Do work").llm(workerModel).build();
        Task task = Task.builder().description("Do the work")
                .expectedOutput("Work done").agent(worker).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(worker)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Manager synthesized");
    }

    // ========================
    // managerMaxIterations
    // ========================

    @Test
    void testHierarchicalWorkflow_customManagerMaxIterations_isRespected() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Done"));

        Agent worker = Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder().description("Task").expectedOutput("Result").agent(worker).build();

        // Should build and run without error with custom maxIterations
        EnsembleOutput output = Ensemble.builder()
                .agent(worker)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .managerMaxIterations(5)
                .build()
                .run();

        assertThat(output).isNotNull();
    }

    // ========================
    // Validation: context ordering not enforced
    // ========================

    @Test
    void testHierarchicalWorkflow_contextOrderingNotRequired() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        Agent worker = Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task1 = Task.builder().description("Task one").expectedOutput("Output one")
                .agent(worker).build();
        // task2 depends on task1 as context but comes BEFORE task1 in the list.
        // Sequential validation would reject this; hierarchical should allow it.
        Task task2 = Task.builder().description("Task two").expectedOutput("Output two")
                .agent(worker).context(List.of(task1)).build();

        assertThatCode(() ->
                Ensemble.builder()
                        .agent(worker)
                        .task(task2)  // task2 first, but it depends on task1 (listed after)
                        .task(task1)
                        .workflow(Workflow.HIERARCHICAL)
                        .managerLlm(managerModel)
                        .build()
                        .run()
        ).doesNotThrowAnyException();
    }

    // ========================
    // Validation still enforced
    // ========================

    @Test
    void testHierarchicalWorkflow_emptyTasks_throwsValidation() {
        ChatModel managerModel = mock(ChatModel.class);
        Agent agent = Agent.builder().role("Worker").goal("Work").llm(managerModel).build();

        assertThatThrownBy(() -> Ensemble.builder()
                .agent(agent)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("task");
    }

    @Test
    void testHierarchicalWorkflow_emptyAgents_throwsValidation() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);
        Agent agent = Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder().description("Task").expectedOutput("Result").agent(agent).build();

        assertThatThrownBy(() -> Ensemble.builder()
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agent");
    }

    // ========================
    // EnsembleOutput structure
    // ========================

    @Test
    void testHierarchicalWorkflow_outputTaskOutputsIsImmutable() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        Agent worker = Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder().description("Task").expectedOutput("Result").agent(worker).build();

        EnsembleOutput output = Ensemble.builder()
                .agent(worker)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).isUnmodifiable();
    }
}
