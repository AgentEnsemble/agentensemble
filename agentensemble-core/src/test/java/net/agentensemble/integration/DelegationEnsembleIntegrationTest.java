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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for agent delegation core scenarios.
 *
 * Covers: no-delegation guard, peer delegation, depth limiting, unknown agent
 * handling, and self-delegation guard.
 * Config, memory, and hierarchical delegation tests are in
 * DelegationEnsembleConfigIntegrationTest.
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

        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write a blog post about AI trends"))
                .thenReturn(textResponse("Blog post complete"));

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

        when(researcherModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Writer", "Write the report"))
                .thenReturn(textResponse("Final output from researcher"));

        when(writerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegationCallResponse("Data Analyst", "Analyse the data"))
                .thenReturn(textResponse("Writer final output"));

        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Analyst output"));

        EnsembleOutput output = Ensemble.builder()
                .agent(researcher)
                .agent(delegatingWriter)
                .agent(analyst)
                .task(researchTask)
                .maxDelegationDepth(1)
                .build()
                .run();

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
}
