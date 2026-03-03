package net.agentensemble.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {

    // ========================
    // Factory: of(mc, verbose, listeners)
    // ========================

    @Test
    void of_withAllArgs_storesMemoryContext() {
        MemoryContext mc = MemoryContext.disabled();
        ExecutionContext ctx = ExecutionContext.of(mc, true, List.of());
        assertThat(ctx.memoryContext()).isSameAs(mc);
    }

    @Test
    void of_withAllArgs_storesVerboseFlag() {
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), true, List.of());
        assertThat(ctx.isVerbose()).isTrue();

        ExecutionContext quiet = ExecutionContext.of(MemoryContext.disabled(), false, List.of());
        assertThat(quiet.isVerbose()).isFalse();
    }

    @Test
    void of_withAllArgs_storesListeners() {
        EnsembleListener listener = new EnsembleListener() {};
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(listener));
        assertThat(ctx.listeners()).containsExactly(listener);
    }

    @Test
    void of_withAllArgs_makesImmutableCopyOfListeners() {
        List<EnsembleListener> mutable = new ArrayList<>();
        EnsembleListener listener = new EnsembleListener() {};
        mutable.add(listener);

        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, mutable);

        // Mutating the original list does not affect the context
        mutable.add(new EnsembleListener() {});
        assertThat(ctx.listeners()).hasSize(1);
    }

    @Test
    void of_withAllArgs_returnedListIsUnmodifiable() {
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, new ArrayList<>());
        assertThatThrownBy(() -> ctx.listeners().add(new EnsembleListener() {}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void of_withAllArgs_nullMemoryContextThrows() {
        assertThatThrownBy(() -> ExecutionContext.of(null, false, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryContext");
    }

    @Test
    void of_withAllArgs_nullListenersThrows() {
        assertThatThrownBy(() -> ExecutionContext.of(MemoryContext.disabled(), false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listeners");
    }

    // ========================
    // Factory: of(mc, verbose)  -- no listeners
    // ========================

    @Test
    void of_withoutListeners_hasEmptyListeners() {
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false);
        assertThat(ctx.listeners()).isEmpty();
    }

    @Test
    void of_withoutListeners_storesFieldsCorrectly() {
        MemoryContext mc = MemoryContext.disabled();
        ExecutionContext ctx = ExecutionContext.of(mc, true);
        assertThat(ctx.memoryContext()).isSameAs(mc);
        assertThat(ctx.isVerbose()).isTrue();
    }

    // ========================
    // Factory: disabled()
    // ========================

    @Test
    void disabled_hasDisabledMemory() {
        ExecutionContext ctx = ExecutionContext.disabled();
        assertThat(ctx.memoryContext().isActive()).isFalse();
    }

    @Test
    void disabled_isNotVerbose() {
        ExecutionContext ctx = ExecutionContext.disabled();
        assertThat(ctx.isVerbose()).isFalse();
    }

    @Test
    void disabled_hasNoListeners() {
        ExecutionContext ctx = ExecutionContext.disabled();
        assertThat(ctx.listeners()).isEmpty();
    }

    // ========================
    // Fire methods: exception safety
    // ========================

    @Test
    void fireTaskStart_callsAllListeners() {
        AtomicInteger callCount = new AtomicInteger(0);
        EnsembleListener l1 = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                callCount.incrementAndGet();
            }
        };
        EnsembleListener l2 = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                callCount.incrementAndGet();
            }
        };

        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(l1, l2));
        TaskStartEvent event = new TaskStartEvent("do something", "Researcher", 1, 3);
        ctx.fireTaskStart(event);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void fireTaskStart_listenerExceptionDoesNotPropagate() {
        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                throw new RuntimeException("listener boom");
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(throwing));
        TaskStartEvent event = new TaskStartEvent("task", "Agent", 1, 1);

        // Must not throw
        ctx.fireTaskStart(event);
    }

    @Test
    void fireTaskStart_subsequentListenersCalledEvenIfOneThrows() {
        AtomicInteger callCount = new AtomicInteger(0);
        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                throw new RuntimeException("boom");
            }
        };
        EnsembleListener counting = new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                callCount.incrementAndGet();
            }
        };

        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(throwing, counting));
        ctx.fireTaskStart(new TaskStartEvent("task", "Agent", 1, 1));

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void fireTaskComplete_callsAllListeners() {
        AtomicInteger callCount = new AtomicInteger(0);
        EnsembleListener l = new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent event) {
                callCount.incrementAndGet();
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(l, l));

        TaskOutput output = TaskOutput.builder()
                .raw("result")
                .taskDescription("do something")
                .agentRole("Agent")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(100))
                .toolCallCount(0)
                .build();
        ctx.fireTaskComplete(new TaskCompleteEvent("do something", "Agent", output, Duration.ofMillis(100), 1, 1));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void fireTaskComplete_listenerExceptionDoesNotPropagate() {
        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent event) {
                throw new RuntimeException("boom");
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(throwing));
        TaskOutput output = TaskOutput.builder()
                .raw("result")
                .taskDescription("task")
                .agentRole("Agent")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(50))
                .toolCallCount(0)
                .build();

        // Must not throw
        ctx.fireTaskComplete(new TaskCompleteEvent("task", "Agent", output, Duration.ofMillis(50), 1, 1));
    }

    @Test
    void fireTaskFailed_callsAllListeners() {
        AtomicInteger callCount = new AtomicInteger(0);
        EnsembleListener l = new EnsembleListener() {
            @Override
            public void onTaskFailed(TaskFailedEvent event) {
                callCount.incrementAndGet();
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(l, l));

        ctx.fireTaskFailed(
                new TaskFailedEvent("task", "Agent", new RuntimeException("fail"), Duration.ofMillis(50), 1, 1));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void fireTaskFailed_listenerExceptionDoesNotPropagate() {
        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onTaskFailed(TaskFailedEvent event) {
                throw new RuntimeException("meta-boom");
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(throwing));

        // Must not throw
        ctx.fireTaskFailed(
                new TaskFailedEvent("task", "Agent", new RuntimeException("original"), Duration.ofMillis(10), 1, 1));
    }

    @Test
    void fireToolCall_callsAllListeners() {
        AtomicInteger callCount = new AtomicInteger(0);
        EnsembleListener l = new EnsembleListener() {
            @Override
            public void onToolCall(ToolCallEvent event) {
                callCount.incrementAndGet();
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(l, l));

        ctx.fireToolCall(new ToolCallEvent("search", "{}", "result", "Researcher", Duration.ofMillis(200)));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void fireToolCall_listenerExceptionDoesNotPropagate() {
        EnsembleListener throwing = new EnsembleListener() {
            @Override
            public void onToolCall(ToolCallEvent event) {
                throw new RuntimeException("boom");
            }
        };
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(throwing));

        // Must not throw
        ctx.fireToolCall(new ToolCallEvent("search", "{}", "result", "Researcher", Duration.ofMillis(100)));
    }
}
