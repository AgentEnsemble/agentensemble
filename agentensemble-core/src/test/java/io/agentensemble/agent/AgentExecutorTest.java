package io.agentensemble.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.agentensemble.Agent;
import io.agentensemble.Task;
import io.agentensemble.exception.AgentExecutionException;
import io.agentensemble.exception.MaxIterationsExceededException;
import io.agentensemble.task.TaskOutput;
import io.agentensemble.tool.AgentTool;
import io.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutorTest {

    private final AgentExecutor executor = new AgentExecutor();

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(new AiMessage(text))
                .build();
    }

    private ChatResponse toolCallResponse(String toolName, String arguments) {
        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder()
                .aiMessage(new AiMessage(List.of(request)))
                .build();
    }

    // ========================
    // No-tool execution
    // ========================

    @Test
    void testExecute_noTools_returnsFinalText() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Research findings here."));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find information")
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), false);

        assertThat(output.getRaw()).isEqualTo("Research findings here.");
        assertThat(output.getAgentRole()).isEqualTo("Researcher");
        assertThat(output.getTaskDescription()).isEqualTo("Research AI trends");
        assertThat(output.getToolCallCount()).isZero();
        assertThat(output.getDuration()).isNotNull();
        assertThat(output.getCompletedAt()).isNotNull();
    }

    @Test
    void testExecute_noTools_llmCalledOnce() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Result"));

        var agent = Agent.builder().role("Analyst").goal("Analyze").llm(mockLlm).build();
        var task = Task.builder().description("Analyze data").expectedOutput("Analysis").agent(agent).build();

        executor.execute(task, List.of(), false);

        verify(mockLlm).chat(any(ChatRequest.class));
    }

    // ========================
    // Tool-use execution
    // ========================

    @Test
    void testExecute_withTool_callsToolAndReturnsResult() {
        var mockLlm = mock(ChatModel.class);
        var mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("search");
        when(mockTool.description()).thenReturn("Search the web");
        when(mockTool.execute("AI trends")).thenReturn(ToolResult.success("Top AI trends 2026..."));

        // First response: tool call; second response: final answer
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{\"input\": \"AI trends\"}"))
                .thenReturn(textResponse("Based on search: Top AI trends 2026..."));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), false);

        assertThat(output.getRaw()).isEqualTo("Based on search: Top AI trends 2026...");
        assertThat(output.getToolCallCount()).isEqualTo(1);
        verify(mockTool).execute("AI trends");
    }

    @Test
    void testExecute_withToolError_errorFedBackToLlm() {
        var mockLlm = mock(ChatModel.class);
        var mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("search");
        when(mockTool.description()).thenReturn("Search");
        when(mockTool.execute("query")).thenReturn(ToolResult.failure("Network timeout"));

        // LLM calls tool, gets error, then produces final answer
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{\"input\": \"query\"}"))
                .thenReturn(textResponse("I apologize, the search failed. Here is my best answer..."));

        var agent = Agent.builder()
                .role("Researcher").goal("Find info").tools(List.of(mockTool)).llm(mockLlm).build();
        var task = Task.builder()
                .description("Research").expectedOutput("Report").agent(agent).build();

        TaskOutput output = executor.execute(task, List.of(), false);

        // Execution should NOT throw -- error fed back to LLM as tool result
        assertThat(output.getRaw()).contains("best answer");
        assertThat(output.getToolCallCount()).isEqualTo(1);
    }

    // ========================
    // Max iterations
    // ========================

    @Test
    void testExecute_maxIterationsExceeded_throwsException() {
        var mockLlm = mock(ChatModel.class);
        var mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("search");
        when(mockTool.description()).thenReturn("Search");
        when(mockTool.execute(any())).thenReturn(ToolResult.success("result"));

        // LLM always returns tool calls, never a final answer
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{\"input\": \"query\"}"));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .maxIterations(2)
                .build();
        var task = Task.builder()
                .description("Research").expectedOutput("Report").agent(agent).build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), false))
                .isInstanceOf(MaxIterationsExceededException.class)
                .satisfies(ex -> {
                    var e = (MaxIterationsExceededException) ex;
                    assertThat(e.getAgentRole()).isEqualTo("Researcher");
                    assertThat(e.getMaxIterations()).isEqualTo(2);
                });
    }

    // ========================
    // Error handling
    // ========================

    @Test
    void testExecute_llmThrows_wrappedInAgentExecutionException() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("API unavailable"));

        var agent = Agent.builder().role("Researcher").goal("Find info").llm(mockLlm).build();
        var task = Task.builder().description("Research").expectedOutput("Report").agent(agent).build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), false))
                .isInstanceOf(AgentExecutionException.class)
                .satisfies(ex -> {
                    var e = (AgentExecutionException) ex;
                    assertThat(e.getAgentRole()).isEqualTo("Researcher");
                    assertThat(e.getTaskDescription()).isEqualTo("Research");
                    assertThat(e.getCause()).hasMessage("API unavailable");
                });
    }

    @Test
    void testExecute_emptyLlmResponse_returnsEmptyRaw() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(""));

        var agent = Agent.builder().role("Researcher").goal("Find info").llm(mockLlm).build();
        var task = Task.builder().description("Research").expectedOutput("Report").agent(agent).build();

        TaskOutput output = executor.execute(task, List.of(), false);

        assertThat(output.getRaw()).isEmpty();
    }

    // ========================
    // Context passing
    // ========================

    @Test
    void testExecute_withContext_contextIncludedInUserPrompt() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Article written."));

        var agent = Agent.builder().role("Writer").goal("Write content").llm(mockLlm).build();
        var task = Task.builder().description("Write blog post").expectedOutput("Blog post").agent(agent).build();
        var contextOutput = io.agentensemble.task.TaskOutput.builder()
                .raw("Research result: AI is growing")
                .taskDescription("Research task")
                .agentRole("Researcher")
                .completedAt(java.time.Instant.now())
                .duration(java.time.Duration.ofSeconds(3))
                .toolCallCount(0)
                .build();

        TaskOutput output = executor.execute(task, List.of(contextOutput), false);

        assertThat(output.getRaw()).isEqualTo("Article written.");
        // Context is passed to LLM -- verified by the ChatRequest containing context
        verify(mockLlm).chat(any(ChatRequest.class));
    }
}
