package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Tests for the EnsembleListener interface default methods and event record field access.
 */
class EnsembleListenerTest {

    // ========================
    // Default method no-ops (must not throw)
    // ========================

    @Test
    void defaultOnTaskStart_doesNotThrow() {
        EnsembleListener listener = new EnsembleListener() {};
        listener.onTaskStart(new TaskStartEvent("task", "Agent", 1, 3));
        // No assertion needed -- absence of exception is the contract
    }

    @Test
    void defaultOnTaskComplete_doesNotThrow() {
        EnsembleListener listener = new EnsembleListener() {};
        TaskOutput output = buildTaskOutput("result");
        listener.onTaskComplete(new TaskCompleteEvent("task", "Agent", output, Duration.ofMillis(100), 1, 1));
    }

    @Test
    void defaultOnTaskFailed_doesNotThrow() {
        EnsembleListener listener = new EnsembleListener() {};
        listener.onTaskFailed(
                new TaskFailedEvent("task", "Agent", new RuntimeException("error"), Duration.ofMillis(50), 1, 1));
    }

    @Test
    void defaultOnToolCall_doesNotThrow() {
        EnsembleListener listener = new EnsembleListener() {};
        listener.onToolCall(new ToolCallEvent("search", "{}", "result", "Researcher", Duration.ofMillis(200)));
    }

    // ========================
    // TaskStartEvent field access
    // ========================

    @Test
    void taskStartEvent_fieldsAccessible() {
        TaskStartEvent event = new TaskStartEvent("do research", "Researcher", 2, 5);
        assertThat(event.taskDescription()).isEqualTo("do research");
        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.taskIndex()).isEqualTo(2);
        assertThat(event.totalTasks()).isEqualTo(5);
    }

    @Test
    void taskStartEvent_recordEqualityOnSameValues() {
        TaskStartEvent a = new TaskStartEvent("task", "Role", 1, 1);
        TaskStartEvent b = new TaskStartEvent("task", "Role", 1, 1);
        assertThat(a).isEqualTo(b);
    }

    // ========================
    // TaskCompleteEvent field access
    // ========================

    @Test
    void taskCompleteEvent_fieldsAccessible() {
        TaskOutput output = buildTaskOutput("analysis done");
        Duration duration = Duration.ofSeconds(5);
        TaskCompleteEvent event = new TaskCompleteEvent("research task", "Researcher", output, duration, 1, 3);
        assertThat(event.taskDescription()).isEqualTo("research task");
        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.taskOutput()).isSameAs(output);
        assertThat(event.duration()).isEqualTo(duration);
        assertThat(event.taskIndex()).isEqualTo(1);
        assertThat(event.totalTasks()).isEqualTo(3);
    }

    // ========================
    // TaskFailedEvent field access
    // ========================

    @Test
    void taskFailedEvent_fieldsAccessible() {
        RuntimeException cause = new RuntimeException("LLM timeout");
        Duration duration = Duration.ofMillis(300);
        TaskFailedEvent event = new TaskFailedEvent("failing task", "Writer", cause, duration, 2, 3);
        assertThat(event.taskDescription()).isEqualTo("failing task");
        assertThat(event.agentRole()).isEqualTo("Writer");
        assertThat(event.cause()).isSameAs(cause);
        assertThat(event.duration()).isEqualTo(duration);
        assertThat(event.taskIndex()).isEqualTo(2);
        assertThat(event.totalTasks()).isEqualTo(3);
    }

    // ========================
    // ToolCallEvent field access
    // ========================

    @Test
    void toolCallEvent_fieldsAccessible() {
        Duration duration = Duration.ofMillis(500);
        ToolCallEvent event = new ToolCallEvent("web_search", "{\"query\":\"AI\"}", "results...", "Researcher", duration);
        assertThat(event.toolName()).isEqualTo("web_search");
        assertThat(event.toolArguments()).isEqualTo("{\"query\":\"AI\"}");
        assertThat(event.toolResult()).isEqualTo("results...");
        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.duration()).isEqualTo(duration);
    }

    // ========================
    // Overriding individual methods
    // ========================

    @Test
    void canOverrideOnTaskStartOnly() {
        AtomicBoolean called = new AtomicBoolean(false);
        EnsembleListener listener = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                called.set(true);
            }
        };

        listener.onTaskStart(new TaskStartEvent("task", "Agent", 1, 1));
        assertThat(called.get()).isTrue();

        // Other default methods still work without override
        listener.onTaskComplete(new TaskCompleteEvent("task", "Agent", buildTaskOutput("done"), Duration.ZERO, 1, 1));
        listener.onTaskFailed(new TaskFailedEvent("task", "Agent", new RuntimeException(), Duration.ZERO, 1, 1));
        listener.onToolCall(new ToolCallEvent("tool", "{}", "result", "Agent", Duration.ZERO));
    }

    // ========================
    // Helpers
    // ========================

    private static TaskOutput buildTaskOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("a task")
                .agentRole("Agent")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(100))
                .toolCallCount(0)
                .build();
    }
}
