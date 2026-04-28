package net.agentensemble.workflow.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Task;
import net.agentensemble.exception.MaxLoopIterationsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import org.junit.jupiter.api.Test;

class LoopExecutorTest {

    private static LoopExecutor newExecutor() {
        return new LoopExecutor(new SequentialWorkflowExecutor(List.of(), 1));
    }

    /**
     * Build an ExecutionContext with the given MemoryStore wired up. The other fields
     * mirror {@link ExecutionContext#disabled()}.
     */
    private static ExecutionContext contextWithStore(MemoryStore store) {
        return ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(),
                Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                store);
    }

    /**
     * Deterministic handler that returns a counter-tagged string each time it runs.
     * Lets tests assert "this body task ran N times".
     */
    private static TaskHandler counting(AtomicInteger counter, String tag) {
        return ctx -> {
            int n = counter.incrementAndGet();
            return ToolResult.success(tag + "#" + n);
        };
    }

    private static Task handlerTask(String name, TaskHandler handler) {
        return Task.builder()
                .name(name)
                .description("desc-" + name)
                .expectedOutput("ok")
                .handler(handler)
                .build();
    }

    // ========================
    // Predicate-driven termination
    // ========================

    @Test
    void predicateStops_onFirstIteration_returnsAfterOneIteration() {
        AtomicInteger writerCount = new AtomicInteger();
        Task writer = handlerTask("writer", counting(writerCount, "draft"));

        Loop loop = Loop.builder()
                .name("oneshot")
                .task(writer)
                .until(ctx -> true) // stop immediately
                .maxIterations(5)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(1);
        assertThat(result.stoppedByPredicate()).isTrue();
        assertThat(result.getHistory()).hasSize(1);
        assertThat(writerCount).hasValue(1);
        assertThat(result.getProjectedOutputs())
                .containsKey(writer)
                .extractingByKey(writer)
                .extracting(o -> o.getRaw())
                .isEqualTo("draft#1");
    }

    @Test
    void predicateStops_atIteration3_runsThreeIterations() {
        AtomicInteger writerCount = new AtomicInteger();
        Task writer = handlerTask("writer", counting(writerCount, "draft"));

        Loop loop = Loop.builder()
                .name("threetimes")
                .task(writer)
                .until(ctx -> ctx.iterationNumber() == 3)
                .maxIterations(10)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(3);
        assertThat(result.stoppedByPredicate()).isTrue();
        assertThat(result.getHistory()).hasSize(3);
        assertThat(writerCount).hasValue(3);
    }

    // ========================
    // Max-iterations termination
    // ========================

    @Test
    void maxIterationsHit_returnLast_returnsLastIterationOutput() {
        AtomicInteger writerCount = new AtomicInteger();
        Task writer = handlerTask("writer", counting(writerCount, "draft"));

        Loop loop = Loop.builder()
                .name("capped")
                .task(writer)
                .until(ctx -> false) // never stops
                .maxIterations(4)
                .onMaxIterations(MaxIterationsAction.RETURN_LAST)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(4);
        assertThat(result.stoppedByMaxIterations()).isTrue();
        assertThat(writerCount).hasValue(4);
        assertThat(result.getProjectedOutputs().get(writer).getRaw()).isEqualTo("draft#4");
    }

    @Test
    void maxIterationsHit_throw_raisesMaxLoopIterationsExceeded() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));

        Loop loop = Loop.builder()
                .name("strict")
                .task(writer)
                .until(ctx -> false)
                .maxIterations(2)
                .onMaxIterations(MaxIterationsAction.THROW)
                .build();

        assertThatThrownBy(() -> newExecutor().execute(loop, ExecutionContext.disabled()))
                .isInstanceOf(MaxLoopIterationsExceededException.class)
                .hasMessageContaining("strict")
                .hasMessageContaining("2");
    }

    @Test
    void noPredicate_runsExactlyMaxIterations() {
        AtomicInteger writerCount = new AtomicInteger();
        Task writer = handlerTask("writer", counting(writerCount, "draft"));

        Loop loop = Loop.builder().name("fixed").task(writer).maxIterations(3).build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(3);
        assertThat(result.stoppedByMaxIterations()).isTrue();
        assertThat(writerCount).hasValue(3);
    }

    // ========================
    // Multi-task body
    // ========================

    @Test
    void twoTaskBody_bothRunPerIteration_inOrder() {
        AtomicInteger writerCount = new AtomicInteger();
        AtomicInteger criticCount = new AtomicInteger();
        Task writer = handlerTask("writer", counting(writerCount, "draft"));
        Task critic = handlerTask("critic", counting(criticCount, "critique"));

        Loop loop = Loop.builder()
                .name("write-critique")
                .task(writer)
                .task(critic)
                .until(ctx -> ctx.iterationNumber() == 2)
                .maxIterations(5)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(2);
        assertThat(writerCount).hasValue(2);
        assertThat(criticCount).hasValue(2);
        // last body output is the critic's output (last task in body declaration order)
        assertThat(result.lastIterationOutputs()).containsKeys("writer", "critic");
        assertThat(result.lastIterationOutputs().get("critic").getRaw()).isEqualTo("critique#2");
    }

    @Test
    void predicate_seesLastBodyOutput_perIteration() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));
        Task critic = handlerTask("critic", ctx -> {
            // returns "APPROVED" on iter 3 (3rd time it runs), else "REVISE"
            // ctx doesn't expose iteration number, so use a closed counter
            return ToolResult.success("verdict");
        });

        // Replace critic handler with one that approves on iteration 3
        AtomicInteger criticInvocations = new AtomicInteger();
        Task criticApproving = handlerTask("critic", ctx -> {
            int n = criticInvocations.incrementAndGet();
            return ToolResult.success(n >= 3 ? "APPROVED" : "REVISE");
        });

        Loop loop = Loop.builder()
                .name("approval")
                .task(writer)
                .task(criticApproving)
                .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
                .maxIterations(10)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getIterationsRun()).isEqualTo(3);
        assertThat(result.stoppedByPredicate()).isTrue();
        assertThat(result.lastIterationOutputs().get("critic").getRaw()).isEqualTo("APPROVED");
    }

    // ========================
    // Output modes
    // ========================

    @Test
    void outputMode_lastIteration_default_projectsLastIterationOutputs() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));

        Loop loop = Loop.builder().name("last").task(writer).maxIterations(3).build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getProjectedOutputs()).containsKey(writer);
        assertThat(result.getProjectedOutputs().get(writer).getRaw()).isEqualTo("draft#3");
    }

    @Test
    void outputMode_finalTaskOnly_projectsOnlyLastBodyTask() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));
        Task critic = handlerTask("critic", counting(new AtomicInteger(), "critique"));

        Loop loop = Loop.builder()
                .name("final-only")
                .task(writer)
                .task(critic)
                .maxIterations(2)
                .outputMode(LoopOutputMode.FINAL_TASK_ONLY)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        assertThat(result.getProjectedOutputs()).hasSize(1).containsKey(critic).doesNotContainKey(writer);
        assertThat(result.getProjectedOutputs().get(critic).getRaw()).isEqualTo("critique#2");
    }

    @Test
    void outputMode_allIterations_concatenatesPerTaskAcrossIterations() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));

        Loop loop = Loop.builder()
                .name("all")
                .task(writer)
                .maxIterations(3)
                .outputMode(LoopOutputMode.ALL_ITERATIONS)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());

        String concatenated = result.getProjectedOutputs().get(writer).getRaw();
        assertThat(concatenated)
                .contains("draft#1", "draft#2", "draft#3", "--- iteration 2 ---", "--- iteration 3 ---");
    }

    // ========================
    // Feedback injection (verified via buildIterationBody directly --
    // TaskHandlerContext does not expose revision fields, only the LLM prompt does)
    // ========================

    @Test
    void buildIterationBody_iteration1_returnsBodyUnchanged() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "x"));
        Loop loop = Loop.builder().name("l").task(writer).maxIterations(3).build();

        List<Task> built = LoopExecutor.buildIterationBody(loop, 1, null);

        assertThat(built).containsExactly(writer);
        assertThat(built.get(0).getRevisionFeedback()).isNull();
        assertThat(built.get(0).getAttemptNumber()).isZero();
    }

    @Test
    void buildIterationBody_iteration2_withFeedback_rebuildsFirstTask() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "x"));
        Task critic = handlerTask("critic", counting(new AtomicInteger(), "y"));
        Loop loop = Loop.builder()
                .name("l")
                .task(writer)
                .task(critic)
                .maxIterations(3)
                .build();

        TaskOutput priorOutput = stubTaskOutput("prior raw output");

        List<Task> built = LoopExecutor.buildIterationBody(loop, 2, priorOutput);

        assertThat(built).hasSize(2);
        Task rebuiltWriter = built.get(0);
        assertThat(rebuiltWriter).isNotSameAs(writer);
        assertThat(rebuiltWriter.getRevisionFeedback()).isNotNull().contains("Loop iteration 2 of 3");
        assertThat(rebuiltWriter.getPriorAttemptOutput()).isEqualTo("prior raw output");
        assertThat(rebuiltWriter.getAttemptNumber()).isEqualTo(1);
        // critic was not the first task, so it stays unchanged (no context to remap here)
        assertThat(built.get(1).getName()).isEqualTo("critic");
    }

    @Test
    void buildIterationBody_iteration2_remapsContextReferencesToRebuiltFirstTask() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "x"));
        Task critic = Task.builder()
                .name("critic")
                .description("critique the draft")
                .expectedOutput("ok")
                .handler(counting(new AtomicInteger(), "y"))
                .context(List.of(writer))
                .build();
        Loop loop = Loop.builder()
                .name("l")
                .task(writer)
                .task(critic)
                .maxIterations(3)
                .build();

        List<Task> built = LoopExecutor.buildIterationBody(loop, 2, stubTaskOutput("prior"));

        Task rebuiltWriter = built.get(0);
        Task rebuiltCritic = built.get(1);
        assertThat(rebuiltCritic.getContext()).hasSize(1);
        assertThat(rebuiltCritic.getContext().get(0))
                .as("critic.context() must be remapped to point at the rebuilt writer instance")
                .isSameAs(rebuiltWriter);
    }

    @Test
    void buildIterationBody_injectFeedbackDisabled_returnsBodyUnchangedForAllIterations() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "x"));
        Loop loop = Loop.builder()
                .name("l")
                .task(writer)
                .maxIterations(3)
                .injectFeedback(false)
                .build();

        List<Task> iter1 = LoopExecutor.buildIterationBody(loop, 1, null);
        List<Task> iter2 = LoopExecutor.buildIterationBody(loop, 2, stubTaskOutput("p"));
        List<Task> iter3 = LoopExecutor.buildIterationBody(loop, 3, stubTaskOutput("p"));

        assertThat(iter1).containsExactly(writer);
        assertThat(iter2).containsExactly(writer);
        assertThat(iter3).containsExactly(writer);
        assertThat(writer.getAttemptNumber()).isZero();
    }

    private static TaskOutput stubTaskOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .agentRole("test")
                .taskDescription("test")
                .completedAt(java.time.Instant.now())
                .duration(java.time.Duration.ZERO)
                .build();
    }

    // ========================
    // Memory mode FRESH_PER_ITERATION
    // ========================

    @Test
    void freshPerIteration_withNoMemoryStore_isNoOp() {
        // FRESH_PER_ITERATION with no MemoryStore on the context just runs normally
        // (consistent with other framework features that silently no-op when no store).
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));

        Loop loop = Loop.builder()
                .name("fresh")
                .task(writer)
                .maxIterations(2)
                .memoryMode(LoopMemoryMode.FRESH_PER_ITERATION)
                .build();

        LoopExecutionResult result = newExecutor().execute(loop, ExecutionContext.disabled());
        assertThat(result.getIterationsRun()).isEqualTo(2);
    }

    @Test
    void freshPerIteration_withInMemoryStore_clearsScopesBetweenIterations() {
        net.agentensemble.memory.MemoryStore store = net.agentensemble.memory.MemoryStore.inMemory();
        // Pre-populate the scope so we can verify it gets cleared between iterations.
        store.store(
                "loopscope",
                net.agentensemble.memory.MemoryEntry.builder()
                        .content("preexisting")
                        .storedAt(java.time.Instant.now())
                        .build());

        AtomicInteger writerCount = new AtomicInteger();
        Task writer = Task.builder()
                .name("writer")
                .description("write")
                .expectedOutput("ok")
                .handler(ctx -> ToolResult.success("draft#" + writerCount.incrementAndGet()))
                .memory("loopscope")
                .build();

        Loop loop = Loop.builder()
                .name("fresh")
                .task(writer)
                .maxIterations(3)
                .memoryMode(LoopMemoryMode.FRESH_PER_ITERATION)
                .build();

        ExecutionContext ctx = contextWithStore(store);
        LoopExecutionResult result = newExecutor().execute(loop, ctx);

        assertThat(result.getIterationsRun()).isEqualTo(3);
        // After loop completion, memory contains only the entries from the LAST iteration --
        // earlier iterations were cleared. (Sequential executor stores the task output into
        // the scope after each task run.)
        var entries = store.retrieve("loopscope", "any", 10);
        // After iteration 3: cleared at start of iter 3, then iteration 3's output stored
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).contains("draft#3");
    }

    @Test
    void window_withInMemoryStore_evictsToWindowSizeBetweenIterations() {
        net.agentensemble.memory.MemoryStore store = net.agentensemble.memory.MemoryStore.inMemory();
        // Pre-populate scope with 5 entries so we can observe eviction.
        for (int i = 0; i < 5; i++) {
            store.store(
                    "winscope",
                    net.agentensemble.memory.MemoryEntry.builder()
                            .content("entry-" + i)
                            .storedAt(java.time.Instant.now())
                            .build());
        }

        AtomicInteger writerCount = new AtomicInteger();
        Task writer = Task.builder()
                .name("writer")
                .description("write")
                .expectedOutput("ok")
                .handler(ctx -> ToolResult.success("draft#" + writerCount.incrementAndGet()))
                .memory("winscope")
                .build();

        Loop loop = Loop.builder()
                .name("win")
                .task(writer)
                .maxIterations(3)
                .memoryMode(LoopMemoryMode.WINDOW)
                .memoryWindowSize(2)
                .build();

        ExecutionContext ctx = contextWithStore(store);
        LoopExecutionResult result = newExecutor().execute(loop, ctx);

        assertThat(result.getIterationsRun()).isEqualTo(3);
        // Eviction fires before iterations 2 and 3, capping the scope at 2 entries each
        // time. After iteration 3 the scope contains the last 2 entries (which include
        // outputs from the body executions; the exact count depends on whether the body
        // task wrote to the scope).
        var entries = store.retrieve("winscope", "any", 100);
        assertThat(entries.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void freshPerIteration_unsupportedStore_throwsActionableException() {
        // Stub store that throws UnsupportedOperationException on clear(),
        // mimicking EmbeddingMemoryStore semantics.
        net.agentensemble.memory.MemoryStore unsupportedClear = new net.agentensemble.memory.MemoryStore() {
            @Override
            public void store(String scope, net.agentensemble.memory.MemoryEntry entry) {}

            @Override
            public List<net.agentensemble.memory.MemoryEntry> retrieve(String scope, String query, int maxResults) {
                return List.of();
            }

            @Override
            public void evict(String scope, net.agentensemble.memory.EvictionPolicy policy) {}

            @Override
            public void clear(String scope) {
                throw new UnsupportedOperationException("backing store does not support clear");
            }
        };

        Task writer = Task.builder()
                .name("writer")
                .description("write")
                .expectedOutput("ok")
                .handler(counting(new AtomicInteger(), "x"))
                .memory("loopscope")
                .build();

        Loop loop = Loop.builder()
                .name("fresh")
                .task(writer)
                .maxIterations(3)
                .memoryMode(LoopMemoryMode.FRESH_PER_ITERATION)
                .build();

        ExecutionContext ctx = contextWithStore(unsupportedClear);
        assertThatThrownBy(() -> newExecutor().execute(loop, ctx))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("FRESH_PER_ITERATION")
                .hasMessageContaining("does not support per-scope clear");
    }

    // ========================
    // Predicate exceptions
    // ========================

    @Test
    void predicateThrows_propagates() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));

        Loop loop = Loop.builder()
                .name("badpredicate")
                .task(writer)
                .until(ctx -> {
                    throw new IllegalStateException("boom");
                })
                .maxIterations(5)
                .build();

        assertThatThrownBy(() -> newExecutor().execute(loop, ExecutionContext.disabled()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }
}
