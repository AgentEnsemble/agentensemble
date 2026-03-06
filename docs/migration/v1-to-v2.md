# Migrating from v1.x to v2.0.0

v2.0.0 is a major release that shifts AgentEnsemble from an **agent-first** to a
**task-first** paradigm. The central change is that `Agent` declarations are now optional --
the framework synthesizes agents automatically from task descriptions. Most migrations are
mechanical find-and-replace operations. This guide walks through every breaking change with
side-by-side before/after examples.

---

## Overview of Breaking Changes

| Area | v1.x | v2.0.0 |
|---|---|---|
| Agent declaration | Required for every task | Optional; auto-synthesized when absent |
| `Ensemble.builder().agents(...)` | Required | Removed |
| `Task.agent(...)` | Required | Optional (power-user escape hatch) |
| Tools | Declared on `Agent` | Declared on `Task` (preferred) |
| `chatLanguageModel` / `llm` | Declared on `Agent` | Declared on `Task` or `Ensemble` (default) |
| Memory | `Ensemble.memory(EnsembleMemory)` | `Task.memory(scope)` + `Ensemble.memoryStore(...)` |
| `EnsembleOutput` | Assumes full completion | `isComplete()`, `exitReason()`, `completedTasks()` |
| Workflow declaration | Required | Optional; inferred from context declarations |
| Module structure | Everything in `agentensemble-core` | Split: core + memory + review |

---

## 1. Removing Redundant Agent Declarations

The most common migration is removing explicit `Agent.builder()` constructs where the agent
adds no information beyond what is already in the task description.

### Before (v1.x)

```java
var researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, up-to-date information on any given topic")
    .background("You are a veteran researcher specialising in technology.")
    .llm(model)
    .build();

var writer = Agent.builder()
    .role("Content Writer")
    .goal("Write engaging, well-structured blog posts")
    .llm(model)
    .build();

var researchTask = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A 400-word summary of current state and key players")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about {topic} based on the provided research")
    .expectedOutput("A 600-800 word blog post in markdown format")
    .agent(writer)
    .context(List.of(researchTask))
    .build();

EnsembleOutput output = Ensemble.builder()
    .task(researchTask)
    .task(writeTask)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run(Map.of("topic", "AI agents"));
```

### After (v2.0.0)

```java
// No Agent declarations needed. The framework synthesizes agents from task descriptions.
var researchTask = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A 400-word summary of current state and key players")
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about {topic} based on the provided research")
    .expectedOutput("A 600-800 word blog post in markdown format")
    .context(List.of(researchTask))  // context declaration unchanged
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)        // default LLM for all synthesized agents
    .task(researchTask)
    .task(writeTask)
    // workflow() not needed -- inferred as PARALLEL from the context declaration
    .build()
    .run(Map.of("topic", "AI agents"));
```

**What changed:**
- `Agent.builder()` removed entirely
- `Task.agent(...)` removed
- `Ensemble.builder().chatLanguageModel(model)` replaces per-agent `llm(model)` calls
- `Ensemble.builder().workflow(Workflow.SEQUENTIAL)` removed -- inferred automatically
- Import for `Agent` removed; `Workflow` import no longer needed for the sequential case

---

## 2. Moving Tools from Agents to Tasks

In v1.x, tools were declared on the agent. In v2.0.0, tools belong on the task where they
are actually used.

### Before (v1.x)

```java
var researcher = Agent.builder()
    .role("Researcher")
    .goal("Research topics thoroughly")
    .tools(List.of(new WebSearchTool(), new WebScraperTool()))
    .llm(model)
    .build();

var task = Task.builder()
    .description("Research {topic}")
    .expectedOutput("A comprehensive research summary")
    .agent(researcher)
    .build();
```

### After (v2.0.0)

```java
// Tools move to the task; no agent declaration needed
var task = Task.builder()
    .description("Research {topic}")
    .expectedOutput("A comprehensive research summary")
    .tools(List.of(new WebSearchTool(), new WebScraperTool()))
    .build();

Ensemble.builder()
    .chatLanguageModel(model)
    .task(task)
    .build()
    .run(Map.of("topic", "AI trends"));
```

**What changed:**
- `Agent.builder().tools(...)` removed
- `Task.builder().tools(...)` added
- Agent removed entirely; `chatLanguageModel` moves to the ensemble

---

## 3. Moving chatLanguageModel to the Ensemble or Task

In v1.x, every agent required a `chatLanguageModel` (or `llm`). In v2.0.0, the LLM is
configured once at the ensemble level. Per-task overrides use `Task.builder().chatLanguageModel(...)`.

### Before (v1.x): Same LLM for all agents

```java
var analyst = Agent.builder().role("Analyst").llm(model).build();
var writer  = Agent.builder().role("Writer").llm(model).build();
```

### After (v2.0.0): Single declaration at ensemble level

```java
Ensemble.builder()
    .chatLanguageModel(model)  // applies to all auto-synthesized agents
    .task(analysisTask)
    .task(writeTask)
    .build()
    .run();
```

### Before (v1.x): Different LLMs per agent (keep explicit agents)

```java
var analyst = Agent.builder()
    .role("Analyst")
    .llm(fastModel)       // different model per agent -- must keep explicit agents
    .build();

var architect = Agent.builder()
    .role("Solution Architect")
    .llm(powerfulModel)
    .build();
```

### After (v2.0.0): Per-task LLM override OR keep explicit agents

```java
// Option A: per-task chatLanguageModel (no explicit agents)
var analysisTask = Task.builder()
    .description("Analyse the system requirements")
    .expectedOutput("A structured requirements analysis")
    .chatLanguageModel(fastModel)       // task-level override
    .build();

var architectTask = Task.builder()
    .description("Design a solution architecture")
    .expectedOutput("A high-level architecture diagram description")
    .chatLanguageModel(powerfulModel)   // different model for this task
    .build();

// Option B: keep explicit agents when agent identity matters across multiple tasks
Agent sharedAnalyst = Agent.builder()
    .role("Lead Analyst")
    .goal("Provide analysis across all project tasks")
    .llm(powerfulModel)
    .build();

var task1 = Task.builder()
    .description("Initial requirements analysis")
    .expectedOutput("Requirements list")
    .agent(sharedAnalyst)   // shared agent identity preserved
    .build();

var task2 = Task.builder()
    .description("Review the implementation plan")
    .expectedOutput("Review notes")
    .agent(sharedAnalyst)   // same agent instance used on both tasks
    .build();
```

---

## 4. Moving maxIterations from Agent to Task

`maxIterations` controlled how many ReAct loop iterations an agent could perform. In v2.0.0
it moves to the task, where it belongs conceptually.

### Before (v1.x)

```java
var researcher = Agent.builder()
    .role("Researcher")
    .llm(model)
    .maxIterations(10)   // agent-level setting
    .build();

var task = Task.builder()
    .description("Research {topic} thoroughly")
    .expectedOutput("A research summary")
    .agent(researcher)
    .build();
```

### After (v2.0.0)

```java
var task = Task.builder()
    .description("Research {topic} thoroughly")
    .expectedOutput("A research summary")
    .maxIterations(10)   // moved to task
    .build();
```

---

## 5. Migrating Memory Configuration

The v1.x memory system (`EnsembleMemory`, `EnsembleMemory.builder()`) is replaced by
task-scoped cross-execution memory that persists across separate `run()` invocations.

### Before (v1.x): Run-scoped short-term memory

```java
EnsembleMemory memory = EnsembleMemory.builder()
    .shortTerm(true)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .memory(memory)    // v1.x ensemble-level memory
    .build()
    .run();
```

### After (v2.0.0): Task-scoped cross-execution memory

```java
// Add agentensemble-memory to your build:
// implementation("net.agentensemble:agentensemble-memory:VERSION")

MemoryStore store = MemoryStore.inMemory();  // or MemoryStore.embeddings(...) for production

var researchTask = Task.builder()
    .description("Research TechCorp competitors for {week}")
    .expectedOutput("Competitive intelligence briefing")
    .memory("competitor-intel")    // declares a named scope
    .build();

var strategyTask = Task.builder()
    .description("Recommend strategy for {week} based on research")
    .expectedOutput("Three strategic recommendations")
    .memory("competitor-intel")    // reads from same scope -- sees prior runs' outputs
    .build();

Ensemble ensemble = Ensemble.builder()
    .chatLanguageModel(model)
    .task(researchTask)
    .task(strategyTask)
    .memoryStore(store)    // v2.0.0 ensemble-level MemoryStore
    .build();

// Run 1: scope is empty; agents work from scratch
ensemble.run(Map.of("week", "2026-01-06"));

// Run 2: agents see Run 1 outputs injected into their prompts automatically
ensemble.run(Map.of("week", "2026-01-13"));
```

**Key differences:**
- `Ensemble.memory(EnsembleMemory)` removed; replaced by `Ensemble.memoryStore(MemoryStore)`
- Memory scope is declared per-task with `.memory("scope-name")`
- Memory persists across multiple `run()` invocations (cross-execution)
- Short-term (intra-run) accumulation is implicit when tasks share a scope in one run
- For production use, back the store with an embedding model:
  `MemoryStore.embeddings(embeddingModel, embeddingStore)`

---

## 6. Updated EnsembleOutput API

`EnsembleOutput` is redesigned to treat partial completion as a first-class outcome.

### Before (v1.x)

```java
EnsembleOutput output = ensemble.run();

// v1.x assumed all tasks completed
String finalResult = output.getRaw();
List<TaskOutput> allOutputs = output.getTaskOutputs();
```

### After (v2.0.0)

```java
EnsembleOutput output = ensemble.run();

// Check whether all tasks completed
if (output.isComplete()) {
    System.out.println("Final result: " + output.getRaw());
} else {
    System.out.printf("Pipeline stopped: %s%n", output.getExitReason());
    // USER_EXIT_EARLY, TIMEOUT, or ERROR
}

// Safe in all cases -- only the tasks that finished
List<TaskOutput> completed = output.completedTasks();

// Last completed output (Optional)
output.lastCompletedOutput().ifPresent(o -> System.out.println(o.getRaw()));

// Lookup by task reference (uses identity)
output.getOutput(researchTask).ifPresent(o -> System.out.println(o.getRaw()));
```

**New methods:**

| Method | Description |
|---|---|
| `isComplete()` | `true` only when all tasks completed (`ExitReason.COMPLETED`) |
| `getExitReason()` | `COMPLETED`, `USER_EXIT_EARLY`, `TIMEOUT`, or `ERROR` |
| `completedTasks()` | Alias for `getTaskOutputs()` -- all outputs that finished |
| `lastCompletedOutput()` | `Optional<TaskOutput>` -- convenience for the final output |
| `getOutput(task)` | `Optional<TaskOutput>` -- lookup by task reference (identity) |

---

## 7. Workflow Inference

In v2.0.0, declaring `.workflow(...)` is optional. The framework infers the execution
strategy from task context declarations:

| Condition | Inferred Workflow |
|---|---|
| No task declares a `context(...)` dependency | `SEQUENTIAL` (tasks run in declaration order) |
| Any task declares a `context(...)` on another task in the ensemble | `PARALLEL` (DAG-based) |

You can still declare `.workflow(...)` explicitly to force a specific strategy.

### Before (v1.x): Always required

```java
Ensemble.builder()
    .workflow(Workflow.SEQUENTIAL)   // always required
    .build();
```

### After (v2.0.0): Inferred

```java
// Sequential (no context deps) -- workflow inferred automatically
Ensemble.builder()
    .chatLanguageModel(model)
    .task(task1)
    .task(task2)
    .build()
    .run();

// Parallel (context deps declared) -- PARALLEL inferred automatically
var task2 = Task.builder()
    .description("Synthesize findings")
    .context(List.of(task1a, task1b))   // infers PARALLEL
    .build();

// Hierarchical -- still requires explicit declaration
Ensemble.builder()
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(powerfulModel)
    .task(task1)
    .task(task2)
    .build()
    .run();
```

---

## 8. New Module Dependencies

v2.0.0 splits optional capabilities into separate modules. Update your `build.gradle.kts`:

### Recommended: Use the top-level BOM

```kotlin
dependencies {
    // Import the BOM to align all module versions
    implementation(platform("net.agentensemble:agentensemble-bom:VERSION"))

    // Then add only the modules you need -- no version required
    implementation("net.agentensemble:agentensemble-core")
    implementation("net.agentensemble:agentensemble-memory")   // task-scoped memory
    implementation("net.agentensemble:agentensemble-review")   // human-in-the-loop gates
}
```

### If you use the memory API (`Task.memory(...)`, `MemoryStore`)

```kotlin
// Before: nothing extra (was in core)
// After:
implementation("net.agentensemble:agentensemble-memory:VERSION")
```

### If you use the review API (`Task.review(...)`, `ReviewHandler`)

```kotlin
// Before: nothing extra (was in core)
// After:
implementation("net.agentensemble:agentensemble-review:VERSION")
```

---

## 9. When to Keep Explicit Agent Declarations

Explicit `Agent.builder()` declarations remain supported as a power-user escape hatch.
Use them when:

1. **Multiple tasks share the same agent identity** -- e.g., a Lead Analyst reviews both
   an initial analysis task and a final review task, and you want both tasks attributed
   to the same agent in the trace.

2. **Different LLMs per agent** -- rather than per-task `chatLanguageModel(...)` overrides,
   you prefer to name the agent explicitly with its model.

3. **Custom `responseFormat`** -- the `responseFormat` field is agent-specific and cannot
   be expressed on `Task.builder()`. Instead, include formatting instructions in the task's
   `expectedOutput` field (preferred), or keep the explicit agent.

4. **Dynamic agent creation in a loop** -- when building fan-out ensembles where each agent
   represents a meaningfully distinct entity (e.g., a per-item chef specialist), explicit
   `Agent.builder()` calls in a loop remain the idiomatic pattern. See
   [Dynamic Agent Creation](../examples/dynamic-agents.md).

```java
// Legitimate use: shared agent identity across two tasks
Agent leadAnalyst = Agent.builder()
    .role("Lead Analyst")
    .goal("Own the analysis quality across the entire project")
    .background("15 years of enterprise software consulting.")
    .llm(powerfulModel)
    .build();

var reviewTask1 = Task.builder()
    .description("Review the initial requirements document")
    .expectedOutput("Annotated requirements with gaps identified")
    .agent(leadAnalyst)   // explicit -- shared identity
    .build();

var reviewTask2 = Task.builder()
    .description("Review the final implementation plan")
    .expectedOutput("Approval or revision notes")
    .agent(leadAnalyst)   // same agent instance
    .build();
```

---

## 10. Zero-Ceremony Static Factory (New in v2.0.0)

For the simplest cases, use the `Ensemble.run(model, tasks...)` static factory:

```java
// Quickest possible way to run two tasks
EnsembleOutput output = Ensemble.run(model,
    Task.of("Research the latest AI developments in healthcare"),
    Task.of("Write a 500-word blog post from the research"));

System.out.println(output.getRaw());
```

`Task.of(description)` uses a sensible default `expectedOutput`. Use
`Task.of(description, expectedOutput)` to specify both.

---

## Quick Reference: Import Changes

| Remove | Add |
|---|---|
| `import net.agentensemble.Agent;` | Remove if no explicit agents remain |
| `import net.agentensemble.workflow.Workflow;` | Remove if workflow is now inferred |
| `import net.agentensemble.memory.EnsembleMemory;` | `import net.agentensemble.memory.MemoryStore;` |

No changes needed for:
- `import net.agentensemble.Ensemble;`
- `import net.agentensemble.Task;`
- `import net.agentensemble.ensemble.EnsembleOutput;`
- `import net.agentensemble.task.TaskOutput;`
