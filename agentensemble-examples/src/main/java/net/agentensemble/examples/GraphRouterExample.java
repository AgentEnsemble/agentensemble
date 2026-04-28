package net.agentensemble.examples;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.graph.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates the {@link Graph} state-machine workflow construct using a tool-router pattern:
 * an {@code analyze} state inspects input and routes to one of two tool states; each tool
 * state returns to {@code analyze}; eventually {@code analyze} terminates the graph.
 *
 * <p>This is the canonical state-machine pattern that {@code Workflow.SEQUENTIAL} and
 * {@code Workflow.PARALLEL} can't express -- the next step is decided per iteration based on
 * the prior output, with arbitrary back-edges.
 *
 * <p>Uses deterministic handler tasks rather than real LLMs so the example runs offline. The
 * same pattern works with AI-backed tasks -- swap the handlers for {@code .description(...)}
 * bodies and provide a {@code chatLanguageModel(...)} on the ensemble.
 *
 * <p>What this shows:
 * <ul>
 *   <li>Cyclic state machine with back-edges (toolA → analyze, toolB → analyze).</li>
 *   <li>Conditional routing using {@code GraphPredicate} on per-step outputs.</li>
 *   <li>Unconditional fallback edge ({@code .edge("analyze", Graph.END)}).</li>
 *   <li>{@code EnsembleOutput.getGraphHistory()} reconstructs the full execution path.</li>
 *   <li>{@code Ensemble.builder().onGraphStateCompleted(...)} for live progress updates.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * ./gradlew :agentensemble-examples:runGraphRouter
 * </pre>
 */
public class GraphRouterExample {

    private static final Logger log = LoggerFactory.getLogger(GraphRouterExample.class);

    public static void main(String[] args) {
        log.info("=== Graph Router Example ===");

        // Counter: analyze decides USE_A twice, USE_B once, then DONE.
        AtomicInteger analyzeCalls = new AtomicInteger();
        Task analyze = Task.builder()
                .name("analyze")
                .description("Inspect input and decide next action")
                .expectedOutput("USE_A | USE_B | DONE")
                .handler(ctx -> {
                    int n = analyzeCalls.incrementAndGet();
                    String verdict =
                            switch (n) {
                                case 1 -> "USE_A";
                                case 2 -> "USE_B";
                                case 3 -> "USE_A";
                                default -> "DONE";
                            };
                    return ToolResult.success(verdict);
                })
                .build();

        AtomicInteger toolACalls = new AtomicInteger();
        Task toolA = Task.builder()
                .name("toolA")
                .description("Execute tool A")
                .expectedOutput("Tool A result")
                .handler(ctx -> ToolResult.success("toolA-result#" + toolACalls.incrementAndGet()))
                .build();

        AtomicInteger toolBCalls = new AtomicInteger();
        Task toolB = Task.builder()
                .name("toolB")
                .description("Execute tool B")
                .expectedOutput("Tool B result")
                .handler(ctx -> ToolResult.success("toolB-result#" + toolBCalls.incrementAndGet()))
                .build();

        Graph router = Graph.builder()
                .name("agent")
                .state("analyze", analyze)
                .state("toolA", toolA)
                .state("toolB", toolB)
                .start("analyze")
                // First-match-wins: conditional edges checked in declaration order.
                .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().equals("USE_A"), "verdict = USE_A")
                .edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().equals("USE_B"), "verdict = USE_B")
                // Unconditional fallback: targets END when no other condition matched.
                .edge("analyze", Graph.END)
                // Back-edges: each tool returns to analyze.
                .edge("toolA", "analyze")
                .edge("toolB", "analyze")
                .maxSteps(20)
                .build();

        EnsembleOutput out = Ensemble.builder()
                .graph(router)
                .onGraphStateCompleted(event -> log.info(
                        "Step {}/{}: {} → {} (took {}ms)",
                        event.stepNumber(),
                        event.maxSteps(),
                        event.stateName(),
                        event.nextState(),
                        event.stepDuration().toMillis()))
                .build()
                .run();

        log.info("=== Execution complete ===");
        log.info("Termination: {}", out.getGraphTerminationReason().orElse("unknown"));
        log.info("Total steps: {}", out.getGraphHistory().size());
        log.info(
                "Path: {}",
                out.getGraphHistory().stream().map(s -> s.getStateName()).toList());
        log.info(
                "analyze called {} times, toolA {} times, toolB {} times",
                analyzeCalls.get(),
                toolACalls.get(),
                toolBCalls.get());

        log.info("=== Done ===");
    }
}
