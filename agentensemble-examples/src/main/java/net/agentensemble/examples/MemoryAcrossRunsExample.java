package net.agentensemble.examples;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.EmbeddingStoreLongTermMemory;
import net.agentensemble.memory.EnsembleMemory;
import net.agentensemble.memory.EntityMemory;
import net.agentensemble.memory.InMemoryEntityMemory;
import net.agentensemble.memory.LongTermMemory;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates long-term memory persisting task outputs across ensemble runs.
 *
 * A competitive intelligence system that runs three simulated weekly cycles.
 * Each run produces a market analysis. Long-term memory means that each
 * week's agents can draw on all prior weeks' analyses when formulating reports.
 *
 * Memory types used:
 *   - Short-term memory  -- agents within a run share outputs automatically
 *   - Long-term memory   -- past run outputs retrieved by semantic similarity
 *   - Entity memory      -- stable company facts available to all agents in every run
 *
 * Note: This example uses InMemoryEmbeddingStore, which does not persist across
 * JVM restarts. In production, use a durable store (Chroma, Qdrant, pgvector, etc.).
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

        var embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small")
                .build();

        // InMemoryEmbeddingStore for demonstration.
        // In production, use a durable store (Chroma, Qdrant, etc.) that persists across JVM restarts.
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        LongTermMemory longTerm = new EmbeddingStoreLongTermMemory(store, embeddingModel);

        // Entity memory: stable facts about the company being analysed
        EntityMemory entities = new InMemoryEntityMemory();
        entities.put(
                "TechCorp",
                "A publicly traded software company (ticker: TECH) "
                        + "specialising in cloud infrastructure tools. "
                        + "Founded 2012. Headquarters: Austin, TX.");
        entities.put("TechCorp CEO", "Sarah Mitchell. Joined as CEO in 2019 from AWS.");

        // Memory configuration shared across all runs
        EnsembleMemory memory = EnsembleMemory.builder()
                .shortTerm(true)
                .longTerm(longTerm)
                .entityMemory(entities)
                .longTermMaxResults(3)
                .build();

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
        // ========================

        var analysisTask = Task.builder()
                .description("Analyse TechCorp's competitive environment for week of {week}. "
                        + "Draw on any relevant historical context from long-term memory.")
                .expectedOutput("A 300-word competitive intelligence briefing for the week of {week}")
                .agent(analyst)
                .build();

        var recommendationTask = Task.builder()
                .description("Provide strategic recommendations for TechCorp based on the " + "week of {week} analysis")
                .expectedOutput("Three specific, actionable strategic recommendations with supporting rationale")
                .agent(strategist)
                .build();

        // Create the ensemble once and reuse it across all runs
        Ensemble ensemble = Ensemble.builder()
                .agent(analyst)
                .agent(strategist)
                .task(analysisTask)
                .task(recommendationTask)
                .workflow(Workflow.SEQUENTIAL)
                .memory(memory)
                .build();

        // ========================
        // Run 1: Week 1
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 1: WEEK OF 2026-01-06");
        System.out.println("=".repeat(60));
        EnsembleOutput run1 = ensemble.run(Map.of("week", "2026-01-06"));
        System.out.println(run1.getRaw());
        System.out.printf("Duration: %s%n", run1.getTotalDuration());

        // ========================
        // Run 2: Week 2
        // Long-term memory now contains Week 1 outputs
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 2: WEEK OF 2026-01-13");
        System.out.println("=".repeat(60));
        EnsembleOutput run2 = ensemble.run(Map.of("week", "2026-01-13"));
        System.out.println(run2.getRaw());
        System.out.printf("Duration: %s%n", run2.getTotalDuration());

        // ========================
        // Run 3: Week 3
        // Agents have access to both prior weeks
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RUN 3: WEEK OF 2026-01-20");
        System.out.println("=".repeat(60));
        EnsembleOutput run3 = ensemble.run(Map.of("week", "2026-01-20"));
        System.out.println(run3.getRaw());
        System.out.printf("Duration: %s%n", run3.getTotalDuration());
    }
}
