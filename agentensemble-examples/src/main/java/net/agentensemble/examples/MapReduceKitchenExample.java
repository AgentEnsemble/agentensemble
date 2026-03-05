package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.task.TaskOutput;

/**
 * Demonstrates the static {@link MapReduceEnsemble} with a kitchen scenario.
 *
 * <p>Scenario: a busy restaurant receives a large order with many dishes. Rather than
 * sending all dish outputs to a single Head Chef (which would overflow the model's
 * context window), MapReduceEnsemble automatically builds a tree-reduction DAG:
 *
 * <pre>
 * chunkSize=3, 7 dishes:
 *
 *   Map phase:   [Risotto Chef] [Duck Chef] [Salmon Chef]  [Fondant Chef] [Soup Chef] [Lamb Chef]  [Tart Chef]
 *                        \           |           /                 \           |          /             |
 *   Reduce L1:        [Sub-Chef A]                              [Sub-Chef B]                        [Sub-Chef C]
 *                              \                                   /                                  /
 *   Final reduce:                            [Head Chef]
 * </pre>
 *
 * <p>Key concepts demonstrated:
 * <ul>
 *   <li>Structured output ({@code DishResult} record) works naturally in the map phase</li>
 *   <li>{@code chunkSize} controls how many map outputs each reduce agent receives</li>
 *   <li>{@code toEnsemble()} provides access to the pre-built DAG for devtools inspection</li>
 *   <li>All independent tasks at each level run concurrently via {@code Workflow.PARALLEL}</li>
 * </ul>
 *
 * <p>Run via:
 * <pre>
 *   ./gradlew :agentensemble-examples:runMapReduceKitchen
 * </pre>
 */
public class MapReduceKitchenExample {

    // ========================
    // Domain model
    // ========================

    record OrderItem(String dish, String cuisine, String dietaryNotes) {}

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

        System.out.printf("=== MapReduce Kitchen Order (%d dishes, chunkSize=3) ===%n%n", items.size());
        items.forEach(i -> System.out.printf(
                "  - %s (%s)%s%n",
                i.dish(), i.cuisine(), i.dietaryNotes().isEmpty() ? "" : " [" + i.dietaryNotes() + "]"));
        System.out.println();

        // Build the MapReduceEnsemble -- constructs the full tree-reduction DAG at build time.
        MapReduceEnsemble<OrderItem> mapReduce = MapReduceEnsemble.<OrderItem>builder()
                .items(items)

                // Map phase: one specialist chef per dish
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
                        .expectedOutput("Structured recipe result as JSON")
                        .agent(agent)
                        .outputType(DishResult.class)
                        .build())

                // Reduce phase: one Sub-Chef consolidates a group of dish preparations
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
                        .context(chunkTasks) // must wire context explicitly
                        .build())
                .chunkSize(3)
                .verbose(true)
                .build();

        // Inspect the pre-built DAG structure before running
        System.out.printf(
                "DAG: %d agents, %d tasks%n",
                mapReduce.toEnsemble().getAgents().size(),
                mapReduce.toEnsemble().getTasks().size());
        System.out.println();

        // Execute -- all independent tasks at each level run concurrently
        EnsembleOutput output = mapReduce.run();

        System.out.println();
        System.out.println("=== Final Meal Plan ===");
        System.out.println(output.getRaw());

        System.out.println("\n=== Individual Task Outputs ===");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("[%s]%n", taskOutput.getAgentRole());
            System.out.println(taskOutput.getRaw());
            System.out.println();
        }

        System.out.printf(
                "%nCompleted in %s | %d tasks | %d total tool calls%n",
                output.getTotalDuration(), output.getTaskOutputs().size(), output.getTotalToolCalls());
    }
}
