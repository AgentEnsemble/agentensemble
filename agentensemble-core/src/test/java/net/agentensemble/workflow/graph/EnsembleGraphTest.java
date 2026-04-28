package net.agentensemble.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.GraphStateCompletedEvent;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for {@code Ensemble.builder().graph(...)}: full pipeline including
 * template resolution, agent synthesis (skipped for handler tasks), dispatch via
 * {@code GraphExecutor}, and {@code EnsembleOutput} side-channel population.
 */
class EnsembleGraphTest {

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

    // ========================
    // Headline scenario: tool router with back-edges
    // ========================

    @Test
    void toolRouter_withBackEdges_runsToConvergence() {
        AtomicInteger analyzeCalls = new AtomicInteger();
        Task analyze = handlerTask("analyze", ctx -> {
            int n = analyzeCalls.incrementAndGet();
            String verdict = (n < 3) ? "USE_TOOL" : "DONE";
            return ToolResult.success(verdict);
        });
        Task tool = handlerTask("tool", counting(new AtomicInteger(), "tool"));

        Graph router = Graph.builder()
                .name("agent")
                .state("analyze", analyze)
                .state("tool", tool)
                .start("analyze")
                .edge("analyze", "tool", ctx -> ctx.lastOutput().getRaw().equals("USE_TOOL"))
                .edge("analyze", Graph.END)
                .edge("tool", "analyze")
                .build();

        EnsembleOutput out = Ensemble.builder().graph(router).build().run();

        assertThat(out.getGraphHistory()).hasSize(5); // analyze, tool, analyze, tool, analyze
        assertThat(out.getGraphHistory())
                .extracting(GraphStep::getStateName)
                .containsExactly("analyze", "tool", "analyze", "tool", "analyze");
        assertThat(out.getGraphTerminationReason()).contains("terminal");
        assertThat(out.getRaw()).isEqualTo("DONE");
    }

    // ========================
    // Selective feedback edge
    // ========================

    @Test
    void selectiveFeedback_critiqueRoutesBackToWriteOnFailure() {
        // research → write → critique → publish
        //                       ^_________|  on REJECT
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicInteger critiqueCalls = new AtomicInteger();

        Task research = handlerTask("research", ctx -> ToolResult.success("research findings"));
        Task write = handlerTask("write", ctx -> ToolResult.success("draft#" + writeCalls.incrementAndGet()));
        Task critique = handlerTask("critique", ctx -> {
            int n = critiqueCalls.incrementAndGet();
            return ToolResult.success(n < 2 ? "REJECT" : "APPROVE");
        });
        Task publish = handlerTask("publish", ctx -> ToolResult.success("PUBLISHED"));

        Graph g = Graph.builder()
                .name("pipeline")
                .state("research", research)
                .state("write", write)
                .state("critique", critique)
                .state("publish", publish)
                .start("research")
                .edge("research", "write")
                .edge("write", "critique")
                // critique routes selectively: back to write on REJECT, forward to publish on APPROVE
                .edge("critique", "write", ctx -> ctx.lastOutput().getRaw().equals("REJECT"))
                .edge("critique", "publish")
                .edge("publish", Graph.END)
                .build();

        EnsembleOutput out = Ensemble.builder().graph(g).build().run();

        // Steps: research, write#1, critique=REJECT, write#2, critique=APPROVE, publish
        assertThat(writeCalls).hasValue(2);
        assertThat(critiqueCalls).hasValue(2);
        assertThat(out.getGraphHistory())
                .extracting(GraphStep::getStateName)
                .containsExactly("research", "write", "critique", "write", "critique", "publish");
        assertThat(out.getRaw()).isEqualTo("PUBLISHED");
    }

    // ========================
    // Output / state accessors
    // ========================

    @Test
    void taskOutputs_populatedInExecutionOrder() {
        Task a = handlerTask("a", ctx -> ToolResult.success("A"));
        Task b = handlerTask("b", ctx -> ToolResult.success("B"));

        Graph g = Graph.builder()
                .name("g")
                .state("a", a)
                .state("b", b)
                .start("a")
                .edge("a", "b")
                .edge("b", Graph.END)
                .build();

        EnsembleOutput out = Ensemble.builder().graph(g).build().run();

        assertThat(out.getTaskOutputs()).hasSize(2);
        assertThat(out.getTaskOutputs().get(0).getRaw()).isEqualTo("A");
        assertThat(out.getTaskOutputs().get(1).getRaw()).isEqualTo("B");
        // Identity index uses the original state Task instances
        assertThat(out.getOutput(a).orElseThrow().getRaw()).isEqualTo("A");
        assertThat(out.getOutput(b).orElseThrow().getRaw()).isEqualTo("B");
    }

    // ========================
    // Listener event
    // ========================

    @Test
    void onGraphStateCompleted_firedPerStep() {
        java.util.List<GraphStateCompletedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        Task a = handlerTask("a", ctx -> ToolResult.success("A"));
        Graph g = Graph.builder()
                .name("evt")
                .state("a", a)
                .start("a")
                .edge("a", Graph.END)
                .build();

        Ensemble.builder().graph(g).onGraphStateCompleted(events::add).build().run();

        assertThat(events).hasSize(1);
        GraphStateCompletedEvent e = events.get(0);
        assertThat(e.graphName()).isEqualTo("evt");
        assertThat(e.stateName()).isEqualTo("a");
        assertThat(e.stepNumber()).isEqualTo(1);
        assertThat(e.nextState()).isEqualTo(Graph.END);
        assertThat(e.maxSteps()).isEqualTo(Graph.DEFAULT_MAX_STEPS);
        assertThat(e.stepDuration()).isNotNull();
    }

    // ========================
    // Mutual exclusion validation
    // ========================

    @Test
    void graphAndTask_rejectedAtValidation() {
        Task t = handlerTask("t", counting(new AtomicInteger(), "t"));
        Graph g = Graph.builder()
                .name("g")
                .state("a", handlerTask("a", counting(new AtomicInteger(), "a")))
                .start("a")
                .edge("a", Graph.END)
                .build();

        assertThatThrownBy(() -> Ensemble.builder().task(t).graph(g).build().run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot mix graph()");
    }

    @Test
    void graphAndLoop_rejectedAtValidation() {
        net.agentensemble.workflow.loop.Loop loop = net.agentensemble.workflow.loop.Loop.builder()
                .name("loop")
                .task(handlerTask("body", counting(new AtomicInteger(), "body")))
                .maxIterations(1)
                .build();
        Graph g = Graph.builder()
                .name("g")
                .state("a", handlerTask("a", counting(new AtomicInteger(), "a")))
                .start("a")
                .edge("a", Graph.END)
                .build();

        assertThatThrownBy(() -> Ensemble.builder().loop(loop).graph(g).build().run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot mix graph()");
    }

    @Test
    void graphAndPhase_rejectedAtValidation() {
        Graph g = Graph.builder()
                .name("g")
                .state("a", handlerTask("a", counting(new AtomicInteger(), "a")))
                .start("a")
                .edge("a", Graph.END)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .graph(g)
                        .phase(net.agentensemble.workflow.Phase.builder()
                                .name("p")
                                .task(handlerTask("p", counting(new AtomicInteger(), "p")))
                                .build())
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cannot mix graph()");
    }

    @Test
    void graphAndHierarchical_rejectedAtValidation() {
        Graph g = Graph.builder()
                .name("g")
                .state("a", handlerTask("a", counting(new AtomicInteger(), "a")))
                .start("a")
                .edge("a", Graph.END)
                .build();

        assertThatThrownBy(() -> Ensemble.builder()
                        .graph(g)
                        .workflow(net.agentensemble.workflow.Workflow.HIERARCHICAL)
                        .build()
                        .run())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("HIERARCHICAL does not support a Graph");
    }

    // ========================
    // Template variables resolved on state Tasks
    // ========================

    @Test
    void stateTask_templateVariables_resolveAtRunTime() {
        Task templated = Task.builder()
                .name("worker")
                .description("Process {topic} for client {client}")
                .expectedOutput("ok")
                .handler(ctx -> ToolResult.success(ctx.description()))
                .build();

        Graph g = Graph.builder()
                .name("templated")
                .state("worker", templated)
                .start("worker")
                .edge("worker", Graph.END)
                .build();

        EnsembleOutput out = Ensemble.builder().graph(g).build().run(java.util.Map.of("topic", "AI", "client", "Acme"));

        assertThat(out.getRaw()).isEqualTo("Process AI for client Acme");
    }

    // ========================
    // RETURN_WITH_FLAG
    // ========================

    @Test
    void returnWithFlag_setsFlagOnEnsembleOutput() {
        Task selfLooper = handlerTask("a", counting(new AtomicInteger(), "a"));
        Graph g = Graph.builder()
                .name("nonconverging")
                .state("a", selfLooper)
                .start("a")
                .edge("a", "a")
                .maxSteps(3)
                .onMaxSteps(MaxStepsAction.RETURN_WITH_FLAG)
                .build();

        EnsembleOutput out = Ensemble.builder().graph(g).build().run();

        assertThat(out.wasGraphTerminatedByMaxSteps()).isTrue();
        assertThat(out.getGraphTerminationReason()).contains("maxSteps");
    }
}
