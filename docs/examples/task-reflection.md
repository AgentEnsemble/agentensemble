# Task Reflection Examples

## Basic Reflection — Accumulating Improvements Across Runs

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.reflection.InMemoryReflectionStore;
import net.agentensemble.reflection.ReflectionStore;

// Keep the same store instance across runs to persist reflections
ReflectionStore store = new InMemoryReflectionStore();

Task researchTask = Task.builder()
    .description("Research the top 5 AI trends of 2025")
    .expectedOutput("A structured report with trend name, description, and impact assessment")
    .reflect(true)   // enable with default settings
    .build();

Ensemble ensemble = Ensemble.builder()
    .chatLanguageModel(model)
    .task(researchTask)
    .reflectionStore(store)
    .build();

// Run 1: baseline execution, reflection stored afterward
EnsembleOutput run1 = ensemble.run();
System.out.println("Run 1 complete: " + run1.getRaw());

// Run 2: reflection notes injected into prompt
EnsembleOutput run2 = ensemble.run();
System.out.println("Run 2 complete: " + run2.getRaw());

// Run 3: further refined
EnsembleOutput run3 = ensemble.run();
System.out.println("Run 3 complete: " + run3.getRaw());
```

---

## Using a Cheaper Model for Reflection

```java
ChatModel primaryModel = OpenAiChatModel.builder()
    .apiKey(apiKey)
    .modelName("gpt-4o")
    .build();

ChatModel reflectionModel = OpenAiChatModel.builder()
    .apiKey(apiKey)
    .modelName("gpt-4o-mini")   // cheaper for meta-analysis
    .build();

Task task = Task.builder()
    .description("Write a detailed technical architecture document")
    .expectedOutput("A structured architecture doc with sections: Overview, Components, Data Flow, Security")
    .reflect(ReflectionConfig.builder()
        .model(reflectionModel)
        .build())
    .build();

Ensemble.builder()
    .chatLanguageModel(primaryModel)
    .task(task)
    .reflectionStore(new InMemoryReflectionStore())
    .build()
    .run();
```

---

## Observing Reflection Events

```java
ReflectionStore store = new InMemoryReflectionStore();

Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Summarise the quarterly earnings report")
        .expectedOutput("A concise executive summary with key metrics")
        .reflect(true)
        .build())
    .reflectionStore(store)
    .onTaskReflected(event -> {
        System.out.printf("[Reflection] Task: %s | Run: %d | First: %s%n",
            event.taskDescription(),
            event.reflection().runCount(),
            event.isFirstReflection());
        System.out.println("  Refined description: " + event.reflection().refinedDescription());
        System.out.println("  Observations: " + event.reflection().observations());
    })
    .build()
    .run();
```

---

## Custom Reflection Strategy

```java
// Domain-specific strategy that checks output against a known rubric
ReflectionStrategy rubricStrategy = input -> {
    String output = input.taskOutput();
    List<String> observations = new ArrayList<>();
    List<String> suggestions = new ArrayList<>();

    if (!output.contains("Executive Summary")) {
        observations.add("Output was missing an Executive Summary section");
        suggestions.add("Explicitly require an Executive Summary in the task description");
    }
    if (output.length() < 500) {
        observations.add("Output was shorter than expected for a detailed report");
        suggestions.add("Specify a minimum length or section count in the expected output");
    }

    String refinedDesc = input.task().getDescription()
        + " Include an Executive Summary section.";

    return TaskReflection.ofFirstRun(
        refinedDesc,
        input.task().getExpectedOutput(),
        observations,
        suggestions
    );
};

Task task = Task.builder()
    .description("Produce a market analysis report")
    .expectedOutput("A detailed market analysis")
    .reflect(ReflectionConfig.builder()
        .strategy(rubricStrategy)
        .build())
    .build();
```

---

## Reflection with Multiple Tasks in a Pipeline

Only tasks with `.reflect(true)` will be reflected on. Tasks without it run normally:

```java
Task fetchTask = Task.builder()
    .description("Fetch the latest earnings data from the financial API")
    .expectedOutput("JSON array of quarterly earnings records")
    .handler(ctx -> ToolResult.success(apiClient.fetchEarnings()))
    // No reflection -- this is a deterministic data fetch, no prompt to improve
    .build();

Task analysisTask = Task.builder()
    .description("Analyse the earnings data and identify key trends")
    .expectedOutput("A structured analysis with trend identification and year-over-year comparison")
    .context(List.of(fetchTask))
    .reflect(true)   // reflection only on the analytical step
    .build();

ReflectionStore store = new InMemoryReflectionStore();

Ensemble.builder()
    .chatLanguageModel(model)
    .task(fetchTask)
    .task(analysisTask)
    .reflectionStore(store)
    .build()
    .run();
```

---

## Inspecting a Stored Reflection

```java
ReflectionStore store = new InMemoryReflectionStore();

Task task = Task.builder()
    .description("Write a technical blog post about microservices")
    .expectedOutput("A 600-800 word blog post with introduction, 3 main points, and conclusion")
    .reflect(true)
    .build();

Ensemble.builder()
    .chatLanguageModel(model)
    .task(task)
    .reflectionStore(store)
    .build()
    .run();

// Inspect the stored reflection directly
String identity = TaskIdentity.of(task);
store.retrieve(identity).ifPresent(reflection -> {
    System.out.println("Refined description: " + reflection.refinedDescription());
    System.out.println("Refined expected output: " + reflection.refinedExpectedOutput());
    System.out.println("Observations:");
    reflection.observations().forEach(o -> System.out.println("  - " + o));
    System.out.println("Suggestions:");
    reflection.suggestions().forEach(s -> System.out.println("  - " + s));
    System.out.println("Run count: " + reflection.runCount());
});
```
