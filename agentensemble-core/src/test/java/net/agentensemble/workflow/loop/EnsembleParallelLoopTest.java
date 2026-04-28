package net.agentensemble.workflow.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@code Workflow.PARALLEL} ensembles that include loops.
 *
 * <p>Phase D-1 ordering: tasks execute in parallel via the existing DAG, then loops execute
 * sequentially after the task DAG completes. A future release may lift this by scheduling
 * loops alongside tasks in the dependency graph.
 */
class EnsembleParallelLoopTest {

    private static TaskHandler counting(AtomicInteger c, String tag) {
        return ctx -> ToolResult.success(tag + "#" + c.incrementAndGet());
    }

    private static Task handlerTask(String name, TaskHandler handler) {
        return Task.builder()
                .name(name)
                .description("desc-" + name)
                .expectedOutput("ok")
                .handler(handler)
                .build();
    }

    @Test
    void parallelTasksWithLoop_loopRunsAfterTaskDag() {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        AtomicInteger writerCount = new AtomicInteger();

        Task taskA = handlerTask("a", counting(aCount, "a"));
        Task taskB = handlerTask("b", counting(bCount, "b"));
        Loop reflection = Loop.builder()
                .name("reflection")
                .task(handlerTask("writer", counting(writerCount, "draft")))
                .maxIterations(2)
                .build();

        EnsembleOutput out = Ensemble.builder()
                .workflow(Workflow.PARALLEL)
                .task(taskA)
                .task(taskB)
                .loop(reflection)
                .build()
                .run();

        assertThat(aCount).hasValue(1);
        assertThat(bCount).hasValue(1);
        assertThat(writerCount).hasValue(2);
        assertThat(out.getLoopHistory("reflection")).hasSize(2);
    }

    @Test
    void multipleLoops_inParallelWorkflow_runInDeclarationOrder() {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();

        Loop loopA = Loop.builder()
                .name("loopA")
                .task(handlerTask("a", counting(aCount, "a")))
                .maxIterations(2)
                .build();
        Loop loopB = Loop.builder()
                .name("loopB")
                .task(handlerTask("b", counting(bCount, "b")))
                .maxIterations(3)
                .build();

        EnsembleOutput out = Ensemble.builder()
                .workflow(Workflow.PARALLEL)
                .loop(loopA)
                .loop(loopB)
                .build()
                .run();

        assertThat(aCount).hasValue(2);
        assertThat(bCount).hasValue(3);
        assertThat(out.getLoopHistory("loopA")).hasSize(2);
        assertThat(out.getLoopHistory("loopB")).hasSize(3);
        // Loops execute in declaration order, so projected outputs come after parallel tasks.
        assertThat(out.getTaskOutputs()).extracting(o -> o.getRaw()).containsExactly("a#2", "b#3");
    }

    @Test
    void loopsRunConcurrentlyWithIndependentTasks_underParallelWorkflow() throws Exception {
        // Verify that a Loop with no outer-DAG deps runs in parallel with an independent
        // Task: both should start ~simultaneously, so the wall-clock duration is close to
        // the maximum of either alone, not the sum.
        java.util.concurrent.atomic.AtomicLong taskStart = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong taskEnd = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong loopFirstIterStart = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong loopLastIterEnd = new java.util.concurrent.atomic.AtomicLong();

        Task independentTask = Task.builder()
                .name("independent")
                .description("desc-independent")
                .expectedOutput("ok")
                .handler(ctx -> {
                    taskStart.compareAndSet(0L, System.nanoTime());
                    try {
                        Thread.sleep(150); // simulate work
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    taskEnd.set(System.nanoTime());
                    return ToolResult.success("done");
                })
                .build();

        java.util.concurrent.atomic.AtomicInteger loopIters = new java.util.concurrent.atomic.AtomicInteger();
        Task body = Task.builder()
                .name("body")
                .description("desc-body")
                .expectedOutput("ok")
                .handler(ctx -> {
                    int n = loopIters.incrementAndGet();
                    loopFirstIterStart.compareAndSet(0L, System.nanoTime());
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    loopLastIterEnd.set(System.nanoTime());
                    return ToolResult.success("iter#" + n);
                })
                .build();

        Loop loop =
                Loop.builder().name("background").task(body).maxIterations(3).build();

        long runStart = System.nanoTime();
        EnsembleOutput out = Ensemble.builder()
                .workflow(Workflow.PARALLEL)
                .task(independentTask)
                .loop(loop)
                .build()
                .run();
        long runEnd = System.nanoTime();

        assertThat(loopIters).hasValue(3);
        assertThat(out.getLoopHistory("background")).hasSize(3);

        // Concurrency check: the loop's first iteration must start before the independent
        // task ends -- if the loop ran serially after the task, loopFirstIterStart would
        // be >= taskEnd. Allow a small slack for thread scheduling.
        long taskEndedAt = taskEnd.get();
        long loopStartedAt = loopFirstIterStart.get();
        assertThat(loopStartedAt)
                .as("loop must start before independent task ends -- proves parallel scheduling")
                .isLessThan(taskEndedAt);

        // Wall-clock: the run should complete in roughly max(taskDuration, loopTotalDuration)
        // = max(150ms, 3 * 60ms = 180ms) ~= 180ms, not the serial sum (~330ms). Allow generous
        // slack for CI variance.
        long totalMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(runEnd - runStart);
        assertThat(totalMs)
                .as("PARALLEL+Loop should run concurrently, not serially")
                .isLessThan(300L);
    }

    @Test
    void loopWithOuterContextDep_waitsForUpstreamTaskBeforeRunning() {
        // A Loop whose Loop.context() lists an upstream Task must not start until that task
        // completes -- proving outer-DAG dependencies are honoured by the parallel scheduler.
        java.util.concurrent.atomic.AtomicLong upstreamCompletedAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong loopStartedAt = new java.util.concurrent.atomic.AtomicLong();

        Task upstream = Task.builder()
                .name("upstream")
                .description("desc-upstream")
                .expectedOutput("ok")
                .handler(ctx -> {
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    upstreamCompletedAt.set(System.nanoTime());
                    return ToolResult.success("up-done");
                })
                .build();

        Task body = Task.builder()
                .name("body")
                .description("desc-body")
                .expectedOutput("ok")
                .handler(ctx -> {
                    loopStartedAt.compareAndSet(0L, System.nanoTime());
                    return ToolResult.success("iter");
                })
                .build();

        Loop loop = Loop.builder()
                .name("dependent")
                .task(body)
                .maxIterations(2)
                .context(upstream) // outer-DAG dep: wait for upstream to complete
                .build();

        EnsembleOutput out = Ensemble.builder()
                .workflow(Workflow.PARALLEL)
                .task(upstream)
                .loop(loop)
                .build()
                .run();

        assertThat(out.getLoopHistory("dependent")).hasSize(2);
        // The loop must not start until the upstream task has completed
        assertThat(loopStartedAt.get())
                .as("loop must wait for upstream task before starting first iteration")
                .isGreaterThan(upstreamCompletedAt.get());
    }

    @Test
    void hierarchicalWithLoop_rejectsAtValidation() {
        // HIERARCHICAL doesn't support loops -- verify the validator catches it.
        Loop loop = Loop.builder()
                .name("any")
                .task(handlerTask("x", counting(new AtomicInteger(), "x")))
                .maxIterations(1)
                .build();

        // Need an agent + chatLanguageModel for HIERARCHICAL to even start construction. Use
        // an agent with no LLM and assert the validation fails before resolution starts.
        assertThatThrownBy(() -> Ensemble.builder()
                        .workflow(Workflow.HIERARCHICAL)
                        .task(handlerTask("setup", counting(new AtomicInteger(), "setup")))
                        .loop(loop)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("HIERARCHICAL does not support Loop");
    }
}
