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

## Structured Output

Set `outputType` to have the agent produce a structured JSON object that is automatically parsed into a typed Java object.

### Typed output with a record

```java
record ResearchReport(String title, List<String> findings, String conclusion) {}

Task task = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A structured research report with title, findings, and conclusion")
    .agent(researcher)
    .outputType(ResearchReport.class)
    .build();
```

When the ensemble runs, the agent is instructed to produce JSON matching the schema derived from `ResearchReport`. After execution, access the parsed result:

```java
EnsembleOutput output = ensemble.run(Map.of("topic", "AI agents"));
TaskOutput taskOutput = output.getTaskOutputs().get(0);

// Raw text is always available
System.out.println(taskOutput.getRaw());

// Typed access to the parsed object
ResearchReport report = taskOutput.getParsedOutput(ResearchReport.class);
System.out.println(report.title());
report.findings().forEach(System.out::println);
```

### Markdown output (no schema required)

When you only need the agent to produce well-formatted text (such as Markdown), use `expectedOutput` and `Agent.responseFormat` -- no `outputType` needed:

```java
var writer = Agent.builder()
    .role("Content Writer")
    .goal("Write clear, well-structured content")
    .responseFormat("Always format responses in Markdown with headers, bullet points, and code blocks where appropriate.")
    .llm(model)
    .build();

Task task = Task.builder()
    .description("Write a technical guide for {topic}")
    .expectedOutput(
        "A 600-800 word Markdown guide with: " +
        "a title, an introduction, three main sections with subheadings, and a summary.")
    .agent(writer)
    .build();
```

The `raw` field of `TaskOutput` contains the formatted Markdown response.

### Retry behaviour

If the agent's response cannot be parsed, the framework retries automatically. On each retry the agent is shown the parse error and the required schema:

```java
Task task = Task.builder()
    .description("Classify the following text")
    .expectedOutput("A classification result")
    .agent(classifier)
    .outputType(ClassificationResult.class)
    .maxOutputRetries(5)   // default is 3; use 0 to disable retries
    .build();
```

If all retries are exhausted, `OutputParsingException` is thrown with the raw output, the parse error history, and the attempt count.

### Supported types for `outputType`

- Java **records** (recommended -- field order and names are preserved)
- **POJOs** with declared instance fields (Jackson deserialization)
- Any type Jackson can deserialize: `String`, boxed numerics, `boolean`, `List<T>`, `Map<K,V>`, enums, nested objects

Unsupported: **primitives** (`int.class`, etc.), **void**, and **top-level arrays** (wrap the array in a record or class).

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
