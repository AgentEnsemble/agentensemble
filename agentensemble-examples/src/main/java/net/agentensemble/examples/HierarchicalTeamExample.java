package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.DefaultManagerPromptStrategy;
import net.agentensemble.workflow.ManagerPromptStrategy;
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
 * Worker agents are auto-synthesised from each task's description and expectedOutput.
 * The Manager decides task assignment and ordering at run time.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runHierarchicalTeam
 *
 * To analyse a different company:
 *   ./gradlew :agentensemble-examples:runHierarchicalTeam --args="Acme Corp"
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

        // Worker tasks use a faster model; the manager uses a more capable model
        var fastModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build();

        var powerfulModel =
                OpenAiChatModel.builder().apiKey(apiKey).modelName("gpt-4o").build();

        // ========================
        // Define tasks
        // ========================
        // Agents are auto-synthesised from the task descriptions.
        // The manager decides which synthesised agent handles each task at run time.

        var marketTask = Task.builder()
                .description("Analyse {company}'s current market position and competitive landscape")
                .expectedOutput("A market analysis covering: market share, top three competitors, "
                        + "key differentiators, and two to three growth opportunities")
                .build();

        var financialTask = Task.builder()
                .description("Review {company}'s financial health and key performance indicators")
                .expectedOutput("A financial summary covering: revenue trend (last 3 years), "
                        + "profitability metrics, balance sheet strength, and investment thesis")
                .build();

        var reportTask = Task.builder()
                .description(
                        "Write an executive investment brief for {company} based on market " + "and financial analysis")
                .expectedOutput("A 600-word executive brief with: company overview, market position, "
                        + "financial highlights, risks, and investment recommendation")
                .build();

        // ========================
        // Build and run the ensemble
        // ========================

        // Optional: inject a domain constraint into the manager's system prompt.
        // The default strategy (DefaultManagerPromptStrategy.DEFAULT) is used when
        // managerPromptStrategy() is not called. This demonstrates extending it:
        ManagerPromptStrategy investmentStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(net.agentensemble.workflow.ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx)
                        + "\n\nFocus on investment-relevant insights. "
                        + "Always ask the Financial Analyst to complete their analysis before the "
                        + "Report Writer begins synthesising.";
            }

            @Override
            public String buildUserPrompt(net.agentensemble.workflow.ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
            }
        };

        EnsembleOutput output = Ensemble.builder()
                .task(marketTask)
                .task(financialTask)
                .task(reportTask)
                .workflow(Workflow.HIERARCHICAL)
                .chatLanguageModel(fastModel)
                .managerLlm(powerfulModel)
                .managerMaxIterations(15)
                .managerPromptStrategy(investmentStrategy)
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
