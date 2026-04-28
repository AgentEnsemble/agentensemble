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
 * Demonstrates the selective-feedback pattern (the LangGraph killer use case): a quality
 * gate that can route either forward to publish OR back to a specific upstream state to
 * retry, without re-running unrelated upstream work.
 *
 * <p>Pipeline:
 * <pre>
 *   research → write → critique → publish
 *                ^________|         (REJECT routes back to write only)
 * </pre>
 *
 * <p>If the critique state returns "REJECT", the graph routes back to {@code write} —
 * NOT to {@code research}. Research's expensive output is retained; only the writing is
 * redone. This is the pattern Loop can't cleanly express, since Loop iterates a fixed body
 * (you'd have to either include research in the body and re-run it, or only loop write +
 * critique without research being upstream).
 *
 * <p>Uses deterministic handler tasks. Critique fails twice, then approves on the third
 * draft — write runs three times; research runs once.
 *
 * <p>Usage:
 * <pre>
 * ./gradlew :agentensemble-examples:runGraphRetryWithFallback
 * </pre>
 */
public class GraphRetryWithFallbackExample {

    private static final Logger log = LoggerFactory.getLogger(GraphRetryWithFallbackExample.class);

    public static void main(String[] args) {
        log.info("=== Graph Retry-With-Fallback Example ===");

        AtomicInteger researchCalls = new AtomicInteger();
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicInteger critiqueCalls = new AtomicInteger();
        AtomicInteger publishCalls = new AtomicInteger();

        Task research = Task.builder()
                .name("research")
                .description("Gather research findings")
                .expectedOutput("Findings text")
                .handler(ctx -> {
                    researchCalls.incrementAndGet();
                    return ToolResult.success("research findings on topic X");
                })
                .build();

        Task write = Task.builder()
                .name("write")
                .description("Write a draft from the research")
                .expectedOutput("Draft text")
                .handler(ctx -> ToolResult.success("draft attempt #" + writeCalls.incrementAndGet()))
                .build();

        // Critique fails the first 2 times, approves on the 3rd.
        Task critique = Task.builder()
                .name("critique")
                .description("Critique the draft")
                .expectedOutput("REJECT or APPROVE")
                .handler(ctx -> {
                    int n = critiqueCalls.incrementAndGet();
                    return ToolResult.success(n < 3 ? "REJECT: tighten paragraph 2" : "APPROVE");
                })
                .build();

        Task publish = Task.builder()
                .name("publish")
                .description("Publish the approved draft")
                .expectedOutput("Confirmation")
                .handler(ctx -> {
                    publishCalls.incrementAndGet();
                    return ToolResult.success("PUBLISHED");
                })
                .build();

        Graph pipeline = Graph.builder()
                .name("publish-pipeline")
                .state("research", research)
                .state("write", write)
                .state("critique", critique)
                .state("publish", publish)
                .start("research")
                .edge("research", "write")
                .edge("write", "critique")
                // critique decides: REJECT routes back to write (selective feedback edge);
                // anything else (APPROVE) routes forward to publish.
                .edge(
                        "critique",
                        "write",
                        ctx -> ctx.lastOutput().getRaw().startsWith("REJECT"),
                        "verdict starts with REJECT")
                .edge("critique", "publish")
                .edge("publish", Graph.END)
                .maxSteps(20)
                .build();

        EnsembleOutput out = Ensemble.builder().graph(pipeline).build().run();

        log.info("=== Execution complete ===");
        log.info("Termination: {}", out.getGraphTerminationReason().orElse("unknown"));
        log.info(
                "Path: {}",
                out.getGraphHistory().stream().map(s -> s.getStateName()).toList());
        log.info(
                "research={}, write={}, critique={}, publish={}",
                researchCalls.get(),
                writeCalls.get(),
                critiqueCalls.get(),
                publishCalls.get());
        log.info(
                "Notice: research ran exactly 1 time even though write ran {} times -- "
                        + "the back-edge from critique targets write specifically, not research.",
                writeCalls.get());

        log.info("=== Done ===");
    }
}
