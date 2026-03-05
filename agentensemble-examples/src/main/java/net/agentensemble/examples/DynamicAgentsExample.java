package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

/**
 * Demonstrates dynamic agent creation using the existing Ensemble + Workflow.PARALLEL API.
 *
 * Scenario: a restaurant kitchen receives an order with multiple dishes. A separate
 * specialist agent is created for each dish (fan-out). All specialists work in parallel.
 * A Head Chef then aggregates all their preparations into a final meal plan (fan-in).
 *
 * Key concepts:
 * - Agents and Tasks are ordinary Java objects -- they can be created dynamically in a loop.
 * - Workflow.PARALLEL automatically derives concurrency from context() declarations.
 * - Independent tasks (map phase) run concurrently; the aggregation task (reduce phase)
 *   waits for all of them via context().
 *
 * Run via:
 *   ./gradlew :agentensemble-examples:runDynamicAgents
 *
 * Pass dish names as arguments:
 *   ./gradlew :agentensemble-examples:runDynamicAgents --args="Risotto Steak Tiramisu"
 */
public class DynamicAgentsExample {

    // ---- Domain model -----

    record OrderItem(String dish, String cuisine, String dietaryNotes) {}

    record Order(String tableNumber, List<OrderItem> items) {}

    // ---- Example entry point -----

    public static void main(String[] args) {

        var model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        Order order = buildOrder(args);

        System.out.printf(
                "=== Order for Table %s (%d items) ===%n%n",
                order.tableNumber(), order.items().size());
        order.items()
                .forEach(item -> System.out.printf(
                        "  - %s (%s)%s%n",
                        item.dish(),
                        item.cuisine(),
                        item.dietaryNotes().isEmpty() ? "" : " [" + item.dietaryNotes() + "]"));
        System.out.println();

        EnsembleOutput output = fulfillOrder(order, model);

        System.out.println("=== Final Meal Plan ===");
        System.out.println(output.getRaw());

        System.out.println("\n=== Individual Dish Preparations ===");
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            System.out.printf("[%s]%n", taskOutput.getAgentRole());
            System.out.println(taskOutput.getRaw());
            System.out.println();
        }

        System.out.printf(
                "Completed in %s | %d total tool calls%n", output.getTotalDuration(), output.getTotalToolCalls());
    }

    // ---- Dynamic ensemble construction -----

    static EnsembleOutput fulfillOrder(Order order, dev.langchain4j.model.chat.ChatModel model) {

        // Phase 1: Dynamically create one specialist agent + task per dish.
        //
        // Agent and Task are ordinary immutable value objects built with
        // standard builders. Creating them in a loop is the same as creating
        // them individually -- the framework does not care how they were built.

        List<Agent> specialistAgents = new ArrayList<>();
        List<Task> dishTasks = new ArrayList<>();

        for (OrderItem item : order.items()) {
            Agent specialist = Agent.builder()
                    .role(item.dish() + " Specialist")
                    .goal("Prepare " + item.dish() + " to perfection")
                    .background("You are an expert in " + item.cuisine() + " cuisine. "
                            + "You understand the techniques, ingredients, and presentation "
                            + "standards for classic " + item.cuisine() + " dishes.")
                    .llm(model)
                    .build();

            String dietaryClause =
                    item.dietaryNotes().isEmpty() ? "" : " Dietary requirements: " + item.dietaryNotes() + ".";

            Task dishTask = Task.builder()
                    .description("Prepare the recipe for " + item.dish() + "."
                            + dietaryClause
                            + " Provide key ingredients, preparation steps, cook time, "
                            + "and plating instructions.")
                    .expectedOutput("Recipe summary with ingredients list, numbered preparation "
                            + "steps, total time, and a brief plating description.")
                    .agent(specialist)
                    .build();

            specialistAgents.add(specialist);
            dishTasks.add(dishTask);
        }

        // Phase 2: Create a single Head Chef agent that aggregates all dish outputs.
        //
        // By declaring context(dishTasks), this task depends on ALL specialist tasks.
        // Workflow.PARALLEL ensures the Head Chef only executes after every
        // specialist has finished -- but the specialists themselves run concurrently.

        Agent headChef = Agent.builder()
                .role("Head Chef")
                .goal("Coordinate all dishes into a cohesive, well-timed meal service")
                .background("You are a Michelin-starred head chef responsible for the overall "
                        + "quality and timing of every plate that leaves the kitchen.")
                .llm(model)
                .build();

        Task mealPlanTask = Task.builder()
                .description("Review the preparations for all " + order.items().size()
                        + " dishes provided in context. Create a final meal plan that "
                        + "coordinates cooking schedules, plating sequence, and service timing "
                        + "so all dishes arrive at table " + order.tableNumber()
                        + " at the right temperature simultaneously.")
                .expectedOutput("A coordinated meal service plan: serving order, timing for each "
                        + "dish, any final-minute coordination notes, and a brief quality checklist.")
                .agent(headChef)
                .context(dishTasks) // fan-in: waits for all specialist tasks
                .build();

        // Phase 3: Assemble the ensemble.
        //
        // All specialist agents + the Head Chef are registered. All dish tasks +
        // the meal plan task are registered. Workflow.PARALLEL derives execution
        // order automatically from the context declarations:
        //   - Dish tasks have no context dependencies -- they run concurrently.
        //   - Meal plan task declares context(dishTasks) -- it runs after all dish tasks.

        Ensemble.EnsembleBuilder builder = Ensemble.builder().workflow(Workflow.PARALLEL);

        for (Task task : dishTasks) {
            builder.task(task);
        }
        builder.task(mealPlanTask);

        return builder.build().run();
    }

    // ---- Helpers -----

    private static Order buildOrder(String[] args) {
        if (args.length > 0) {
            List<OrderItem> items = new ArrayList<>();
            for (String dish : args) {
                items.add(new OrderItem(dish, "International", ""));
            }
            return new Order("42", items);
        }

        // Default order for demonstration
        return new Order(
                "7",
                List.of(
                        new OrderItem("Truffle Risotto", "Italian", "vegetarian"),
                        new OrderItem("Pan-seared Duck Breast", "French", ""),
                        new OrderItem("Miso-glazed Salmon", "Japanese", "gluten-free"),
                        new OrderItem("Chocolate Fondant", "French", "contains nuts")));
    }
}
