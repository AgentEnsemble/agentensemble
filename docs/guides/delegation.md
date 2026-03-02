# Delegation

Agent delegation allows agents to hand off subtasks to other agents in the ensemble during their own task execution. This enables peer-to-peer collaboration without requiring a hierarchical manager.

---

## Overview

When an agent has `allowDelegation = true`, the framework automatically injects a `delegate` tool into its tool list at execution time. The agent can call this tool during its ReAct reasoning loop:

```
Agent A calls delegate("Agent B", "Write a summary of the research below: ...")
  -> Framework executes Agent B with the subtask
  -> Agent B's output is returned to Agent A as the tool result
Agent A incorporates the result and produces its final answer
```

---

## Enabling Delegation

Set `allowDelegation = true` on any agent that should be able to delegate:

```java
Agent leadResearcher = Agent.builder()
    .role("Lead Researcher")
    .goal("Coordinate research by identifying and delegating specialised subtasks")
    .llm(model)
    .allowDelegation(true)
    .build();

Agent writer = Agent.builder()
    .role("Content Writer")
    .goal("Write clear, engaging content from research notes")
    .llm(model)
    .build();  // allowDelegation defaults to false
```

---

## Basic Example

```java
var coordinateTask = Task.builder()
    .description("Research the latest AI developments and produce a well-written summary")
    .expectedOutput("A polished 800-word blog post covering AI trends")
    .agent(leadResearcher)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(leadResearcher)
    .agent(writer)
    .task(coordinateTask)
    .build()
    .run();
```

During execution, the `leadResearcher` may decide to call:

```
delegate("Content Writer", "Write an 800-word blog post about these AI trends: [findings]")
```

The framework executes the writer, returns the blog post to the researcher, and the researcher incorporates it into its final answer.

---

## Delegation Guards

Three guards are enforced automatically:

### 1. Self-Delegation

An agent cannot delegate to itself. If attempted, the tool returns:

```
Cannot delegate to yourself (role: 'Lead Researcher'). Choose a different agent.
```

The calling agent receives this as the tool result and must handle it.

### 2. Unknown Agent

If the specified role does not match any registered agent (case-insensitive), the tool returns:

```
Agent not found with role 'Data Scientist'. Available roles: [Lead Researcher, Content Writer]
```

### 3. Depth Limit

Delegation chains are limited to prevent infinite recursion. When the limit is reached, the tool returns:

```
Delegation depth limit reached (max: 3, current: 3). Complete this task yourself without further delegation.
```

---

## Delegation Depth

The `maxDelegationDepth` field on the ensemble controls how many delegation levels are permitted. The default is 3.

```java
Ensemble.builder()
    .agent(researchCoordinator)
    .agent(researcher)
    .agent(writer)
    .task(task)
    .maxDelegationDepth(2)   // A can delegate to B, B can delegate to C, but C cannot delegate further
    .build();
```

Set `maxDelegationDepth(1)` to allow only one level of delegation (A delegates to B, but B cannot delegate).

---

## Delegation in Hierarchical Workflow

In hierarchical workflow, the Manager agent delegates tasks to workers using the `delegateTask` tool. This is separate from peer delegation. Worker agents can also delegate to each other (if `allowDelegation = true`) in addition to the manager's own delegation.

```java
Ensemble.builder()
    .agent(leadResearcher)     // allowDelegation = true
    .agent(dataAnalyst)
    .agent(writer)
    .task(task1)
    .task(task2)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(managerModel)
    .maxDelegationDepth(2)    // applies to worker-to-worker delegation
    .build();
```

---

## Delegation and Memory

When memory is configured, the delegation tool passes the same `MemoryContext` to the delegated agent. This means:

- The delegated agent can read from short-term memory (prior task outputs in the run)
- The delegated agent's output is recorded into memory
- Long-term and entity memory are available to all delegated agents

---

## MDC Logging

When delegation occurs, two MDC keys are set for the duration of the delegated execution:

| Key | Example Value |
|---|---|
| `delegation.depth` | `"1"` |
| `delegation.parent` | `"Lead Researcher"` |

This allows log aggregation tools to show the full parent-child delegation chain.

---

## Delegation vs. Hierarchical Workflow

| | Peer Delegation | Hierarchical Workflow |
|---|---|---|
| Initiator | Any agent with `allowDelegation = true` | Automatic Manager agent |
| Trigger | Agent decides during reasoning | Manager tool call |
| Routing | Agent chooses who to delegate to | Manager decides |
| Configuration | `agent.allowDelegation(true)` | `workflow(Workflow.HIERARCHICAL)` |
| Depth limit | `ensemble.maxDelegationDepth(n)` | `managerMaxIterations` |

Both can be used together: hierarchical workflow with worker agents that also support peer delegation.
