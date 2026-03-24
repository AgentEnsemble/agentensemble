package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import java.util.Map;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates the Phases feature: named task-group workstreams with a DAG-based
 * dependency model. Independent phases execute in parallel; a phase only starts
 * when all of its declared predecessors have completed.
 *
 * This example shows three patterns:
 *
 * Pattern 1 -- Sequential phases with deterministic handlers (no LLM required):
 *   Two phases execute back-to-back. The second phase depends on the first.
 *   All tasks use deterministic handlers, so no API key is needed.
 *
 * Pattern 2 -- Parallel convergent phases (no LLM required):
 *   The classic kitchen scenario: three independent dish-preparation phases run
 *   concurrently, then converge into a serving phase. All tasks use deterministic
 *   handlers to make the example runnable without an API key.
 *
 * Pattern 3 -- AI-backed phases with cross-phase context (requires OpenAI API key):
 *   A market-research phase and a technical-research phase run in parallel (each
 *   with their own AI tasks). A report phase waits for both and uses cross-phase
 *   context to synthesize the findings.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runPhases
 */
public class PhasesExample {

    private static final Logger log = LoggerFactory.getLogger(PhasesExample.class);

    public static void main(String[] args) {
        log.info("=== Phases Example ===");

        // ============================================================
        // Pattern 1: Sequential phases -- deterministic, no API key
        // ============================================================
        runPattern1_sequentialPhases();

        // ============================================================
        // Pattern 2: Parallel convergent phases -- kitchen scenario
        // ============================================================
        runPattern2_kitchenScenario();

        // ============================================================
        // Pattern 3: AI-backed parallel phases (requires API key)
        // ============================================================
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            runPattern3_aiParallelPhases(apiKey);
        } else {
            log.info("OPENAI_API_KEY not set -- skipping Pattern 3 (AI parallel phases)");
        }

        log.info("=== Phases Example complete ===");
    }

    // ================================================
    // Pattern 1: Two sequential phases, no LLM
    // ================================================

    static void runPattern1_sequentialPhases() {
        log.info("--- Pattern 1: Sequential phases ---");

        Task fetchTask = Task.builder()
                .description("Fetch product data from catalogue")
                .expectedOutput("Product data JSON")
                .handler(ctx -> {
                    String data = "{\"name\": \"Widget Pro\", \"price\": 149.99, \"stock\": 42}";
                    log.info("Phase 'fetch' produced: {}", data);
                    return ToolResult.success(data);
                })
                .build();

        Phase fetch = Phase.of("fetch", fetchTask);

        Task normalizeTask = Task.builder()
                .description("Normalize and format product data for display")
                .expectedOutput("Formatted product summary")
                .context(List.of(fetchTask))
                .handler(ctx -> {
                    String prior = ctx.contextOutputs().get(0).getRaw();
                    String summary = "Product: Widget Pro | Price: $149.99 | Stock: 42 units";
                    log.info("Phase 'normalize' received: {} -- produced: {}", prior, summary);
                    return ToolResult.success(summary);
                })
                .build();

        Phase normalize = Phase.builder()
                .name("normalize")
                .after(fetch)
                .task(normalizeTask)
                .build();

        EnsembleOutput output =
                Ensemble.builder().phase(fetch).phase(normalize).build().run();

        if (log.isInfoEnabled()) {
            log.info("Pattern 1 final output: {}", output.getRaw());
        }

        Map<String, List<TaskOutput>> byPhase = output.getPhaseOutputs();
        if (log.isInfoEnabled()) {
            log.info("Phase 'fetch' outputs:     {}", byPhase.get("fetch").size());
        }
        if (log.isInfoEnabled()) {
            log.info("Phase 'normalize' outputs: {}", byPhase.get("normalize").size());
        }
    }

    // ================================================
    // Pattern 2: Kitchen scenario -- 3 parallel + 1 convergent
    // ================================================

    static void runPattern2_kitchenScenario() {
        log.info("--- Pattern 2: Kitchen scenario (parallel convergent phases) ---");

        // Each dish phase prepares independently
        Phase steak = Phase.builder()
                .name("steak")
                .task(Task.builder()
                        .description("Prepare steak")
                        .expectedOutput("Seasoned steak")
                        .handler(ctx -> simulateCook("steak", "prep", 80))
                        .build())
                .task(Task.builder()
                        .description("Sear steak")
                        .expectedOutput("Seared steak")
                        .handler(ctx -> simulateCook("steak", "sear", 120))
                        .build())
                .task(Task.builder()
                        .description("Plate steak")
                        .expectedOutput("Plated steak")
                        .handler(ctx -> simulateCook("steak", "plate", 20))
                        .build())
                .build();

        Phase salmon = Phase.builder()
                .name("salmon")
                .task(Task.builder()
                        .description("Prepare salmon")
                        .expectedOutput("Seasoned salmon")
                        .handler(ctx -> simulateCook("salmon", "prep", 60))
                        .build())
                .task(Task.builder()
                        .description("Cook salmon")
                        .expectedOutput("Cooked salmon")
                        .handler(ctx -> simulateCook("salmon", "cook", 90))
                        .build())
                .task(Task.builder()
                        .description("Plate salmon")
                        .expectedOutput("Plated salmon")
                        .handler(ctx -> simulateCook("salmon", "plate", 20))
                        .build())
                .build();

        Phase pasta = Phase.builder()
                .name("pasta")
                .task(Task.builder()
                        .description("Boil pasta")
                        .expectedOutput("Al dente pasta")
                        .handler(ctx -> simulateCook("pasta", "boil", 100))
                        .build())
                .task(Task.builder()
                        .description("Make sauce")
                        .expectedOutput("Tomato sauce")
                        .handler(ctx -> simulateCook("pasta", "sauce", 70))
                        .build())
                .task(Task.builder()
                        .description("Plate pasta")
                        .expectedOutput("Plated pasta")
                        .handler(ctx -> simulateCook("pasta", "plate", 15))
                        .build())
                .build();

        // Serve waits for all three dishes to be ready
        Phase serve = Phase.builder()
                .name("serve")
                .after(steak, salmon, pasta)
                .task(Task.builder()
                        .description("Deliver all plates simultaneously")
                        .expectedOutput("All plates delivered")
                        .handler(ctx -> {
                            log.info("All dishes ready -- delivering steak, salmon, and pasta together!");
                            return ToolResult.success("Steak, salmon, and pasta delivered simultaneously");
                        })
                        .build())
                .build();

        long start = System.currentTimeMillis();

        EnsembleOutput output = Ensemble.builder()
                .phase(steak)
                .phase(salmon)
                .phase(pasta)
                .phase(serve)
                .build()
                .run();

        long elapsed = System.currentTimeMillis() - start;
        if (log.isInfoEnabled()) {
            log.info("Pattern 2 final output: {}", output.getRaw());
        }
        log.info("Total wall time: {}ms (phases ran in parallel)", elapsed);
    }

    private static ToolResult simulateCook(String dish, String step, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = dish + " " + step + " complete";
        log.info("[{}] {}", dish, result);
        return ToolResult.success(result);
    }

    // ================================================
    // Pattern 3: AI-backed parallel phases
    // ================================================

    static void runPattern3_aiParallelPhases(String apiKey) {
        log.info("--- Pattern 3: AI-backed parallel phases ---");

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        Task marketTask = Task.builder()
                .description("Research market positioning for a new Java developer tooling product. "
                        + "Focus on pricing, target segments, and competitive landscape.")
                .expectedOutput("Bullet-point market positioning summary")
                .chatLanguageModel(model)
                .build();

        Task technicalTask = Task.builder()
                .description("Assess the technical complexity and integration requirements for "
                        + "a Java multi-agent AI orchestration framework.")
                .expectedOutput("Technical complexity score (1-10) with rationale and integration checklist")
                .chatLanguageModel(model)
                .build();

        Phase marketResearch = Phase.of("market-research", marketTask);

        Phase technicalResearch = Phase.of("technical-research", technicalTask);

        // Report phase waits for both and references their outputs via context
        Task reportTask = Task.builder()
                .description("Write a concise product feasibility summary combining the market positioning "
                        + "and technical assessment. Include a go/no-go recommendation.")
                .expectedOutput("One-page feasibility summary with go/no-go recommendation")
                .chatLanguageModel(model)
                .context(List.of(marketTask, technicalTask))
                .build();

        Phase report = Phase.builder()
                .name("report")
                .after(marketResearch, technicalResearch)
                .task(reportTask)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .phase(marketResearch)
                .phase(technicalResearch)
                .phase(report)
                .build()
                .run();

        log.info("Pattern 3 results:");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            if (log.isInfoEnabled()) {
                log.info("[{}] {}", taskOutput.getAgentRole(), taskOutput.getRaw());
            }
        }

        Map<String, List<TaskOutput>> byPhase = output.getPhaseOutputs();
        if (log.isInfoEnabled()) {
            log.info(
                    "market-research phase tasks completed: {}",
                    byPhase.get("market-research").size());
        }
        if (log.isInfoEnabled()) {
            log.info(
                    "technical-research phase tasks completed: {}",
                    byPhase.get("technical-research").size());
        }
        if (log.isInfoEnabled()) {
            log.info("report phase tasks completed: {}", byPhase.get("report").size());
        }
        if (log.isInfoEnabled()) {
            log.info("Final recommendation:\n{}", output.getRaw());
        }
    }
}
