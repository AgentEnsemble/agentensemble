package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for delegation configuration, memory threading, and hierarchical
 * workflow delegation.
 *
 * Core delegation behaviour (peer delegation, depth limit, unknown agent, self-delegation)
 * is in DelegationEnsembleIntegrationTest.
 */
class DelegationEnsembleConfigIntegrationTest {

    private ChatModel researcherModel;
    private ChatModel writerModel;
    private ChatModel analystModel;
    private Agent researcher;
    private Agent writer;

    @BeforeEach
    void setUp() {
        researcherModel = mock(ChatModel.class);
        writerModel = mock(ChatModel.class);
        analystModel = mock(ChatModel.class);

        researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics thoroughly")
                .llm(researcherModel)
                .allowDelegation(true)
                .build();

        writer = Agent.builder()
                .role("Writer")
                .goal("Write engaging content")
                .llm(writerModel)
                .build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegationCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("del-1")
                .name("delegate")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    // ========================
    // Sequential workflow -- delegation with memory
    // ========================

    @Test
    void sequential_delegationWithShortTermMemory_memoryContextThreadedThrough() {
        Task task = Task.builder()
                .description("Research and delegate")
                .expectedOutput("Result with memory")
                .agent(researcher)
                .build();

        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write with full context"))
                .thenReturn(textResponse("Researcher final with memory"));

        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Writer result"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(task)
                .memory(EnsembleMemory.builder().shortTerm(true).build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Researcher final with memory");
    }

    // ========================
    // Sequential workflow -- maxDelegationDepth configuration
    // ========================

    @Test
    void sequential_maxDelegationDepthDefault_isThree() {
        Task task = Task.builder()
                .description("Research topics")
                .expectedOutput("Summary")
                .agent(researcher)
                .build();

        Ensemble ensemble = Ensemble.builder().agent(researcher).task(task).build();

        assertThat(ensemble.getMaxDelegationDepth()).isEqualTo(3);
    }

    @Test
    void sequential_customMaxDelegationDepth_isRespected() {
        Task task = Task.builder()
                .description("Research topics")
                .expectedOutput("Summary")
                .agent(researcher)
                .build();

        Ensemble ensemble = Ensemble.builder()
                .agent(researcher)
                .task(task)
                .maxDelegationDepth(5)
                .build();

        assertThat(ensemble.getMaxDelegationDepth()).isEqualTo(5);
    }

    // ========================
    // Sequential workflow -- backward compatibility
    // ========================

    @Test
    void sequential_agentWithoutDelegation_backwardCompatible() {
        Agent plainAgent = Agent.builder()
                .role("Analyst")
                .goal("Analyse")
                .llm(analystModel)
                .build();

        Task task = Task.builder()
                .description("Analyse data")
                .expectedOutput("Insights")
                .agent(plainAgent)
                .build();

        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("analysis result"));

        EnsembleOutput output =
                Ensemble.builder().agent(plainAgent).task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("analysis result");
    }

    // ========================
    // Hierarchical workflow -- delegation within hierarchical
    // ========================

    @Test
    void hierarchical_workerWithAllowDelegation_canDelegateToPeer() {
        ChatModel managerModel = mock(ChatModel.class);

        Agent delegatingWorker = Agent.builder()
                .role("Lead Researcher")
                .goal("Coordinate research by delegating")
                .llm(researcherModel)
                .allowDelegation(true)
                .build();

        Task task = Task.builder()
                .description("Research and produce analysis")
                .expectedOutput("Comprehensive result")
                .agent(delegatingWorker)
                .build();

        ToolExecutionRequest managerDelegation = ToolExecutionRequest.builder()
                .id("mgr-1")
                .name("delegateTask")
                .arguments("{\"agentRole\": \"Lead Researcher\", " + "\"taskDescription\": \"Research AI trends\"}")
                .build();
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(managerDelegation))
                        .build())
                .thenReturn(textResponse("Manager synthesized result"));

        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write the summary"))
                .thenReturn(textResponse("Lead researcher final answer"));

        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Writer contribution"));

        EnsembleOutput output = Ensemble.builder()
                .agent(delegatingWorker)
                .agent(writer)
                .task(task)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Manager synthesized result");
    }
}
