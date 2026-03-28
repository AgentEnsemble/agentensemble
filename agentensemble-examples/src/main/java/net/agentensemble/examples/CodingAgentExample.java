package net.agentensemble.examples;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Path;
import net.agentensemble.coding.CodingEnsemble;
import net.agentensemble.coding.CodingTask;
import net.agentensemble.ensemble.EnsembleOutput;

/**
 * Demonstrates the CodingAgent factory with {@link CodingEnsemble#run}.
 *
 * <p>The coding ensemble auto-detects the project type, assembles appropriate tools,
 * and runs the given coding task directly in the working directory.
 *
 * <p>Run with:
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :agentensemble-examples:runCodingAgent --args="/path/to/your/project"
 * </pre>
 *
 * <p>The first argument is the path to the project directory. If omitted, the current
 * directory is used.
 */
public final class CodingAgentExample {

    public static void main(String[] args) {
        Path projectDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        System.out.println("=".repeat(70));
        System.out.println("AgentEnsemble: Coding Agent Example");
        System.out.println("=".repeat(70));
        System.out.println("Project directory: " + projectDir.toAbsolutePath());
        System.out.println();

        EnsembleOutput output = CodingEnsemble.run(
                model,
                projectDir,
                CodingTask.fix("Find and fix any compilation errors or obvious bugs in the project"));

        System.out.println("\n--- Result ---");
        System.out.println(output.getRaw());
    }
}
