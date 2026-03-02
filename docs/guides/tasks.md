# Tasks

A task is a unit of work assigned to one agent. It describes what the agent should do and what the output should look like.

---

## Creating a Task

```java
Task task = Task.builder()
    .description("Research the latest developments in quantum computing")
    .expectedOutput("A 400-word summary covering key breakthroughs, key players, and near-term outlook")
    .agent(researcher)
    .build();
```

The `description`, `expectedOutput`, and `agent` fields are required.

---

## Description

The `description` field tells the agent what to do. Write it clearly and specifically. The description supports `{variable}` placeholders that are resolved with values from `ensemble.run(Map)`.

```java
Task task = Task.builder()
    .description("Analyse the sales data for {region} in {year} and identify the top three growth drivers")
    .expectedOutput("A structured analysis listing three growth drivers with supporting evidence")
    .agent(analyst)
    .build();
```

Resolved at run time:

```java
ensemble.run(Map.of("region", "EMEA", "year", "2025"));
```

See the [Template Variables guide](template-variables.md) for full syntax details.

---

## Expected Output

The `expectedOutput` field is quality guidance for the agent. It describes what the result should look like, not a validation rule. Include:

- Format (markdown, JSON, bullet points, prose)
- Length (word count, number of items)
- Structure (sections, headings)
- Constraints (what to include, what to exclude)

Good example:

```java
.expectedOutput(
    "A 600-800 word blog post in markdown format with: " +
    "an engaging headline, an introduction paragraph, three main sections each with a subheading, " +
    "and a conclusion paragraph. Suitable for a technical audience.")
```

---

## Agent Assignment

Each task is assigned to exactly one agent:

```java
Task researchTask = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A research summary")
    .agent(researcher)      // researcher will execute this task
    .build();
```

In hierarchical workflow, the Manager agent decides at run time which worker handles each task based on agent roles and goals. You still specify an agent per task for configuration purposes and validation, but the manager may re-assign at runtime.

---

## Context Dependencies

In sequential workflow, a task can receive outputs from prior tasks using the `context` field. Context outputs are injected into the agent's user prompt before the task runs.

```java
var researchTask = Task.builder()
    .description("Research the history of {topic}")
    .expectedOutput("A factual historical summary")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a compelling blog post about {topic}")
    .expectedOutput("A 700-word blog post")
    .agent(writer)
    .context(List.of(researchTask))   // writer sees researcher's output
    .build();
```

Rules:
- Context tasks must appear earlier in the ensemble's task list
- Circular dependencies are detected at validation time and throw `ValidationException`
- A task may declare context on multiple prior tasks

When short-term memory is enabled, explicit `context` declarations are not required -- all prior task outputs within the run are automatically injected.

---

## Multiple Context Tasks

```java
var writeTask = Task.builder()
    .description("Write a final report incorporating both the research and the financial analysis")
    .expectedOutput("A 1000-word executive report")
    .agent(writer)
    .context(List.of(researchTask, analysisTask))  // receives both prior outputs
    .build();
```

---

## Tasks Are Immutable

Tasks are immutable value objects. The builder produces a new instance on each call to `build()`. Use `toBuilder()` to create modified copies:

```java
Task verboseTask = task.toBuilder()
    .description("Research {topic} with particular focus on regulatory changes")
    .build();
```

---

## Reference

See the [Task Configuration reference](../reference/task-configuration.md) for the complete field table.
