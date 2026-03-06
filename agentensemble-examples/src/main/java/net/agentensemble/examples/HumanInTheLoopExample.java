package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates human-in-the-loop review gates on individual tasks.
 *
 * Three review patterns are shown:
 *
 *   1. beforeReview -- a pre-flight gate: the reviewer approves before the task starts.
 *      A 30-second timeout auto-continues if no input arrives, so the demo is
 *      non-blocking when run unattended.
 *
 *   2. review (post-execution gate) -- the reviewer reads the completed output and
 *      approves or rejects it. A 60-second timeout stops the ensemble (EXIT_EARLY) if
 *      no response is received.
 *
 *   3. Review.skip() -- explicitly bypasses the review gate on the final task,
 *      even though a default reviewHandler is registered on the ensemble.
 *
 * ReviewHandler.console() reads from stdin and writes prompts to stdout, making it
 * suitable for interactive terminal sessions.
 *
 * The v2 EnsembleOutput API provides:
 *   - isComplete()          -- true if all tasks finished without being halted
 *   - getExitReason()       -- explains why execution stopped (if not complete)
 *   - completedTasks()      -- list of TaskOutput for every task that ran to completion
 *   - lastCompletedOutput() -- raw text of the most recently completed task
 *   - getOutput(task)       -- output for a specific Task reference
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runHumanInTheLoop
 *
 * To change the product name:
 *   ./gradlew :agentensemble-examples:runHumanInTheLoop --args="AgentEnsemble v2"
 */
public class HumanInTheLoopExample {

    private static final Logger log = LoggerFactory.getLogger(HumanInTheLoopExample.class);

    public static void main(String[] args) throws Exception {
        String product = args.length > 0 ? String.join(" ", args) : "AgentEnsemble v2";

        log.info("Starting human-in-the-loop workflow for product: {}", product);

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
        // Task 1: Announcement draft
        // beforeReview gates execution BEFORE the task starts.
        // If no response arrives within 30 seconds, execution continues automatically.
        // ========================
        var draftTask = Task.builder()
                .description("Draft a one-paragraph product announcement for {product}")
                .expectedOutput("A single compelling paragraph announcing the product launch")
                .beforeReview(Review.builder()
                        .prompt("Proceed with drafting the product announcement for '{product}'?")
                        .timeout(Duration.ofSeconds(30))
                        .onTimeout(OnTimeoutAction.CONTINUE)
                        .build())
                .build();

        // ========================
        // Task 2: Press release
        // review gates execution AFTER the task completes.
        // The reviewer reads the output, then approves or rejects.
        // If no response arrives within 60 seconds, the ensemble exits early.
        // ========================
        var pressReleaseTask = Task.builder()
                .description("Expand the announcement into a 300-word press release for {product}")
                .expectedOutput("A 300-word press release with a headline, body paragraphs, and a boilerplate")
                .context(List.of(draftTask))
                .review(Review.builder()
                        .prompt("Review the press release above. Approve to continue publishing, or reject to stop.")
                        .timeout(Duration.ofSeconds(60))
                        .onTimeout(OnTimeoutAction.EXIT_EARLY)
                        .build())
                .build();

        // ========================
        // Task 3: Social media post
        // Review.skip() explicitly bypasses the gate for this task.
        // ========================
        var socialTask = Task.builder()
                .description("Write a 280-character social media post announcing {product}")
                .expectedOutput("A punchy 280-character social post with relevant hashtags")
                .context(List.of(pressReleaseTask))
                .review(Review.skip())
                .build();

        // ========================
        // Build and run
        // ReviewHandler.console() prompts the user interactively on stdin/stdout.
        // ========================
        EnsembleOutput output = Ensemble.builder()
                .task(draftTask)
                .task(pressReleaseTask)
                .task(socialTask)
                .chatLanguageModel(model)
                .reviewHandler(ReviewHandler.console())
                .input("product", product)
                .build()
                .run();

        // ========================
        // Inspect results using the v2 EnsembleOutput API
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.printf("WORKFLOW RESULT: %s%n", product);
        System.out.println("=".repeat(60));

        System.out.printf("Complete:    %s%n", output.isComplete());
        System.out.printf("Exit reason: %s%n", output.getExitReason());
        System.out.printf("Tasks done:  %d / %d%n", output.completedTasks().size(), 3);

        System.out.println("\nCompleted task outputs:");
        for (TaskOutput taskOutput : output.completedTasks()) {
            System.out.printf("%n  [%s]%n  %s%n", taskOutput.getAgentRole(), truncate(taskOutput.getRaw(), 120));
        }

        System.out.println("\nLast completed output:");
        output.lastCompletedOutput().ifPresent(o -> System.out.println(o.getRaw()));

        // Access a specific task output by reference
        Optional<TaskOutput> draftOutput = output.getOutput(draftTask);
        draftOutput.ifPresent(o -> {
            System.out.println("\nDraft task output (via getOutput):");
            System.out.println(o.getRaw());
        });

        System.out.printf("%nTotal duration: %s%n", output.getTotalDuration());
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
