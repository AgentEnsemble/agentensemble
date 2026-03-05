# Example: Dynamic Agent Creation (Fan-Out / Fan-In)

This example demonstrates how to programmatically create agents and tasks at runtime using the
existing `Workflow.PARALLEL` API. No special framework features are required -- `Agent` and
`Task` are ordinary Java builder objects and can be constructed in a loop.

## Scenario

A restaurant kitchen receives an order with multiple dishes. Rather than pre-defining a fixed
set of agents, the kitchen creates a specialist agent for each dish on the fly (fan-out). All
specialists work in parallel. A Head Chef then aggregates their individual preparations into a
coordinated meal service plan (fan-in).

This pattern applies whenever:

- The number of agents is not known until runtime
- Each item in a dynamic collection needs independent processing
- Results must be aggregated into a single final output

## How It Works

```
Input: Order with N dishes
         |
         +-- [Risotto Specialist] ----+
         |                            |
         +-- [Duck Specialist] -------+--> [Head Chef] --> Final Meal Plan
         |                            |
         +-- [Salmon Specialist] -----+
         |                            |
         +-- [Fondant Specialist] ----+
```

The specialists have no dependencies on each other and run concurrently. The Head Chef task
declares `context(allDishTasks)`, which causes `Workflow.PARALLEL` to execute it only after
every specialist has finished.

## Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workflow.Workflow;

public class DynamicAgentsExample {

    record OrderItem(String dish, String cuisine, String dietaryNotes) {}
    record Order(String tableNumber, List<OrderItem> items) {}

    public static EnsembleOutput fulfillOrder(Order order,
            dev.langchain4j.model.chat.ChatModel model) {

        // Phase 1: Fan-out -- one specialist agent + task per dish
        List<Agent> specialistAgents = new ArrayList<>();
        List<Task> dishTasks = new ArrayList<>();

        for (OrderItem item : order.items()) {
            Agent specialist = Agent.builder()
                    .role(item.dish() + " Specialist")
                    .goal("Prepare " + item.dish() + " to perfection")
                    .background("You are an expert in " + item.cuisine() + " cuisine.")
                    .llm(model)
                    .build();

            Task dishTask = Task.builder()
                    .description("Prepare the recipe for " + item.dish() + ". "
                            + "Provide key ingredients, preparation steps, and plating instructions.")
                    .expectedOutput("Recipe summary with ingredients, steps, total time, "
                            + "and plating description.")
                    .agent(specialist)
                    .build();

            specialistAgents.add(specialist);
            dishTasks.add(dishTask);
        }

        // Phase 2: Fan-in -- single Head Chef aggregates all specialist outputs
        Agent headChef = Agent.builder()
                .role("Head Chef")
                .goal("Coordinate all dishes into a cohesive, well-timed meal service")
                .background("You are a Michelin-starred head chef.")
                .llm(model)
                .build();

        Task mealPlanTask = Task.builder()
                .description("Review the preparations for all dishes. Create a coordinated "
                        + "meal plan with cooking schedule, plating sequence, and service timing.")
                .expectedOutput("Coordinated meal service plan with serving order and timing.")
                .agent(headChef)
                .context(dishTasks)  // depends on ALL specialist tasks
                .build();

        // Phase 3: Assemble and run
        //
        // Workflow.PARALLEL derives execution order from context() declarations:
        //   - Dish tasks have no dependencies -> run concurrently
        //   - Meal plan task declares context(dishTasks) -> runs after all dish tasks
        Ensemble.EnsembleBuilder builder = Ensemble.builder()
                .workflow(Workflow.PARALLEL);

        specialistAgents.forEach(builder::agent);
        builder.agent(headChef);

        dishTasks.forEach(builder::task);
        builder.task(mealPlanTask);

        return builder.build().run();
    }
}
```

## Running the Example

```bash
export OPENAI_API_KEY=your-api-key

# Default four-course order
./gradlew :agentensemble-examples:runDynamicAgents

# Custom dishes (space-separated)
./gradlew :agentensemble-examples:runDynamicAgents --args="Risotto Steak Tiramisu"
```

## Key Points

**1. Agents and Tasks are plain Java objects**

There is nothing special about creating them in a loop versus creating them individually. The
framework does not distinguish between statically-declared and dynamically-constructed instances.

**2. Fan-out is implicit**

Tasks with no `context` declarations are automatically identified as roots by the
`ParallelWorkflowExecutor` and started immediately. You do not need to mark them as parallel.

**3. Fan-in via `context(list)`**

Passing a `List<Task>` to `context()` creates a dependency on every task in the list. The
aggregation task starts only after all listed tasks complete -- regardless of how many there are.

**4. Context text grows with the number of tasks**

Each specialist task's output is injected into the Head Chef's user prompt as a "Context from
prior tasks" section. With many agents, this context can become large. If this is a concern:

- Use `outputType(RecordClass.class)` on each specialist task to produce compact structured
  JSON instead of verbose prose.
- For very large N, consider a tree-reduction approach: group specialist outputs into batches,
  reduce each batch independently, then aggregate the batch summaries.

See the design document for the planned `MapReduceEnsemble` builder, which automates this pattern.

**5. `EnsembleOutput` is always in topological order**

`output.getTaskOutputs()` returns results in dependency order. Specialist outputs come first
(in completion order), followed by the Head Chef output last.

## Execution Timeline

```
Time -->

[Risotto Specialist] ------+
[Duck Specialist] ---------+---> [Head Chef] ---> Final Meal Plan
[Salmon Specialist] -------+
[Fondant Specialist] ------+
```

All four specialists run concurrently. The Head Chef starts as soon as the last specialist
finishes.

## Context Size Consideration

When `N` specialist agents each produce substantial output, the aggregation task receives
`N * avg_output_size` tokens of context. For small `N` (up to approximately 5-10 specialists),
this is typically within all major models' context windows. For larger `N`, use structured
output to keep each specialist's response compact:

```java
record DishSummary(String dish, List<String> ingredients, int prepMinutes, String plating) {}

Task dishTask = Task.builder()
        .description("Prepare the recipe for " + item.dish())
        .expectedOutput("Structured recipe summary")
        .agent(specialist)
        .outputType(DishSummary.class)  // compact JSON instead of prose
        .build();
```

A `DishSummary` record typically serializes to 100-200 tokens versus 1,000-2,000 tokens for
a prose recipe, reducing aggregation context by 5-10x.

## Related

- [Parallel Workflow](parallel-workflow.md) -- static parallel pipeline with fixed agents
- [Structured Output](structured-output.md) -- compact JSON output from agents
- [Hierarchical Team](hierarchical-team.md) -- LLM-directed agent routing
