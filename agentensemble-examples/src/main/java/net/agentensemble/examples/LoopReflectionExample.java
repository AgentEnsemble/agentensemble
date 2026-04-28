package net.agentensemble.examples;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.loop.Loop;
import net.agentensemble.workflow.loop.LoopOutputMode;
import net.agentensemble.workflow.loop.MaxIterationsAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates the {@link Loop} workflow construct using the reflection-loop pattern: a writer
 * drafts content; a critic reviews; the loop repeats until the critic approves or the
 * iteration cap is hit.
 *
 * <p>Uses deterministic handler tasks rather than real LLMs so the example runs offline and
 * its assertions exercise the loop control flow rather than model behaviour. The same pattern
 * works with AI-backed tasks -- swap the handlers for {@code .description(...)} bodies and
 * provide a {@code chatLanguageModel(...)} on the ensemble.
 *
 * <p>What this shows:
 * <ul>
 *   <li>How to declare a multi-task loop body that repeats until a predicate fires.</li>
 *   <li>How to read the per-iteration history from {@code EnsembleOutput.getLoopHistory(...)}.</li>
 *   <li>How {@code LoopOutputMode.LAST_ITERATION} (default) projects only the final
 *       iteration's outputs to the rest of the ensemble.</li>
 *   <li>How {@code MaxIterationsAction.RETURN_LAST} returns the last attempt as the final
 *       output if the loop never converges.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * ./gradlew :agentensemble-examples:runLoopReflection
 * </pre>
 */
public class LoopReflectionExample {

    private static final Logger log = LoggerFactory.getLogger(LoopReflectionExample.class);

    public static void main(String[] args) {
        log.info("=== Loop Reflection Example ===");

        // Counter wired into the deterministic critic so the third pass returns "APPROVED".
        AtomicInteger criticInvocations = new AtomicInteger();

        // The writer produces a draft tagged with its attempt number. In a real ensemble this
        // would be a description-driven task using an LLM; the handler form keeps the example
        // offline and reproducible.
        Task writer = Task.builder()
                .name("writer")
                .description("Write a 600-word article on edge AI inference")
                .expectedOutput("A polished article")
                .handler(ctx -> ToolResult.success(
                        "Draft attempt #" + (criticInvocations.get() + 1) + " on edge AI inference."))
                .build();

        // The critic approves on the third invocation; earlier attempts return REVISE.
        Task critic = Task.builder()
                .name("critic")
                .description("Critique the article. Reply 'APPROVED' if it meets the bar.")
                .expectedOutput("APPROVED or a list of issues")
                .handler(ctx -> {
                    int attempt = criticInvocations.incrementAndGet();
                    String verdict = (attempt >= 3) ? "APPROVED" : "REVISE: tighten paragraph 2";
                    return ToolResult.success(verdict);
                })
                .build();

        Loop reflection = Loop.builder()
                .name("reflection")
                .task(writer)
                .task(critic)
                // Stop when the critic approves; otherwise keep iterating up to the cap.
                .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
                .maxIterations(5)
                .onMaxIterations(MaxIterationsAction.RETURN_LAST)
                .outputMode(LoopOutputMode.LAST_ITERATION)
                .build();

        EnsembleOutput out = Ensemble.builder().loop(reflection).build().run();

        // Per-iteration history is always available via the side channel, regardless of
        // outputMode. Outer list is iteration-ordered; inner map is keyed by body-task name.
        log.info("Loop ran {} iteration(s)", out.getLoopHistory("reflection").size());
        out.getLoopTerminationReason("reflection").ifPresent(reason -> log.info("Termination reason: {}", reason));

        // Projected outputs (LAST_ITERATION default): one TaskOutput per body task name from
        // the iteration that satisfied the predicate (or the final iteration if it didn't).
        TaskOutput finalDraft = out.getOutput(writer).orElseThrow();
        TaskOutput finalVerdict = out.getOutput(critic).orElseThrow();
        log.info("Final draft: {}", finalDraft.getRaw());
        log.info("Final verdict: {}", finalVerdict.getRaw());

        log.info("=== Done ===");
    }
}
