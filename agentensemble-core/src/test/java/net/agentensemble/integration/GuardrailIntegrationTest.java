package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for the guardrail system.
 *
 * Uses mocked LLMs to exercise the full guardrail lifecycle through
 * {@link Ensemble#run()} without requiring real API keys.
 *
 * Verifies that input guardrails block execution before any LLM call,
 * output guardrails block acceptance of a response, that first-failure
 * semantics hold across multiple guardrails, and that TaskFailedEvent
 * callbacks fire correctly when a guardrail violation occurs.
 */
class GuardrailIntegrationTest {

    // ========================
    // Helpers
    // ========================

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent mockAgent(String role, ChatModel llm) {
        return Agent.builder().role(role).goal("Do work").llm(llm).build();
    }

    // ========================
    // Input guardrail -- happy path
    // ========================

    @Test
    void ensemble_inputGuardrailPasses_taskCompletes() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Result from agent"));

        var agent = mockAgent("Analyst", mockLlm);
        var task = Task.builder()
                .description("Analyze trends")
                .expectedOutput("Analysis")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.success()))
                .build();

        EnsembleOutput output =
                Ensemble.builder().agent(agent).task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("Result from agent");
        verify(mockLlm).chat(any(ChatRequest.class));
    }

    // ========================
    // Input guardrail -- violation
    // ========================

    @Test
    void ensemble_inputGuardrailBlocks_throwsTaskExecutionException() {
        var mockLlm = mock(ChatModel.class);

        var agent = mockAgent("Writer", mockLlm);
        var task = Task.builder()
                .description("Write sensitive content")
                .expectedOutput("Content")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("prohibited content in task")))
                .build();

        assertThatThrownBy(
                        () -> Ensemble.builder().agent(agent).task(task).build().run())
                .isInstanceOf(TaskExecutionException.class)
                .cause()
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.INPUT);
                    assertThat(e.getViolationMessage()).isEqualTo("prohibited content in task");
                    assertThat(e.getAgentRole()).isEqualTo("Writer");
                });

        // LLM must never have been called
        verify(mockLlm, never()).chat(any(ChatRequest.class));
    }

    // ========================
    // Output guardrail -- happy path
    // ========================

    @Test
    void ensemble_outputGuardrailPasses_taskCompletes() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Accepted response"));

        var agent = mockAgent("Analyst", mockLlm);
        var task = Task.builder()
                .description("Analyze data")
                .expectedOutput("Analysis")
                .agent(agent)
                .outputGuardrails(List.of(output -> GuardrailResult.success()))
                .build();

        EnsembleOutput output =
                Ensemble.builder().agent(agent).task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("Accepted response");
    }

    // ========================
    // Output guardrail -- violation
    // ========================

    @Test
    void ensemble_outputGuardrailBlocks_throwsTaskExecutionException() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Toxic response"));

        var agent = mockAgent("Writer", mockLlm);
        var task = Task.builder()
                .description("Write an article")
                .expectedOutput("Article")
                .agent(agent)
                .outputGuardrails(List.of(output -> output.rawResponse().contains("Toxic")
                        ? GuardrailResult.failure("response contains prohibited content")
                        : GuardrailResult.success()))
                .build();

        assertThatThrownBy(
                        () -> Ensemble.builder().agent(agent).task(task).build().run())
                .isInstanceOf(TaskExecutionException.class)
                .cause()
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.OUTPUT);
                    assertThat(e.getViolationMessage()).isEqualTo("response contains prohibited content");
                });

        // LLM was called before output guardrail ran
        verify(mockLlm).chat(any(ChatRequest.class));
    }

    // ========================
    // Multi-task: only guarded task is blocked
    // ========================

    @Test
    void ensemble_twoTasks_inputGuardrailBlocksSecondTask_firstCompletes() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("First task done"));

        var agent = mockAgent("Analyst", mockLlm);

        var task1 = Task.builder()
                .description("First task")
                .expectedOutput("Output 1")
                .agent(agent)
                .build();

        var task2 = Task.builder()
                .description("Blocked task")
                .expectedOutput("Output 2")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("blocked")))
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .agent(agent)
                        .tasks(List.of(task1, task2))
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var e = (TaskExecutionException) ex;
                    // First task completed
                    assertThat(e.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(e.getCompletedTaskOutputs().get(0).getRaw()).isEqualTo("First task done");
                    // Second task caused the failure
                    assertThat(e.getTaskDescription()).isEqualTo("Blocked task");
                })
                .cause()
                .isInstanceOf(GuardrailViolationException.class);
    }

    // ========================
    // Multiple guardrails -- first failure wins
    // ========================

    @Test
    void ensemble_multipleInputGuardrails_firstFailureReported() {
        var mockLlm = mock(ChatModel.class);
        var agent = mockAgent("Writer", mockLlm);

        AtomicInteger secondGuardrailCallCount = new AtomicInteger(0);

        var task = Task.builder()
                .description("Write content")
                .expectedOutput("Content")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("first check failed"), input -> {
                    secondGuardrailCallCount.incrementAndGet();
                    return GuardrailResult.failure("second check failed");
                }))
                .build();

        assertThatThrownBy(
                        () -> Ensemble.builder().agent(agent).task(task).build().run())
                .isInstanceOf(TaskExecutionException.class)
                .cause()
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    assertThat(e.getViolationMessage()).isEqualTo("first check failed");
                });

        // Second guardrail must not have been evaluated
        assertThat(secondGuardrailCallCount.get()).isZero();
        verify(mockLlm, never()).chat(any(ChatRequest.class));
    }

    // ========================
    // Callback: TaskFailedEvent fires on guardrail violation
    // ========================

    @Test
    void ensemble_inputGuardrailFails_taskFailedEventFired() {
        var mockLlm = mock(ChatModel.class);
        var agent = mockAgent("Writer", mockLlm);
        var task = Task.builder()
                .description("Write blocked content")
                .expectedOutput("Content")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("not allowed")))
                .build();

        var failedEvents = new java.util.ArrayList<net.agentensemble.callback.TaskFailedEvent>();

        try {
            Ensemble.builder()
                    .agent(agent)
                    .task(task)
                    .onTaskFailed(failedEvents::add)
                    .build()
                    .run();
        } catch (TaskExecutionException ignored) {
            // expected
        }

        assertThat(failedEvents).hasSize(1);
        assertThat(failedEvents.get(0).taskDescription()).isEqualTo("Write blocked content");
        assertThat(failedEvents.get(0).cause()).isInstanceOf(GuardrailViolationException.class);
    }

    // ========================
    // Both input and output guardrails -- input checked first
    // ========================

    @Test
    void ensemble_bothGuardrails_inputCheckedBeforeOutput() {
        var mockLlm = mock(ChatModel.class);
        var agent = mockAgent("Writer", mockLlm);

        var task = Task.builder()
                .description("Blocked by input")
                .expectedOutput("Content")
                .agent(agent)
                .inputGuardrails(List.of(input -> GuardrailResult.failure("input rejected")))
                .outputGuardrails(List.of(output -> GuardrailResult.failure("output rejected")))
                .build();

        assertThatThrownBy(
                        () -> Ensemble.builder().agent(agent).task(task).build().run())
                .isInstanceOf(TaskExecutionException.class)
                .cause()
                .isInstanceOf(GuardrailViolationException.class)
                .satisfies(ex -> {
                    var e = (GuardrailViolationException) ex;
                    // Input violation must be the one thrown (before LLM was called)
                    assertThat(e.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.INPUT);
                    assertThat(e.getViolationMessage()).isEqualTo("input rejected");
                });

        // LLM never called because input guardrail fired first
        verify(mockLlm, never()).chat(any(ChatRequest.class));
    }
}
