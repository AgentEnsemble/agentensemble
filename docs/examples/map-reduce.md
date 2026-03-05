# MapReduce Kitchen Example

This example demonstrates the **static `MapReduceEnsemble`** with a restaurant kitchen
scenario. A large order with 7 dishes is processed using tree-reduction with `chunkSize=3`,
automatically keeping each reducer's context bounded while all independent tasks run
concurrently.

Full source: `agentensemble-examples/src/main/java/net/agentensemble/examples/MapReduceKitchenExample.java`

```
./gradlew :agentensemble-examples:runMapReduceKitchen
```

---

## Scenario

A restaurant receives an order with 7 dishes. If we used a plain parallel workflow and
passed all 7 specialist outputs to a single Head Chef, the aggregator's context would be
`7 * avg_output_size`. With `MapReduceEnsemble(chunkSize=3)` the framework automatically
builds:

```
Map phase:   [Risotto] [Duck] [Salmon]   [Fondant] [Soup] [Lamb]   [Tart]
                   \     |     /                \     |     /          |
Reduce L1:      [Sub-Chef A]               [Sub-Chef B]           [Sub-Chef C]
                       \                       /                      /
Final reduce:                       [Head Chef]
```

Each Sub-Chef sees at most 3 dish preparations. The Head Chef sees 3 sub-plans. Context
is bounded at every level.

---

## Domain model

```java
record OrderItem(String dish, String cuisine, String dietaryNotes) {}

record DishResult(
    String dish,
    List<String> ingredients,
    int prepMinutes,
    String plating
) {}
```

---

## Building the MapReduceEnsemble

```java
MapReduceEnsemble<OrderItem> mapReduce = MapReduceEnsemble.<OrderItem>builder()
    .items(order.getItems())

    // Map phase: one specialist chef per dish
    .mapAgent(item -> Agent.builder()
        .role(item.dish() + " Chef")
        .goal("Prepare " + item.dish() + " to perfection")
        .background("You are an expert in " + item.cuisine() + " cuisine.")
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder()
        .description("Prepare the recipe for " + item.dish() + ". "
            + "Provide key ingredients, preparation steps, cook time, and plating.")
        .expectedOutput("Structured recipe result as JSON")
        .agent(agent)
        .outputType(DishResult.class)   // structured output
        .build())

    // Reduce phase: Sub-Chef consolidates each group of 3 dish preparations
    .reduceAgent(() -> Agent.builder()
        .role("Sub-Chef")
        .goal("Consolidate dish preparations into a cohesive sub-plan")
        .background("Senior sous chef who coordinates multiple dishes.")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) -> Task.builder()
        .description("Review the dish preparations in context. Create a consolidated "
            + "sub-plan covering timing, shared mise en place, and coordination.")
        .expectedOutput("Coordinated sub-plan with timing and coordination notes.")
        .agent(agent)
        .context(chunkTasks)  // wire context explicitly -- required
        .build())

    .chunkSize(3)
    .verbose(true)
    .build();
```

---

## Inspecting the DAG before execution

`toEnsemble()` returns the pre-built inner `Ensemble`. You can inspect its structure or
export it with `DagExporter` before any LLM calls are made:

```java
System.out.printf("DAG: %d agents, %d tasks%n",
    mapReduce.toEnsemble().getAgents().size(),    // 11 (7 map + 3 L1 + 1 final)
    mapReduce.toEnsemble().getTasks().size());     // 11

// Export enriched DAG for agentensemble-viz (includes MAP/REDUCE/AGGREGATE badges)
DagModel dag = DagExporter.build(mapReduce);
dag.toJson(Path.of("./traces/kitchen.dag.json"));
```

---

## Running the ensemble

```java
EnsembleOutput output = mapReduce.run();

System.out.println(output.getRaw());             // final Head Chef plan
System.out.println(output.getTaskOutputs().size()); // 11 task outputs
System.out.println(output.getTotalDuration());   // wall-clock time
```

All 7 map tasks run concurrently. After they complete, the 3 L1 reduce tasks run
concurrently. Then the final reduce task runs. Total wall-clock time is dominated by
the longest single task, not the sum of all tasks.

---

## Key points

**Structured output in the map phase**

The `outputType(DishResult.class)` field on the map tasks tells the framework to parse
the LLM response as JSON. Reduce tasks receive the full structured output as context.
This works exactly as it does in a standard `Ensemble`.

**Factory calls happen at `build()` time**

Both `mapAgent` and `reduceAgent` factories are called during `build()`, not `run()`.
The full DAG -- all agents and all tasks -- is constructed before any execution starts.
This is why `toEnsemble()` can return the complete structure.

**Each factory call produces a distinct agent**

`mapAgent` is called once per item; `reduceAgent` is called once per reduce group. Each
call produces a new, independent `Agent` instance. No agents are shared between tasks.

**Context wiring is explicit**

The `reduceTask` factory receives `chunkTasks` (the upstream tasks for that group) and
**must** wire them with `.context(chunkTasks)`. The framework does not mutate the returned
task. Omitting this causes `ValidationException` when the inner `Ensemble` validates.

---

## Expected output structure

```
=== MapReduce Kitchen Order (7 dishes, chunkSize=3) ===

  - Truffle Risotto (Italian) [vegetarian]
  - Pan-seared Duck Breast (French)
  - Miso-glazed Salmon (Japanese) [gluten-free]
  - Dark Chocolate Fondant (French) [contains nuts]
  - French Onion Soup (French)
  - Herb-crusted Lamb Rack (Mediterranean)
  - Lemon Tart (French) [gluten-free option available]

DAG: 11 agents, 11 tasks

... (verbose execution log) ...

=== Final Meal Plan ===
[Head Chef consolidates all three sub-plans into a unified service plan]

=== Individual Task Outputs ===
[Truffle Risotto Chef]
{"dish":"Truffle Risotto","ingredients":[...],"prepMinutes":35,"plating":"..."}
...

Completed in PT2M14S | 11 tasks | 0 total tool calls
```
