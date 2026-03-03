package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates a hierarchical workflow where a Manager agent coordinates a team of specialists.
 *
 * A product analysis team where a Manager coordinates:
 *   - Market Researcher  -- analyses market trends and competitive landscape
 *   - Financial Analyst  -- reviews financial metrics and projections
 *   - Report Writer      -- synthesises findings into an executive report
 *
 * The Manager decides task assignment and ordering at run time based on agent roles and goals.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runHierarchicalTeam
 *
 * To analyse a different company:
 *   ./gradlew :agentensemble-examples:runHierarchicalTeam --args="Tesla"
 */
public class HierarchicalTeamExample {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalTeamExample.class);

    public static void main(String[] args) throws Exception {
        String company = args.length > 0 ? args[0] : "Acme Corp";

        log.info("Starting hierarchical team workflow for company: {}", company);

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is not set. " + "Please set it to your OpenAI API key.");
        }

        // Worker agents use a faster model; the manager uses a more capable model
        var fastModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        var powerfulModel =
                OpenAiChatModel.builder().apiKey(apiKey).modelName("gpt-4o").build();

        // ========================
        // Define worker agents
        // ========================

        var marketResearcher = Agent.builder()
                .role("Market Research Analyst")
                .goal("Analyse market trends, competitive dynamics, and growth opportunities")
                .background("You specialise in technology sector market research. "
                        + "You provide concise, evidence-based insights.")
                .llm(fastModel)
                .build();

        var financialAnalyst = Agent.builder()
                .role("Financial Analyst")
                .goal("Analyse financial performance, metrics, and investment implications")
                .background("You are a CFA charter holder with 10 years of equity research experience. "
                        + "You focus on publicly available financial data.")
                .llm(fastModel)
                .build();

        var reportWriter = Agent.builder()
                .role("Executive Report Writer")
                .goal("Transform complex analysis into clear, compelling executive reports")
                .background("You write board-level reports. You use clear language, structured sections, "
                        + "and evidence-based conclusions.")
                .llm(fastModel)
                .build();

        // ========================
        // Define tasks
        // ========================
        // The manager decides which agent handles each task at run time.

        var marketTask = Task.builder()
                .description("Analyse {company}'s current market position and competitive landscape")
                .expectedOutput("A market analysis covering: market share, top three competitors, "
                        + "key differentiators, and two to three growth opportunities")
                .agent(marketResearcher)
                .build();

        var financialTask = Task.builder()
                .description("Review {company}'s financial health and key performance indicators")
                .expectedOutput("A financial summary covering: revenue trend (last 3 years), "
                        + "profitability metrics, balance sheet strength, and investment thesis")
                .agent(financialAnalyst)
                .build();

        var reportTask = Task.builder()
                .description(
                        "Write an executive investment brief for {company} based on market " + "and financial analysis")
                .expectedOutput("A 600-word executive brief with: company overview, market position, "
                        + "financial highlights, risks, and investment recommendation")
                .agent(reportWriter)
                .build();

        // ========================
        // Build and run the ensemble
        // ========================

        EnsembleOutput output = Ensemble.builder()
                .agent(marketResearcher)
                .agent(financialAnalyst)
                .agent(reportWriter)
                .task(marketTask)
                .task(financialTask)
                .task(reportTask)
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(powerfulModel)
                .managerMaxIterations(15)
                .input("company", company)
                .build()
                .run();

        // ========================
        // Display results
        // ========================

        System.out.println("\n" + "=".repeat(60));
        System.out.printf("EXECUTIVE BRIEF: %s%n", company);
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());

        // Individual worker outputs (all elements except the last, which is the manager's synthesis)
        System.out.println("\n" + "-".repeat(60));
        System.out.println("WORKER OUTPUTS");
        System.out.println("-".repeat(60));
        java.util.List<TaskOutput> taskOutputs = output.getTaskOutputs();
        for (int i = 0; i < taskOutputs.size() - 1; i++) {
            TaskOutput task = taskOutputs.get(i);
            System.out.printf("%n[%s]%n%s%n", task.getAgentRole(), task.getRaw());
        }

        System.out.printf("%nTotal duration: %s%n", output.getTotalDuration());
    }
}
