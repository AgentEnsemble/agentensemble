package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workflow.Workflow;

/**
 * Demonstrates the callback and event listener API.
 *
 * Shows two listener registration styles:
 *   1. Lambda convenience methods (.onTaskStart, .onTaskComplete, .onTaskFailed, .onToolCall)
 *   2. Full EnsembleListener interface implementation (MetricsCollector)
 *
 * A Researcher and Writer run sequentially. Listeners observe every lifecycle
 * event and print a summary at the end.
 *
 * Usage:
 *   Set OPENAI_API_KEY environment variable, then run:
 *   ./gradlew :agentensemble-examples:runCallbacks
 *
 * To change the topic:
 *   ./gradlew :agentensemble-examples:runCallbacks --args="quantum computing"
 */
public class CallbackExample {

    // ========================
    // Full listener implementation
    // ========================

    /**
     * Collects execution metrics across all tasks and tools.
     *
     * Demonstrates the full EnsembleListener interface: override only the
     * event methods you care about; the rest default to no-ops.
     */
    static class MetricsCollector implements EnsembleListener {

        private final List<String> completedTasks = new ArrayList<>();
        private final List<String> failedTasks = new ArrayList<>();
        private final List<String> toolsCalled = new ArrayList<>();
        private Duration totalTaskDuration = Duration.ZERO;

        @Override
        public void onTaskStart(TaskStartEvent event) {
            System.out.printf(
                    "[METRICS] Task %d/%d started | Agent: %s%n",
                    event.taskIndex(), event.totalTasks(), event.agentRole());
        }

        @Override
        public void onTaskComplete(TaskCompleteEvent event) {
            completedTasks.add(event.agentRole() + " - " + truncate(event.taskDescription(), 40));
            totalTaskDuration = totalTaskDuration.plus(event.duration());
            System.out.printf(
                    "[METRICS] Task %d/%d completed | Agent: %s | Duration: %s%n",
                    event.taskIndex(), event.totalTasks(), event.agentRole(), event.duration());
        }

        @Override
        public void onTaskFailed(TaskFailedEvent event) {
            failedTasks.add(event.agentRole() + " - " + truncate(event.taskDescription(), 40));
            System.out.printf(
                    "[METRICS] Task %d/%d FAILED | Agent: %s | Error: %s%n",
                    event.taskIndex(),
                    event.totalTasks(),
                    event.agentRole(),
                    event.cause().getMessage());
        }

        @Override
        public void onToolCall(ToolCallEvent event) {
            toolsCalled.add(event.toolName() + " (" + event.agentRole() + ")");
            System.out.printf(
                    "[METRICS] Tool called: %s | Agent: %s | Duration: %s%n",
                    event.toolName(), event.agentRole(), event.duration());
        }

        void printSummary() {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("EXECUTION METRICS SUMMARY");
            System.out.println("=".repeat(60));
            System.out.printf("Tasks completed: %d%n", completedTasks.size());
            completedTasks.forEach(t -> System.out.println("  - " + t));
            if (!failedTasks.isEmpty()) {
                System.out.printf("Tasks failed: %d%n", failedTasks.size());
                failedTasks.forEach(t -> System.out.println("  - " + t));
            }
            System.out.printf("Total task duration: %s%n", totalTaskDuration);
            System.out.printf("Tool calls made: %d%n", toolsCalled.size());
            toolsCalled.forEach(t -> System.out.println("  - " + t));
        }

        private static String truncate(String text, int max) {
            return text.length() > max ? text.substring(0, max) + "..." : text;
        }
    }

    // ========================
    // Main
    // ========================

    public static void main(String[] args) throws Exception {
        String topic = args.length > 0 ? String.join(" ", args) : "the future of AI agents";

        System.out.println("=".repeat(60));
        System.out.println("AgentEnsemble Callback Example");
        System.out.println("Topic: " + topic);
        System.out.println("=".repeat(60));

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
        // Define agents and tasks
        // ========================
        var researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics thoroughly and produce clear summaries")
                .background("You are a knowledgeable research analyst.")
                .llm(model)
                .build();

        var writer = Agent.builder()
                .role("Writer")
                .goal("Write concise, engaging content based on research")
                .background("You are an experienced technology writer.")
                .llm(model)
                .build();

        var researchTask = Task.builder()
                .description("Research {topic} and summarize the 3 most important trends in 200 words.")
                .expectedOutput("A 200-word research summary with 3 key trends.")
                .agent(researcher)
                .build();

        var writeTask = Task.builder()
                .description(
                        "Based on the research, write a 150-word introduction paragraph for a blog post about {topic}.")
                .expectedOutput("A compelling 150-word opening paragraph.")
                .agent(writer)
                .context(List.of(researchTask))
                .build();

        // ========================
        // Create the metrics collector (full interface)
        // ========================
        MetricsCollector metrics = new MetricsCollector();

        // Counter for demonstration of lambda accumulation
        AtomicInteger taskStartCount = new AtomicInteger(0);

        // ========================
        // Build and run with callbacks
        // ========================
        var ensemble = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(writeTask)
                .workflow(Workflow.SEQUENTIAL)
                // Full interface listener: collects all metrics
                .listener(metrics)
                // Lambda convenience: simple progress indicator
                .onTaskStart(event -> {
                    taskStartCount.incrementAndGet();
                    System.out.printf(
                            "%n[PROGRESS] Starting task %d of %d...%n", event.taskIndex(), event.totalTasks());
                })
                // Lambda convenience: alert on failure (would notify external system in production)
                .onTaskFailed(event -> System.out.printf(
                        "[ALERT] Task failure detected for agent '%s': %s%n",
                        event.agentRole(), event.cause().getMessage()))
                .build();

        System.out.println("\nRunning ensemble with callbacks registered...\n");

        EnsembleOutput output = ensemble.run(Map.of("topic", topic));

        // ========================
        // Display results
        // ========================
        System.out.println("\n" + "=".repeat(60));
        System.out.println("RESEARCH SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(output.getTaskOutputs().get(0).getRaw());

        System.out.println("\n" + "=".repeat(60));
        System.out.println("BLOG INTRODUCTION");
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());

        // Print collected metrics from the full listener
        metrics.printSummary();

        System.out.printf("%nTotal tasks started (lambda counter): %d%n", taskStartCount.get());
        System.out.printf(
                "Total duration: %s | Total tool calls: %d%n", output.getTotalDuration(), output.getTotalToolCalls());
    }
}
