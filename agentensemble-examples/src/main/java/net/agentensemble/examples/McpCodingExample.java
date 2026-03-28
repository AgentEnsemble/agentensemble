package net.agentensemble.examples;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.coding.CodingTask;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mcp.McpServerLifecycle;
import net.agentensemble.mcp.McpToolFactory;

/**
 * Demonstrates a coding agent backed by MCP reference servers.
 *
 * <p>This example starts the MCP filesystem and git servers, combines their tools
 * into a single agent, and runs a coding task. The MCP servers are managed with
 * try-with-resources so they are automatically shut down when the task completes.
 *
 * <p>Requires Node.js ({@code npx}) to be installed and available on the system PATH.
 *
 * <p>Run with:
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :agentensemble-examples:runMcpCoding --args="/path/to/git/project"
 * </pre>
 *
 * <p>The first argument is the path to a git-tracked project directory. If omitted,
 * the current directory is used.
 */
public final class McpCodingExample {

    public static void main(String[] args) {
        Path projectDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        System.out.println("=".repeat(70));
        System.out.println("AgentEnsemble: MCP Coding Example");
        System.out.println("=".repeat(70));
        System.out.println("Project directory: " + projectDir.toAbsolutePath());
        System.out.println();

        // Start MCP filesystem and git servers scoped to the project directory.
        // Both are AutoCloseable -- try-with-resources ensures clean shutdown.
        try (McpServerLifecycle fs = McpToolFactory.filesystem(projectDir);
                McpServerLifecycle git = McpToolFactory.git(projectDir)) {

            fs.start();
            git.start();

            // Combine filesystem and git tools into a single list
            List<Object> mcpTools = new ArrayList<>();
            mcpTools.addAll(fs.tools());
            mcpTools.addAll(git.tools());

            System.out.println("MCP tools available: " + mcpTools.size());
            System.out.println();

            // Build a coding agent with MCP tools
            Agent agent = Agent.builder()
                    .role("Senior Software Engineer")
                    .goal("Implement, debug, and refactor code with precision. "
                            + "Read the codebase to understand context, make focused changes, "
                            + "and verify correctness by running tests.")
                    .tools(mcpTools)
                    .llm(model)
                    .maxIterations(75)
                    .build();

            // Use CodingTask for a pre-configured bug-fix task
            Task task = CodingTask.fix("Find and fix any compilation errors or obvious bugs in the project").toBuilder()
                    .agent(agent)
                    .build();

            EnsembleOutput output = Ensemble.run(model, task);

            System.out.println("\n--- Result ---");
            System.out.println(output.getRaw());
        }
    }
}
