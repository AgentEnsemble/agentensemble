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

## Structured Delegation Contracts

For each delegation attempt the framework internally constructs a `DelegationRequest` and produces a `DelegationResponse`. These typed objects are available for observability, audit, and custom tooling after the ensemble run completes.

### DelegationRequest

`DelegationRequest` is an immutable, builder-pattern object built by the framework before each delegation:

```java
// The framework constructs this automatically -- no user action required
DelegationRequest request = DelegationRequest.builder()
    .agentRole("Content Writer")
    .taskDescription("Write a blog post about AI trends: ...")
    .priority(DelegationPriority.NORMAL)  // default
    .build();
// request.getTaskId() is auto-populated with a UUID v4
```

| Field | Type | Default | Description |
|---|---|---|---|
| `taskId` | `String` | Auto-UUID | Unique identifier; correlates with the matching `DelegationResponse` |
| `agentRole` | `String` | (required) | Target agent's role |
| `taskDescription` | `String` | (required) | Subtask description |
| `priority` | `DelegationPriority` | `NORMAL` | Priority hint (`LOW`, `NORMAL`, `HIGH`, `CRITICAL`) |
| `scope` | `Map<String, Object>` | `{}` | Optional bounded context for this delegation |
| `expectedOutputSchema` | `String` | `null` | Optional description of the expected output format |
| `maxOutputRetries` | `int` | `0` | Output parsing retry count |
| `metadata` | `Map<String, Object>` | `{}` | Arbitrary observability metadata |

### DelegationResponse

`DelegationResponse` is an immutable record produced after each delegation attempt, whether successful or blocked by a guard:

| Field | Type | Description |
|---|---|---|
| `taskId()` | `String` | Correlates with the originating `DelegationRequest` |
| `status()` | `DelegationStatus` | `SUCCESS`, `FAILURE`, or `PARTIAL` |
| `workerRole()` | `String` | Role of the agent that executed (or was targeted) |
| `rawOutput()` | `String` | Worker's text output; `null` on failure |
| `parsedOutput()` | `Object` | Parsed Java object for structured output tasks; `null` otherwise |
| `artifacts()` | `Map<String, Object>` | Named artefacts produced during execution |
| `errors()` | `List<String>` | Error messages accumulated; empty on success |
| `metadata()` | `Map<String, Object>` | Observability metadata |
| `duration()` | `Duration` | Wall-clock time from delegation start to completion |

### Accessing Responses After Execution

`AgentDelegationTool` exposes `getDelegationResponses()` for peer delegation. In practice, this is accessible by inspecting the tool instances attached to agents after execution. For programmatic access, use event listeners or custom tool wrappers.

Guard failures (depth limit, self-delegation, unknown role) also produce a `FAILURE` response, so every delegation attempt is auditable regardless of outcome.

---

## Delegation Policy Hooks

Delegation policies let you intercept and validate each delegation attempt _before_ the
worker agent executes. They run after all built-in guards (depth limit, self-delegation,
unknown agent) and before the worker is invoked.

### Why Use Policies?

The built-in guards cover structural constraints. Policies cover _business rules_:

- Block any delegation when a required field is missing: `"project_key must not be UNKNOWN"`
- Deny delegation to a specific role based on context: `"Analyst requires scope.region"`
- Inject missing defaults before the worker sees the request

### DelegationPolicy Interface

`DelegationPolicy` is a `@FunctionalInterface`. Register one or more policies on the
`Ensemble` builder:

```java
Ensemble.builder()
    .agent(coordinator)
    .agent(analyst)
    .task(task)
    .delegationPolicy((request, ctx) -> {
        if ("UNKNOWN".equals(request.scope().get("project_key"))) {
            return DelegationPolicyResult.reject("project_key must not be UNKNOWN");
        }
        return DelegationPolicyResult.allow();
    })
    .build();
```

### DelegationPolicyResult

Each policy returns one of three outcomes:

| Result | Factory Method | Effect |
|--------|---------------|--------|
| Allow | `DelegationPolicyResult.allow()` | Proceed with delegation unchanged |
| Reject | `DelegationPolicyResult.reject("reason")` | Block delegation; worker is never invoked; FAILURE response returned |
| Modify | `DelegationPolicyResult.modify(modifiedRequest)` | Replace the working request and continue evaluating remaining policies |

### DelegationPolicyContext

Each policy receives a `DelegationPolicyContext` alongside the request:

| Field | Type | Description |
|-------|------|-------------|
| `delegatingAgentRole()` | `String` | Role of the agent initiating the delegation |
| `currentDepth()` | `int` | Current delegation depth (0 = root) |
| `maxDepth()` | `int` | Maximum allowed depth for this run |
| `availableWorkerRoles()` | `List<String>` | Roles of agents available in this context |

### Evaluation Semantics

Policies are evaluated in registration order:

1. If any policy returns `REJECT`, evaluation stops immediately. The worker is never invoked
   and a `DelegationResponse` with `status = FAILURE` is returned to the calling agent.
2. If a policy returns `MODIFY`, the modified request replaces the working request for all
   subsequent policy evaluations and for the final worker invocation.
3. If all policies return `ALLOW`, the worker executes normally.

```java
Ensemble.builder()
    .agent(coordinator)
    .agent(analyst)
    .agent(writer)
    .task(task)
    // Policy 1: require project context
    .delegationPolicy((request, ctx) -> {
        if (request.scope().get("project_key") == null) {
            return DelegationPolicyResult.reject("project_key is required");
        }
        return DelegationPolicyResult.allow();
    })
    // Policy 2: inject region default when missing
    .delegationPolicy((request, ctx) -> {
        if ("Analyst".equals(request.agentRole()) && !request.scope().containsKey("region")) {
            var enriched = request.toBuilder()
                .scope(Map.of("region", "us-east-1"))
                .build();
            return DelegationPolicyResult.modify(enriched);
        }
        return DelegationPolicyResult.allow();
    })
    .build();
```

### Scope and Coverage

Policies apply to both peer delegation (`AgentDelegationTool`) and hierarchical delegation
(`DelegateTaskTool`). They are propagated through `DelegationContext.descend()`, so nested
delegation chains also evaluate all registered policies.

---

## Delegation Lifecycle Events

The framework fires delegation lifecycle events to all registered `EnsembleListener`
instances. This enables tracing, latency measurement, and correlation across delegation chains.

### Event Types

#### DelegationStartedEvent

Fired immediately before the worker agent executes. Only fired when all guards and policies
pass -- guard/policy failures produce a `DelegationFailedEvent` directly (no start event).

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Unique correlation ID matching the completed/failed event |
| `delegatingAgentRole()` | `String` | Role of the agent initiating the delegation |
| `workerRole()` | `String` | Role of the agent that will execute the subtask |
| `taskDescription()` | `String` | Description of the subtask |
| `delegationDepth()` | `int` | Depth of this delegation (1 = first, 2 = nested, etc.) |
| `request()` | `DelegationRequest` | The full typed delegation request |

#### DelegationCompletedEvent

Fired immediately after the worker agent completes successfully.

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Matches the corresponding `DelegationStartedEvent` |
| `delegatingAgentRole()` | `String` | Role of the agent that initiated the delegation |
| `workerRole()` | `String` | Role of the worker that executed |
| `response()` | `DelegationResponse` | Full typed response with output, metadata, and duration |
| `duration()` | `Duration` | Elapsed time from delegation start to completion |

#### DelegationFailedEvent

Fired when a delegation fails for any reason: guard violation, policy rejection, or worker
exception. Guard and policy failures have `cause() == null`; worker exceptions carry the
thrown exception.

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Correlation ID matching the `DelegationRequest.taskId` |
| `delegatingAgentRole()` | `String` | Role of the initiating agent |
| `workerRole()` | `String` | Role of the intended target |
| `failureReason()` | `String` | Human-readable failure description |
| `cause()` | `Throwable` | Exception if worker threw; `null` for guard/policy failures |
| `response()` | `DelegationResponse` | FAILURE response with error messages |
| `duration()` | `Duration` | Elapsed time from delegation start to failure |

### Registering Delegation Event Listeners

Use lambda convenience methods on the builder:

```java
Ensemble.builder()
    .agent(coordinator)
    .agent(analyst)
    .task(task)
    .onDelegationStarted(event ->
        log.info("Delegation started [{}]: {} -> {} (depth {})",
            event.delegationId(), event.delegatingAgentRole(),
            event.workerRole(), event.delegationDepth()))
    .onDelegationCompleted(event ->
        metrics.recordDelegationLatency(event.workerRole(), event.duration()))
    .onDelegationFailed(event ->
        log.warn("Delegation failed [{}]: {}", event.delegationId(), event.failureReason()))
    .build();
```

Or implement `EnsembleListener` to handle all delegation events in one class:

```java
public class DelegationAuditListener implements EnsembleListener {

    @Override
    public void onDelegationStarted(DelegationStartedEvent event) {
        auditLog.record("DELEGATION_STARTED", event.delegationId(),
            event.delegatingAgentRole(), event.workerRole());
    }

    @Override
    public void onDelegationCompleted(DelegationCompletedEvent event) {
        auditLog.record("DELEGATION_COMPLETED", event.delegationId(),
            event.workerRole(), event.duration());
    }

    @Override
    public void onDelegationFailed(DelegationFailedEvent event) {
        auditLog.record("DELEGATION_FAILED", event.delegationId(),
            event.workerRole(), event.failureReason());
    }
}
```

### Correlation IDs

The `delegationId` field ties the lifecycle together. For a successful delegation:

1. `DelegationStartedEvent.delegationId()` is emitted
2. `DelegationCompletedEvent.delegationId()` matches (1)

For a failed delegation after worker execution begins:

1. `DelegationStartedEvent.delegationId()` is emitted
2. `DelegationFailedEvent.delegationId()` matches (1)

For guard/policy failures (worker never starts):

- Only `DelegationFailedEvent` is fired; there is no corresponding `DelegationStartedEvent`
- The `delegationId` matches `DelegationRequest.getTaskId()` for correlation with the internal response log

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
