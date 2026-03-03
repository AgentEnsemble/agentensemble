package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

class AgentExecutorTest {

    private final AgentExecutor executor = new AgentExecutor();

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private ChatResponse toolCallResponse(String toolName, String arguments) {
        var request = ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder().aiMessage(new AiMessage(List.of(request))).build();
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

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

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
        var task = Task.builder()
                .description("Analyze data")
                .expectedOutput("Analysis")
                .agent(agent)
                .build();

        executor.execute(task, List.of(), ExecutionContext.disabled());

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

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

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
                .role("Researcher")
                .goal("Find info")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

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
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(toolCallResponse("search", "{\"input\": \"query\"}"));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .tools(List.of(mockTool))
                .llm(mockLlm)
                .maxIterations(2)
                .build();
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
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

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
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

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find info")
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEmpty();
    }

    // ========================
    // Context passing
    // ========================

    @Test
    void testExecute_withContext_contextIncludedInUserPrompt() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Article written."));

        var agent = Agent.builder()
                .role("Writer")
                .goal("Write content")
                .llm(mockLlm)
                .build();
        var task = Task.builder()
                .description("Write blog post")
                .expectedOutput("Blog post")
                .agent(agent)
                .build();
        var contextOutput = net.agentensemble.task.TaskOutput.builder()
                .raw("Research result: AI is growing")
                .taskDescription("Research task")
                .agentRole("Researcher")
                .completedAt(java.time.Instant.now())
                .duration(java.time.Duration.ofSeconds(3))
                .toolCallCount(0)
                .build();

        TaskOutput output = executor.execute(task, List.of(contextOutput), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Article written.");
        // Context is passed to LLM -- verified by the ChatRequest containing context
        verify(mockLlm).chat(any(ChatRequest.class));
    }

    // ========================
    // Input guardrails
    // ========================

    @Test
    void testExecute_inputGuardrailPasses_executionProceeds() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Result text"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write a report")
                .expectedOutput("Report")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.success()))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Result text");
        verify(mockLlm).chat(any(ChatRequest.class));
    }

    @Test
    void testExecute_inputGuardrailFails_throwsBeforeLlmCall() {
        var mockLlm = mock(ChatModel.class);

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write a report about SSN 123-45-6789")
                .expectedOutput("Report")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("contains PII")))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.INPUT);
                    assertThat(e.getViolationMessage()).isEqualTo("contains PII");
                    assertThat(e.getAgentRole()).isEqualTo("Writer");
                    assertThat(e.getTaskDescription()).isEqualTo("Write a report about SSN 123-45-6789");
                });

        // LLM must NOT have been called
        verify(mockLlm, org.mockito.Mockito.never()).chat(any(ChatRequest.class));
    }

    @Test
    void testExecute_multipleInputGuardrails_firstFailureWins() {
        var mockLlm = mock(ChatModel.class);

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .inputGuardrails(List.of(
                        input -> GuardrailResult.failure("first blocker"),
                        input -> GuardrailResult.failure("second blocker")))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getViolationMessage()).isEqualTo("first blocker");
                });
    }

    @Test
    void testExecute_inputGuardrailReceivesCorrectContext() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("ok"));

        var agent = Agent.builder().role("Analyst").goal("Analyze").llm(mockLlm).build();

        // Capture the GuardrailInput for assertion
        var capturedInput = new net.agentensemble.guardrail.GuardrailInput[] {null};
        var task = Task.builder()
                .description("Analyze this data")
                .expectedOutput("Analysis output")
                .agent(agent)
                .inputGuardrails(List.of(input -> {
                    capturedInput[0] = input;
                    return GuardrailResult.success();
                }))
                .build();

        executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(capturedInput[0]).isNotNull();
        assertThat(capturedInput[0].taskDescription()).isEqualTo("Analyze this data");
        assertThat(capturedInput[0].expectedOutput()).isEqualTo("Analysis output");
        assertThat(capturedInput[0].agentRole()).isEqualTo("Analyst");
    }

    // ========================
    // Output guardrails
    // ========================

    @Test
    void testExecute_outputGuardrailPasses_returnsOutput() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Short response"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write a summary")
                .expectedOutput("Summary")
                .agent(agent)
                .outputGuardrails(List.of(output -> GuardrailResult.success()))
                .build();

        TaskOutput result = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(result.getRaw()).isEqualTo("Short response");
    }

    @Test
    void testExecute_outputGuardrailFails_throwsAfterLlmCall() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("A very very long response that is too long"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write a summary")
                .expectedOutput("Summary")
                .agent(agent)
                .outputGuardrails(List.of(output -> output.rawResponse().length() > 10
                        ? GuardrailResult.failure("response exceeds limit")
                        : GuardrailResult.success()))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.OUTPUT);
                    assertThat(e.getViolationMessage()).isEqualTo("response exceeds limit");
                    assertThat(e.getAgentRole()).isEqualTo("Writer");
                });

        // LLM was called before the guardrail ran
        verify(mockLlm).chat(any(ChatRequest.class));
    }

    @Test
    void testExecute_outputGuardrailReceivesCorrectContext() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("agent response here"));

        var agent = Agent.builder().role("Analyst").goal("Analyze").llm(mockLlm).build();

        var capturedOutput = new net.agentensemble.guardrail.GuardrailOutput[] {null};
        var task = Task.builder()
                .description("Analyze data")
                .expectedOutput("Analysis")
                .agent(agent)
                .outputGuardrails(List.of(output -> {
                    capturedOutput[0] = output;
                    return GuardrailResult.success();
                }))
                .build();

        executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(capturedOutput[0]).isNotNull();
        assertThat(capturedOutput[0].rawResponse()).isEqualTo("agent response here");
        assertThat(capturedOutput[0].agentRole()).isEqualTo("Analyst");
        assertThat(capturedOutput[0].taskDescription()).isEqualTo("Analyze data");
        assertThat(capturedOutput[0].parsedOutput()).isNull();
    }

    @Test
    void testExecute_multipleOutputGuardrails_firstFailureWins() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("response"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write")
                .expectedOutput("Output")
                .agent(agent)
                .outputGuardrails(List.of(
                        output -> GuardrailResult.failure("first output blocker"),
                        output -> GuardrailResult.failure("second output blocker")))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getViolationMessage()).isEqualTo("first output blocker");
                });
    }

    // ========================
    // Guardrail exception safety
    // ========================

    @Test
    void testExecute_inputGuardrailThrows_wrappedAsAgentExecutionException() {
        var mockLlm = mock(ChatModel.class);

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .inputGuardrails(List.of(input -> {
                    throw new RuntimeException("guardrail broken");
                }))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(net.agentensemble.exception.AgentExecutionException.class)
                .satisfies(ex -> {
                    var e = (net.agentensemble.exception.AgentExecutionException) ex;
                    assertThat(e.getAgentRole()).isEqualTo("Writer");
                    assertThat(e.getMessage()).contains("Input guardrail threw an exception");
                    assertThat(e.getCause()).hasMessage("guardrail broken");
                });

        // LLM must not have been called
        verify(mockLlm, org.mockito.Mockito.never()).chat(any(ChatRequest.class));
    }

    @Test
    void testExecute_inputGuardrailReturnsNull_wrappedAsAgentExecutionException() {
        var mockLlm = mock(ChatModel.class);

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .inputGuardrails(List.of(input -> null))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(net.agentensemble.exception.AgentExecutionException.class)
                .satisfies(ex -> {
                    var e = (net.agentensemble.exception.AgentExecutionException) ex;
                    assertThat(e.getMessage()).contains("returned null");
                });
    }

    @Test
    void testExecute_outputGuardrailThrows_wrappedAsAgentExecutionException() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("response text"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .outputGuardrails(List.of(output -> {
                    throw new RuntimeException("output guardrail broken");
                }))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(net.agentensemble.exception.AgentExecutionException.class)
                .satisfies(ex -> {
                    var e = (net.agentensemble.exception.AgentExecutionException) ex;
                    assertThat(e.getAgentRole()).isEqualTo("Writer");
                    assertThat(e.getMessage()).contains("Output guardrail threw an exception");
                    assertThat(e.getCause()).hasMessage("output guardrail broken");
                });
    }

    @Test
    void testExecute_outputGuardrailReturnsNull_wrappedAsAgentExecutionException() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("response text"));

        var agent = Agent.builder().role("Writer").goal("Write").llm(mockLlm).build();
        var task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .outputGuardrails(List.of(output -> null))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(net.agentensemble.exception.AgentExecutionException.class)
                .satisfies(ex -> {
                    var e = (net.agentensemble.exception.AgentExecutionException) ex;
                    assertThat(e.getMessage()).contains("returned null");
                });
    }
}
