package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.callback.LlmIterationCompletedEvent;
import net.agentensemble.callback.LlmIterationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HeartbeatEnsembleListener}. */
class HeartbeatEnsembleListenerTest {

    private List<HeartbeatDetail> captured() {
        return new ArrayList<>();
    }

    private HeartbeatEnsembleListener listenerCapturing(List<HeartbeatDetail> sink) {
        return new HeartbeatEnsembleListener(obj -> sink.add((HeartbeatDetail) obj));
    }

    // ========================
    // Constructor
    // ========================

    @Test
    void constructor_nullConsumer_throwsIllegalArgument() {
        assertThatThrownBy(() -> new HeartbeatEnsembleListener(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // onTaskStart
    // ========================

    @Test
    void onTaskStart_emitsTaskStartedEventType() {
        var sink = captured();
        listenerCapturing(sink).onTaskStart(new TaskStartEvent("Research AI", "Researcher", 1, 3));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("task_started");
    }

    @Test
    void onTaskStart_populatesDescriptionAndTaskIndex() {
        var sink = captured();
        listenerCapturing(sink).onTaskStart(new TaskStartEvent("Research AI", "Researcher", 2, 5));

        var detail = sink.get(0);
        assertThat(detail.description()).isEqualTo("Research AI");
        assertThat(detail.taskIndex()).isEqualTo(2);
        assertThat(detail.iteration()).isNull();
    }

    // ========================
    // onTaskComplete
    // ========================

    @Test
    void onTaskComplete_emitsTaskCompletedEventType() {
        var sink = captured();
        listenerCapturing(sink)
                .onTaskComplete(new TaskCompleteEvent("Research AI", "Researcher", null, Duration.ofSeconds(5), 1, 3));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("task_completed");
    }

    @Test
    void onTaskComplete_populatesDescriptionAndTaskIndex() {
        var sink = captured();
        listenerCapturing(sink)
                .onTaskComplete(new TaskCompleteEvent("Write article", "Writer", null, Duration.ofSeconds(10), 3, 5));

        var detail = sink.get(0);
        assertThat(detail.description()).isEqualTo("Write article");
        assertThat(detail.taskIndex()).isEqualTo(3);
        assertThat(detail.iteration()).isNull();
    }

    // ========================
    // onTaskFailed
    // ========================

    @Test
    void onTaskFailed_emitsTaskFailedEventType() {
        var sink = captured();
        listenerCapturing(sink)
                .onTaskFailed(new TaskFailedEvent("Research AI", "Researcher", null, Duration.ofSeconds(2), 1, 3));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("task_failed");
    }

    @Test
    void onTaskFailed_withCause_appendsCauseMessageToDescription() {
        var sink = captured();
        var cause = new RuntimeException("LLM timeout");
        listenerCapturing(sink)
                .onTaskFailed(new TaskFailedEvent("Research AI", "Researcher", cause, Duration.ofSeconds(2), 1, 3));

        assertThat(sink.get(0).description()).contains("Research AI").contains("LLM timeout");
    }

    @Test
    void onTaskFailed_withNullCause_usesTaskDescriptionOnly() {
        var sink = captured();
        listenerCapturing(sink)
                .onTaskFailed(new TaskFailedEvent("Research AI", "Researcher", null, Duration.ofSeconds(2), 1, 3));

        assertThat(sink.get(0).description()).isEqualTo("Research AI");
    }

    // ========================
    // onToolCall
    // ========================

    @Test
    void onToolCall_emitsToolCallEventType() {
        var sink = captured();
        listenerCapturing(sink)
                .onToolCall(new ToolCallEvent(
                        "web-search", "{}", "results", null, "Researcher", Duration.ofMillis(300), 2, "SUCCESS"));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("tool_call");
    }

    @Test
    void onToolCall_populatesToolNameAndTaskIndex() {
        var sink = captured();
        listenerCapturing(sink)
                .onToolCall(new ToolCallEvent(
                        "datetime", "{}", "2026-04-09", null, "Researcher", Duration.ofMillis(50), 1, "SUCCESS"));

        var detail = sink.get(0);
        assertThat(detail.description()).isEqualTo("datetime");
        assertThat(detail.taskIndex()).isEqualTo(1);
        assertThat(detail.iteration()).isNull();
    }

    // ========================
    // onLlmIterationStarted
    // ========================

    @Test
    void onLlmIterationStarted_emitsIterationStartedEventType() {
        var sink = captured();
        listenerCapturing(sink)
                .onLlmIterationStarted(new LlmIterationStartedEvent("Researcher", "Research AI", 0, List.of()));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("iteration_started");
    }

    @Test
    void onLlmIterationStarted_populatesAgentRoleAndIterationIndex() {
        var sink = captured();
        listenerCapturing(sink)
                .onLlmIterationStarted(new LlmIterationStartedEvent("Writer", "Write article", 2, List.of()));

        var detail = sink.get(0);
        assertThat(detail.description()).isEqualTo("Writer");
        assertThat(detail.iteration()).isEqualTo(2);
        assertThat(detail.taskIndex()).isNull();
    }

    // ========================
    // onLlmIterationCompleted
    // ========================

    @Test
    void onLlmIterationCompleted_emitsIterationCompletedEventType() {
        var sink = captured();
        listenerCapturing(sink)
                .onLlmIterationCompleted(new LlmIterationCompletedEvent(
                        "Researcher",
                        "Research AI",
                        1,
                        "FINAL_ANSWER",
                        "The answer",
                        List.of(),
                        100,
                        50,
                        Duration.ofMillis(500)));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).eventType()).isEqualTo("iteration_completed");
    }

    @Test
    void onLlmIterationCompleted_populatesIterationIndex() {
        var sink = captured();
        listenerCapturing(sink)
                .onLlmIterationCompleted(new LlmIterationCompletedEvent(
                        "Writer",
                        "Write article",
                        3,
                        "FINAL_ANSWER",
                        "Done",
                        List.of(),
                        200,
                        80,
                        Duration.ofMillis(750)));

        assertThat(sink.get(0).iteration()).isEqualTo(3);
    }

    // ========================
    // Exception safety
    // ========================

    @Test
    void consumerException_isCaughtAndDoesNotAbortExecution() {
        // Consumer throws; the listener must swallow it (EnsembleListener contract).
        var listener = new HeartbeatEnsembleListener(obj -> {
            throw new RuntimeException("consumer failure");
        });

        // None of these should propagate the exception.
        listener.onTaskStart(new TaskStartEvent("Task", "Agent", 1, 1));
        listener.onToolCall(
                new ToolCallEvent("tool", "{}", "result", null, "Agent", Duration.ofMillis(10), 1, "SUCCESS"));
        listener.onLlmIterationStarted(new LlmIterationStartedEvent("Agent", "Task", 0, List.of()));
    }
}
