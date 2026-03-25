package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Path;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.format.ContextFormat;
import net.agentensemble.task.TaskOutput;

/**
 * Demonstrates the TOON context format feature -- a token-efficient serialization
 * format for structured data in LLM prompts that reduces token usage by 30-60%
 * compared to JSON.
 *
 * <p>This example runs a three-task sequential pipeline (research, analysis, report)
 * with TOON-formatted context passing between tasks. All structured data flowing
 * to the LLM is serialized in TOON instead of JSON, reducing the token footprint
 * of each prompt.
 *
 * <p>Requires the JToon library on the classpath:
 * {@code implementation("dev.toonformat:jtoon:1.0.9")}
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew agentensemble-examples:runToonFormat
 *   ./gradlew agentensemble-examples:runToonFormat --args="quantum computing"
 * </pre>
 */
public class ToonFormatExample {

    public static void main(String[] args) {
        String topic = args.length > 0 ? String.join(" ", args) : "AI agents in enterprise software";

        var model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        // 1. Define tasks with context dependencies
        Task research = Task.builder()
                .description("Research the latest developments in " + topic)
                .expectedOutput("A structured research summary with key findings, "
                        + "statistics from reputable sources, and notable projects or companies")
                .build();

        Task analysis = Task.builder()
                .description("Analyze the research findings and identify the top 3 " + "emerging trends in " + topic)
                .expectedOutput("A ranked list of trends with supporting evidence " + "and market impact assessment")
                .context(java.util.List.of(research)) // receives researcher's output as TOON
                .build();

        Task report = Task.builder()
                .description("Write a concise executive summary about " + topic)
                .expectedOutput("A polished 500-word executive summary suitable "
                        + "for C-level stakeholders, with specific numbers and clear recommendations")
                .context(java.util.List.of(research, analysis)) // receives both outputs as TOON
                .build();

        // 2. Run with TOON context format -- one builder call to enable
        System.out.println("=== Running with TOON Context Format ===\n");

        EnsembleOutput result = Ensemble.builder()
                .chatLanguageModel(model)
                .contextFormat(ContextFormat.TOON) // 30-60% fewer tokens in context
                .task(research)
                .task(analysis)
                .task(report)
                .verbose(true)
                .build()
                .run();

        // 3. Print the final report
        System.out.println("\n=== Executive Summary ===\n");
        System.out.println(result.getRaw());

        // 4. Print token usage per task
        printTokenUsage(result);

        // 5. Export trace in TOON format (also compact)
        result.getTrace().toToon(Path.of("toon-example-trace.toon"));
        System.out.println("\nTrace exported to toon-example-trace.toon");
    }

    private static void printTokenUsage(EnsembleOutput result) {
        System.out.println("\n--- Task Summary ---");
        for (TaskOutput output : result.getTaskOutputs()) {
            System.out.printf(
                    "  %s: %s | Tool calls: %d%n",
                    output.getAgentRole(), output.getDuration(), output.getToolCallCount());
        }
        System.out.printf("  Total duration: %s%n", result.getTotalDuration());
        System.out.printf("  Total tool calls: %d%n", result.getTotalToolCalls());
    }
}
