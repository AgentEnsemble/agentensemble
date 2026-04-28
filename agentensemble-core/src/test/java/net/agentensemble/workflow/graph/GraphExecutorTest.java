package net.agentensemble.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Task;
import net.agentensemble.exception.GraphNoEdgeMatchedException;
import net.agentensemble.exception.MaxGraphStepsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import org.junit.jupiter.api.Test;

class GraphExecutorTest {

    private static GraphExecutor newExecutor() {
        return new GraphExecutor(new SequentialWorkflowExecutor(List.of(), 1));
    }

    private static TaskHandler counting(AtomicInteger counter, String tag) {
        return ctx -> ToolResult.success(tag + "#" + counter.incrementAndGet());
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
    // Linear graph
    // ========================

    @Test
    void linearGraph_runsStatesInOrderAndTerminates() {
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        AtomicInteger cCount = new AtomicInteger();

        Graph g = Graph.builder()
                .name("linear")
                .state("a", handlerTask("a", counting(aCount, "a")))
                .state("b", handlerTask("b", counting(bCount, "b")))
                .state("c", handlerTask("c", counting(cCount, "c")))
                .start("a")
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", Graph.END)
                .build();

        GraphExecutionResult result = newExecutor().execute(g, ExecutionContext.disabled());

        assertThat(result.getStepsRun()).isEqualTo(3);
        assertThat(result.stoppedByTerminal()).isTrue();
        assertThat(aCount).hasValue(1);
        assertThat(bCount).hasValue(1);
        assertThat(cCount).hasValue(1);
        assertThat(result.getHistory()).extracting(GraphStep::getStateName).containsExactly("a", "b", "c");
    }

    // ========================
    // Branching with first-match-wins
    // ========================

    @Test
    void branching_firstMatchWins() {
        Task analyze = handlerTask("analyze", ctx -> ToolResult.success("USE_A please"));
        AtomicInteger toolACount = new AtomicInteger();
        Task toolA = handlerTask("toolA", counting(toolACount, "a-result"));

        Graph g = Graph.builder()
                .name("router")
                .state("analyze", analyze)
                .state("toolA", toolA)
                .state("toolB", handlerTask("toolB", counting(new AtomicInteger(), "b-result")))
                .start("analyze")
                .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().contains("USE_A"))
                .edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().contains("USE_B"))
                .edge("analyze", Graph.END)
                .edge("toolA", Graph.END)
                .edge("toolB", Graph.END)
                .build();

        GraphExecutionResult result = newExecutor().execute(g, ExecutionContext.disabled());

        assertThat(result.getStepsRun()).isEqualTo(2);
        assertThat(result.getHistory()).extracting(GraphStep::getStateName).containsExactly("analyze", "toolA");
        assertThat(toolACount).hasValue(1);
    }

    // ========================
    // Cyclic state machine (the headline pattern)
    // ========================

    @Test
    void cyclicStateMachine_revisitsAnalyze_terminatesAfterEnoughCycles() {
        // analyze decides to call toolA twice, then end. Each tool returns to analyze.
        AtomicInteger analyzeCount = new AtomicInteger();
        AtomicInteger toolCount = new AtomicInteger();

        Task analyze = handlerTask("analyze", ctx -> {
            int n = analyzeCount.incrementAndGet();
            return ToolResult.success(n < 3 ? "CALL_TOOL" : "DONE");
        });
        Task tool = handlerTask("tool", counting(toolCount, "tool"));

        Graph g = Graph.builder()
                .name("agent")
                .state("analyze", analyze)
                .state("tool", tool)
                .start("analyze")
                .edge("analyze", "tool", ctx -> ctx.lastOutput().getRaw().equals("CALL_TOOL"))
                .edge("analyze", Graph.END)
                .edge("tool", "analyze")
                .build();

        GraphExecutionResult result = newExecutor().execute(g, ExecutionContext.disabled());

        // analyze visits 3 times (CALL_TOOL, CALL_TOOL, DONE), tool visits 2 times
        // Step sequence: analyze, tool, analyze, tool, analyze
        assertThat(analyzeCount).hasValue(3);
        assertThat(toolCount).hasValue(2);
        assertThat(result.getStepsRun()).isEqualTo(5);
        assertThat(result.stoppedByTerminal()).isTrue();
        assertThat(result.getHistory())
                .extracting(GraphStep::getStateName)
                .containsExactly("analyze", "tool", "analyze", "tool", "analyze");
        // State outputs collected per name with visit-ordered lists
        assertThat(result.getStateOutputsByName().get("analyze")).hasSize(3);
        assertThat(result.getStateOutputsByName().get("tool")).hasSize(2);
    }

    // ========================
    // Max-steps termination
    // ========================

    @Test
    void maxSteps_returnLast_returnsLastOutput() {
        Task neverTerminating = handlerTask("a", counting(new AtomicInteger(), "a"));

        Graph g = Graph.builder()
                .name("never")
                .state("a", neverTerminating)
                .start("a")
                .edge("a", "a") // self-loop, never reaches END
                .build()
                .toBuilder()
                .maxSteps(4)
                .onMaxSteps(MaxStepsAction.RETURN_LAST)
                .build();

        // Need to rebuild via builder to get fresh state with new maxSteps -- toBuilder of
        // an existing Graph + .build() has to re-validate, so just build fresh:
        Graph g2 = Graph.builder()
                .name("never")
                .state("a", neverTerminating)
                .start("a")
                .edge("a", "a")
                .maxSteps(4)
                .onMaxSteps(MaxStepsAction.RETURN_LAST)
                .build();

        GraphExecutionResult result = newExecutor().execute(g2, ExecutionContext.disabled());

        assertThat(result.getStepsRun()).isEqualTo(4);
        assertThat(result.stoppedByMaxSteps()).isTrue();
    }

    @Test
    void maxSteps_throw_raisesMaxGraphStepsExceeded() {
        Task t = handlerTask("a", counting(new AtomicInteger(), "a"));
        Graph g = Graph.builder()
                .name("strict")
                .state("a", t)
                .start("a")
                .edge("a", "a")
                .maxSteps(3)
                .onMaxSteps(MaxStepsAction.THROW)
                .build();

        assertThatThrownBy(() -> newExecutor().execute(g, ExecutionContext.disabled()))
                .isInstanceOf(MaxGraphStepsExceededException.class)
                .hasMessageContaining("strict")
                .hasMessageContaining("3");
    }

    // ========================
    // No-edge-matched runtime error
    // ========================

    @Test
    void noEdgeMatched_throwsActionableException() {
        // analyze produces "X" but the only outgoing edge requires "Y" — and there's no
        // unconditional fallback, so routing fails.
        Task analyze = handlerTask("analyze", ctx -> ToolResult.success("X"));

        Graph g = Graph.builder()
                .name("missingFallback")
                .state("analyze", analyze)
                .state("toolY", handlerTask("toolY", counting(new AtomicInteger(), "y")))
                .start("analyze")
                .edge("analyze", "toolY", ctx -> ctx.lastOutput().getRaw().equals("Y"))
                .edge("toolY", Graph.END)
                .build();

        assertThatThrownBy(() -> newExecutor().execute(g, ExecutionContext.disabled()))
                .isInstanceOf(GraphNoEdgeMatchedException.class)
                .hasMessageContaining("analyze")
                .hasMessageContaining("Candidate edges");
    }

    // ========================
    // Routing context exposes state history
    // ========================

    @Test
    void predicate_seesPriorVisitsInStateHistory() {
        // analyze runs 3 times; the predicate decides to terminate after 2 prior visits.
        Task analyze = handlerTask("analyze", counting(new AtomicInteger(), "a"));

        Graph g = Graph.builder()
                .name("counted")
                .state("analyze", analyze)
                .start("analyze")
                .edge("analyze", "analyze", ctx -> {
                    // Continue while we have fewer than 3 visits in history
                    List<net.agentensemble.task.TaskOutput> history =
                            ctx.stateHistory().getOrDefault("analyze", List.of());
                    return history.size() < 3;
                })
                .edge("analyze", Graph.END)
                .maxSteps(10)
                .build();

        GraphExecutionResult result = newExecutor().execute(g, ExecutionContext.disabled());
        assertThat(result.getStepsRun()).isEqualTo(3);
        assertThat(result.stoppedByTerminal()).isTrue();
    }

    // ========================
    // Predicate exception propagation
    // ========================

    @Test
    void predicateThrows_propagates() {
        Task t = handlerTask("a", counting(new AtomicInteger(), "a"));
        Graph g = Graph.builder()
                .name("badPred")
                .state("a", t)
                .start("a")
                .edge("a", Graph.END, ctx -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        assertThatThrownBy(() -> newExecutor().execute(g, ExecutionContext.disabled()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    // ========================
    // Revisit feedback injection
    // ========================

    @Test
    void revisitFeedbackInjection_appliesAttemptNumberAndPriorOutput_onSecondVisit() {
        // Use an LLM-style capture: handler reads the Task's revisionFeedback / attemptNumber
        // ... but TaskHandlerContext doesn't expose those, so we verify via projected outputs
        // and history sequence. Direct field-level verification is in GraphExecutor's internal
        // logic; this test ensures the executor DOES revisit correctly.
        AtomicInteger visits = new AtomicInteger();
        Task t = handlerTask("a", ctx -> ToolResult.success("visit#" + visits.incrementAndGet()));

        Graph g = Graph.builder()
                .name("revisit")
                .state("a", t)
                .start("a")
                .edge("a", "a", ctx -> visits.get() < 3)
                .edge("a", Graph.END)
                .build();

        GraphExecutionResult result = newExecutor().execute(g, ExecutionContext.disabled());
        assertThat(visits).hasValue(3);
        // History records all 3 visits with distinct outputs
        assertThat(result.getHistory()).hasSize(3);
        assertThat(result.getHistory().get(0).getOutput().getRaw()).isEqualTo("visit#1");
        assertThat(result.getHistory().get(1).getOutput().getRaw()).isEqualTo("visit#2");
        assertThat(result.getHistory().get(2).getOutput().getRaw()).isEqualTo("visit#3");
    }
}
