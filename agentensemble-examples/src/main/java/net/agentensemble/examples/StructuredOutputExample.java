package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates two approaches to controlling agent output format.
 *
 * Part 1 -- Typed JSON output:
 *   The agent produces JSON that is automatically parsed into a strongly-typed
 *   Java record. Access fields via getParsedOutput(ResearchReport.class).
 *
 * Part 2 -- Formatted Markdown output:
 *   A researcher and writer work sequentially. The writer uses responseFormat
 *   to produce a well-structured Markdown blog post without any JSON parsing.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runStructuredOutput
 *
 * To change the research topic:
 *   ./gradlew :agentensemble-examples:runStructuredOutput --args="quantum computing"
 */
public class StructuredOutputExample {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputExample.class);

    record ResearchReport(String title, List<String> findings, String conclusion) {}

    public static void main(String[] args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "AI agents in 2025";

        log.info("Starting structured output example for topic: {}", topic);

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " + "Please set it to your OpenAI API key.");
        }

        var model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        // ========================
        // Part 1: Typed JSON output
        // ========================

        System.out.println("\n" + "=".repeat(60));
        System.out.println("PART 1: TYPED JSON OUTPUT");
        System.out.println("=".repeat(60));

        var researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Find accurate, well-structured information on any given topic")
                .llm(model)
                .build();

        var researchTask = Task.builder()
                .description("Research the most important developments in " + topic)
                .expectedOutput("A structured report with a title, a list of key findings, and a conclusion")
                .agent(researcher)
                .outputType(ResearchReport.class)
                .maxOutputRetries(3)
                .build();

        EnsembleOutput structuredOutput =
                Ensemble.builder().task(researchTask).build().run();

        TaskOutput structuredTaskOutput = structuredOutput.getTaskOutputs().get(0);

        // Raw text is always available
        System.out.println("\nRaw response:");
        System.out.println(structuredTaskOutput.getRaw());

        // Typed access to the parsed object
        ResearchReport report = structuredTaskOutput.getParsedOutput(ResearchReport.class);
        System.out.println("\nParsed report:");
        System.out.println("Title: " + report.title());
        System.out.println("Findings:");
        report.findings().forEach(f -> System.out.println("  - " + f));
        System.out.println("Conclusion: " + report.conclusion());

        // ========================
        // Part 2: Formatted Markdown output
        // ========================

        System.out.println("\n" + "=".repeat(60));
        System.out.println("PART 2: FORMATTED MARKDOWN OUTPUT");
        System.out.println("=".repeat(60));

        var plainResearcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Find accurate, well-sourced information on any given topic")
                .llm(model)
                .build();

        var writer = Agent.builder()
                .role("Content Writer")
                .goal("Write engaging, well-structured blog posts from research notes")
                .responseFormat("Always format your response in Markdown. "
                        + "Include a title (# heading), an introduction paragraph, "
                        + "three sections with subheadings (## heading), and a conclusion.")
                .llm(model)
                .build();

        var plainResearchTask = Task.builder()
                .description("Research the latest developments in {topic}")
                .expectedOutput("A factual summary of key developments, major players, and future outlook")
                .agent(plainResearcher)
                .build();

        var writeTask = Task.builder()
                .description("Write a 700-word blog post about {topic} based on the research provided")
                .expectedOutput("A 700-word blog post in Markdown format with: "
                        + "an engaging title, introduction, three sections with subheadings, and a conclusion")
                .agent(writer)
                .context(List.of(plainResearchTask))
                .build();

        EnsembleOutput markdownOutput = Ensemble.builder()
                .task(plainResearchTask)
                .task(writeTask)
                .workflow(Workflow.SEQUENTIAL)
                .input("topic", topic)
                .build()
                .run();

        System.out.println(markdownOutput.getRaw());

        System.out.printf(
                "%nCompleted in %s | Total tool calls: %d%n",
                markdownOutput.getTotalDuration(), markdownOutput.getTotalToolCalls());
    }
}
