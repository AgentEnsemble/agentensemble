package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.MapReduceLevelSummary;
import net.agentensemble.trace.TaskTrace;

/**
 * Demonstrates the adaptive {@link MapReduceEnsemble} with a kitchen scenario.
 *
 * <p>Unlike the static strategy ({@code chunkSize}), the adaptive strategy measures actual
 * output token counts after each level and groups them to fit within {@code targetTokenBudget}.
 * This eliminates the need to guess output sizes upfront.
 *
 * <p>After the map phase, the framework:
 * <ol>
 *   <li>Estimates total output tokens. If the total fits within the budget, runs one final
 *       reduce and returns.</li>
 *   <li>Otherwise, bin-packs outputs (first-fit-decreasing) into groups within the budget,
 *       runs one parallel reduce per group, and repeats.</li>
 * </ol>
 *
 * <p>Key concepts demonstrated:
 * <ul>
 *   <li>{@code targetTokenBudget} drives reduction instead of a fixed {@code chunkSize}</li>
 *   <li>The same {@code reduceTask} factory (with {@code context(chunkTasks)}) works for
 *       both static and adaptive modes</li>
 *   <li>Post-execution {@code trace.getMapReduceLevels()} exposes per-level timing and task counts</li>
 *   <li>{@code CaptureMode.STANDARD} captures full LLM message history per task</li>
 * </ul>
 *
 * <p>Run via:
 * <pre>
 *   ./gradlew :agentensemble-examples:runMapReduceAdaptiveKitchen
 * </pre>
 */
public class MapReduceAdaptiveKitchenExample {

    // ========================
    // Domain model
    // ========================

    record OrderItem(String dish, String cuisine, String dietaryNotes) {}

    // ========================
    // Entry point
    // ========================

    public static void main(String[] args) {

        var model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        List<OrderItem> items = List.of(
                new OrderItem("Truffle Risotto", "Italian", "vegetarian"),
                new OrderItem("Pan-seared Duck Breast", "French", ""),
                new OrderItem("Miso-glazed Salmon", "Japanese", "gluten-free"),
                new OrderItem("Dark Chocolate Fondant", "French", "contains nuts"),
                new OrderItem("French Onion Soup", "French", ""),
                new OrderItem("Herb-crusted Lamb Rack", "Mediterranean", ""),
                new OrderItem("Lemon Tart", "French", "gluten-free option available"));

        System.out.printf(
                "=== Adaptive MapReduce Kitchen Order (%d dishes, targetTokenBudget=4000) ===%n%n", items.size());
        items.forEach(i -> System.out.printf(
                "  - %s (%s)%s%n",
                i.dish(), i.cuisine(), i.dietaryNotes().isEmpty() ? "" : " [" + i.dietaryNotes() + "]"));
        System.out.println();

        // Build the adaptive MapReduceEnsemble.
        //
        // The DAG shape is NOT known at build time. After the map phase runs, the framework
        // measures each output's token count and bin-packs groups that fit within 4000 tokens.
        // If the L1 reduce outputs still exceed 4000 tokens total, another reduce level runs.
        EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
                .items(items)

                // Map phase: one specialist chef per dish (identical to static mode)
                .mapAgent(item -> Agent.builder()
                        .role(item.dish() + " Chef")
                        .goal("Prepare " + item.dish() + " to perfection")
                        .background("You are an expert in " + item.cuisine() + " cuisine. "
                                + "You know every technique, ingredient, and presentation standard "
                                + "for classic " + item.cuisine() + " dishes.")
                        .llm(model)
                        .build())
                .mapTask((item, agent) -> Task.builder()
                        .description("Prepare the recipe for " + item.dish() + ". "
                                + (item.dietaryNotes().isEmpty()
                                        ? ""
                                        : "Dietary requirements: " + item.dietaryNotes() + ". ")
                                + "Provide key ingredients, preparation steps, cook time in minutes, "
                                + "and a brief plating description.")
                        .expectedOutput("Recipe with ingredients, steps, cook time, and plating.")
                        .agent(agent)
                        .build())

                // Reduce phase: same factory as static mode -- context wiring is unchanged
                .reduceAgent(() -> Agent.builder()
                        .role("Sub-Chef")
                        .goal("Consolidate dish preparations into a cohesive sub-plan")
                        .background("You are a senior sous chef who coordinates multiple dishes. "
                                + "You ensure timing, dietary restrictions, and plating work together.")
                        .llm(model)
                        .build())
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Review the dish preparations provided in context. "
                                + "Create a consolidated sub-plan: note timing dependencies, "
                                + "common mise en place, and any coordination required between these dishes.")
                        .expectedOutput("A coordinated sub-plan covering timing, shared prep steps, "
                                + "and any cross-dish coordination notes.")
                        .agent(agent)
                        .context(chunkTasks) // wire context explicitly -- same as static mode
                        .build())

                // Adaptive strategy: keep reducing until total context < 4000 tokens.
                // The framework measures actual output token counts after each level.
                .targetTokenBudget(4_000)
                .maxReduceLevels(5) // safety valve
                .captureMode(CaptureMode.STANDARD)
                .verbose(true)
                .build()
                .run();

        System.out.println();
        System.out.println("=== Final Meal Plan ===");
        System.out.println(output.getRaw());

        // Inspect per-level breakdown from the aggregated trace
        System.out.println("\n=== Per-Level Breakdown ===");
        for (MapReduceLevelSummary level : output.getTrace().getMapReduceLevels()) {
            System.out.printf(
                    "  Level %d (%s): %d tasks | duration=%s%n",
                    level.getLevel(),
                    level.getLevel() == 0
                            ? "map"
                            : (level.getLevel()
                                            == output.getTrace()
                                                            .getMapReduceLevels()
                                                            .size()
                                                    - 1
                                    ? "final-reduce"
                                    : "reduce"),
                    level.getTaskCount(),
                    level.getDuration());
        }

        // Show task traces with nodeType annotations
        System.out.println("\n=== Task Traces (annotated) ===");
        for (TaskTrace taskTrace : output.getTrace().getTaskTraces()) {
            System.out.printf(
                    "[%s | nodeType=%s | level=%d]%n",
                    taskTrace.getAgentRole(), taskTrace.getNodeType(), taskTrace.getMapReduceLevel());
        }

        System.out.println("\n=== Individual Task Outputs ===");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("[%s]%n", taskOutput.getAgentRole());
            System.out.println(taskOutput.getRaw());
            System.out.println();
        }

        System.out.printf(
                "%nCompleted in %s | %d task outputs | %d total tool calls%n",
                output.getTotalDuration(), output.getTaskOutputs().size(), output.getTotalToolCalls());
    }
}
