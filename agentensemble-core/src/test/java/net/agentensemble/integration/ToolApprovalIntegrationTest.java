package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for tool-level approval gates.
 *
 * <p>Uses Mockito-mocked LLMs (no real network calls) and programmatic
 * {@link ReviewHandler} implementations to verify end-to-end behaviour.
 *
 * <p>The test structure for each test:
 * <ol>
 *   <li>Mock LLM returns a tool call request on the first invocation</li>
 *   <li>The tool calls requestApproval() with the configured handler</li>
 *   <li>Based on the handler decision, the tool either succeeds, fails, or uses revised content</li>
 *   <li>Mock LLM returns a final text answer on the second invocation</li>
 * </ol>
 */
class ToolApprovalIntegrationTest {

    // ========================
    // Approval-required tool fixture
    // ========================

    /**
     * A simple tool that requires approval before executing.
     * Returns success when approved, failure when rejected.
     */
    static class ApprovalRequiredTool extends AbstractAgentTool {

        private final AtomicInteger approvalCallCount = new AtomicInteger(0);
        private final AtomicInteger executeCallCount = new AtomicInteger(0);

        @Override
        public String name() {
            return "approval_required_tool";
        }

        @Override
        public String description() {
            return "A tool that requires human approval before executing.";
        }

        @Override
        protected ToolResult doExecute(String input) {
            executeCallCount.incrementAndGet();
            if (rawReviewHandler() == null) {
                throw new IllegalStateException(
                        "Tool '" + name() + "' requires approval but no ReviewHandler is configured.");
            }
            approvalCallCount.incrementAndGet();
            ReviewDecision decision = requestApproval("Execute: " + input);
            if (decision instanceof ReviewDecision.ExitEarly) {
                return ToolResult.failure("Execution rejected by reviewer: " + input);
            }
            if (decision instanceof ReviewDecision.Edit edit) {
                return ToolResult.success("Executed with revised input: " + edit.revisedOutput());
            }
            return ToolResult.success("Executed successfully: " + input);
        }

        int approvalCallCount() {
            return approvalCallCount.get();
        }

        int executeCallCount() {
            return executeCallCount.get();
        }
    }

    // ========================
    // Helpers
    // ========================

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private static ChatResponse toolCallResponse(String toolName, String input) {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("req-1")
                .name(toolName)
                .arguments("{\"input\":\"" + input + "\"}")
                .build();
        return ChatResponse.builder()
                .aiMessage(new AiMessage(List.of(toolRequest)))
                .build();
    }

    /**
     * Build an agent with the given tools whose mock LLM first calls the named tool,
     * then returns a final answer.
     */
    private static Agent agentWithToolCall(String toolName, String toolCallInput, String finalAnswer, Object... tools) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse(toolName, toolCallInput))
                .thenReturn(textResponse(finalAnswer));
        return Agent.builder()
                .role("Worker")
                .goal("Complete tasks using the approval-required tool")
                .llm(mockLlm)
                .tools(List.of(tools))
                .build();
    }

    // ========================
    // Handler approves (Continue) -- tool runs, agent produces final answer
    // ========================

    @Test
    void toolApproval_handlerContinue_toolExecutes_agentProducesFinalAnswer() {
        var approvalTool = new ApprovalRequiredTool();
        var agent = agentWithToolCall("approval_required_tool", "do something", "Task completed", approvalTool);
        var task = Task.builder()
                .description("Use the approval tool")
                .expectedOutput("A result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(ReviewHandler.autoApprove())
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Task completed");
        assertThat(approvalTool.approvalCallCount()).isEqualTo(1);
        assertThat(approvalTool.executeCallCount()).isEqualTo(1);
    }

    // ========================
    // Handler rejects (ExitEarly) -- tool returns failure, agent adapts
    // ========================

    @Test
    void toolApproval_handlerExitEarly_toolReturnsFailure_agentAdaptsAndContinues() {
        var approvalTool = new ApprovalRequiredTool();
        var agent = agentWithToolCall(
                "approval_required_tool",
                "do something",
                "I cannot complete this as the action was rejected",
                approvalTool);
        var task = Task.builder()
                .description("Use the approval tool")
                .expectedOutput("A result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> ReviewDecision.exitEarly())
                .build()
                .run();

        // The ensemble completes -- the tool returned failure (not threw ExitEarlyException),
        // so the agent adapts and produces a final answer
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).contains("rejected");
        assertThat(approvalTool.approvalCallCount()).isEqualTo(1);
    }

    // ========================
    // Handler edits -- tool uses revised content
    // ========================

    @Test
    void toolApproval_handlerEdit_toolUsesRevisedContent() {
        var approvalTool = new ApprovalRequiredTool();
        var agent = agentWithToolCall(
                "approval_required_tool", "original input", "Task done with revised content", approvalTool);
        var task = Task.builder()
                .description("Use the approval tool")
                .expectedOutput("A result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> ReviewDecision.edit("reviewer-revised input"))
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(approvalTool.approvalCallCount()).isEqualTo(1);
    }

    // ========================
    // Approval request contains the right context
    // ========================

    @Test
    void toolApproval_reviewRequestContainsToolInput() {
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var approvalTool = new ApprovalRequiredTool();
        var agent = agentWithToolCall("approval_required_tool", "critical operation", "Final answer", approvalTool);
        var task = Task.builder()
                .description("Use the approval tool")
                .expectedOutput("A result")
                .agent(agent)
                .build();

        Ensemble.builder()
                .task(task)
                .reviewHandler(request -> {
                    capturedRequest[0] = request;
                    return ReviewDecision.continueExecution();
                })
                .build()
                .run();

        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].taskDescription()).contains("critical operation");
        assertThat(capturedRequest[0].timing()).isEqualTo(ReviewTiming.DURING_EXECUTION);
    }

    // ========================
    // No review handler + requireApproval check (ISE propagates through stack)
    // ========================

    @Test
    void toolApproval_noHandlerConfigured_toolThrowsIllegalStateException() {
        var approvalTool = new ApprovalRequiredTool();
        var agent = agentWithToolCall(
                "approval_required_tool", "do something", "This answer should never be produced", approvalTool);
        var task = Task.builder()
                .description("Use the approval tool")
                .expectedOutput("A result")
                .agent(agent)
                .build();

        // No reviewHandler configured; the tool throws ISE, which propagates
        // through AbstractAgentTool.execute() and is wrapped in TaskExecutionException
        var ensemble = Ensemble.builder().task(task).build();
        assertThatThrownBy(ensemble::run)
                .isInstanceOf(TaskExecutionException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval");
    }

    // ========================
    // Parallel tool approval: two tools in same turn, both require approval
    // ========================

    @Test
    void parallelToolApproval_twoToolsBothApproved_bothExecuteCorrectly() {
        var tool1 = new ApprovalRequiredTool() {
            @Override
            public String name() {
                return "tool_one";
            }
        };
        var tool2 = new ApprovalRequiredTool() {
            @Override
            public String name() {
                return "tool_two";
            }

            @Override
            public String description() {
                return "Second tool requiring approval.";
            }
        };

        // LLM requests both tools in the same turn, then returns final answer
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
                .id("r1")
                .name("tool_one")
                .arguments("{\"input\":\"input-1\"}")
                .build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
                .id("r2")
                .name("tool_two")
                .arguments("{\"input\":\"input-2\"}")
                .build();
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(List.of(req1, req2)))
                        .build())
                .thenReturn(textResponse("Both tools approved and executed"));

        var agent = Agent.builder()
                .role("Worker")
                .goal("Use both tools")
                .llm(mockLlm)
                .tools(List.of(tool1, tool2))
                .build();
        var task = Task.builder()
                .description("Use both tools in parallel")
                .expectedOutput("Combined result")
                .agent(agent)
                .build();

        AtomicInteger approvalCount = new AtomicInteger(0);
        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .reviewHandler(request -> {
                    approvalCount.incrementAndGet();
                    return ReviewDecision.continueExecution();
                })
                .build()
                .run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(approvalCount.get()).isEqualTo(2);
        assertThat(tool1.approvalCallCount()).isEqualTo(1);
        assertThat(tool2.approvalCallCount()).isEqualTo(1);
    }
}
