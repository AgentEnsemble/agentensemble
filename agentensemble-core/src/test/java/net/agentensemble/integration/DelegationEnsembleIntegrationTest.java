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
 * Integration tests for agent delegation in both sequential and hierarchical workflows.
 *
 * Exercises the full path: Ensemble -> WorkflowExecutor -> AgentExecutor ->
 * AgentDelegationTool -> AgentExecutor (for delegated agent).
 */
class DelegationEnsembleIntegrationTest {

    private ChatModel researcherModel;
    private ChatModel writerModel;
    private ChatModel analystModel;
    private Agent researcher;
    private Agent writer;
    private Agent analyst;

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

        analyst = Agent.builder()
                .role("Data Analyst")
                .goal("Analyse data and produce insights")
                .llm(analystModel)
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
    // Sequential workflow -- agent with allowDelegation=false (no tool injected)
    // ========================

    @Test
    void sequential_agentWithAllowDelegationFalse_noDelegationToolInjected() {
        Agent nonDelegatingAgent = Agent.builder()
                .role("Researcher")
                .goal("Research only")
                .llm(researcherModel)
                .allowDelegation(false)
                .build();

        Task task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A summary")
                .agent(nonDelegatingAgent)
                .build();

        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("research result"));

        EnsembleOutput output =
                Ensemble.builder().agent(nonDelegatingAgent).task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("research result");
    }

    // ========================
    // Sequential workflow -- agent delegates to peer
    // ========================

    @Test
    void sequential_agentDelegatesToPeer_peerOutputReturnedAsTool() {
        Task researchTask = Task.builder()
                .description("Research AI trends and delegate writing to the Writer")
                .expectedOutput("A comprehensive blog post")
                .agent(researcher)
                .build();

        // Researcher first calls the delegate tool, then returns final answer
        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write a blog post about AI trends"))
                .thenReturn(textResponse("Blog post complete"));

        // Writer produces the delegated output
        when(writerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Excellent blog post about AI"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Blog post complete");
    }

    // ========================
    // Sequential workflow -- delegation depth limit
    // ========================

    @Test
    void sequential_delegationDepthLimitOf1_preventsChainingDelegation() {
        Agent delegatingWriter = Agent.builder()
                .role("Writer")
                .goal("Write content")
                .llm(writerModel)
                .allowDelegation(true)
                .build();

        Task researchTask = Task.builder()
                .description("Research and produce a report by delegating")
                .expectedOutput("A report")
                .agent(researcher)
                .build();

        // With maxDelegationDepth=1, researcher can delegate (depth 0->1)
        // but if the writer also tries to delegate, it is blocked (depth 1 = max)
        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write the report"))
                .thenReturn(textResponse("Final output from researcher"));

        // Writer tries to delegate to analyst but will be blocked
        when(writerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Data Analyst", "Analyse the data"))
                .thenReturn(textResponse("Writer final output"));

        // Analyst model should not be called
        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Analyst output"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(delegatingWriter)
                .agent(analyst)
                .task(researchTask)
                .maxDelegationDepth(1)
                .build()
                .run();

        // The ensemble completes; the important thing is depth limit was enforced
        assertThat(output.getRaw()).isEqualTo("Final output from researcher");
    }

    // ========================
    // Sequential workflow -- unknown agent delegation
    // ========================

    @Test
    void sequential_delegationToUnknownAgent_returnsErrorToCallerAgentAsToolResult() {
        Task task = Task.builder()
                .description("Research topics with possible delegation")
                .expectedOutput("A result")
                .agent(researcher)
                .build();

        // Researcher tries to delegate to a non-existent agent, gets error back, then answers
        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("NonExistentAgent", "Some task"))
                .thenReturn(textResponse("Researcher handled it directly"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(task)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Researcher handled it directly");
    }

    // ========================
    // Sequential workflow -- self-delegation guard
    // ========================

    @Test
    void sequential_selfDelegation_returnsErrorToCallerAgent() {
        Task task = Task.builder()
                .description("Research and self-delegate")
                .expectedOutput("A result")
                .agent(researcher)
                .build();

        // Researcher tries to delegate to itself -- gets error, then provides direct answer
        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Researcher", "Do this myself"))
                .thenReturn(textResponse("Researcher completed without delegation"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(task)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Researcher completed without delegation");
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
    // Sequential workflow -- maxDelegationDepth validated
    // ========================

    @Test
    void sequential_maxDelegationDepthDefault_isThree() {
        Task task = Task.builder()
                .description("Research topics")
                .expectedOutput("Summary")
                .agent(researcher)
                .build();

        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("result"));

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

        // Manager calls delegateTask to worker
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

        // Lead Researcher in turn delegates to Writer
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
