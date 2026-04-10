package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Map;
import net.agentensemble.executor.AgentSpec;
import net.agentensemble.executor.EnsembleExecutor;
import net.agentensemble.executor.EnsembleRequest;
import net.agentensemble.executor.SimpleModelProvider;
import net.agentensemble.executor.SimpleToolProvider;
import net.agentensemble.executor.TaskExecutor;
import net.agentensemble.executor.TaskRequest;
import net.agentensemble.executor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates the agentensemble-executor module for direct in-process invocation from an
 * external workflow engine (Temporal, AWS Step Functions, Kafka Streams, etc.).
 *
 * Two modes are shown:
 *   Part 1 -- TaskExecutor: each AgentEnsemble task is called as a separate step.
 *             The caller passes context (upstream outputs) between steps explicitly.
 *             This mirrors how a Temporal workflow would sequence activities.
 *
 *   Part 2 -- EnsembleExecutor: a full multi-task ensemble runs inside a single call.
 *             AgentEnsemble's internal sequential orchestration handles task ordering.
 *
 * Heartbeat callbacks are wired to log output, illustrating how Temporal would receive
 * heartbeats from Activity.getExecutionContext()::heartbeat.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runExecutor
 *
 * To change the topic:
 *   ./gradlew :agentensemble-examples:runExecutor --args="quantum computing"
 */
public class ExecutorExample {

    private static final Logger log = LoggerFactory.getLogger(ExecutorExample.class);

    public static void main(String[] args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "the future of AI agents";

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY environment variable is not set.");
        }

        var model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();

        var modelProvider = SimpleModelProvider.of(model);
        var toolProvider = SimpleToolProvider.empty(); // no external tools in this example

        log.info("=== Part 1: TaskExecutor (task-per-activity pattern) ===");
        runWithTaskExecutor(topic, modelProvider, toolProvider);

        log.info("");
        log.info("=== Part 2: EnsembleExecutor (ensemble-per-activity pattern) ===");
        runWithEnsembleExecutor(topic, modelProvider, toolProvider);
    }

    // ========================
    // Part 1: TaskExecutor
    // ========================

    /**
     * Demonstrates calling AgentEnsemble one task at a time. Context (the research output)
     * is passed explicitly to the downstream writing task -- mirroring how a Temporal workflow
     * would pass activity results between sequential activities.
     */
    private static void runWithTaskExecutor(
            String topic, SimpleModelProvider modelProvider, SimpleToolProvider toolProvider) {
        var executor = new TaskExecutor(modelProvider, toolProvider);

        // Step 1: research
        log.info("Executing research task for topic: {}", topic);
        TaskResult research = executor.execute(
                TaskRequest.builder()
                        .description("Research the topic: {topic}")
                        .expectedOutput("A concise research summary with key findings")
                        .agent(AgentSpec.of("Research Analyst", "Find accurate information on any topic"))
                        .inputs(Map.of("topic", topic))
                        .build(),
                // In Temporal this would be: Activity.getExecutionContext()::heartbeat
                // Here we log the heartbeat detail to show what would be sent.
                heartbeat -> log.info("  [heartbeat] {}", heartbeat));

        log.info("Research complete: {} chars, exit={}", research.output().length(), research.exitReason());

        // Step 2: write -- injects research output as a template variable {research}
        log.info("Executing writing task for topic: {}", topic);
        TaskResult article = executor.execute(
                TaskRequest.builder()
                        .description("Write a blog post about {topic} based on this research: {research}")
                        .expectedOutput("A polished, engaging 300-word blog post")
                        .agent(AgentSpec.of("Technical Writer", "Write clear, compelling content"))
                        .context(Map.of("research", research.output()))
                        .inputs(Map.of("topic", topic))
                        .build(),
                heartbeat -> log.info("  [heartbeat] {}", heartbeat));

        log.info("Writing complete: exit={}", article.exitReason());
        log.info("Final article ({} chars):", article.output().length());
        log.info("---");
        log.info("{}", article.output());
        log.info("---");
    }

    // ========================
    // Part 2: EnsembleExecutor
    // ========================

    /**
     * Demonstrates running a full two-task ensemble as a single call.
     * AgentEnsemble handles the sequential orchestration internally.
     */
    private static void runWithEnsembleExecutor(
            String topic, SimpleModelProvider modelProvider, SimpleToolProvider toolProvider) {
        var executor = new EnsembleExecutor(modelProvider, toolProvider);

        var result = executor.execute(
                EnsembleRequest.builder()
                        .task(TaskRequest.of("Research {topic}", "A concise research summary"))
                        .task(TaskRequest.of(
                                "Write a blog post about {topic}", "A polished, engaging 300-word blog post"))
                        .workflow("SEQUENTIAL")
                        .inputs(Map.of("topic", topic))
                        .build(),
                heartbeat -> log.info("  [heartbeat] {}", heartbeat));

        log.info(
                "Ensemble complete: {} tasks, {}ms, exit={}",
                result.taskOutputs().size(),
                result.totalDurationMs(),
                result.exitReason());
        log.info("Final output ({} chars):", result.finalOutput().length());
        log.info("---");
        log.info("{}", result.finalOutput());
        log.info("---");
    }
}
