package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates a two-agent research and writing workflow.
 *
 * A Researcher agent gathers information on a topic, and a Writer agent
 * uses that research to produce a blog post. Both agents run sequentially,
 * with the researcher's output passed as context to the writer.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:run
 *
 * To change the topic:
 *   ./gradlew :agentensemble-examples:run --args="quantum computing"
 */
public class ResearchWriterExample {

    private static final Logger log = LoggerFactory.getLogger(ResearchWriterExample.class);

    public static void main(String[] args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "artificial intelligence agents in 2026";

        log.info("Starting research and writing workflow for topic: {}", topic);

        // ========================
        // Configure the LLM
        // ========================
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
        // Define the agents
        // ========================

        // The Researcher finds and synthesizes information on the topic
        var researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Find accurate, up-to-date information on the given topic "
                        + "and provide a clear, well-structured summary")
                .background("You are a seasoned research analyst with expertise in "
                        + "technology and business. You excel at synthesizing complex "
                        + "information from multiple angles into clear, actionable insights.")
                .llm(model)
                .maxIterations(5)
                .build();

        // The Writer produces an engaging blog post based on the research
        var writer = Agent.builder()
                .role("Content Strategist and Writer")
                .goal("Write engaging, well-structured blog posts that are "
                        + "informative, accessible, and compelling to read")
                .background("You are an experienced technology writer who can "
                        + "transform complex research into compelling blog content. "
                        + "Your writing is clear, engaging, and well-organized.")
                .llm(model)
                .responseFormat("Structure the blog post with: an engaging title, "
                        + "an introduction paragraph, 3-4 main sections with headings, "
                        + "and a conclusion paragraph. Use markdown formatting.")
                .build();

        // ========================
        // Define the tasks
        // ========================

        // Task 1: Research the topic
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
                .agent(researcher)
                .build();

        // Task 2: Write a blog post based on the research
        var writeTask = Task.builder()
                .description("Write an engaging blog post about {topic} based on the "
                        + "research provided in the context. The blog post should be "
                        + "informative yet accessible to a general technical audience.")
                .expectedOutput("A well-written blog post of 600-800 words in markdown format, "
                        + "with an engaging title, introduction, body sections, and conclusion. "
                        + "The post should be ready to publish.")
                .agent(writer)
                .context(java.util.List.of(researchTask)) // Writer gets researcher's output as context
                .build();

        // ========================
        // Assemble and run the ensemble
        // ========================

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(writeTask)
                .workflow(Workflow.SEQUENTIAL)
                .verbose(false)
                .input("topic", topic)
                .build();

        log.info("Running ensemble with 2 agents and 2 tasks...");
        log.info("Topic: {}", topic);
        System.out.println("\n" + "=".repeat(60));

        EnsembleOutput output = ensemble.run();

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
