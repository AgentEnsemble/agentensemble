package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates task-scoped cross-execution memory persisting outputs across ensemble runs.
 *
 * A competitive intelligence system that runs three simulated weekly cycles.
 * Each run produces a market analysis. Because both tasks declare the same
 * named memory scope ("weekly-intelligence"), each week's agents can draw on
 * all prior weeks' outputs when formulating reports.
 *
 * How it works (v2.0.0 MemoryStore API):
 *   - A shared {@code MemoryStore.inMemory()} instance is created once
 *   - Both tasks declare {@code .memory("weekly-intelligence")}
 *   - Before each task executes, entries from "weekly-intelligence" are injected
 *     into the agent's prompt automatically
 *   - After each task completes, the output is stored into "weekly-intelligence"
 *   - In run 2, the agents see run 1's outputs; in run 3, they see runs 1 and 2
 *
 * Note: InMemoryStore does not persist across JVM restarts. For production use,
 * back MemoryStore with a durable embedding store:
 *   {@code MemoryStore.embeddings(embeddingModel, embeddingStore)}
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runMemoryAcrossRuns
 */
public class MemoryAcrossRunsExample {

    private static final Logger log = LoggerFactory.getLogger(MemoryAcrossRunsExample.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting memory-across-runs workflow (3 simulated weekly runs)");

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " + "Please set it to your OpenAI API key.");
        }

        var chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        // Single MemoryStore shared across all three runs.
        // Entries accumulate each week so later runs have richer context.
        MemoryStore store = MemoryStore.inMemory();

        // ========================
        // Define agents (reused across runs)
        // ========================

        var analyst = Agent.builder()
                .role("Market Intelligence Analyst")
                .goal("Track market developments, competitive movements, and industry trends for TechCorp")
                .background("You produce weekly intelligence briefings. You draw on historical context "
                        + "from prior analyses to identify trends and changes over time.")
                .llm(chatModel)
                .build();

        var strategist = Agent.builder()
                .role("Strategic Advisor")
                .goal("Translate market intelligence into strategic recommendations for TechCorp")
                .background("You provide actionable strategic recommendations grounded in both "
                        + "current data and historical context.")
                .llm(chatModel)
                .build();

        // ========================
        // Define tasks (reused across runs)
        //
        // Both tasks declare .memory("weekly-intelligence").
        // The framework will:
        //   1. Read prior entries from the scope before each task runs
        //   2. Inject them into the agent's prompt as "## Memory: weekly-intelligence"
        //   3. Store each task's output into the scope after it completes
        // ========================

        var analysisTask = Task.builder()
                .description("Analyse TechCorp's competitive environment for week of {week}. "
                        + "Draw on any relevant historical context from memory.")
                .expectedOutput("A 300-word competitive intelligence briefing for the week of {week}")
                .agent(analyst)
                .memory("weekly-intelligence")
                .build();

        var recommendationTask = Task.builder()
                .description("Provide strategic recommendations for TechCorp based on the " + "week of {week} analysis")
                .expectedOutput("Three specific, actionable strategic recommendations with supporting rationale")
                .agent(strategist)
                .memory("weekly-intelligence")
                .build();

        // Create the ensemble once and reuse it across all runs.
        // The memoryStore is shared so entries accumulate across runs.
        Ensemble ensemble = Ensemble.builder()
                .task(analysisTask)
                .task(recommendationTask)
                .workflow(Workflow.SEQUENTIAL)
                .memoryStore(store)
                .build();

        // ========================
        // Run 1: Week 1
        // Scope is empty -- agents work from scratch
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 1: WEEK OF 2026-01-06");
        System.out.println("=".repeat(60));
        EnsembleOutput run1 = ensemble.run(Map.of("week", "2026-01-06"));
        System.out.println(run1.getRaw());
        System.out.printf(
                "Duration: %s | Scope entries after run: %s%n",
                run1.getTotalDuration(),
                store.retrieve("weekly-intelligence", "TechCorp", 100).size());

        // ========================
        // Run 2: Week 2
        // Scope now contains Week 1 outputs; agents see prior context
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 2: WEEK OF 2026-01-13");
        System.out.println("=".repeat(60));
        EnsembleOutput run2 = ensemble.run(Map.of("week", "2026-01-13"));
        System.out.println(run2.getRaw());
        System.out.printf(
                "Duration: %s | Scope entries after run: %s%n",
                run2.getTotalDuration(),
                store.retrieve("weekly-intelligence", "TechCorp", 100).size());

        // ========================
        // Run 3: Week 3
        // Agents have access to both prior weeks
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 3: WEEK OF 2026-01-20");
        System.out.println("=".repeat(60));
        EnsembleOutput run3 = ensemble.run(Map.of("week", "2026-01-20"));
        System.out.println(run3.getRaw());
        System.out.printf(
                "Duration: %s | Scope entries after run: %s%n",
                run3.getTotalDuration(),
                store.retrieve("weekly-intelligence", "TechCorp", 100).size());
    }
}
