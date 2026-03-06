package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import net.agentensemble.Ensemble;
import net.agentensemble.EnsembleOutput;
import net.agentensemble.Task;
import net.agentensemble.memory.MemoryStore;

/**
 * Demonstrates task-scoped cross-run memory using the v2.0.0 MemoryStore API.
 *
 * <p>A single {@link MemoryStore} is created once and shared across two separate
 * {@link Ensemble#run(Map)} calls. The first run performs research and stores its
 * output in the {@code "research"} named scope. The second run declares the same
 * scope, so the framework automatically injects the prior findings into the agent
 * prompt before execution.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :agentensemble-examples:runCrossRunMemory --args="renewable energy"
 * </pre>
 */
public class CrossRunMemoryExample {

    public static void main(String[] args) {
        String topic = args.length > 0 ? String.join(" ", args) : "renewable energy trends";

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        // A single MemoryStore persists across both Ensemble.run() calls.
        MemoryStore store = MemoryStore.inMemory();

        System.out.println("=== Run 1: Research phase ===");

        // Run 1: The research task writes its findings into the "research" scope.
        // After execution the framework stores the task output in the scope
        // automatically via AgentExecutor.storeInDeclaredScopes().
        Task researchTask = Task.builder()
                .description("Research the key facts and statistics about: " + topic)
                .expectedOutput("A bullet-point summary of 5-7 key facts with supporting data and figures")
                .memory("research")
                .build();

        EnsembleOutput run1 = Ensemble.builder()
                .chatLanguageModel(model)
                .task(researchTask)
                .memoryStore(store)
                .build()
                .run(Map.of());

        System.out.println("Research output:");
        System.out.println(run1.getRaw());

        System.out.println("\n=== Run 2: Writing phase (reads research from memory) ===");

        // Run 2: The writing task declares the same "research" scope. Before
        // execution the framework retrieves the most recent entries from the scope
        // and injects them under a "## Memory: research" section in the agent
        // system prompt -- no explicit wiring required.
        Task writeTask = Task.builder()
                .description(
                        "Using the research findings available in memory, write a 300-word article about: " + topic)
                .expectedOutput("A polished, publication-ready article with an introduction,"
                        + " 2-3 body paragraphs, and a conclusion")
                .memory("research")
                .build();

        EnsembleOutput run2 = Ensemble.builder()
                .chatLanguageModel(model)
                .task(writeTask)
                .memoryStore(store)
                .build()
                .run(Map.of());

        System.out.println("Article:");
        System.out.println(run2.getRaw());
    }
}
