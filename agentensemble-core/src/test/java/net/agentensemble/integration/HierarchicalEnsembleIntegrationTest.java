package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for hierarchical ensemble execution.
 *
 * Covers basic execution flow, manager LLM configuration, manager iterations,
 * context ordering, and output structure. Validation and callback tests are in
 * HierarchicalEnsembleValidationIntegrationTest.
 */
class HierarchicalEnsembleIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("d-1")
                .name("delegateTask")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
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

        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(workerModel)
                .build();
        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("AI trend summary")
                .agent(researcher)
                .build();

        EnsembleOutput output = Ensemble.builder()
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

        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(workerModel)
                .build();
        Agent writer =
                Agent.builder().role("Writer").goal("Write").llm(workerModel).build();
        Task researchTask = Task.builder()
                .description("Research AI trends")
                .expectedOutput("Research findings")
                .agent(researcher)
                .build();
        Task writeTask = Task.builder()
                .description("Write about AI trends")
                .expectedOutput("Blog post")
                .agent(writer)
                .build();

        EnsembleOutput output = Ensemble.builder()
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

        when(sharedModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Worker output"))
                .thenReturn(textResponse("Final answer"));

        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(sharedModel)
                .build();
        Task task = Task.builder()
                .description("Research something")
                .expectedOutput("Result")
                .agent(researcher)
                .build();

        // No managerLlm set -- should default to first agent's LLM
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .build()
                .run();

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

        Agent worker =
                Agent.builder().role("Worker").goal("Do work").llm(workerModel).build();
        Task task = Task.builder()
                .description("Do the work")
                .expectedOutput("Work done")
                .agent(worker)
                .build();

        EnsembleOutput output = Ensemble.builder()
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

        Agent worker =
                Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder()
                .description("Task")
                .expectedOutput("Result")
                .agent(worker)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .managerMaxIterations(5)
                .build()
                .run();

        assertThat(output).isNotNull();
    }

    // ========================
    // Context ordering not enforced in hierarchical
    // ========================

    @Test
    void testHierarchicalWorkflow_contextOrderingNotRequired() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        // Use distinct roles -- HIERARCHICAL requires unique roles for unambiguous delegation
        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(workerModel)
                .build();
        Agent analyst =
                Agent.builder().role("Analyst").goal("Analyse").llm(workerModel).build();
        Task task1 = Task.builder()
                .description("Task one")
                .expectedOutput("Output one")
                .agent(researcher)
                .build();
        Task task2 = Task.builder()
                .description("Task two")
                .expectedOutput("Output two")
                .agent(analyst)
                .context(List.of(task1))
                .build();

        // task2 listed before task1 -- valid for HIERARCHICAL (no ordering constraint)
        assertThatCode(() -> Ensemble.builder()
                        .task(task2)
                        .task(task1)
                        .workflow(Workflow.HIERARCHICAL)
                        .managerLlm(managerModel)
                        .build()
                        .run())
                .doesNotThrowAnyException();
    }

    // ========================
    // EnsembleOutput structure
    // ========================

    @Test
    void testHierarchicalWorkflow_outputTaskOutputsIsImmutable() {
        ChatModel managerModel = mock(ChatModel.class);
        ChatModel workerModel = mock(ChatModel.class);

        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        Agent worker =
                Agent.builder().role("Worker").goal("Work").llm(workerModel).build();
        Task task = Task.builder()
                .description("Task")
                .expectedOutput("Result")
                .agent(worker)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).isUnmodifiable();
    }
}
