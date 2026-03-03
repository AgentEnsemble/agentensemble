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
 * Demonstrates a parallel workflow for competitive intelligence analysis.
 *
 * Four tasks with dependency-driven scheduling:
 *   1. Market Research    -- gathers competitor landscape data (no dependencies)
 *   2. Financial Analysis -- pulls financial metrics for competitors (no dependencies)
 *   3. SWOT Synthesis     -- synthesizes findings from both above (depends on 1 and 2)
 *   4. Executive Summary  -- writes the final report (depends on 3)
 *
 * Tasks 1 and 2 run concurrently. Task 3 waits for both. Task 4 waits for 3.
 * Parallelism is derived automatically from context declarations -- no extra annotations needed.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runParallelWorkflow
 *
 * To analyse a different company:
 *   ./gradlew :agentensemble-examples:runParallelWorkflow --args="Tesla"
 */
public class ParallelCompetitiveIntelligenceExample {

    private static final Logger log = LoggerFactory.getLogger(ParallelCompetitiveIntelligenceExample.class);

    public static void main(String[] args) throws Exception {
        String company = args.length > 0 ? args[0] : "Acme Corp";
        String industry = args.length > 1 ? args[1] : "enterprise software";

        log.info("Starting parallel competitive intelligence workflow for: {} ({})", company, industry);

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
        // Define agents
        // ========================

        var marketResearcher = Agent.builder()
                .role("Market Research Analyst")
                .goal("Gather comprehensive data on the competitive landscape")
                .background("You specialize in identifying market trends and competitor positioning.")
                .llm(model)
                .build();

        var financialAnalyst = Agent.builder()
                .role("Financial Analyst")
                .goal("Analyse financial metrics and performance indicators for competitors")
                .background("You specialize in financial statement analysis and KPI benchmarking.")
                .llm(model)
                .build();

        var strategist = Agent.builder()
                .role("Strategy Consultant")
                .goal("Synthesize market and financial findings into strategic insights")
                .background("You produce SWOT analyses and strategic recommendations.")
                .llm(model)
                .build();

        var writer = Agent.builder()
                .role("Executive Writer")
                .goal("Write concise, clear executive summaries for senior leadership")
                .llm(model)
                .build();

        // ========================
        // Define tasks
        // ========================
        // Tasks 1 and 2 have no context dependencies -- they run in PARALLEL.

        var marketTask = Task.builder()
                .description("Research the competitive landscape for {company} in the {industry} industry. "
                        + "Identify the top 5 competitors, their market positioning, and key differentiators.")
                .expectedOutput("A structured competitor analysis covering market share, positioning, "
                        + "strengths, and recent strategic moves for each competitor.")
                .agent(marketResearcher)
                .build();

        var financialTask = Task.builder()
                .description("Analyse the financial performance of the top competitors in the "
                        + "{industry} industry. Focus on revenue growth, margins, and R&D spend.")
                .expectedOutput("A financial comparison table and narrative highlighting which "
                        + "competitors are gaining or losing financial ground.")
                .agent(financialAnalyst)
                .build();

        // Task 3 depends on BOTH market and financial tasks
        var swotTask = Task.builder()
                .description("Using the market research and financial analysis provided as context, "
                        + "produce a SWOT analysis for {company} relative to its key competitors.")
                .expectedOutput("A complete SWOT analysis (Strengths, Weaknesses, Opportunities, "
                        + "Threats) with supporting evidence from the market and financial data.")
                .agent(strategist)
                .context(List.of(marketTask, financialTask))
                .build();

        // Task 4 depends on the SWOT synthesis
        var summaryTask = Task.builder()
                .description("Write a one-page executive summary of the competitive position of "
                        + "{company} based on the SWOT analysis provided in context.")
                .expectedOutput("A concise, well-structured executive summary suitable for "
                        + "presentation to the board of directors.")
                .agent(writer)
                .context(List.of(swotTask))
                .build();

        // ========================
        // Build and run the ensemble
        // ========================

        EnsembleOutput output = Ensemble.builder()
                .agent(marketResearcher)
                .agent(financialAnalyst)
                .agent(strategist)
                .agent(writer)
                .task(marketTask)
                .task(financialTask)
                .task(swotTask)
                .task(summaryTask)
                .workflow(Workflow.PARALLEL)
                .input("company", company)
                .input("industry", industry)
                .build()
                .run();

        // ========================
        // Display results
        // ========================

        System.out.println("\n" + "=".repeat(60));
        System.out.println("EXECUTIVE SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());

        System.out.println("\n" + "-".repeat(60));
        System.out.println("ALL TASK OUTPUTS");
        System.out.println("-".repeat(60));
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("%n[%s]%n%s%n", taskOutput.getAgentRole(), taskOutput.getRaw());
        }

        System.out.printf(
                "%nCompleted in %s | %d total tool calls%n", output.getTotalDuration(), output.getTotalToolCalls());
    }
}
