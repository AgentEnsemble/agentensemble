# Task Reflection

Task reflection enables a **self-optimizing prompt loop**: after a task executes and its output is accepted, an automated analysis identifies how the task's instructions could be improved for future runs. Improvements are stored persistently and injected into the prompt on subsequent executions — without ever modifying the compile-time task definition.

---

## The Core Idea

In AgentEnsemble, tasks are defined statically at compile time:

```java
Task task = Task.builder()
    .description("Research AI trends and write a summary report")
    .expectedOutput("A structured markdown report with three sections")
    .build();
```

This is intentional — static definitions are safe, reproducible, and version-controlled. But they cannot learn from execution experience.

Reflection bridges this gap:

```
Run 1: Static definition -> Execute -> Reflect -> Store improvement
Run 2: Static definition + Stored improvement -> Execute -> Reflect -> Update improvement
Run N: Static definition + Latest improvement -> Execute -> ...
```

The original definition never changes. The *effective* prompt evolves in the `ReflectionStore`.

---

## Key Distinction from Phase Review

| | Phase Review | Task Reflection |
|---|---|---|
| **When** | Within a single run | Across separate `Ensemble.run()` calls |
| **Who triggers it** | External reviewer | Automatic post-completion analysis |
| **Purpose** | Fix this output now | Improve instructions for next time |
| **Storage** | Transient | Persistent (`ReflectionStore`) |

Use phase review to correct a specific run's output. Use reflection to improve all future runs.

---

## Quick Start

### 1. Enable reflection on a task

```java
Task researchTask = Task.builder()
    .description("Research AI trends in 2025 and write a summary report")
    .expectedOutput("A structured report with sections: Introduction, Key Trends, Conclusion")
    .reflect(true)   // enable reflection with all defaults
    .build();
```

### 2. Configure a persistent store on the Ensemble

```java
InMemoryReflectionStore store = new InMemoryReflectionStore();

Ensemble ensemble = Ensemble.builder()
    .chatLanguageModel(model)
    .task(researchTask)
    .reflectionStore(store)   // reuse across runs to accumulate improvements
    .build();

// Run 1: no stored reflection, executes normally
ensemble.run();

// Run 2: prior reflection injected into prompt, agent has improved guidance
ensemble.run();

// Run 3: further refined, run count = 3
ensemble.run();
```

---

## How It Works

### Execution lifecycle

1. Task executes normally (ReAct loop or deterministic handler)
2. All input and output guardrails pass
3. Phase/task reviews pass (output accepted)
4. Memory scopes are written
5. **Reflection step** (if enabled):
   - Load prior reflection from `ReflectionStore` (if any)
   - Build a meta-prompt: *"How could these instructions be improved?"*
   - Call the LLM to analyze the task definition and output
   - Store the improved definition in `ReflectionStore`
   - Fire `TaskReflectedEvent` to listeners

### What gets stored

```
TaskReflection:
  refinedDescription      -- improved version of task.description
  refinedExpectedOutput   -- improved version of task.expectedOutput
  observations            -- patterns noticed during analysis
  suggestions             -- actionable improvements
  reflectedAt             -- timestamp
  runCount                -- how many runs have informed this reflection
```

### What gets injected next run

When a stored reflection exists, it is injected **before** the task description:

```
## Task Improvement Notes (from prior executions)

The following refinements were identified by analyzing previous runs of this task.
Apply them to improve your approach while still fulfilling the original requirements below.

### Refined Instructions
[improved task description from stored reflection]

### Output Guidance
[improved expected output specification]

### Observations
- [pattern or issue observed]

### Suggestions
- [specific actionable improvement]

---

## Task
[original static task description -- always present]

## Expected Output
[original static expected output -- always present]
```

The static definition always follows the reflection notes, ensuring the original contract is honored.

---

## Configuration Options

### Use a specific (cheaper) model for reflection

Reflection is a meta-analysis task that doesn't require the full capability of your primary model. A faster, cheaper model is often appropriate:

```java
Task task = Task.builder()
    .description("Write a quarterly business report")
    .expectedOutput("A structured PDF-ready report")
    .reflect(ReflectionConfig.builder()
        .model(cheaperModel)   // e.g., gpt-4o-mini, claude-haiku
        .build())
    .build();
```

Model resolution order:
1. `ReflectionConfig.model` (if set)
2. `Task.chatLanguageModel` (if set)
3. `Ensemble.chatLanguageModel` (ensemble-level model)

### Provide a custom reflection strategy

For domain-specific analysis logic:

```java
ReflectionStrategy myStrategy = input -> {
    String improvedDesc = analyzeWithMyLogic(
        input.task().getDescription(),
        input.taskOutput()
    );
    return TaskReflection.ofFirstRun(
        improvedDesc,
        input.task().getExpectedOutput(),
        List.of("Custom analysis applied"),
        List.of()
    );
};

Task task = Task.builder()
    .description("...")
    .reflect(ReflectionConfig.builder()
        .strategy(myStrategy)
        .build())
    .build();
```

---

## Persistent Storage

The `ReflectionStore` SPI allows any backend:

```java
public interface ReflectionStore {
    void store(String taskIdentity, TaskReflection reflection);
    Optional<TaskReflection> retrieve(String taskIdentity);
}
```

### Built-in: InMemoryReflectionStore

Suitable for development, testing, and single-JVM deployments. **Reflections do not survive JVM restarts.**

```java
ReflectionStore store = new InMemoryReflectionStore();
```

To simulate cross-run persistence in tests, reuse the same instance across multiple `run()` calls.

### Custom: Database-backed store

For production use, implement `ReflectionStore` with your preferred storage:

```java
public class JdbcReflectionStore implements ReflectionStore {

    private final DataSource dataSource;

    @Override
    public void store(String taskIdentity, TaskReflection reflection) {
        // persist to your database
    }

    @Override
    public Optional<TaskReflection> retrieve(String taskIdentity) {
        // query from your database
    }
}

// Usage
Ensemble.builder()
    .reflectionStore(new JdbcReflectionStore(dataSource))
    .build();
```

### Task Identity

Reflections are keyed by a SHA-256 hash of the task's description. This means:
- Two tasks with the same description share a reflection entry (by design)
- Changing a task's description creates a new reflection entry (the definition changed)
- Identity is stable across JVM restarts

Use `TaskIdentity.of(task)` if you need the identity key in custom store implementations.

---

## Observing Reflections via Callbacks

```java
Ensemble.builder()
    .onTaskReflected(event -> {
        System.out.printf("Task '%s' reflected (run %d)%n",
            event.taskDescription(),
            event.reflection().runCount());
        if (event.isFirstReflection()) {
            System.out.println("First reflection for this task");
        }
    })
    .build();
```

The `TaskReflectedEvent` contains:
- `taskDescription` — the original task description
- `reflection` — the `TaskReflection` that was stored
- `isFirstReflection` — true if this is the first reflection for this task

---

## Default Reflection Prompt

The default `LlmReflectionStrategy` sends this prompt to the LLM:

```
You are a task prompt optimization specialist. Your role is to analyze how a task
definition performed and propose improvements to its instructions for future executions.

## Original Task Definition

### Description
{task.description}

### Expected Output Specification
{task.expectedOutput}

## What Was Produced
{taskOutput}

## Analysis Instructions

1. Evaluate whether the task instructions were clear, concise, and effective.
2. Identify where the instructions helped or hindered the agent's execution flow.
3. Propose targeted improvements focused on:
   - Improving clarity and conciseness
   - Consolidating overlapping or redundant guidance
   - Identifying outdated or low-impact instructions that add noise
   - Tightening the expected output format if output deviated from intent

Respond using EXACTLY the following structured format:

REFINED_DESCRIPTION:
[An improved version of the task description]

REFINED_EXPECTED_OUTPUT:
[An improved version of the expected output specification]

OBSERVATIONS:
- [Key observation about what worked or did not work]

SUGGESTIONS:
- [Specific actionable improvement for future runs]
```

---

## Reflection with Deterministic Tasks

Reflection works on handler-based (deterministic) tasks too. Since deterministic tasks have no agent LLM, configure a model explicitly on the `ReflectionConfig`:

```java
Task fetchTask = Task.builder()
    .description("Fetch product catalog from the inventory API")
    .expectedOutput("JSON array of product records")
    .handler(ctx -> ToolResult.success(apiClient.fetchProducts()))
    .reflect(ReflectionConfig.builder()
        .model(analysisModel)   // required for deterministic tasks
        .build())
    .build();
```

---

## When Reflection Does Not Fire

Reflection is skipped when:
- The task has no `reflectionConfig` (`.reflect()` was not called)
- No model is available (no `ReflectionConfig.model`, no `Task.chatLanguageModel`, no `Ensemble.chatLanguageModel`)

In the model-unavailable case, a WARN is logged and reflection is silently skipped. Reflection failures (LLM errors, parse failures) are also non-fatal — they log a WARN and the task output is unaffected.
