package net.agentensemble.examples;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.tools.coding.BuildRunnerTool;
import net.agentensemble.tools.coding.CodeEditTool;
import net.agentensemble.tools.coding.CodeSearchTool;
import net.agentensemble.tools.coding.GitTool;
import net.agentensemble.tools.coding.GlobTool;
import net.agentensemble.tools.coding.ShellTool;
import net.agentensemble.tools.coding.TestRunnerTool;

/**
 * Demonstrates assembling all 7 coding tools directly without the CodingAgent factory.
 *
 * <p>This gives full control over tool configuration: approval gates, timeouts,
 * and which tools to include. Compare with {@link CodingAgentExample} which uses
 * the higher-level {@code CodingEnsemble.run()} API.
 *
 * <p>Run with:
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :agentensemble-examples:runCodingTools --args="/path/to/project"
 * </pre>
 *
 * <p>The first argument is the path to the project directory. If omitted, the current
 * directory is used.
 */
public final class CodingToolsExample {

    public static void main(String[] args) {
        Path workspace = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        System.out.println("=".repeat(70));
        System.out.println("AgentEnsemble: Coding Tools (Raw Assembly) Example");
        System.out.println("=".repeat(70));
        System.out.println("Workspace: " + workspace.toAbsolutePath());
        System.out.println();

        // Assemble all 7 coding tools with explicit configuration
        List<Object> tools = List.of(
                GlobTool.of(workspace),
                CodeSearchTool.of(workspace),
                CodeEditTool.builder(workspace).requireApproval(true).build(),
                ShellTool.builder(workspace)
                        .requireApproval(true)
                        .timeout(Duration.ofSeconds(60))
                        .build(),
                GitTool.builder(workspace).requireApproval(true).build(),
                BuildRunnerTool.of(workspace),
                TestRunnerTool.of(workspace));

        Agent agent = Agent.builder()
                .role("Senior Software Engineer")
                .goal("Fix bugs and implement features in the codebase")
                .tools(tools)
                .llm(model)
                .maxIterations(75)
                .build();

        Task task = Task.builder()
                .description("Explore the project structure, identify any compilation errors "
                        + "or obvious bugs, and fix them. Run the build to verify your fixes.")
                .expectedOutput("A summary of the issues found and fixed, with build verification.")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.run(model, task);

        System.out.println("\n--- Result ---");
        System.out.println(output.getRaw());
    }
}
