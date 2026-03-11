package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.task.TaskHandlerContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DeterministicTaskExecutor -- covering the full execution lifecycle
 * including guardrails, memory, structured output, and error paths.
 */
class DeterministicTaskExecutorTest {

    private final DeterministicTaskExecutor executor = new DeterministicTaskExecutor();

    // ========================
    // Happy path
    // ========================

    @Test
    void execute_simpleHandler_returnsTaskOutput() {
        Task task = Task.builder()
                .description("Return hello")
                .expectedOutput("A greeting")
                .handler(ctx -> ToolResult.success("hello world"))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("hello world");
        assertThat(output.getAgentRole()).isEqualTo(DeterministicTaskExecutor.DETERMINISTIC_ROLE);
        assertThat(output.getTaskDescription()).isEqualTo("Return hello");
        assertThat(output.getToolCallCount()).isZero();
        assertThat(output.getDuration()).isNotNull();
        assertThat(output.getCompletedAt()).isNotNull();
    }

    @Test
    void execute_handlerReceivesCorrectContext() {
        TaskHandlerContext[] capturedContext = new TaskHandlerContext[1];

        Task task = Task.builder()
                .description("Capture context")
                .expectedOutput("Result")
                .handler(ctx -> {
                    capturedContext[0] = ctx;
                    return ToolResult.success("ok");
                })
                .build();

        executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(capturedContext[0]).isNotNull();
        assertThat(capturedContext[0].description()).isEqualTo("Capture context");
        assertThat(capturedContext[0].expectedOutput()).isEqualTo("Result");
        assertThat(capturedContext[0].contextOutputs()).isEmpty();
    }

    @Test
    void execute_handlerReceivesPriorTaskOutputs_inContext() {
        TaskOutput priorOutput = TaskOutput.builder()
                .raw("prior data")
                .taskDescription("Prior task")
                .agentRole("(deterministic)")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(5))
                .toolCallCount(0)
                .build();

        TaskHandlerContext[] capturedContext = new TaskHandlerContext[1];
        Task task = Task.builder()
                .description("Process prior data")
                .expectedOutput("Processed")
                .handler(ctx -> {
                    capturedContext[0] = ctx;
                    return ToolResult.success(
                            ctx.contextOutputs().get(0).getRaw().toUpperCase());
                })
                .build();

        TaskOutput output = executor.execute(task, List.of(priorOutput), ExecutionContext.disabled());

        assertThat(capturedContext[0].contextOutputs()).hasSize(1);
        assertThat(output.getRaw()).isEqualTo("PRIOR DATA");
    }

    @Test
    void execute_nullRawOutput_normalizedToEmptyString() {
        // When ToolResult.success("") is returned, output should be empty string
        Task task = Task.builder()
                .description("Empty result")
                .expectedOutput("Nothing")
                .handler(ctx -> ToolResult.success(""))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("");
    }

    // ========================
    // Structured output
    // ========================

    @Test
    void execute_withOutputTypeAndStructuredOutput_setsCorrectly() {
        record MyRecord(String name, int value) {}

        MyRecord typedValue = new MyRecord("test", 42);

        Task task = Task.builder()
                .description("Return structured data")
                .expectedOutput("A record")
                .outputType(MyRecord.class)
                .handler(ctx -> ToolResult.success("{\"name\":\"test\",\"value\":42}", typedValue))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getOutputType()).isEqualTo(MyRecord.class);
        assertThat(output.getParsedOutput(MyRecord.class)).isEqualTo(typedValue);
    }

    @Test
    void execute_withOutputTypeButNoStructuredOutput_parsedOutputIsNull() {
        record MyRecord(String name) {}

        Task task = Task.builder()
                .description("Return raw JSON")
                .expectedOutput("A record")
                .outputType(MyRecord.class)
                .handler(ctx -> ToolResult.success("{\"name\":\"test\"}"))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getOutputType()).isEqualTo(MyRecord.class);
        assertThat(output.getParsedOutput()).isNull();
        assertThat(output.getRaw()).isEqualTo("{\"name\":\"test\"}");
    }

    @Test
    void execute_withoutOutputType_structuredOutputIgnored() {
        Task task = Task.builder()
                .description("Return data")
                .expectedOutput("Data")
                .handler(ctx -> ToolResult.success("text", new Object()))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getOutputType()).isNull();
        assertThat(output.getParsedOutput()).isNull();
        assertThat(output.getRaw()).isEqualTo("text");
    }

    // ========================
    // Error paths
    // ========================

    @Test
    void execute_handlerReturnsFailure_throwsAgentExecutionException() {
        Task task = Task.builder()
                .description("Failing task")
                .expectedOutput("Won't produce output")
                .handler(ctx -> ToolResult.failure("API call failed: 503"))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Deterministic task failed")
                .hasMessageContaining("API call failed: 503");
    }

    @Test
    void execute_handlerThrowsException_throwsAgentExecutionException() {
        Task task = Task.builder()
                .description("Exception task")
                .expectedOutput("Won't produce output")
                .handler(ctx -> {
                    throw new RuntimeException("connection refused");
                })
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("Deterministic task handler threw an exception")
                .hasMessageContaining("connection refused");
    }

    @Test
    void execute_handlerReturnsNull_throwsAgentExecutionException() {
        Task task = Task.builder()
                .description("Null-returning task")
                .expectedOutput("Won't produce output")
                .handler(ctx -> null)
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("handler returned null");
    }

    @Test
    void execute_taskWithNoHandler_throwsIllegalArgumentException() {
        Task task = Task.builder()
                .description("AI task with no handler")
                .expectedOutput("AI output")
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no handler configured");
    }

    // ========================
    // Guardrails
    // ========================

    @Test
    void execute_inputGuardrailPasses_handlerExecutes() {
        Task task = Task.builder()
                .description("Validated task")
                .expectedOutput("Output")
                .inputGuardrails(List.of(input -> GuardrailResult.success()))
                .handler(ctx -> ToolResult.success("executed"))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("executed");
    }

    @Test
    void execute_inputGuardrailFails_throwsGuardrailViolation() {
        Task task = Task.builder()
                .description("Blocked task")
                .expectedOutput("Output")
                .inputGuardrails(List.of(input -> GuardrailResult.failure("input blocked")))
                .handler(ctx -> ToolResult.success("should not be called"))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .hasMessageContaining("input blocked");
    }

    @Test
    void execute_outputGuardrailPasses_outputReturned() {
        Task task = Task.builder()
                .description("Output-validated task")
                .expectedOutput("Output")
                .outputGuardrails(List.of(output -> GuardrailResult.success()))
                .handler(ctx -> ToolResult.success("clean output"))
                .build();

        TaskOutput output = executor.execute(task, List.of(), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("clean output");
    }

    @Test
    void execute_outputGuardrailFails_throwsGuardrailViolation() {
        Task task = Task.builder()
                .description("Output-blocked task")
                .expectedOutput("Output")
                .outputGuardrails(List.of(output -> GuardrailResult.failure("output blocked")))
                .handler(ctx -> ToolResult.success("some output"))
                .build();

        assertThatThrownBy(() -> executor.execute(task, List.of(), ExecutionContext.disabled()))
                .isInstanceOf(GuardrailViolationException.class)
                .hasMessageContaining("output blocked");
    }

    // ========================
    // Null execution context
    // ========================

    @Test
    void execute_nullExecutionContext_usesDisabledContext() {
        Task task = Task.builder()
                .description("Task with null context")
                .expectedOutput("Output")
                .handler(ctx -> ToolResult.success("result"))
                .build();

        // Should not throw -- null context is replaced with ExecutionContext.disabled()
        TaskOutput output = executor.execute(task, List.of(), null);

        assertThat(output.getRaw()).isEqualTo("result");
    }
}
