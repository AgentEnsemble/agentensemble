package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.agent.DeterministicTaskExecutor;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for deterministic (handler-based) tasks exercising the full
 * ensemble execution path -- sequential and parallel workflows.
 */
class DeterministicTaskIntegrationTest {

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(10, 20))
                .build();
    }

    private static ChatModel agentWithResponse(String response) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return model;
    }

    // ========================
    // Sequential: deterministic only
    // ========================

    @Test
    void sequential_singleDeterministicTask_noLlmRequired() {
        // An ensemble of only deterministic tasks does not require a ChatModel
        Task fetchData = Task.builder()
                .description("Fetch stock prices")
                .expectedOutput("JSON prices")
                .handler(ctx -> ToolResult.success("{\"price\": 42.0}"))
                .build();

        // Note: no chatLanguageModel set -- valid because all tasks are deterministic
        EnsembleOutput output = Ensemble.builder()
                .task(fetchData)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("{\"price\": 42.0}");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getAgentRole())
                .isEqualTo(DeterministicTaskExecutor.DETERMINISTIC_ROLE);
        assertThat(output.getTaskOutputs().get(0).getToolCallCount()).isZero();
    }

    @Test
    void sequential_multipleDeterministicTasks_executesInOrder() {
        AtomicInteger executionOrder = new AtomicInteger(0);
        int[] task1Order = {-1};
        int[] task2Order = {-1};

        Task task1 = Task.builder()
                .description("Step 1")
                .expectedOutput("Result 1")
                .handler(ctx -> {
                    task1Order[0] = executionOrder.incrementAndGet();
                    return ToolResult.success("result-1");
                })
                .build();

        Task task2 = Task.builder()
                .description("Step 2")
                .expectedOutput("Result 2")
                .handler(ctx -> {
                    task2Order[0] = executionOrder.incrementAndGet();
                    return ToolResult.success("result-2");
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(task1Order[0]).isLessThan(task2Order[0]);
        assertThat(output.getRaw()).isEqualTo("result-2");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Sequential: mixed AI + deterministic
    // ========================

    @Test
    void sequential_mixed_deterministicThenAi_contextFlowsThrough() {
        // Task 1: deterministic (fetch data)
        Task fetchPrices = Task.builder()
                .description("Fetch current stock prices")
                .expectedOutput("JSON prices")
                .handler(ctx -> ToolResult.success("{\"AAPL\": 175.0, \"MSFT\": 320.0}"))
                .build();

        // Task 2: AI-backed (analyze the data) -- receives Task 1's output as context
        ChatModel analysisModel = agentWithResponse("AAPL and MSFT prices look stable.");
        Task analyzeTask = Task.builder()
                .description("Analyze the stock prices")
                .expectedOutput("A brief analysis")
                .chatLanguageModel(analysisModel)
                .context(List.of(fetchPrices))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(fetchPrices)
                .task(analyzeTask)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("AAPL and MSFT prices look stable.");
        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getAgentRole())
                .isEqualTo(DeterministicTaskExecutor.DETERMINISTIC_ROLE);
    }

    @Test
    void sequential_mixed_aiThenDeterministic_deterministicUsesAiOutput() {
        // Task 1: AI-backed
        ChatModel aiModel = agentWithResponse("The AI returned data: {key: value}");
        Task aiTask = Task.builder()
                .description("Generate some data")
                .expectedOutput("Data")
                .chatLanguageModel(aiModel)
                .build();

        // Task 2: deterministic -- transforms the AI output
        String[] capturedInput = new String[1];
        Task transformTask = Task.builder()
                .description("Transform the data")
                .expectedOutput("Transformed data")
                .context(List.of(aiTask))
                .handler(ctx -> {
                    capturedInput[0] = ctx.contextOutputs().get(0).getRaw();
                    return ToolResult.success(
                            "TRANSFORMED: " + ctx.contextOutputs().get(0).getRaw());
                })
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(aiTask)
                .task(transformTask)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(capturedInput[0]).isEqualTo("The AI returned data: {key: value}");
        assertThat(output.getRaw()).startsWith("TRANSFORMED:");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Parallel: deterministic tasks
    // ========================

    @Test
    void parallel_deterministicWithDependency_contextFlowsThrough() {
        // Root task (no dependency)
        Task fetchPrices = Task.builder()
                .description("Fetch prices")
                .expectedOutput("JSON")
                .handler(ctx -> ToolResult.success("{\"price\": 99.0}"))
                .build();

        // Dependent task (depends on fetchPrices)
        Task formatPrices = Task.builder()
                .description("Format prices")
                .expectedOutput("Formatted prices")
                .context(List.of(fetchPrices))
                .handler(ctx -> ToolResult.success(
                        "Formatted: " + ctx.contextOutputs().get(0).getRaw()))
                .build();

        // Using parallel workflow (auto-inferred from context dependency)
        EnsembleOutput output =
                Ensemble.builder().task(fetchPrices).task(formatPrices).build().run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        // The format task should have processed the fetch task's output
        String lastOutput = output.getTaskOutputs().getLast().getRaw();
        assertThat(lastOutput).contains("{\"price\": 99.0}");
    }

    // ========================
    // Error handling
    // ========================

    @Test
    void sequential_deterministicTaskFails_throwsTaskExecutionException() {
        Task failingTask = Task.builder()
                .description("This task fails")
                .expectedOutput("Never produced")
                .handler(ctx -> ToolResult.failure("Service unavailable: 503"))
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .task(failingTask)
                        .workflow(Workflow.SEQUENTIAL)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Task failed");
    }

    @Test
    void sequential_deterministicTaskThrows_wrappedInTaskExecutionException() {
        Task throwingTask = Task.builder()
                .description("This task throws")
                .expectedOutput("Never produced")
                .handler(ctx -> {
                    throw new RuntimeException("Database connection refused");
                })
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .task(throwingTask)
                        .workflow(Workflow.SEQUENTIAL)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class)
                .hasMessageContaining("Task failed");
    }

    // ========================
    // Guardrails on deterministic tasks
    // ========================

    @Test
    void sequential_deterministicWithInputGuardrail_guardrailReceivesContext() {
        String[] capturedRole = new String[1];

        Task guarded = Task.builder()
                .description("Guarded fetch")
                .expectedOutput("Result")
                .inputGuardrails(List.of(input -> {
                    capturedRole[0] = input.agentRole();
                    return GuardrailResult.success();
                }))
                .handler(ctx -> ToolResult.success("data"))
                .build();

        Ensemble.builder().task(guarded).workflow(Workflow.SEQUENTIAL).build().run();

        assertThat(capturedRole[0]).isEqualTo(DeterministicTaskExecutor.DETERMINISTIC_ROLE);
    }

    // ========================
    // TaskOutput metadata
    // ========================

    @Test
    void sequential_deterministicTask_outputHasCorrectMetadata() {
        Task task = Task.builder()
                .description("Get weather data")
                .expectedOutput("Weather JSON")
                .handler(ctx -> ToolResult.success("{\"temp\": 22}"))
                .build();

        EnsembleOutput output = Ensemble.builder()
                .task(task)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        var taskOutput = output.getTaskOutputs().get(0);
        assertThat(taskOutput.getAgentRole()).isEqualTo(DeterministicTaskExecutor.DETERMINISTIC_ROLE);
        assertThat(taskOutput.getTaskDescription()).isEqualTo("Get weather data");
        assertThat(taskOutput.getToolCallCount()).isZero();
        assertThat(taskOutput.getDuration()).isNotNull();
        assertThat(taskOutput.getCompletedAt()).isNotNull();
        assertThat(taskOutput.getMetrics()).isNotNull();
    }

    // ========================
    // EnsembleValidator: handler task in hierarchical workflow rejected
    // ========================

    @Test
    void hierarchical_withDeterministicTask_throwsValidationException() {
        ChatModel managerModel = agentWithResponse("delegating...");

        Task deterministicTask = Task.builder()
                .description("Deterministic step")
                .expectedOutput("Result")
                .handler(ctx -> ToolResult.success("value"))
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .chatLanguageModel(managerModel)
                        .task(deterministicTask)
                        .workflow(Workflow.HIERARCHICAL)
                        .build()
                        .run())
                .isInstanceOf(net.agentensemble.exception.ValidationException.class)
                .hasMessageContaining("HIERARCHICAL")
                .hasMessageContaining("handler");
    }

    // ========================
    // Callbacks fired for deterministic tasks
    // ========================

    @Test
    void sequential_deterministicTask_callbacksFired() {
        AtomicInteger startCount = new AtomicInteger(0);
        AtomicInteger completeCount = new AtomicInteger(0);

        Task task = Task.builder()
                .description("Callback test")
                .expectedOutput("Result")
                .handler(ctx -> ToolResult.success("output"))
                .build();

        Ensemble.builder()
                .task(task)
                .workflow(Workflow.SEQUENTIAL)
                .onTaskStart(event -> startCount.incrementAndGet())
                .onTaskComplete(event -> completeCount.incrementAndGet())
                .build()
                .run();

        assertThat(startCount.get()).isEqualTo(1);
        assertThat(completeCount.get()).isEqualTo(1);
    }
}
