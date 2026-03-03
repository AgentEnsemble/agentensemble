package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the callback / event listener system.
 *
 * Verifies listener invocation, exception safety, and all event types.
 */
class EnsembleListenerTest {

    // ========================
    // EnsembleListener defaults
    // ========================

    @Test
    void testDefaultMethods_doNotThrow() {
        EnsembleListener listener = new EnsembleListener() {};

        assertThatCode(() -> listener.onTaskStart(sampleTaskStartEvent(1, 1))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onTaskComplete(sampleTaskCompleteEvent(1, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> listener.onToolCall(sampleToolCallEvent())).doesNotThrowAnyException();
    }

    // ========================
    // TaskStartEvent
    // ========================

    @Test
    void testTaskStartEvent_fieldsAccessible() {
        TaskStartEvent event = new TaskStartEvent("Research AI trends", "Researcher", 1, 3);

        assertThat(event.taskDescription()).isEqualTo("Research AI trends");
        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.taskIndex()).isEqualTo(1);
        assertThat(event.totalTasks()).isEqualTo(3);
    }

    // ========================
    // TaskCompleteEvent
    // ========================

    @Test
    void testTaskCompleteEvent_fieldsAccessible() {
        TaskOutput output = buildTaskOutput("Done");
        TaskCompleteEvent event = new TaskCompleteEvent("Write blog", "Writer", output, Duration.ofSeconds(5), 2, 3);

        assertThat(event.taskDescription()).isEqualTo("Write blog");
        assertThat(event.agentRole()).isEqualTo("Writer");
        assertThat(event.taskOutput()).isEqualTo(output);
        assertThat(event.duration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(event.taskIndex()).isEqualTo(2);
        assertThat(event.totalTasks()).isEqualTo(3);
    }

    // ========================
    // ToolCallEvent
    // ========================

    @Test
    void testToolCallEvent_fieldsAccessible() {
        ToolCallEvent event =
                new ToolCallEvent("search", "{\"q\":\"AI\"}", "Results...", "Researcher", Duration.ofMillis(200));

        assertThat(event.toolName()).isEqualTo("search");
        assertThat(event.toolArguments()).isEqualTo("{\"q\":\"AI\"}");
        assertThat(event.toolResult()).isEqualTo("Results...");
        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.duration()).isEqualTo(Duration.ofMillis(200));
    }

    // ========================
    // ExecutionContext listener invocation
    // ========================

    @Test
    void testExecutionContext_firesTaskStartToAllListeners() {
        List<String> log = new ArrayList<>();

        EnsembleListener l1 = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent e) {
                log.add("l1:" + e.taskDescription());
            }
        };
        EnsembleListener l2 = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent e) {
                log.add("l2:" + e.taskDescription());
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(l1, l2));
        ctx.fireTaskStart(new TaskStartEvent("Research task", "Researcher", 1, 2));

        assertThat(log).containsExactly("l1:Research task", "l2:Research task");
    }

    @Test
    void testExecutionContext_firesTaskCompleteToAllListeners() {
        List<String> log = new ArrayList<>();
        TaskOutput output = buildTaskOutput("Result");

        EnsembleListener l1 = new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent e) {
                log.add("done:" + e.taskDescription());
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(l1));
        ctx.fireTaskComplete(new TaskCompleteEvent("Research task", "Researcher", output, Duration.ofSeconds(1), 1, 1));

        assertThat(log).containsExactly("done:Research task");
    }

    @Test
    void testExecutionContext_firesToolCallToAllListeners() {
        List<String> log = new ArrayList<>();

        EnsembleListener l1 = new EnsembleListener() {
            @Override
            public void onToolCall(ToolCallEvent e) {
                log.add("tool:" + e.toolName());
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(l1));
        ctx.fireToolCall(new ToolCallEvent("search", "{}", "results", "Agent", Duration.ofMillis(50)));

        assertThat(log).containsExactly("tool:search");
    }

    // ========================
    // Exception safety
    // ========================

    @Test
    void testExecutionContext_listenerExceptionInOnTaskStart_doesNotAbortExecution() {
        List<String> log = new ArrayList<>();

        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent e) {
                throw new RuntimeException("listener error");
            }
        };
        EnsembleListener safe = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent e) {
                log.add("safe invoked");
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(throwing, safe));

        // Must not propagate the exception
        assertThatCode(() -> ctx.fireTaskStart(sampleTaskStartEvent(1, 1))).doesNotThrowAnyException();

        // The safe listener after the throwing one must still be called
        assertThat(log).containsExactly("safe invoked");
    }

    @Test
    void testExecutionContext_listenerExceptionInOnTaskComplete_doesNotAbortExecution() {
        List<String> log = new ArrayList<>();

        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent e) {
                throw new RuntimeException("listener error");
            }
        };
        EnsembleListener safe = new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent e) {
                log.add("safe invoked");
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(throwing, safe));

        assertThatCode(() -> ctx.fireTaskComplete(sampleTaskCompleteEvent(1, 1)))
                .doesNotThrowAnyException();
        assertThat(log).containsExactly("safe invoked");
    }

    @Test
    void testExecutionContext_listenerExceptionInOnToolCall_doesNotAbortExecution() {
        List<String> log = new ArrayList<>();

        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onToolCall(ToolCallEvent e) {
                throw new RuntimeException("listener error");
            }
        };
        EnsembleListener safe = new EnsembleListener() {
            @Override
            public void onToolCall(ToolCallEvent e) {
                log.add("safe invoked");
            }
        };

        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled(), List.of(throwing, safe));

        assertThatCode(() -> ctx.fireToolCall(sampleToolCallEvent())).doesNotThrowAnyException();
        assertThat(log).containsExactly("safe invoked");
    }

    @Test
    void testExecutionContext_noListeners_firingEventsDoesNotThrow() {
        ExecutionContext ctx = ExecutionContext.of(false, MemoryContext.disabled());

        assertThatCode(() -> ctx.fireTaskStart(sampleTaskStartEvent(1, 1))).doesNotThrowAnyException();
        assertThatCode(() -> ctx.fireTaskComplete(sampleTaskCompleteEvent(1, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ctx.fireToolCall(sampleToolCallEvent())).doesNotThrowAnyException();
    }

    // ========================
    // Helper methods
    // ========================

    private TaskStartEvent sampleTaskStartEvent(int taskIndex, int totalTasks) {
        return new TaskStartEvent("Sample task", "Agent", taskIndex, totalTasks);
    }

    private TaskCompleteEvent sampleTaskCompleteEvent(int taskIndex, int totalTasks) {
        return new TaskCompleteEvent(
                "Sample task", "Agent", buildTaskOutput("output"), Duration.ofSeconds(1), taskIndex, totalTasks);
    }

    private ToolCallEvent sampleToolCallEvent() {
        return new ToolCallEvent("search", "{}", "results", "Agent", Duration.ofMillis(50));
    }

    private TaskOutput buildTaskOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("Sample task")
                .agentRole("Agent")
                .completedAt(java.time.Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();
    }
}
