# Workflows

A workflow defines how tasks are executed. AgentEnsemble supports two strategies: `SEQUENTIAL` and `HIERARCHICAL`.

---

## SEQUENTIAL (Default)

Tasks execute one after another in the order they are declared. Each task that declares `context` dependencies receives those prior outputs injected into its agent's prompt.

```java
Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)           // writeTask has context(List.of(researchTask))
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run();
```

### Execution Order

Tasks run in list order. The ensemble validates at build time that context tasks always appear before the tasks that reference them. If this ordering is violated, a `ValidationException` is thrown immediately -- not at run time.

### Context Injection

When a task has a non-empty `context` list, each referenced task's output is injected into the agent's user prompt as a "Context from prior tasks" section. The agent uses this to inform its response.

### When to Use SEQUENTIAL

- You have a defined, linear pipeline (research -> write -> review)
- Task order is fixed and predictable
- Each task depends on the output of the task immediately before it

---

## HIERARCHICAL

A virtual Manager agent is automatically created at run time. The manager receives:
- A system prompt describing all worker agents and their roles/goals
- A user prompt listing all tasks to complete

The manager uses a `delegateTask` tool to assign tasks to workers. Workers execute and return their outputs as tool results. The manager synthesizes a final response.

```java
Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .agent(editor)
    .task(researchTask)
    .task(writeTask)
    .task(editTask)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(gpt4Model)       // optional: dedicated LLM for the manager
    .managerMaxIterations(20)    // optional: default is 20
    .build()
    .run();
```

### Manager Agent

The manager is a virtual, automatically-configured agent with:
- **role**: `"Manager"`
- **goal**: `"Coordinate worker agents to complete all tasks and synthesize a comprehensive final result"`
- **background**: A generated description of all worker agents and their capabilities
- **tools**: The `delegateTask` tool

The manager is not included in the `agents` list -- it is created internally.

### Manager LLM

If `managerLlm` is not set, the manager uses the first registered agent's LLM. For production use, it is recommended to provide a capable LLM (GPT-4o, Claude 3.5, etc.) as the manager:

```java
ChatModel powerfulModel = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

Ensemble.builder()
    .agents(...)
    .tasks(...)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(powerfulModel)
    .build();
```

### Manager Max Iterations

The `managerMaxIterations` field limits how many delegation tool calls the manager can make before being forced to synthesize. Default is 20.

### Output Structure

In hierarchical workflow, `EnsembleOutput.getTaskOutputs()` contains:
1. All worker outputs in delegation order
2. The manager's final synthesized output (last)

`EnsembleOutput.getRaw()` is the manager's final synthesis.

### When to Use HIERARCHICAL

- You want the LLM to decide which agent handles each task
- The task-to-agent mapping is not obvious from the task descriptions
- You want the manager to re-order or combine tasks dynamically
- You are building a system where task routing should be AI-driven

---

## Choosing a Workflow

| Consideration | SEQUENTIAL | HIERARCHICAL |
|---|---|---|
| Task order | Fixed, user-defined | Dynamic, manager-decided |
| Routing logic | Explicit (agent per task) | Implicit (manager decides) |
| LLM calls | N calls (one per task) | N+1 calls (tasks + manager) |
| Predictability | High | Lower |
| Flexibility | Lower | Higher |

---

## Workflow and Memory

Both workflows support all memory types. Memory context is shared across all agent executions within a single `run()` call.

In hierarchical workflow, the Manager agent itself does not participate in memory -- only the worker agents do.

See the [Memory guide](memory.md).

---

## Workflow and Delegation

Both workflows support agent-to-agent delegation when agents have `allowDelegation = true`. In hierarchical workflow, worker agents can delegate to peer workers in addition to the manager's own delegation via `delegateTask`.

See the [Delegation guide](delegation.md).
