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
import net.agentensemble.trace.CapturedMessage;
import net.agentensemble.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code maxToolOutputLength} and {@code toolLogTruncateLength} in
 * {@link AgentExecutor}.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>When {@code maxToolOutputLength >= 0}, the LLM sees a truncated result.</li>
 *   <li>The trace always stores the full, untruncated output regardless of truncation.</li>
 *   <li>When {@code maxToolOutputLength = -1}, the LLM sees the full output.</li>
 * </ul>
 */
class AgentExecutorToolOutputLengthTest {

    private final AgentExecutor executor = new AgentExecutor();

    // ========================
    // Helpers
    // ========================

    private ChatResponse toolCallResponse(String toolName, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(request)).build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /** Build an ExecutionContext with the given truncation settings. STANDARD capture is on so
     * messages are captured in the trace for verification. */
    private ExecutionContext contextWith(int maxToolOutputLength, int toolLogTruncateLength) {
        return ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(),
                Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.STANDARD,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                maxToolOutputLength,
                toolLogTruncateLength);
    }

    /** Execute a task where the tool returns {@code toolOutput}. */
    private TaskOutput runWithToolOutput(String toolOutput, int maxToolOutputLength) {
        return runWithToolOutput(toolOutput, maxToolOutputLength, 200);
    }

    private TaskOutput runWithToolOutput(String toolOutput, int maxToolOutputLength, int toolLogTruncateLength) {
        ChatModel mockLlm = mock(ChatModel.class);
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("search");
        when(mockTool.description()).thenReturn("Search");
        when(mockTool.execute(anyString())).thenReturn(ToolResult.success(toolOutput));
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("search", "{}"))
                .thenReturn(textResponse("Done."));

        var agent = Agent.builder()
                .role("R")
                .goal("G")
                .llm(mockLlm)
                .tools(List.of(mockTool))
                .build();
        var task =
                Task.builder().description("D").expectedOutput("E").agent(agent).build();
        return executor.execute(task, List.of(), contextWith(maxToolOutputLength, toolLogTruncateLength));
    }

    /** Get the captured tool-result message from the second LLM iteration. */
    private String capturedToolResultContent(TaskOutput output) {
        // In STANDARD capture, iteration 1 (0-based) is the second LLM call and contains the
        // ToolExecutionResultMessage that was generated from the tool output.
        List<CapturedMessage> messages =
                output.getTrace().getLlmInteractions().get(1).getMessages();
        return messages.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .map(CapturedMessage::getContent)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool message found in iteration 1"));
    }

    // ========================
    // maxToolOutputLength = -1 (unlimited)
    // ========================

    @Test
    void maxToolOutputLength_unlimited_llmSeesFullOutput() {
        String longOutput = "X".repeat(500);
        TaskOutput output = runWithToolOutput(longOutput, -1);

        assertThat(capturedToolResultContent(output)).isEqualTo(longOutput);
    }

    @Test
    void maxToolOutputLength_unlimited_traceHasFullOutput() {
        String longOutput = "X".repeat(500);
        TaskOutput output = runWithToolOutput(longOutput, -1);

        ToolCallTrace trace =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(trace.getResult()).isEqualTo(longOutput);
    }

    // ========================
    // maxToolOutputLength > 0 (truncation)
    // ========================

    @Test
    void maxToolOutputLength_positive_llmSeesTruncatedOutput() {
        String longOutput = "Y".repeat(500);
        TaskOutput output = runWithToolOutput(longOutput, 100);

        String llmText = capturedToolResultContent(output);
        assertThat(llmText).startsWith("Y".repeat(100));
        assertThat(llmText).contains("[truncated");
        assertThat(llmText.length()).isLessThan(longOutput.length());
    }

    @Test
    void maxToolOutputLength_positive_traceAlwaysHasFullOutput() {
        String longOutput = "Z".repeat(500);
        TaskOutput output = runWithToolOutput(longOutput, 50);

        // Trace is never truncated — developer always sees the complete tool result
        ToolCallTrace trace =
                output.getTrace().getLlmInteractions().get(0).getToolCalls().get(0);
        assertThat(trace.getResult()).isEqualTo(longOutput);
    }

    @Test
    void maxToolOutputLength_equalsOutputLength_noTruncation() {
        String output = "A".repeat(100);
        TaskOutput result = runWithToolOutput(output, 100);

        // Exactly at the limit — no truncation note should appear
        assertThat(capturedToolResultContent(result)).isEqualTo(output);
    }

    @Test
    void maxToolOutputLength_exceedsOutputLength_noTruncation() {
        String shortOutput = "Hello";
        TaskOutput result = runWithToolOutput(shortOutput, 1000);

        assertThat(capturedToolResultContent(result)).isEqualTo(shortOutput);
    }

    @Test
    void maxToolOutputLength_zero_llmSeesEmptyTruncationNote() {
        String output = "Non-empty output";
        TaskOutput result = runWithToolOutput(output, 0);

        // At length=0 the prefix is empty but the truncation note is still appended
        String llmText = capturedToolResultContent(result);
        assertThat(llmText).contains("[truncated");
        assertThat(llmText).doesNotContain("Non-empty output");
    }

    // ========================
    // toolLogTruncateLength branches
    // ========================

    @Test
    void toolLogTruncateLength_negative_unlimitedLogging() {
        // toolLogTruncateLength = -1 hits the maxLength < 0 branch in truncate()
        // The run must complete successfully regardless of log truncation setting
        String output = "A".repeat(500);
        TaskOutput result = runWithToolOutput(output, -1, -1);
        assertThat(result.getRaw()).isEqualTo("Done.");
    }

    @Test
    void toolLogTruncateLength_zero_suppressesLogContent() {
        // toolLogTruncateLength = 0 hits the new maxLength == 0 branch in truncate()
        String output = "B".repeat(100);
        TaskOutput result = runWithToolOutput(output, -1, 0);
        assertThat(result.getRaw()).isEqualTo("Done.");
    }
}
