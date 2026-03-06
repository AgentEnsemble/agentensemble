# MapReduce Kitchen Examples

This page demonstrates `MapReduceEnsemble` using a restaurant kitchen scenario -- both the
**task-first API** (v2.0.0, agents synthesised automatically) and the **agent-first API**
(explicit agent declarations).

---

## Task-first examples (v2.0.0)

In the task-first paradigm, you declare what work needs to be done and the framework
synthesises agents automatically. No agent builders are required.

**Task-first (zero-ceremony + builder)**: Full source: `agentensemble-examples/src/main/java/net/agentensemble/examples/MapReduceTaskFirstKitchenExample.java`

```
./gradlew :agentensemble-examples:runMapReduceTaskFirstKitchen
```

### Zero-ceremony factory

```java
EnsembleOutput output = MapReduceEnsemble.of(
    model,
    List.of("Truffle Risotto", "Duck Breast", "Salmon", "Fondant", "Onion Soup"),
    "Prepare a complete recipe for",
    "Combine these individual recipes into a unified dinner service plan");

System.out.println(output.getRaw());
```

Two lines of configuration. The framework synthesises one agent per dish and one agent
for the final reduce -- all from the task descriptions.

### Task-first builder

For more control over task configuration (structured output, tools, per-task LLM):

```java
record DishResult(String dish, List<String> ingredients, int prepMinutes, String plating) {}

EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .chatLanguageModel(model)
    .items(order.getItems())

    // Map phase: task-first, agent synthesised from description
    .mapTask(item -> Task.builder()
        .description("Prepare the recipe for " + item.dish() + ". "
            + "Dietary requirements: " + item.dietaryNotes() + ". "
            + "Provide key ingredients, preparation steps, cook time, and plating.")
        .expectedOutput("Structured recipe result as JSON")
        .outputType(DishResult.class)   // structured output supported
        .build())

    // Reduce phase: task-first, agent synthesised from description
    .reduceTask(chunkTasks -> Task.builder()
        .description("Review the dish preparations provided in context. "
            + "Create a consolidated sub-plan: note timing dependencies, "
            + "common mise en place, and any coordination required between dishes.")
        .expectedOutput("A coordinated sub-plan covering timing, shared prep, and coordination notes.")
        .context(chunkTasks)            // wire context explicitly
        .build())

    .chunkSize(3)
    .verbose(true)
    .build()
    .run();
```

The `AgentSynthesizer` (template-based by default) derives the agent role from the task
description verb and noun. For "Prepare the recipe for Truffle Risotto", it produces a
"Chef/Cook" persona with a matching goal. No extra LLM call is made.

---

## Agent-first examples (power-user)

When you need precise control over agent personas, use the agent-first API.

**Static mode** (`chunkSize=3`): Full source: `agentensemble-examples/src/main/java/net/agentensemble/examples/MapReduceKitchenExample.java`

```
./gradlew :agentensemble-examples:runMapReduceKitchen
```

**Adaptive mode** (`targetTokenBudget=4000`): Full source: `agentensemble-examples/src/main/java/net/agentensemble/examples/MapReduceAdaptiveKitchenExample.java`

```
./gradlew :agentensemble-examples:runMapReduceAdaptiveKitchen
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

## Adaptive mode example

This example uses `targetTokenBudget` instead of `chunkSize`. After the map phase runs,
the framework measures actual output token counts and bins them to fit within the budget.

```java
EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .items(order.getItems())

    // Map phase: same as static mode
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
        .outputType(DishResult.class)
        .build())

    // Reduce phase: same factory -- context wiring is identical to static mode
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
        .context(chunkTasks)
        .build())

    // Adaptive strategy: keep reducing until total context < 8000 tokens.
    // The framework measures actual output token counts after each level and
    // bin-packs groups so that each group's combined tokens stay within budget.
    .targetTokenBudget(8_000)
    .maxReduceLevels(5)
    .captureMode(CaptureMode.STANDARD)
    .build()
    .run();

// Inspect per-level breakdown from the aggregated trace
output.getTrace().getMapReduceLevels().forEach(level ->
    System.out.printf("Level %d: %d tasks, duration=%s%n",
        level.getLevel(), level.getTaskCount(), level.getDuration()));

// Post-execution DAG export (adaptive DAG shape is only known after execution)
DagModel dag = DagExporter.build(output.getTrace());
dag.toJson(Path.of("./traces/adaptive-kitchen.dag.json"));
```

The map phase, reduce factories, and `context` wiring are identical to the static example.
Only the strategy field changes (`targetTokenBudget` instead of `chunkSize`).

### When adaptive mode adds intermediate reduce levels

If map outputs are large (say 2000 tokens each and budget is 8000), the executor would:

```
Map phase (7 items):  [C1:2000] [C2:2000] [C3:2000] [C4:2000] [C5:2000] [C6:2000] [C7:2000]
                       Total = 14000 > 8000 -> bin-pack into groups

  Bin-pack (FFD):     Bin A: [C1,C2,C3] (6000 <= 8000)
                      Bin B: [C4,C5,C6] (6000 <= 8000)
                      Bin C: [C7]       (2000 <= 8000)

L1 reduce (3 tasks):  [R1:1000] [R2:1000] [R3:1000]
                       Total = 3000 <= 8000 -> single final reduce

Final reduce (1 task): [Final]
```

Total: 3 ensemble runs (map + L1 + final), vs. 2 runs if all outputs fit in budget.

---

## Short-circuit: small order, single direct task

When the order is small (e.g., 2-3 dishes), the map-reduce pipeline overhead is
unnecessary. Configure `directAgent` and `directTask` alongside the standard factories.
The framework estimates input size before any LLM call and bypasses the pipeline when it
fits within the token budget.

```java
record OrderItem(String dish, String cuisine, boolean isVegetarian) {
    public String summary() { return dish + " (" + cuisine + ")"; }
}

List<OrderItem> smallOrder = List.of(
    new OrderItem("Truffle Risotto", "Italian", true),
    new OrderItem("Pan-seared Duck Breast", "French", false)
);

EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .items(smallOrder)

    // Standard map + reduce config (used when order is too large for direct processing)
    .mapAgent(item -> Agent.builder()
        .role(item.dish() + " Chef")
        .goal("Prepare " + item.dish())
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder()
        .description("Execute the recipe for: " + item.dish())
        .expectedOutput("Recipe with ingredients, steps, and timing")
        .agent(agent)
        .build())
    .reduceAgent(() -> Agent.builder()
        .role("Sub-Chef")
        .goal("Consolidate dish preparations")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) -> Task.builder()
        .description("Consolidate these dish preparations.")
        .expectedOutput("Coordinated sub-plan")
        .agent(agent)
        .context(chunkTasks)
        .build())

    // Short-circuit: if total estimated input fits in budget, run this instead
    .directAgent(() -> Agent.builder()
        .role("Head Chef")
        .goal("Plan the entire meal directly for a small order")
        .llm(model)
        .build())
    .directTask((agent, allItems) -> {
        String dishes = allItems.stream()
            .map(OrderItem::summary)
            .collect(Collectors.joining(", "));
        return Task.builder()
            .description("Plan the complete meal: " + dishes)
            .expectedOutput("Complete meal plan with all dishes, timing, and plating")
            .agent(agent)
            .build();
    })

    // Optional: use a compact representation for estimation
    // (avoids counting the full toString() of each OrderItem)
    .inputEstimator(OrderItem::summary)

    .contextWindowSize(128_000)
    .budgetRatio(0.5)  // targetTokenBudget = 64_000
    .build()
    .run();

// When short-circuit fires: single task output, nodeType="direct"
System.out.println("Task outputs: " + output.getTaskOutputs().size()); // 1
System.out.println("Output: " + output.getRaw());
```

### Decision tree

```
Before any LLM call:

  estimated_input_tokens = sum(item.summary().length() / 4 for item in smallOrder)

  For a 2-dish order with summaries ~30 chars each:
    estimated = 2 * (30 / 4) = 2 * 7 = 14 tokens

  14 tokens <= 64_000 (budget) AND directAgent/directTask configured
    --> SHORT-CIRCUIT fires
    --> 1 LLM call (Head Chef)
    --> EnsembleOutput with 1 TaskOutput, nodeType="direct"
```

### What the trace looks like

After a short-circuit run, `output.getTrace()` has:
- `workflow = "MAP_REDUCE_ADAPTIVE"`
- `mapReduceLevels` with exactly 1 entry (level 0, taskCount=1)
- 1 `TaskTrace` with `nodeType = "direct"` and `mapReduceLevel = 0`

In `agentensemble-viz`, the Flow View shows a single node with a **DIRECT** badge instead
of the normal map/reduce tree.

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
