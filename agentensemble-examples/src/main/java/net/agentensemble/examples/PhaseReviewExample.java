package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates phase-level review and retry with three patterns:
 *
 * <p>Pattern 1 -- Deterministic self-retry (no LLM required):
 * A research phase retries until its output passes a programmatic quality gate. The review
 * task uses {@code .context()} to read the phase task output, and feedback is injected into
 * the task prompt on each retry.
 *
 * <p>Pattern 2 -- Deterministic predecessor retry (no LLM required):
 * A writing phase detects that the upstream research phase was insufficient and requests a
 * research redo before continuing. The writing review task uses {@code .context()} to read
 * the draft and evaluate whether the research backing is sufficient.
 *
 * <p>Pattern 3 -- AI-backed review (requires OpenAI API key):
 * An LLM evaluates the research output against defined criteria and either approves it or
 * provides specific actionable feedback for improvement. The AI review task uses
 * {@code .context()} so the LLM sees the research output in its prompt.
 *
 * <p>Usage:
 *
 * <pre>
 * ./gradlew :agentensemble-examples:runPhaseReview
 * </pre>
 */
public class PhaseReviewExample {

    private static final Logger log = LoggerFactory.getLogger(PhaseReviewExample.class);

    public static void main(String[] args) {
        log.info("=== Phase Review Example ===");

        runPattern1_deterministicSelfRetry();
        runPattern2_predecessorRetry();

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            runPattern3_aiReview(apiKey);
        } else {
            log.info("OPENAI_API_KEY not set -- skipping Pattern 3 (AI reviewer)");
        }

        log.info("=== Phase Review Example complete ===");
    }

    // ================================================
    // Pattern 1: Deterministic self-retry
    // ================================================

    static void runPattern1_deterministicSelfRetry() {
        log.info("--- Pattern 1: Deterministic self-retry ---");

        AtomicInteger attemptCount = new AtomicInteger(0);

        // Work task: produces a short output on attempt 1, a detailed output on attempt 2+.
        Task researchTask = Task.builder()
                .description("Research quantum computing applications")
                .expectedOutput("A detailed research summary")
                .handler(ctx -> {
                    int n = attemptCount.incrementAndGet();
                    String output = n == 1
                            ? "Quantum computing is fast."
                            : "Quantum computing offers exponential speedup for specific problem classes "
                                    + "including cryptography (Shor's algorithm), optimisation (QAOA), and "
                                    + "simulation of molecular systems. Key hardware: superconducting qubits "
                                    + "and trapped ions. Leading vendors: IBM, Google, IonQ. [sources: A, B, C]";
                    if (log.isInfoEnabled()) {
                        log.info("[research] Attempt {}: produced {} chars", n, output.length());
                    }
                    return ToolResult.success(output);
                })
                .build();

        // Review task: declares .context() to read the research task output, then checks length.
        // The review task MUST declare .context() to access phase task outputs via ctx.contextOutputs().
        Task reviewTask = Task.builder()
                .description("Quality gate: check output quality")
                .context(List.of(researchTask)) // required: read the research task output
                .handler(ctx -> {
                    String output = ctx.contextOutputs().isEmpty()
                            ? ""
                            : ctx.contextOutputs().getFirst().getRaw();
                    if (output.length() < 100) {
                        String feedback = "Output too short (" + output.length()
                                + " chars). Expand to cover: key algorithms, "
                                + "hardware platforms, and real-world applications.";
                        log.info("[review] RETRY requested: {}", feedback);
                        return ToolResult.success(
                                PhaseReviewDecision.retry(feedback).toText());
                    }
                    if (log.isInfoEnabled()) {
                        log.info("[review] APPROVED -- output length: {} chars", output.length());
                    }
                    return ToolResult.success(PhaseReviewDecision.approve().toText());
                })
                .build();

        Phase research = Phase.builder()
                .name("research")
                .task(researchTask)
                .review(PhaseReview.of(reviewTask, 3))
                .build();

        EnsembleOutput output = Ensemble.builder().phase(research).build().run();

        if (log.isInfoEnabled()) {
            log.info("Pattern 1 result ({} attempts total): {}", attemptCount.get(), output.getRaw());
        }
        log.info(""); // blank line
    }

    // ================================================
    // Pattern 2: Predecessor retry
    // ================================================

    static void runPattern2_predecessorRetry() {
        log.info("--- Pattern 2: Predecessor retry ---");

        AtomicInteger researchRunCount = new AtomicInteger(0);
        AtomicInteger writingRunCount = new AtomicInteger(0);
        AtomicInteger writingReviewCount = new AtomicInteger(0);

        // Research task: weak output on first run, strong on second.
        Task gatherTask = Task.builder()
                .description("Gather data for the report")
                .expectedOutput("Research data")
                .handler(ctx -> {
                    int n = researchRunCount.incrementAndGet();
                    String output = n == 1
                            ? "Some basic facts."
                            : "Comprehensive data with 5 sources, " + "quantitative metrics, and trend analysis.";
                    log.info("[research] Run {}: {}", n, output);
                    return ToolResult.success(output);
                })
                .build();

        Phase research = Phase.of("research", gatherTask);

        // Writing task: uses whatever research was provided.
        Task draftTask = Task.builder()
                .description("Write a report based on the research findings")
                .expectedOutput("Report draft")
                .handler(ctx -> {
                    int n = writingRunCount.incrementAndGet();
                    log.info("[writing] Run {}", n);
                    return ToolResult.success("Report draft based on research (run " + n + ")");
                })
                .build();

        // Writing review: declares .context() to read the draft, then decides whether
        // research needs to be re-done or the draft is acceptable.
        Task writingReviewTask = Task.builder()
                .description("Review the writing quality and check whether research backing is sufficient")
                .context(List.of(draftTask)) // required: read the draft to evaluate
                .handler(ctx -> {
                    int n = writingReviewCount.incrementAndGet();
                    if (n == 1) {
                        String feedback = "Research data is too sparse. Need comprehensive "
                                + "data with sources and quantitative metrics.";
                        log.info("[writing-review] RETRY_PREDECESSOR requested (call {}): {}", n, feedback);
                        return ToolResult.success(PhaseReviewDecision.retryPredecessor("research", feedback)
                                .toText());
                    }
                    log.info("[writing-review] APPROVED (call {})", n);
                    return ToolResult.success(PhaseReviewDecision.approve().toText());
                })
                .build();

        Phase writing = Phase.builder()
                .name("writing")
                .after(research)
                .task(draftTask)
                .review(PhaseReview.builder()
                        .task(writingReviewTask)
                        .maxRetries(1)
                        .maxPredecessorRetries(1)
                        .build())
                .build();

        EnsembleOutput output =
                Ensemble.builder().phase(research).phase(writing).build().run();

        log.info("Pattern 2 results:");
        if (log.isInfoEnabled()) {
            log.info("  Research ran {} time(s)", researchRunCount.get());
        }
        if (log.isInfoEnabled()) {
            log.info("  Writing ran {} time(s)", writingRunCount.get());
        }
        if (log.isInfoEnabled()) {
            log.info("  Writing review ran {} time(s)", writingReviewCount.get());
        }
        if (log.isInfoEnabled()) {
            log.info("  Final output: {}", output.getRaw());
        }
        log.info(""); // blank line
    }

    // ================================================
    // Pattern 3: AI reviewer (requires API key)
    // ================================================

    static void runPattern3_aiReview(String apiKey) {
        log.info("--- Pattern 3: AI reviewer ---");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        Task researchTask = Task.builder()
                .description("Research the environmental impact of large language models. "
                        + "Cover energy consumption, carbon footprint, water usage, and mitigation strategies.")
                .expectedOutput("A detailed research summary with sources and quantitative data")
                .chatLanguageModel(model)
                .build();

        // AI reviewer: declares .context() so the LLM sees the research output in its prompt
        // under "## Context from Previous Tasks". The LLM evaluates it against criteria
        // and responds with APPROVE or RETRY: <feedback>.
        Task aiReviewTask = Task.builder()
                .description("Evaluate the research output provided above.\n\n"
                        + "Criteria:\n"
                        + "- At least 3 distinct sources or studies cited\n"
                        + "- Quantitative data (numbers, percentages) for energy and carbon claims\n"
                        + "- Mitigation strategies mentioned\n\n"
                        + "If ALL criteria are met, respond with exactly: APPROVE\n"
                        + "Otherwise, respond with: RETRY: <specific actionable feedback on what is missing>")
                .context(List.of(researchTask)) // required: LLM sees research output in its prompt
                .chatLanguageModel(model)
                .build();

        Phase research = Phase.builder()
                .name("research")
                .task(researchTask)
                .review(PhaseReview.builder().task(aiReviewTask).maxRetries(2).build())
                .build();

        EnsembleOutput output = Ensemble.builder().phase(research).build().run();

        if (log.isInfoEnabled()) {
            log.info("Pattern 3 final output:\n{}", output.getRaw());
        }
    }
}
