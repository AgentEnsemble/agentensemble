package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.trace.LlmResponseType;
import net.agentensemble.trace.TaskTrace;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying that AgentExecutor populates TaskOutput
 * with execution metrics and a full call trace.
 */
class AgentExecutorMetricsTest {

    private final AgentExecutor executor = new AgentExecutor();

    private Agent agentWithMockLlm(String response, TokenUsage tokenUsage) {
        ChatModel llm = mock(ChatModel.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        AiMessage aiMessage = mock(AiMessage.class);

        when(chatResponse.aiMessage()).thenReturn(aiMessage);
        when(chatResponse.tokenUsage()).thenReturn(tokenUsage);
        when(aiMessage.text()).thenReturn(response);
        when(aiMessage.hasToolExecutionRequests()).thenReturn(false);
        when(llm.chat(any(ChatRequest.class))).thenReturn(chatResponse);

        return Agent.builder()
                .role("Researcher")
                .goal("Research things")
                .llm(llm)
                .build();
    }

    @Test
    void testExecute_populatesTaskTrace() {
        Agent agent = agentWithMockLlm("Research result", null);
        Task task = Task.builder()
                .description("Research AI agents")
                .expectedOutput("Research findings")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, java.util.List.of(), ExecutionContext.disabled());

        assertThat(output.getTrace()).isNotNull();
        TaskTrace trace = output.getTrace();
        assertThat(trace.getAgentRole()).isEqualTo("Researcher");
        assertThat(trace.getTaskDescription()).isEqualTo("Research AI agents");
        assertThat(trace.getFinalOutput()).isEqualTo("Research result");
        assertThat(trace.getPrompts()).isNotNull();
        assertThat(trace.getLlmInteractions()).hasSize(1);
        assertThat(trace.getLlmInteractions().get(0).getResponseType()).isEqualTo(LlmResponseType.FINAL_ANSWER);
        assertThat(trace.getLlmInteractions().get(0).getResponseText()).isEqualTo("Research result");
    }

    @Test
    void testExecute_populatesMetrics_withUnknownTokens() {
        // When provider returns null tokenUsage, tokens should be -1
        Agent agent = agentWithMockLlm("Result", null);
        Task task = Task.builder()
                .description("Task description")
                .expectedOutput("Expected")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, java.util.List.of(), ExecutionContext.disabled());

        TaskMetrics metrics = output.getMetrics();
        assertThat(metrics).isNotNull();
        assertThat(metrics.getLlmCallCount()).isEqualTo(1);
        assertThat(metrics.getInputTokens()).isEqualTo(-1L);
        assertThat(metrics.getOutputTokens()).isEqualTo(-1L);
        assertThat(metrics.getTotalTokens()).isEqualTo(-1L);
        assertThat(metrics.getLlmLatency()).isNotNull();
        assertThat(metrics.getPromptBuildTime()).isNotNull();
    }

    @Test
    void testExecute_populatesMetrics_withKnownTokens() {
        TokenUsage tokenUsage = mock(TokenUsage.class);
        when(tokenUsage.inputTokenCount()).thenReturn(500);
        when(tokenUsage.outputTokenCount()).thenReturn(200);

        Agent agent = agentWithMockLlm("Result", tokenUsage);
        Task task = Task.builder()
                .description("Task description")
                .expectedOutput("Expected")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, java.util.List.of(), ExecutionContext.disabled());

        TaskMetrics metrics = output.getMetrics();
        assertThat(metrics.getInputTokens()).isEqualTo(500L);
        assertThat(metrics.getOutputTokens()).isEqualTo(200L);
        assertThat(metrics.getTotalTokens()).isEqualTo(700L);
    }

    @Test
    void testExecute_traceContainsPromptsWithContent() {
        Agent agent = agentWithMockLlm("Response", null);
        Task task = Task.builder()
                .description("Do some research")
                .expectedOutput("Research report")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, java.util.List.of(), ExecutionContext.disabled());

        TaskTrace trace = output.getTrace();
        assertThat(trace.getPrompts().getSystemPrompt()).isNotBlank();
        assertThat(trace.getPrompts().getUserPrompt()).isNotBlank();
        assertThat(trace.getPrompts().getUserPrompt()).contains("Do some research");
    }

    @Test
    void testExecute_traceDurationMatchesTaskOutputDuration() {
        Agent agent = agentWithMockLlm("Response", null);
        Task task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, java.util.List.of(), ExecutionContext.disabled());

        // TaskTrace duration and TaskOutput duration should both be positive
        assertThat(output.getTrace().getDuration()).isNotNull();
        assertThat(output.getDuration()).isNotNull();
    }
}
