package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.task.TaskOutput;

/**
 * Demonstrates the task-first API (v2.0.0) of {@link MapReduceEnsemble}.
 *
 * <p>This is the same restaurant kitchen scenario used in
 * {@link MapReduceKitchenExample}, but agents are <b>omitted entirely</b>. The framework
 * synthesises an appropriate agent for each task automatically from the task description
 * using the default template-based {@code AgentSynthesizer} -- no extra LLM call needed.
 *
 * <p>Three approaches are shown, from simplest to most configurable:
 *
 * <ol>
 *   <li><b>Zero-ceremony factory</b>: one static method call --
 *       {@code MapReduceEnsemble.of(model, items, mapDesc, reduceDesc)}</li>
 *   <li><b>Task-first builder</b>: full control over task descriptions and
 *       expected outputs, structured output, no agent declarations.</li>
 *   <li><b>Task-first with tools</b>: tools declared on tasks, synthesised agent
 *       automatically inherits them.</li>
 * </ol>
 *
 * <p>Run via:
 * <pre>
 *   ./gradlew :agentensemble-examples:runMapReduceTaskFirstKitchen
 * </pre>
 */
public class MapReduceTaskFirstKitchenExample {

    // ========================
    // Domain model
    // ========================

    record OrderItem(String dish, String cuisine, String dietaryNotes) {
        @Override
        public String toString() {
            return dish + " (" + cuisine + ")" + (dietaryNotes.isBlank() ? "" : " [" + dietaryNotes + "]");
        }
    }

    record DishResult(String dish, List<String> ingredients, int prepMinutes, String plating) {}

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

        System.out.println("=== MapReduce Task-First Kitchen Example ===");
        System.out.println();
        System.out.printf("Processing %d dishes (chunkSize=3)%n", items.size());
        items.forEach(i -> System.out.printf("  - %s%n", i));
        System.out.println();

        // ========================
        // Approach 1: Zero-ceremony factory
        // ========================

        System.out.println("--- Approach 1: Zero-ceremony factory ---");
        System.out.println();

        EnsembleOutput zeroCeremonyOutput = MapReduceEnsemble.of(
                model,
                items,
                "Prepare a complete recipe for the following dish:",
                "Combine these individual dish recipes into a unified dinner service plan. "
                        + "Include timing coordination, shared preparation steps, and service order.");

        System.out.println("=== Meal Plan (zero-ceremony) ===");
        System.out.println(zeroCeremonyOutput.getRaw());
        System.out.printf(
                "%nCompleted in %s | %d task outputs%n",
                zeroCeremonyOutput.getTotalDuration(),
                zeroCeremonyOutput.getTaskOutputs().size());

        System.out.println();
        System.out.println("=================================================");
        System.out.println();

        // ========================
        // Approach 2: Task-first builder
        // ========================

        System.out.println("--- Approach 2: Task-first builder ---");
        System.out.println();

        // No agent declarations -- the framework synthesises agents from task descriptions.
        // Structured output, per-task expected output, and chunkSize are all configurable.
        MapReduceEnsemble<OrderItem> taskFirstMre = MapReduceEnsemble.<OrderItem>builder()
                .chatLanguageModel(model)
                .items(items)

                // Map phase: one task per dish, agent synthesised from description
                .mapTask(item -> Task.builder()
                        .description("Prepare the complete recipe for: "
                                + item.dish()
                                + " ("
                                + item.cuisine()
                                + " cuisine). "
                                + (item.dietaryNotes().isBlank()
                                        ? ""
                                        : "Dietary requirements: " + item.dietaryNotes() + ". ")
                                + "Include key ingredients, preparation steps, cook time in minutes, "
                                + "and a brief plating description.")
                        .expectedOutput("Structured recipe result as JSON")
                        .outputType(DishResult.class) // structured output
                        .build())

                // Reduce phase: one task per group, agent synthesised from description
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Review the dish preparations provided in context. "
                                + "Create a consolidated service sub-plan: note timing dependencies, "
                                + "common mise en place, and any coordination required between these dishes.")
                        .expectedOutput("A coordinated sub-plan covering timing, shared preparation steps, "
                                + "and cross-dish coordination notes.")
                        .context(chunkTasks) // required: wire upstream task outputs
                        .build())
                .chunkSize(3)
                .verbose(true)
                .build();

        System.out.printf(
                "DAG: %d tasks (task-first: no explicit agents)%n",
                taskFirstMre.toEnsemble().getTasks().size());
        System.out.println();

        EnsembleOutput taskFirstOutput = taskFirstMre.run();

        System.out.println();
        System.out.println("=== Final Meal Plan (task-first) ===");
        System.out.println(taskFirstOutput.getRaw());

        System.out.println();
        System.out.println("=== Individual Task Outputs ===");
        for (TaskOutput taskOutput : taskFirstOutput.getTaskOutputs()) {
            System.out.printf("[%s]%n", taskOutput.getAgentRole()); // synthesised role shown here
            System.out.println(taskOutput.getRaw());
            System.out.println();
        }

        System.out.printf(
                "Completed in %s | %d tasks | %d total tool calls%n",
                taskFirstOutput.getTotalDuration(),
                taskFirstOutput.getTaskOutputs().size(),
                taskFirstOutput.getTotalToolCalls());
    }
}
