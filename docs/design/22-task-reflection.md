# 22. Task Reflection — Self-Optimizing Prompt Loop

**Target release:** v0.x

Task reflection allows any task (or phase) to evaluate its own output after execution and
produce an improved version of its instructions for future runs. The improvement is stored
via a pluggable `ReflectionStore` SPI and injected into the prompt on subsequent executions,
creating a self-optimizing loop without ever modifying the compile-time task definition.

---

## 1. Problem Statement

Tasks are defined statically at compile time:

```java
Task.builder()
    .description("Research AI trends and write a report")
    .expectedOutput("A structured markdown report with three sections...")
    .build();
```

This is intentional — compile-time definitions provide safety, reproducibility, and
version-controlled prompts. However, static definitions cannot learn from execution
experience. Human experts improve through reflection on their own work; this feature
gives agents the same capability.

---

## 2. Design Goals

1. **Immutable compile-time definition is preserved.** Reflection never mutates the `Task`
   object. The static description and expectedOutput are always the contract.

2. **Reflection is cross-run.** Unlike phase review (which retries within a single run),
   reflection stores improvement notes that persist and are applied to future runs.

3. **Pluggable storage.** The `ReflectionStore` SPI allows in-memory (default), RDBMS,
   SQLite, file-system, REST API, or any other backend.

4. **Post-acceptance only.** Reflection runs after all reviews pass — on accepted output.
   It is not a quality gate; it is a learning step.

5. **Reflection is just another Task.** The reflection analysis is performed by an LLM
   call using the same infrastructure. Users can override the model, prompt, or strategy.

6. **Complements, not replaces, existing features.** Reflection augments the prompt;
   it does not replace memory scopes, short-term memory, or phase review.

---

## 3. Key Distinction from Phase Review

| Aspect            | Phase Review (design 21)           | Task Reflection (this document)         |
|-------------------|------------------------------------|-----------------------------------------|
| **Scope**         | Within a single run (retry loop)   | Across separate `Ensemble.run()` calls  |
| **Trigger**       | External reviewer decides "retry"  | Automatic post-acceptance analysis      |
| **Storage**       | Transient (task copy fields)       | Persistent (`ReflectionStore`)          |
| **Purpose**       | Fix this specific output now       | Improve instructions for next time      |
| **Who initiates** | Human or reviewer LLM              | The task itself, automatically          |

Phase review and reflection are complementary. Review corrects the current run;
reflection learns from it.

---

## 4. Execution Lifecycle

```
Ensemble.run()
    |
    v
Task executes (ReAct loop or deterministic handler)
    |
    v
Input guardrails pass
    |
    v
Output guardrails pass
    |
    v
Review gate passes  <-- existing phase-review / task-review
    |
    v
Memory scopes written  <-- existing MemoryStore
    |
    v
[if task.reflection != null]
Reflection step:
    1. Load prior reflection from ReflectionStore (if any)
    2. Build reflection prompt (original definition + output + prior notes)
    3. Call LLM to produce TaskReflection
    4. Store updated TaskReflection in ReflectionStore
    5. Fire TaskReflectedEvent to listeners
    |
    v
Task complete
```

---

## 5. Prompt Injection

On subsequent runs, `AgentPromptBuilder` injects stored reflection data **before** the
task description in the user prompt:

```
## Task Improvement Notes (from prior executions)

The following refinements were identified by analyzing previous runs of this task.
Apply them to improve your approach while fulfilling the original task requirements below.

### Refined Instructions
[improved version of description from stored reflection]

### Output Guidance
[improved version of expectedOutput from stored reflection]

### Observations
- [observation from stored reflection]

---
```

The original task description and expectedOutput always follow, ensuring the static
contract is honored.

---

## 6. Default Reflection Prompt

The default reflection LLM call uses this template:

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

## Prior Improvement Notes
{priorReflection.refinedDescription or "None — this is the first execution."}

## Analysis Instructions

Using the task definition and execution output above:

1. Evaluate whether the task instructions were clear, concise, and effective.
2. Identify where the instructions helped or hindered the agent's execution flow.
3. Propose targeted improvements focused on:
   - Improving clarity and conciseness
   - Consolidating overlapping or redundant guidance
   - Identifying outdated or low-impact instructions that add noise
   - Tightening the expected output format if output deviated from intent

Respond in the following structured format:

REFINED_DESCRIPTION:
[An improved version of the task description]

REFINED_EXPECTED_OUTPUT:
[An improved version of the expected output specification]

OBSERVATIONS:
- [Key observation]

SUGGESTIONS:
- [Specific actionable improvement]
```

---

## 7. SPI: ReflectionStore

```java
package net.agentensemble.reflection;

public interface ReflectionStore {

    /**
     * Store or replace the reflection for a given task identity.
     * Implementations must be thread-safe.
     *
     * @param taskIdentity  stable identifier derived from the task (e.g., description hash)
     * @param reflection    the reflection to store; must not be null
     */
    void store(String taskIdentity, TaskReflection reflection);

    /**
     * Retrieve the latest reflection for a given task identity.
     *
     * @param taskIdentity  stable identifier for the task
     * @return the stored reflection, or empty if none exists
     */
    Optional<TaskReflection> retrieve(String taskIdentity);
}
```

The module ships `InMemoryReflectionStore` as the default. Custom implementations
can use any backend: RDBMS, SQLite, Redis, REST API, etc.

---

## 8. Domain Object: TaskReflection

```java
package net.agentensemble.reflection;

public record TaskReflection(
    String refinedDescription,
    String refinedExpectedOutput,
    List<String> observations,
    List<String> suggestions,
    Instant reflectedAt,
    int runCount
) {}
```

- `refinedDescription` — improved version of `task.description`
- `refinedExpectedOutput` — improved version of `task.expectedOutput`
- `observations` — notable patterns or issues identified during analysis
- `suggestions` — actionable improvements for future runs
- `reflectedAt` — timestamp of the most recent reflection
- `runCount` — number of times this task has been reflected on

---

## 9. Configuration: ReflectionConfig

```java
Task.builder()
    .description("Research AI trends")
    .expectedOutput("A structured report")
    .reflect(true)  // enable with defaults
    .build();

Task.builder()
    .description("Research AI trends")
    .expectedOutput("A structured report")
    .reflect(ReflectionConfig.builder()
        .model(cheapReflectionModel)      // use a cheaper model for reflection
        .strategy(myCustomStrategy)       // or fully custom strategy
        .build())
    .build();
```

Fields on `ReflectionConfig`:
- `model` (`ChatModel`) — LLM for the reflection call; falls back to the task's model
- `strategy` (`ReflectionStrategy`) — custom reflection logic; defaults to `LlmReflectionStrategy`

---

## 10. SPI: ReflectionStrategy

For full control over how reflection is performed:

```java
public interface ReflectionStrategy {
    TaskReflection reflect(ReflectionInput input);
}
```

`ReflectionInput` bundles:
- `task` — the original `Task` (description, expectedOutput)
- `taskOutput` — the accepted output text
- `priorReflection` — the stored reflection from the previous run (may be null)

---

## 11. Ensemble Integration

```java
Ensemble.builder()
    .model(model)
    .reflectionStore(new InMemoryReflectionStore())  // use same store across runs
    .build()
    .run(taskWithReflection);
```

The `reflectionStore` field is optional. If a task has `.reflect(true)` but no
`reflectionStore` is configured on the Ensemble, an `InMemoryReflectionStore` is
created automatically (with a WARN log noting that reflections will not persist
across JVM restarts).

---

## 12. Callback: TaskReflectedEvent

A new event is fired to `EnsembleListener` after each successful reflection:

```java
public interface EnsembleListener {
    // ...existing methods...
    default void onTaskReflected(TaskReflectedEvent event) {}
}
```

`TaskReflectedEvent` contains:
- `taskDescription` — the original task description
- `reflection` — the `TaskReflection` that was produced and stored
- `isFirstReflection` — true if no prior reflection existed

---

## 13. Module Structure

The reflection SPI lives in a new module following the same pattern as
`agentensemble-memory` and `agentensemble-review`:

```
agentensemble-reflection/
  src/main/java/net/agentensemble/reflection/
    ReflectionStore.java          -- SPI interface
    ReflectionStrategy.java       -- strategy SPI
    ReflectionConfig.java         -- configuration value object
    ReflectionInput.java          -- input bundle for strategies
    TaskReflection.java           -- data record
    InMemoryReflectionStore.java  -- default in-memory implementation
```

`agentensemble-core` depends on `agentensemble-reflection` and contributes:
- `LlmReflectionStrategy` — default LLM-based implementation
- `ReflectionPromptBuilder` — builds the meta-prompt
- `TaskReflectedEvent` — callback event
- Integration into `AgentExecutor`, `AgentPromptBuilder`, `ExecutionContext`, `Ensemble`

---

## 14. Task Identity Key

The reflection store key is a hex-encoded SHA-256 hash of the task's description
string. This is:
- Stable across JVM restarts
- Consistent regardless of other task fields
- Human-debuggable (the description is the semantic identity of a task)

If two tasks share the same description they share a reflection entry, which is
intentional — they represent the same logical operation.

---

## 15. Thread Safety

`InMemoryReflectionStore` is safe for concurrent use. The `store()` method uses
`ConcurrentHashMap.put()` (last-write-wins), which is appropriate because reflection
updates are idempotent in nature — a later reflection is always the better one.

Custom `ReflectionStore` implementations must document their thread-safety guarantees.
