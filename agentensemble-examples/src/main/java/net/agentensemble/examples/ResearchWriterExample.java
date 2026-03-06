package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates a two-task research and writing workflow using the v2 task-first API.
 *
 * A research task gathers information on a topic, then a writing task uses that
 * research to produce a blog post. Both tasks run in dependency order -- the writer
 * receives the researcher's output as context via the DAG-based executor.
 *
 * No explicit Agent declarations are needed. The framework synthesizes agents
 * automatically from the task descriptions. The LLM is declared once at the ensemble
 * level and applies to all synthesized agents.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runResearchWriter
 *
 * To change the topic:
 *   ./gradlew :agentensemble-examples:runResearchWriter --args="quantum computing"
 */
public class ResearchWriterExample {

    private static final Logger log = LoggerFactory.getLogger(ResearchWriterExample.class);

    public static void main(String[] args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "artificial intelligence agents in 2026";

        log.info("Starting research and writing workflow for topic: {}", topic);

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " + "Please set it to your OpenAI API key.");
        }

        var model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();

        // ========================
        // Define the tasks
        // No Agent declarations needed -- agents are synthesized from task descriptions.
        // ========================

        var researchTask = Task.builder()
                .description("Research the topic of {topic}. Find the most important "
                        + "developments, key players, current state, and future outlook. "
                        + "Focus on what would be most interesting and relevant to a "
                        + "technically-minded audience.")
                .expectedOutput("A comprehensive research summary covering: "
                        + "(1) what it is and why it matters, "
                        + "(2) current state and key developments, "
                        + "(3) notable examples or use cases, "
                        + "(4) challenges and limitations, "
                        + "(5) future outlook. "
                        + "The summary should be 400-600 words.")
                .maxIterations(5)
                .build();

        var writeTask = Task.builder()
                .description("Write an engaging blog post about {topic} based on the "
                        + "research provided in the context. The blog post should be "
                        + "informative yet accessible to a general technical audience.")
                .expectedOutput("A well-written blog post of 600-800 words in markdown format, "
                        + "with an engaging title, introduction, 3-4 sections with headings, "
                        + "and a conclusion. The post should be ready to publish.")
                .context(java.util.List.of(researchTask)) // writer gets researcher's output as context
                .build();

        // ========================
        // Assemble and run the ensemble
        //
        // chatLanguageModel() sets the default LLM for all synthesized agents.
        // workflow() is not declared -- inferred automatically from context declarations.
        // ========================

        log.info("Running ensemble with 2 tasks (topic: {})...", topic);
        System.out.println("\n" + "=".repeat(60));

        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(model)
                .task(researchTask)
                .task(writeTask)
                .verbose(false)
                .input("topic", topic)
                .build()
                .run();

        // ========================
        // Display results
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RESEARCH SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(output.getTaskOutputs().get(0).getRaw());

        System.out.println("\n" + "=".repeat(60));
        System.out.println("BLOG POST");
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());

        System.out.println("\n" + "=".repeat(60));
        System.out.printf(
                "Completed in %s | Total tool calls: %d%n", output.getTotalDuration(), output.getTotalToolCalls());
    }
}
