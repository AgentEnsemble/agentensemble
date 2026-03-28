package net.agentensemble.examples;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.file.Path;
import net.agentensemble.coding.CodingEnsemble;
import net.agentensemble.coding.CodingTask;
import net.agentensemble.ensemble.EnsembleOutput;

/**
 * Demonstrates isolated coding with {@link CodingEnsemble#runIsolated}.
 *
 * <p>The isolated runner creates a git worktree, runs the coding agent there, and
 * preserves the worktree on success so you can review and merge the changes.
 *
 * <p>Run with:
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   ./gradlew :agentensemble-examples:runIsolatedCoding --args="/path/to/git/repo"
 * </pre>
 *
 * <p>The first argument must be the root of a git repository.
 */
public final class IsolatedCodingExample {

    public static void main(String[] args) {
        Path repoRoot = args.length > 0 ? Path.of(args[0]) : Path.of(".");

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        System.out.println("=".repeat(70));
        System.out.println("AgentEnsemble: Isolated Coding Example");
        System.out.println("=".repeat(70));
        System.out.println("Repository root: " + repoRoot.toAbsolutePath());
        System.out.println();

        EnsembleOutput output = CodingEnsemble.runIsolated(
                model, repoRoot, CodingTask.implement("Add a README.md file with a project overview"));

        System.out.println("\n--- Result ---");
        System.out.println(output.getRaw());
        System.out.println("\nThe worktree has been preserved. Review the changes and merge when ready.");
    }
}
