# Migration Guide: v1.x to v2.0

AgentEnsemble v2.0 is a **major version** with breaking changes. This guide covers every
breaking change and the corresponding migration steps.

---

## Summary of Breaking Changes

| Area | v1.x | v2.0 |
|---|---|---|
| `Ensemble.builder().agent()` | Required to register agents | **Removed** -- agents live on tasks |
| `Task.agent` | Required field | Optional -- synthesized when absent |
| `EnsembleValidator` | Validates `agents` list | Validates LLM availability per task |
| `Ensemble.getAgents()` | Returns registered agents | Derives agents from tasks |

---

## 1. Remove agents from the Ensemble builder

In v1, agents were registered at the ensemble level:

```java
// v1.x
EnsembleOutput result = Ensemble.builder()
    .agent(researcher)   // register here
    .agent(writer)
    .task(researchTask)  // and bind in the task
    .task(writeTask)
    .build()
    .run();
```

In v2, agents are declared only on their tasks. Remove the `.agent()` calls from
`Ensemble.builder()`:

```java
// v2.0
EnsembleOutput result = Ensemble.builder()
    .task(researchTask)  // researchTask already has .agent(researcher)
    .task(writeTask)     // writeTask already has .agent(writer)
    .build()
    .run();
```

---

## 2. Task.agent is now optional

In v1, every `Task` required an explicit `agent`. In v2, `agent` is optional. When absent,
the framework synthesizes an agent from the task description automatically.

```java
// v2.0 -- zero-ceremony: agent synthesized from description
Task task = Task.of("Research the latest AI trends and write a summary");

// v2.0 -- explicit agent still works (power-user escape hatch)
Task task = Task.builder()
    .description("Research the latest AI trends")
    .expectedOutput("A comprehensive report")
    .agent(researcher)   // optional: omit to use synthesis
    .build();
```

---

## 3. New: Task-level LLM and tools

Instead of configuring an agent explicitly, you can declare LLM and tools on the task.
The framework synthesizes an agent using these settings:

```java
Task task = Task.builder()
    .description("Research AI trends using web search")
    .expectedOutput("A detailed report")
    .chatLanguageModel(gpt4oModel)      // task-level LLM
    .tools(List.of(webSearchTool))      // task-level tools
    .maxIterations(20)                  // task-level iteration cap
    .build();
```

---

## 4. New: zero-ceremony API

For the simplest cases, use `Task.of()` and `Ensemble.run()`:

```java
// v2.0 -- minimal code, agents synthesized automatically
EnsembleOutput result = Ensemble.run(model,
    Task.of("Research the latest AI trends"),
    Task.of("Write a blog post based on the research"));
```

---

## 5. Ensemble-level LLM

If all tasks use synthesized agents (no explicit agents), set the ensemble-level LLM so
the synthesizer knows which model to use:

```java
Ensemble.builder()
    .chatLanguageModel(model)   // used for all synthesized agents
    .task(Task.of("Research AI"))
    .task(Task.of("Write summary"))
    .build()
    .run();
```

---

## 6. Validation changes

The v1 validation check for "empty agents list" is replaced by a per-task LLM check:

- **v1**: throws if `agents` list is empty
- **v2**: throws if a task has no explicit agent AND no LLM source (task-level or ensemble-level)

---

## 7. EnsembleValidator (package-private)

If you wrote tests that directly instantiate `EnsembleValidator`, note that it no longer
reads `ensemble.getAgents()`. The constructor only reads fields that still exist on the
ensemble: `tasks`, `chatLanguageModel`, `workflow`, and constraint fields.

---

## 8. getAgents() semantics

`Ensemble.getAgents()` now **derives** agents from the tasks list instead of returning
a separately registered list. The return value is the same type (`List<Agent>`), but:

- Only tasks with **explicit** agents contribute.
- Agents are deduplicated by object identity.
- Synthesized agents are **not** included (they are ephemeral).

---

## Quick migration checklist

- [ ] Remove all `.agent(X)` calls from `Ensemble.builder()` chains.
- [ ] Ensure all `Task.builder()` calls that had `.agent(researcher)` still have it (no change needed).
- [ ] If you have tasks that never had an agent, add `.chatLanguageModel(model)` or set the ensemble-level `chatLanguageModel`.
- [ ] Update any code that expected `ValidationException` with "agent" message for empty-agents cases.
- [ ] Re-run your test suite and fix any `.agent()` compile errors on the `Ensemble.builder()`.
