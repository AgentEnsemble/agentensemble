package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.Executors;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.LlmInteraction;
import net.agentensemble.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for AgentExecutor verifying CaptureMode behavior:
 * - OFF: no message history, no parsedInput
 * - STANDARD: message history captured per iteration
 * - FULL: message history + parsedInput populated
 */
class AgentExecutorCaptureModeTest {

    private final AgentExecutor executor = new AgentExecutor();

    // ========================
    // Helpers
    // ========================

    private ExecutionContext contextWithCaptureMode(CaptureMode captureMode) {
        return ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(),
                Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                captureMode);
    }

    /**
     * Builds a mock ChatResponse with a single tool call request.
     */
    private ChatResponse toolCallResponse(String toolName, String arguments) {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /**
     * Builds an Agent with a mock LLM that requests one tool call then produces a final answer.
     * The tool is a mock that returns "tool result" for any input.
     */
    private Agent agentWithToolCallThenFinalAnswer(String toolName, String toolArguments) {
        ChatModel llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse(toolName, toolArguments))
                .thenReturn(textResponse("The final answer."));

        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(toolName);
        when(tool.description()).thenReturn("Test tool");
        when(tool.execute(anyString())).thenReturn(ToolResult.success("tool result"));

        return Agent.builder()
                .role("Worker")
                .goal("Complete task")
                .llm(llm)
                .tools(List.of(tool))
                .build();
    }

    private Agent agentWithFinalAnswer(String response) {
        ChatModel llm = mock(ChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role("Worker").goal("Complete task").llm(llm).build();
    }

    // ========================
    // CaptureMode.OFF (default)
    // ========================

    @Test
    void off_noToolCalls_messagesEmptyInTrace() {
        Agent agent = agentWithFinalAnswer("Done.");
        Task task = Task.builder()
                .description("Do something")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.OFF));

        LlmInteraction interaction = output.getTrace().getLlmInteractions().get(0);
        assertThat(interaction.getMessages()).isEmpty();
    }

    @Test
    void off_withToolCall_messagesEmptyAndParsedInputNull() {
        Agent agent = agentWithToolCallThenFinalAnswer("no_args_tool", "{}");
        Task task = Task.builder()
                .description("Use the tool")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.OFF));

        // Verify no messages in any iteration
        output.getTrace().getLlmInteractions().forEach(i -> assertThat(i.getMessages())
                .isEmpty());

        // Verify parsedInput is null on tool call trace
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNull();
    }

    // ========================
    // CaptureMode.STANDARD
    // ========================

    @Test
    void standard_noToolCalls_messagesPopulatedInTrace() {
        Agent agent = agentWithFinalAnswer("Done.");
        Task task = Task.builder()
                .description("Do something")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.STANDARD));

        LlmInteraction interaction = output.getTrace().getLlmInteractions().get(0);
        assertThat(interaction.getMessages()).isNotEmpty();
        // Should have at least system + user messages
        assertThat(interaction.getMessages().stream().anyMatch(m -> "system".equals(m.getRole())))
                .isTrue();
        assertThat(interaction.getMessages().stream().anyMatch(m -> "user".equals(m.getRole())))
                .isTrue();
    }

    @Test
    void standard_withToolCall_messagesPopulatedEachIteration() {
        Agent agent = agentWithToolCallThenFinalAnswer("test_tool", "{\"key\":\"value\"}");
        Task task = Task.builder()
                .description("Use the tool")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.STANDARD));

        assertThat(output.getTrace().getLlmInteractions()).hasSize(2);

        // First iteration (tool call): system + user
        LlmInteraction firstIter = output.getTrace().getLlmInteractions().get(0);
        assertThat(firstIter.getMessages()).isNotEmpty();

        // Second iteration (final answer): system + user + assistant(tool call) + tool result
        LlmInteraction secondIter = output.getTrace().getLlmInteractions().get(1);
        assertThat(secondIter.getMessages()).isNotEmpty();
        // Second iteration has a growing history -- more messages than first
        assertThat(secondIter.getMessages().size())
                .isGreaterThan(firstIter.getMessages().size());
    }

    @Test
    void standard_withToolCall_parsedInputStillNull() {
        Agent agent = agentWithToolCallThenFinalAnswer("test_tool", "{\"query\":\"AI\"}");
        Task task = Task.builder()
                .description("Use the tool")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.STANDARD));

        // STANDARD does not populate parsedInput -- that's FULL only
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNull();
    }

    // ========================
    // CaptureMode.FULL
    // ========================

    @Test
    void full_withToolCall_messagesPopulatedAndParsedInputPopulated() {
        Agent agent = agentWithToolCallThenFinalAnswer("search", "{\"query\":\"AI agents\",\"count\":5}");
        Task task = Task.builder()
                .description("Search for things")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.FULL));

        LlmInteraction firstIter = output.getTrace().getLlmInteractions().get(0);

        // Messages present (FULL >= STANDARD)
        assertThat(firstIter.getMessages()).isNotEmpty();

        // parsedInput populated (FULL)
        ToolCallTrace toolCall = firstIter.getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNotNull();
        assertThat(toolCall.getParsedInput()).containsKey("query");
        assertThat(toolCall.getParsedInput()).containsKey("count");
        assertThat(toolCall.getParsedInput().get("query")).isEqualTo("AI agents");
    }

    @Test
    void full_withEmptyToolArguments_parsedInputIsEmptyMap() {
        Agent agent = agentWithToolCallThenFinalAnswer("no_args", "{}");
        Task task = Task.builder()
                .description("Run tool")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.FULL));

        // {} parses to an empty map, which is non-null
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNotNull();
        assertThat(toolCall.getParsedInput()).isEmpty();
    }

    @Test
    void full_withNullToolArguments_parsedInputIsEmptyMap() {
        // Previously parseArguments(null) returned null; now returns Collections.emptyMap().
        Agent agent = agentWithToolCallThenFinalAnswer("null_args_tool", null);
        Task task = Task.builder()
                .description("Run tool with null args")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.FULL));

        // null args should produce an empty map (not null) in parsedInput
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNotNull();
        assertThat(toolCall.getParsedInput()).isEmpty();
    }

    @Test
    void full_withBlankToolArguments_parsedInputIsEmptyMap() {
        // Previously parseArguments("") returned null; now returns Collections.emptyMap().
        Agent agent = agentWithToolCallThenFinalAnswer("blank_args_tool", "   ");
        Task task = Task.builder()
                .description("Run tool with blank args")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.FULL));

        // blank args should produce an empty map (not null) in parsedInput
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNotNull();
        assertThat(toolCall.getParsedInput()).isEmpty();
    }

    @Test
    void full_withInvalidJsonToolArguments_parsedInputIsEmptyMap() {
        // Previously parseArguments("not json") returned null; now returns Collections.emptyMap().
        Agent agent = agentWithToolCallThenFinalAnswer("bad_args_tool", "not valid json");
        Task task = Task.builder()
                .description("Run tool with bad args")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        TaskOutput output = executor.execute(task, List.of(), contextWithCaptureMode(CaptureMode.FULL));

        // invalid JSON should produce an empty map (not null) in parsedInput
        ToolCallTrace toolCall =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(toolCall.getParsedInput()).isNotNull();
        assertThat(toolCall.getParsedInput()).isEmpty();
    }
}
