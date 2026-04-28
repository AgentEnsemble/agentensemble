package net.agentensemble.workflow.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.MaxLoopIterationsExceededException;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for SEQUENTIAL ensembles that mix top-level tasks with one or more loops.
 *
 * <p>All scenarios use deterministic handler tasks so the assertions exercise the executor
 * dispatch and merge logic rather than LLM behaviour. AI-backed body tasks go through the
 * same {@code resolveTasks} / {@code resolveAgents} pipeline as regular tasks.
 */
class EnsembleSequentialLoopTest {

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
    void taskThenLoop_runsTaskFirstThenLoop_outputsAreInOrder() {
        AtomicInteger researchCount = new AtomicInteger();
        AtomicInteger writerCount = new AtomicInteger();

        Task research = handlerTask("research", counting(researchCount, "research"));
        Task writer = handlerTask("writer", counting(writerCount, "draft"));

        Loop reflection =
                Loop.builder().name("reflection").task(writer).maxIterations(3).build();

        EnsembleOutput out =
                Ensemble.builder().task(research).loop(reflection).build().run();

        assertThat(researchCount).hasValue(1);
        assertThat(writerCount).hasValue(3);
        // taskOutputs ordering: research first, then loop's projected writer output
        assertThat(out.getTaskOutputs()).hasSize(2);
        assertThat(out.getTaskOutputs().get(0).getRaw()).isEqualTo("research#1");
        assertThat(out.getTaskOutputs().get(1).getRaw()).isEqualTo("draft#3");
        // Loop history side channel is populated
        assertThat(out.getLoopHistory("reflection")).hasSize(3);
        assertThat(out.getLoopTerminationReason("reflection")).contains("maxIterations");
    }

    @Test
    void multipleLoops_runInDeclarationOrder() {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();

        Loop loopA = Loop.builder()
                .name("loopA")
                .task(handlerTask("workerA", counting(aCount, "a")))
                .maxIterations(2)
                .build();

        Loop loopB = Loop.builder()
                .name("loopB")
                .task(handlerTask("workerB", counting(bCount, "b")))
                .maxIterations(2)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loopA).loop(loopB).build().run();

        assertThat(aCount).hasValue(2);
        assertThat(bCount).hasValue(2);
        assertThat(out.getTaskOutputs()).extracting(o -> o.getRaw()).containsExactly("a#2", "b#2");
        assertThat(out.getLoopHistory("loopA")).hasSize(2);
        assertThat(out.getLoopHistory("loopB")).hasSize(2);
    }

    @Test
    void loopWithMultiTaskBody_predicateApprovesOnIteration2() {
        AtomicInteger writerCount = new AtomicInteger();
        AtomicInteger criticInvocations = new AtomicInteger();

        Task writer = handlerTask("writer", counting(writerCount, "draft"));
        Task critic = handlerTask("critic", ctx -> {
            int n = criticInvocations.incrementAndGet();
            return ToolResult.success(n >= 2 ? "APPROVED" : "REVISE");
        });

        Loop loop = Loop.builder()
                .name("approval")
                .task(writer)
                .task(critic)
                .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
                .maxIterations(5)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loop).build().run();

        assertThat(writerCount).hasValue(2);
        assertThat(criticInvocations).hasValue(2);
        assertThat(out.getLoopTerminationReason("approval")).contains("predicate");
        // Default LAST_ITERATION mode: writer + critic outputs from iteration 2 are projected
        assertThat(out.getTaskOutputs()).hasSize(2);
        assertThat(out.getTaskOutputs().get(1).getRaw()).isEqualTo("APPROVED");
    }

    @Test
    void loopReturnWithFlag_setsFlagOnEnsembleOutput() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));
        Loop loop = Loop.builder()
                .name("nonconverging")
                .task(writer)
                .until(ctx -> false)
                .maxIterations(2)
                .onMaxIterations(MaxIterationsAction.RETURN_WITH_FLAG)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loop).build().run();

        assertThat(out.wasLoopTerminatedByMaxIterations("nonconverging")).isTrue();
        assertThat(out.wasLoopTerminatedByMaxIterations("other")).isFalse();
    }

    @Test
    void loopThrowOnMaxIterations_propagates() {
        Task writer = handlerTask("writer", counting(new AtomicInteger(), "draft"));
        Loop loop = Loop.builder()
                .name("strict")
                .task(writer)
                .until(ctx -> false)
                .maxIterations(2)
                .onMaxIterations(MaxIterationsAction.THROW)
                .build();

        assertThatThrownBy(() -> Ensemble.builder().loop(loop).build().run())
                .isInstanceOf(MaxLoopIterationsExceededException.class)
                .hasMessageContaining("strict");
    }

    @Test
    void duplicateLoopNames_rejectedAtValidation() {
        Loop a = Loop.builder()
                .name("dup")
                .task(handlerTask("x", counting(new AtomicInteger(), "x")))
                .maxIterations(1)
                .build();
        Loop b = Loop.builder()
                .name("dup")
                .task(handlerTask("y", counting(new AtomicInteger(), "y")))
                .maxIterations(1)
                .build();

        assertThatThrownBy(() -> Ensemble.builder().loop(a).loop(b).build().run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate loop name");
    }

    @Test
    void loopAndPhases_rejectedAtValidation() {
        Loop loop = Loop.builder()
                .name("loop")
                .task(handlerTask("x", counting(new AtomicInteger(), "x")))
                .maxIterations(1)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .loop(loop)
                        .phase(net.agentensemble.workflow.Phase.builder()
                                .name("phase1")
                                .task(handlerTask("p", counting(new AtomicInteger(), "p")))
                                .build())
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot mix loop() and phase()");
    }

    @Test
    void loopOnly_runsWithoutTopLevelTasks() {
        AtomicInteger count = new AtomicInteger();
        Loop loop = Loop.builder()
                .name("solo")
                .task(handlerTask("only", counting(count, "x")))
                .maxIterations(3)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loop).build().run();

        assertThat(count).hasValue(3);
        assertThat(out.getLoopHistory("solo")).hasSize(3);
    }

    @Test
    void loopTrace_isPopulatedOnExecutionTrace() {
        Loop loop = Loop.builder()
                .name("traced")
                .task(handlerTask("worker", counting(new AtomicInteger(), "x")))
                .maxIterations(2)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loop).build().run();

        assertThat(out.getTrace()).isNotNull();
        assertThat(out.getTrace().getLoopTraces()).hasSize(1);
        net.agentensemble.trace.LoopTrace lt = out.getTrace().getLoopTraces().get(0);
        assertThat(lt.getLoopName()).isEqualTo("traced");
        assertThat(lt.getIterationsRun()).isEqualTo(2);
        assertThat(lt.getMaxIterations()).isEqualTo(2);
        assertThat(lt.getTerminationReason()).isEqualTo("maxIterations");
        assertThat(lt.getOnMaxIterations()).isEqualTo("RETURN_LAST");
        assertThat(lt.getOutputMode()).isEqualTo("LAST_ITERATION");
        assertThat(lt.getMemoryMode()).isEqualTo("ACCUMULATE");
        assertThat(lt.getIterations()).hasSize(2);
        assertThat(lt.getIterations().get(0)).contains("worker");
    }

    @Test
    void loopIterationCompletedEvent_firedPerIteration() {
        java.util.List<net.agentensemble.callback.LoopIterationCompletedEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        Loop loop = Loop.builder()
                .name("event-test")
                .task(handlerTask("worker", counting(new AtomicInteger(), "x")))
                .maxIterations(3)
                .build();

        Ensemble.builder()
                .loop(loop)
                .onLoopIterationCompleted(events::add)
                .build()
                .run();

        assertThat(events).hasSize(3);
        assertThat(events).extracting(e -> e.iterationNumber()).containsExactly(1, 2, 3);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.loopName()).isEqualTo("event-test");
            assertThat(e.maxIterations()).isEqualTo(3);
            assertThat(e.iterationOutputs()).containsKey("worker");
            assertThat(e.iterationDuration()).isNotNull();
        });
    }

    @Test
    void loopBodyTaskTemplateVariables_resolveAtRunTime() {
        Loop loop = Loop.builder()
                .name("templated")
                .task(Task.builder()
                        .name("worker")
                        .description("Process {topic} for client {client}")
                        .expectedOutput("ok")
                        .handler(ctx -> ToolResult.success(ctx.description()))
                        .build())
                .maxIterations(1)
                .build();

        EnsembleOutput out =
                Ensemble.builder().loop(loop).build().run(java.util.Map.of("topic", "AI", "client", "Acme"));

        // Template variables resolved on the body task's description before execution
        assertThat(out.getLoopHistory("templated").get(0).get("worker").getRaw())
                .isEqualTo("Process AI for client Acme");
    }
}
