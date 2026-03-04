package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.metrics.micrometer.MicrometerToolMetrics;
import net.agentensemble.tools.calculator.CalculatorTool;
import net.agentensemble.tools.datetime.DateTimeTool;

/**
 * Demonstrates tool metrics collection using MicrometerToolMetrics.
 *
 * This example:
 * - Configures a SimpleMeterRegistry for in-process metric collection
 * - Runs an ensemble with calculator and datetime tools instrumented with metrics
 * - Prints the recorded metrics (execution counts and timing) after the run
 *
 * In production, replace SimpleMeterRegistry with your preferred Micrometer
 * registry implementation (Prometheus, Datadog, InfluxDB, etc.).
 *
 * Prerequisites:
 * - Set the OPENAI_API_KEY environment variable.
 *
 * Run with:
 *   ./gradlew :agentensemble-examples:runMetrics
 */
public class MetricsExample {

    public static void main(String[] args) {
        // ========================
        // Set up metrics registry
        // ========================
        var registry = new SimpleMeterRegistry();
        var toolMetrics = new MicrometerToolMetrics(registry);

        // ========================
        // Build the ensemble
        // ========================
        var chatModel = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        var analyst = Agent.builder()
                .role("Data Analyst")
                .goal("Answer quantitative questions using calculation and date tools")
                .tools(List.of(new CalculatorTool(), new DateTimeTool()))
                .llm(chatModel)
                .maxIterations(10)
                .build();

        var task = Task.builder()
                .description("A project started on 2024-01-15. Calculate:\n"
                        + "1. What is today's date?\n"
                        + "2. How many days has the project been running "
                        + "(use the calculator to compute days between dates if needed)?\n"
                        + "3. If the project runs for 365 days total, "
                        + "what percentage complete is it? (use the calculator)")
                .expectedOutput("Current date, number of days the project has been running, "
                        + "and the percentage complete as a decimal to 2 places.")
                .agent(analyst)
                .build();

        // ========================
        // Run with metrics configured
        // ========================
        EnsembleOutput output = Ensemble.builder()
                .agent(analyst)
                .task(task)
                .toolMetrics(toolMetrics)
                .build()
                .run();

        System.out.println("=== Agent Output ===");
        System.out.println(output.getRaw());
        System.out.println();

        // ========================
        // Print collected metrics
        // ========================
        System.out.println("=== Tool Metrics ===");
        System.out.println("Total tool calls: " + output.getTotalToolCalls());
        System.out.println();

        // Execution counters per tool/agent/outcome
        System.out.println("--- Execution Counters ---");
        for (Counter counter :
                registry.find(MicrometerToolMetrics.METRIC_EXECUTIONS).counters()) {
            String toolName = counter.getId().getTag(MicrometerToolMetrics.TAG_TOOL_NAME);
            String agentRole = counter.getId().getTag(MicrometerToolMetrics.TAG_AGENT_ROLE);
            String outcome = counter.getId().getTag(MicrometerToolMetrics.TAG_OUTCOME);
            System.out.printf("  %-20s %-20s %-10s = %.0f%n", toolName, agentRole, outcome, counter.count());
        }
        System.out.println();

        // Duration timers per tool/agent
        System.out.println("--- Execution Timing ---");
        for (Timer timer : registry.find(MicrometerToolMetrics.METRIC_DURATION).timers()) {
            String toolName = timer.getId().getTag(MicrometerToolMetrics.TAG_TOOL_NAME);
            String agentRole = timer.getId().getTag(MicrometerToolMetrics.TAG_AGENT_ROLE);
            double totalMs = timer.totalTime(TimeUnit.MILLISECONDS);
            double meanMs = timer.count() > 0 ? totalMs / timer.count() : 0.0;
            System.out.printf(
                    "  %-20s %-20s count=%-4d mean=%.1fms total=%.1fms%n",
                    toolName, agentRole, timer.count(), meanMs, totalMs);
        }
    }
}
