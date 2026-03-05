# AgentEnsemble v2.0.0 Architecture Design

## Overview

AgentEnsemble v2.0.0 is a major version that shifts the framework from an **agent-first**
to a **task-first** paradigm. The central insight is that users care about what work needs
to be done, not about constructing agent personas. Agent composition becomes an implementation
detail managed invisibly by the framework.

Three new architectural pillars accompany this shift:

1. **Task-scoped cross-execution memory** -- tasks declare named memory scopes that persist
   across separate `ensemble.run()` invocations
2. **Human-in-the-loop review gates** -- tasks can pause before, during, or after execution
   to collect external input (human approval, correction, or clarification)
3. **Graceful partial results** -- `EnsembleOutput` is redesigned to treat incomplete
   pipelines as a first-class outcome

Because this is a semantic-versioned major release, all of the above are delivered as
**breaking changes** with a provided migration guide. No compatibility shim is carried.

---

## 1. Paradigm: Task-First, Agent-Invisible

### Motivation

The v1.x API requires users to think in terms of three tightly coupled concepts:

```java
// v1.x: define agent, define task, wire them together, define ensemble
Agent researcher = Agent.builder()
    .role("Researcher").goal("...").backstory("...")
    .chatLanguageModel(model).tools(webSearchTool).build();

Task task = Task.builder()
    .description("Research AI trends").expectedOutput("Summary")
    .agent(researcher)
    .build();

Ensemble.builder().agents(researcher).tasks(task)
    .workflow(Workflow.SEQUENTIAL).build().run();
```

For the majority of use cases the `Agent` object is **accidental complexity**. Users think
"I need research done, then a report written" -- not "I need a Researcher persona with a
specific backstory, then I wire it to a task."

### v2.0 API: Zero-Ceremony Path

```java
// Simplest form: no agents declared
EnsembleOutput output = Ensemble.run(model,
    Task.of("Research the latest AI trends in healthcare"),
    Task.of("Write a 1000-word blog post from the research")
);
```

### v2.0 API: Configured Path (still no agents)

```java
EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .memoryStore(MemoryStore.embeddings(embeddingModel, embeddingStore))
    .reviewHandler(ReviewHandler.console())
    .task(Task.builder()
        .description("Research AI trends in healthcare")
        .expectedOutput("Detailed research summary with citations")
        .tools(webSearchTool, webScraperTool)
        .outputType(ResearchReport.class)
        .memory("market-intel")
        .build())
    .task(Task.builder()
        .description("Write blog post from the research")
        .expectedOutput("1000-word blog post in Markdown")
        .review(Review.required("Approve the blog post draft"))
        .build())
    .run();
```

### v2.0 API: Explicit Agent (Power-User Escape Hatch)

`Agent` remains available for users who need explicit persona control, shared agent identity
across multiple tasks, or per-agent LLM selection:

```java
Agent researcher = Agent.builder()
    .role("Senior Healthcare Analyst").goal("...").backstory("...")
    .chatLanguageModel(anthropicModel)
    .build();

Task.builder()
    .description("Research AI trends")
    .agent(researcher)  // opt-in: binds an explicit agent
    .build();
```

### Agent Auto-Synthesis

When no agent is declared on a task, the framework synthesizes one from the task description.
The synthesis strategy is pluggable:

```java
public interface AgentSynthesizer {
    Agent synthesize(Task task, SynthesisContext context);

    static AgentSynthesizer template() { ... }   // default: template-based
    static AgentSynthesizer llmBased() { ... }   // LLM-generated persona (opt-in)
}
```

The **template-based** synthesizer (default) derives role/goal/backstory from the task
description using configurable templates. No extra LLM call is required.

The **LLM-based** synthesizer invokes the LLM to generate an optimal persona for the task.
It produces higher-quality personas at the cost of an additional LLM call per task.

Configured at the ensemble level:

```java
Ensemble.builder()
    .agentSynthesizer(AgentSynthesizer.template())  // default; explicit for clarity
    // or: .agentSynthesizer(AgentSynthesizer.llmBased())
    // or: .agentSynthesizer(myCustomSynthesizer)
```

### What Moves from Agent to Task

| Capability | v1.x Location | v2.0 Location |
|---|---|---|
| `tools` | Agent (required for tool use) | Task (preferred) or Agent (when explicit) |
| `chatLanguageModel` | Agent (required) | Task (per-task override) or Ensemble (default) |
| `guardrails` | Task | Task (unchanged) |
| `memory` | Ensemble / EnsembleMemory | Task (scoped, see section 2) |
| `maxIterations` | Agent | Task or Ensemble default |
| `role`, `goal`, `backstory` | Agent (required) | Agent (optional; auto-synthesized otherwise) |

### Breaking Changes to `Ensemble.builder()`

| v1.x method | v2.0 |
|---|---|
| `.agents(agent, ...)` | Removed; agents are auto-synthesized or set per-task via `Task.agent()` |
| `.workflow(Workflow.X)` | Retained; now optional (inferred by default -- see section 5) |
| `.memory(EnsembleMemory)` | Replaced by `.memoryStore(MemoryStore)` + task-level `.memory(scope)` |

---

## 2. Task-Scoped Cross-Execution Memory

### Problem with v1.x Memory

The v1.x memory system (`EnsembleMemory`, `MemoryContext`) is configured at the ensemble
level and scoped to the lifetime of a single `ensemble.run()` invocation. There is no
first-class mechanism for tasks to share knowledge **across separate runs**.

### Named Memory Scopes

In v2.0, tasks declare one or more **named memory scopes**. A scope is a logical namespace
within the backing store. Memory is written at the end of task execution and read at the
start of the next execution of any task with the same scope.

```java
Task.builder()
    .description("Research competitor pricing for Q1")
    .memory("competitor-research")          // single scope
    .build();

Task.builder()
    .description("Analyze pricing trends over time")
    .memory("competitor-research", "market-trends")  // reads from both scopes
    .build();
```

Execution semantics:
- **Write**: at task completion, the task output (raw text and optional structured output)
  is stored in each declared scope
- **Read**: at task startup, relevant entries from each declared scope are retrieved and
  injected into the agent prompt as context
- **Edited output**: if a review gate modifies the task output, the **edited** version
  is what gets stored in memory
- **Isolation**: a task can only read from scopes it explicitly declares

### MemoryStore SPI

The backing store is configured once at the ensemble level. Scopes are logical partitions:

```java
public interface MemoryStore {
    void store(String scope, MemoryEntry entry);
    List<MemoryEntry> retrieve(String scope, String query, int maxResults);
    void evict(String scope, EvictionPolicy policy);

    static MemoryStore embeddings(EmbeddingModel model, EmbeddingStore store) { ... }
    static MemoryStore inMemory() { ... }   // dev / testing
}
```

```java
Ensemble.builder()
    .memoryStore(MemoryStore.embeddings(embeddingModel, store))
    .task(task1)
    .task(task2)
    .run();
```

### Memory Configuration per Scope

Tasks can configure scope-specific eviction:

```java
Task.builder()
    .description("Research competitor pricing")
    .memory(MemoryScope.builder()
        .name("competitor-research")
        .keepLastEntries(10)           // keep last 10 entries
        // or: .keepEntriesWithin(Duration.ofDays(30))
        .build())
    .build();
```

### MemoryTool

Agents can explicitly query or write to memory during execution via a built-in `MemoryTool`.
This is in addition to the automatic prompt injection at task startup:

```java
Task.builder()
    .description("Find relevant past findings and extend them")
    .memory("research-history")
    .tools(MemoryTool.of("research-history"))  // explicit mid-task access
    .build();
```

---

## 3. Human-in-the-Loop Review System

### Concept: Review Gates

A **review gate** is a point in task execution where external input is requested. Execution
pauses until input is received or a timeout expires.

Review gates support three timing points:

| Timing | API | Use Case |
|---|---|---|
| Before execution | `Task.builder().beforeReview(Review...)` | Gate expensive or sensitive tasks |
| During execution | `HumanInputTool` in the agent's tool list | Agent asks clarifying questions mid-task |
| After execution | `Task.builder().review(Review...)` | Review output before it passes to the next task |

### ReviewHandler SPI

```java
public interface ReviewHandler {
    /**
     * Present a review request to an external source.
     * Blocks until a decision is made or the timeout expires.
     */
    ReviewDecision review(ReviewRequest request);
}

public sealed interface ReviewDecision permits ReviewDecision.Continue,
        ReviewDecision.Edit, ReviewDecision.ExitEarly {

    record Continue() implements ReviewDecision {}
    record Edit(String revisedOutput) implements ReviewDecision {}
    record ExitEarly() implements ReviewDecision {}
}
```

Built-in implementations:

```java
ReviewHandler.console()                      // CLI with stdin, countdown timer display
ReviewHandler.web(URI callbackUrl)           // Webhook-based (for production deployments)
ReviewHandler.autoApprove()                  // Testing / CI: always continues immediately
ReviewHandler.autoApproveWithDelay(Duration) // Simulates human review timing in tests
```

### Timeout and Default Actions

Every review gate has a configurable timeout with a default action when time expires:

```java
Review.builder()
    .prompt("Review the pricing recommendation memo")
    .timeout(Duration.ofMinutes(5))
    .onTimeout(Review.CONTINUE)    // auto-continue when timeout expires
    // or: .onTimeout(Review.EXIT_EARLY)
    // or: .onTimeout(Review.FAIL)
    .build()
```

### CLI Interaction (ConsoleReviewHandler)

```
=== Task Complete: Draft pricing recommendation memo ===

Output:
  Based on competitor analysis, I recommend a 12% price reduction
  on the Standard tier and a 5% increase on Enterprise...

  [Truncated -- full output in trace]

[c] Continue  [e] Edit  [x] Exit early  (auto-continue in 4:58)
> _
```

### During-Execution Review (HumanInputTool)

An agent can request clarification mid-task via a `HumanInputTool`. The ReviewHandler
surfaces the request to the same CLI/web interface:

```java
Task.builder()
    .description("Draft a marketing email")
    .tools(HumanInputTool.of())     // agent can pause and ask questions
    .build()
```

During execution, the agent invokes the tool:
```
=== Input Requested: Draft a marketing email ===

Agent asks: "Should the email focus on the enterprise or SMB segment?"

[type response and press Enter]  (auto-skip in 2:00)
> Enterprise segment, especially CISO persona
```

The timeout for during-execution requests is configurable separately from post-execution review.

### Ensemble-Level Review Policy

A global `ReviewHandler` and `ReviewPolicy` can be set at the ensemble level. Individual
tasks can override:

```java
Ensemble.builder()
    .reviewHandler(ReviewHandler.console())
    .reviewPolicy(Review.afterEveryTask())       // review each task output
    // or: .reviewPolicy(Review.afterLastTask())  // only final output
    // or: .reviewPolicy(Review.never())           // fully autonomous (default)
    .task(task1)
    .task(task2)
    .run();

// Individual task overrides
Task.builder()
    .description("Execute financial transaction")
    .review(Review.required())   // always review, even if ensemble default is never
    .build();

Task.builder()
    .description("Format the report header")
    .review(Review.skip())       // skip, even if ensemble default is always
    .build();
```

---

## 4. Partial Results and Graceful Exit-Early

### Problem with v1.x EnsembleOutput

The current `EnsembleOutput` assumes all tasks ran to completion. An error or user-triggered
exit leaves users with either a thrown exception or no results at all.

### v2.0 EnsembleOutput

`EnsembleOutput` is redesigned to treat partial completion as a first-class, non-error outcome:

```java
EnsembleOutput output = ensemble.run();

output.isComplete();              // true only when all tasks completed
output.completedTasks();          // list of outputs for completed tasks (always safe to call)
output.exitReason();              // COMPLETED | USER_EXIT_EARLY | TIMEOUT | ERROR
output.lastCompletedOutput();     // convenience: last task that finished
output.taskOutputs();             // all completed outputs (unchanged method name)

// Accessing by task identity
output.getOutput(task1);          // returns Optional<TaskOutput>
```

### Exit-Early Contract

The framework guarantees that any task that completed before an exit-early decision:
- Has its output included in `EnsembleOutput.completedTasks()`
- Has its output persisted to any declared memory scopes
- Can be inspected via `output.getOutput(task)`

This enables resumable workflows in future iterations: a subsequent run can read from
memory scopes populated by completed tasks in a prior run.

### ExitReason

```java
public enum ExitReason {
    COMPLETED,         // all tasks finished normally
    USER_EXIT_EARLY,   // user selected "exit early" at a review gate
    TIMEOUT,           // a review gate timeout expired with onTimeout(EXIT_EARLY)
    ERROR              // an unrecoverable error terminated the pipeline
}
```

---

## 5. Workflow Inference

### Default: Sequential by Declaration Order

When no workflow is specified, tasks execute sequentially in the order they are declared.
Context flows forward automatically.

### Inferred Parallelism from Context Declarations

When tasks declare explicit `context(...)` dependencies, the framework constructs a DAG
and executes tasks in parallel where the dependency graph allows:

```java
// Framework infers: tasks 1 and 2 run in parallel, task 3 waits for both
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder().description("Analyze market A").build())
    .task(Task.builder().description("Analyze market B").build())
    .task(Task.builder()
        .description("Combine market analyses")
        .context("Analyze market A", "Analyze market B")
        .build())
    .run();
```

### Explicit Override

Users who want to force a specific execution strategy can still declare it explicitly:

```java
Ensemble.builder()
    .workflow(Workflow.SEQUENTIAL)    // force sequential regardless of DAG structure
    .workflow(Workflow.PARALLEL)      // force all tasks into DAG executor
    .workflow(Workflow.HIERARCHICAL)  // manager-worker delegation (unchanged semantics)
```

---

## 6. Module Structure

The monolithic `agentensemble-core` is split into focused modules with clear SPI boundaries:

| Module | Contents | Required |
|---|---|---|
| `agentensemble-core` | Task, Ensemble, Agent, AgentSynthesizer, EnsembleOutput, workflow engine, template resolver, validation, exception hierarchy | Yes |
| `agentensemble-memory` | MemoryStore SPI, MemoryScope, MemoryEntry, InMemoryStore, EmbeddingStoreLongTermMemory, MemoryTool | Optional |
| `agentensemble-review` | ReviewHandler SPI, Review, ReviewDecision, ReviewRequest, ConsoleReviewHandler, HumanInputTool | Optional |
| `agentensemble-metrics-micrometer` | MicrometerToolMetrics (unchanged) | Optional |
| `agentensemble-devtools` | DagExporter, EnsembleDevTools (unchanged) | Optional |
| `agentensemble-tools/*` | Built-in tool modules (unchanged) | Optional |
| `agentensemble-viz` | Visualization tooling (unchanged) | Optional |
| `agentensemble-examples` | Updated examples (task-first API) | No |
| `agentensemble-bom` | Bill of Materials for all modules | No (convenience) |

Minimal dependency (core framework only):
```kotlin
implementation("net.agentensemble:agentensemble-core:2.0.0")
```

Full dependency via BOM:
```kotlin
implementation(platform("net.agentensemble:agentensemble-bom:2.0.0"))
implementation("net.agentensemble:agentensemble-core")
implementation("net.agentensemble:agentensemble-memory")
implementation("net.agentensemble:agentensemble-review")
```

---

## 7. SPI Boundary Definitions

These interfaces define the contracts between modules. They must be stable before
parallel development begins on each workstream.

### AgentSynthesizer (core)

```java
package net.agentensemble;

public interface AgentSynthesizer {
    Agent synthesize(Task task, SynthesisContext context);
}

public record SynthesisContext(ChatLanguageModel model, Locale locale) {}
```

### MemoryStore (agentensemble-memory)

```java
package net.agentensemble.memory;

public interface MemoryStore {
    void store(String scope, MemoryEntry entry);
    List<MemoryEntry> retrieve(String scope, String query, int maxResults);
    void evict(String scope, EvictionPolicy policy);
}

public record MemoryEntry(String content, Object structuredContent,
                          Instant storedAt, Map<String, String> metadata) {}
```

### ReviewHandler (agentensemble-review)

```java
package net.agentensemble.review;

public interface ReviewHandler {
    ReviewDecision review(ReviewRequest request);
}

public record ReviewRequest(
    String taskDescription,
    String taskOutput,
    ReviewTiming timing,
    Duration timeout
) {}

public enum ReviewTiming { BEFORE_EXECUTION, DURING_EXECUTION, AFTER_EXECUTION }

public sealed interface ReviewDecision permits ReviewDecision.Continue,
        ReviewDecision.Edit, ReviewDecision.ExitEarly {

    record Continue() implements ReviewDecision {}
    record Edit(String revisedOutput) implements ReviewDecision {}
    record ExitEarly() implements ReviewDecision {}
}
```

---

## 8. Breaking Changes Summary

| Area | v1.x | v2.0 |
|---|---|---|
| Agent declaration | Required for every task | Optional; auto-synthesized when absent |
| `Ensemble.builder().agents(...)` | Required | Removed |
| `Task.agent(...)` | Required | Optional |
| Tools | Declared on Agent | Declared on Task (primary); Agent retains when explicit |
| `chatLanguageModel` | Declared on Agent | Declared on Task (per-task) or Ensemble (default) |
| Memory | `Ensemble.memory(EnsembleMemory)` -- run-scoped | `Task.memory(scope)` + `Ensemble.memoryStore(...)` -- cross-run |
| `EnsembleOutput` | Assumes full completion | `isComplete()`, `exitReason()`, `completedTasks()` |
| Workflow | Must be explicitly declared | Inferred; explicit override available |
| Module structure | Everything in `agentensemble-core` | Split into core + memory + review |

---

## 9. Implementation Sequencing

MapReduceEnsemble (issues #98, #99, #100) ships in the current v1.x API before this work
begins. The MapReduce implementation will be refactored as part of the v2.0 migration.

### Parallel Workstreams

The following groups can be developed in parallel once the SPI contracts (section 7) are
agreed and documented:

**Group A -- Core refactor** (foundation; other groups depend on this)
- Task-first core: Task absorbs tools, LLM, maxIterations from Agent; `Ensemble.builder().agents()` removed
- Agent auto-synthesis: `AgentSynthesizer` SPI, template-based default, LLM-based opt-in

**Group B -- Memory** (parallel with Group A; depends on SPI contract)
- Module split: extract `agentensemble-memory` from core
- Task-scoped cross-execution memory: named scopes, MemoryStore SPI, TTL/eviction config

**Group C -- Review** (parallel with Groups A and B; depends on SPI contract)
- Module split: extract `agentensemble-review` from core
- ReviewHandler SPI + ConsoleReviewHandler: CLI interaction, timeout countdown
- Review gates: before/during/after timing, ensemble-level policy, HumanInputTool

**Group D -- Output and Workflow** (depends on Group A)
- Partial results: `EnsembleOutput` redesign, ExitReason, exit-early memory persistence guarantee
- Workflow inference: DAG inference from `context()` declarations, explicit override preserved

**Group E -- MapReduce refactor** (depends on Group A + #98-100 merging)
- Rework `MapReduceEnsemble` to task-first paradigm

**Group F -- Finalization** (depends on Groups A + B + C)
- BOM module: `agentensemble-bom` covering all v2.0 modules
- Migration guide: `docs/migration/v1-to-v2.md`, updated examples, updated docs

---

## 10. Migration from v1.x

A migration guide will be provided at `docs/migration/v1-to-v2.md` covering:

- Translating `Agent.builder()` declarations into `Task.builder()` configuration
- Moving tools from agents to tasks
- Moving `chatLanguageModel` from agents to the ensemble or individual tasks
- Replacing `Ensemble.memory(EnsembleMemory)` with task-scoped memory
- Updating module dependencies for the new module structure
- Handling the new `EnsembleOutput` shape

A mechanical translation is possible for all common v1.x patterns. The only case requiring
architectural thought is when a single agent is deliberately reused across multiple tasks
for shared state or persona continuity -- users should continue to use explicit `Agent`
declarations via `Task.agent(...)` in that case.
