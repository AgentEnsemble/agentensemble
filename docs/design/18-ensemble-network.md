# 18 - Ensemble Network: Distributed Multi-Ensemble Orchestration

This document specifies the design for the Ensemble Network: a distributed architecture
where autonomous, long-running ensembles communicate peer-to-peer, share tasks and tools
across service boundaries, and allow humans to participate as optional observers and
decision-makers.

This is the v3.0.0 architecture. It builds on the v2.1.0 `agentensemble-web` module
(WebSocket server, wire protocol, live dashboard) and extends it into a fully distributed
multi-ensemble system.

---

## 1. Motivation

### The limitation of single-ensemble execution

AgentEnsemble v2.x treats each ensemble as a self-contained unit: define tasks, run them,
get output. This works well for discrete, bounded problems -- "research this topic and write
a report." But real-world AI systems are not discrete. They are:

- **Always-on**: running continuously, handling work as it arrives
- **Multi-domain**: different capabilities owned by different teams/services
- **Decentralized**: departments that communicate laterally, not through a central controller
- **Human-augmented**: people who observe, direct, and make critical decisions -- but who
  also go home at night while the system keeps running

### The hotel analogy

Consider a hotel. It is composed of departments: front desk, housekeeping, kitchen, room
service, maintenance, procurement, accounting. Each department is autonomous -- it has its
own staff, its own processes, its own expertise. The departments communicate with each other
directly: room service calls the kitchen to prepare a meal, maintenance calls procurement to
order spare parts.

The hotel runs 24/7/365. Humans -- the manager, the receptionist, the bell staff -- come and
go. When the manager leaves for the night, the hotel does not stop. It keeps running. When
the manager arrives in the morning, they observe the current state, give direction where
needed, and handle decisions that require their authority (like opening the safe).

This is the model AgentEnsemble v3.0.0 implements:

| Hotel | AgentEnsemble |
|---|---|
| A department (kitchen, maintenance) | An **Ensemble** -- long-running, autonomous |
| Staff within a department | **Agents and Tasks** within the ensemble |
| The intercom / phone system | **WebSocket mesh** -- the message transport |
| A guest request or work order | A **WorkRequest** -- the standard message envelope |
| The hotel directory | **Service registry** -- ensembles discover each other |
| A duty manager | A **human** who connects via the dashboard to observe and intervene |
| The shared guest ledger | **Shared memory** -- cross-ensemble state |
| The hotel chain | A **federation** -- multiple realms sharing capacity |

### The core differentiator

Existing multi-agent frameworks (CrewAI, AutoGen, LangGraph) and protocols (MCP) operate
within a single process boundary. MCP provides tool-level interoperability (call a function,
get a result). AgentEnsemble Network provides **ensemble-level interoperability**: one
ensemble delegates a complex, multi-step task to another ensemble, which runs it with its
own agents, tools, memory, and review gates. The delegating ensemble is the beneficiary of
the output -- it does not need to know or care about the internal process.

This is the difference between "borrow a tool" and "hire a department."

---

## 2. Architecture Overview

```
                    Browser (agentensemble-viz /network route)
                        |
                        | WebSocket (human portal)
                        v
+-------------+    +-------------+    +-------------+    +-------------+
| Ensemble A  |<-->| Ensemble B  |<-->| Ensemble C  |<-->| Ensemble D  |
| (kitchen)   |    | (room-svc)  |    | (maintenance)|   | (procurement)|
+-------------+    +-------------+    +-------------+    +-------------+
       |                  |                  |                  |
       +------ Durable Queue / Topic (Kafka, Redis Streams) ---+
       |                  |                  |                  |
       +---------- Shared Result Store (Redis) ----------------+
       |                  |                  |                  |
       +---------- Shared Memory Scopes (MemoryStore SPI) -----+
```

Each ensemble is deployed as a Kubernetes Service (one or more pods). They discover each
other via K8s DNS. Communication flows over WebSocket for real-time events and over durable
queues for reliable work delivery. Shared state lives in external stores.

### Three types of participants

1. **Ensembles** -- autonomous, always running. Handle their domain. Communicate with peers.
2. **Humans** -- come and go. Observe, direct, query, approve gated decisions.
3. **External systems** -- submit work via HTTP API, queue, or webhook. Consume results.

All three interact through the same WorkRequest envelope and wire protocol.

---

## 3. Ensemble Execution Modes

### One-shot (existing v2.x -- a "gig")

```java
EnsembleOutput output = Ensemble.run(model,
    Task.of("Research AI trends"),
    Task.of("Write a report"));
```

Tasks execute, output is returned, the ensemble is done. Unchanged from v2.x.

### Long-running (new v3.0 -- a "residency")

```java
Ensemble kitchen = Ensemble.builder()
    .name("kitchen")
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))

    // Share capabilities to the network
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified")
        .expectedOutput("Confirmation with preparation details and timing")
        .build())
    .shareTool("check-inventory", inventoryTool)
    .shareTool("dietary-check", allergyCheckTool)

    // Scheduled proactive task
    .scheduledTask(ScheduledTask.builder()
        .name("inventory-report")
        .task(Task.of("Check current inventory levels and report shortages"))
        .schedule(Schedule.every(Duration.ofHours(1)))
        .broadcastTo("hotel.inventory")
        .build())

    .build();

kitchen.start(7329);  // WebSocket server, K8s Service fronts this
```

In long-running mode, the ensemble:
- Registers its shared tasks and tools on the network
- Accepts incoming WorkRequests (via WebSocket, queue, HTTP, or topic subscription)
- Processes work through its priority queue
- Delivers results via the caller-specified delivery method
- Runs scheduled proactive tasks on their configured intervals
- Continues until explicitly stopped or drained

---

## 4. Sharing Primitives

### Share a Task: "Kitchen, make a club sandwich"

An ensemble exposes a named task that other ensembles can trigger. The target ensemble runs
the full task with its own agents, tools, and context. The caller hands off the work and
gets back a result.

```java
// Kitchen shares the "prepare-meal" task
Ensemble kitchen = Ensemble.builder()
    .name("kitchen")
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified")
        .expectedOutput("Confirmation with prep time and details")
        .build())
    .build();
```

### Share a Tool: "Can I borrow your meat thermometer?"

An ensemble exposes a specific tool that other ensembles' agents can call directly in their
ReAct loop. The tool executes in the owning ensemble's context but returns results to the
calling agent.

```java
// Kitchen shares the "check-inventory" tool
Ensemble kitchen = Ensemble.builder()
    .name("kitchen")
    .shareTool("check-inventory", inventoryTool)
    .build();
```

### Consuming shared capabilities

```java
// Room service uses kitchen's shared task and tool
Ensemble roomService = Ensemble.builder()
    .name("room-service")
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest room service request")
        .tools(
            // Delegates the full "prepare-meal" task to kitchen
            NetworkTask.from("kitchen", "prepare-meal"),

            // Uses kitchen's inventory tool directly in the ReAct loop
            NetworkTool.from("kitchen", "check-inventory"),
            NetworkTool.from("kitchen", "dietary-check"),

            // Delegates to maintenance
            NetworkTask.from("maintenance", "repair-request"))
        .build())
    .build();
```

### How it works under the hood

Both `NetworkTask` and `NetworkTool` implement the existing `AgentTool` interface. An agent
does not know whether a tool is local or remote. The existing ReAct loop, tool executor,
metrics, and tracing all work unchanged.

**NetworkTool** (synchronous tool call):
1. Agent calls `check-inventory("wagyu beef")`
2. `NetworkTool` serializes the call into a `WorkRequest`
3. Request is sent to kitchen (WebSocket or queue)
4. Kitchen executes `inventoryTool.execute("wagyu beef")` locally
5. Result flows back: `"Yes, 3 portions available"`
6. Agent continues its ReAct loop

**NetworkTask** (cross-ensemble delegation):
1. Agent calls `prepare-meal("Wagyu steak, medium-rare, room 403")`
2. `NetworkTask` serializes a `WorkRequest` with the full task context
3. Request is sent to kitchen
4. Kitchen runs its complete task pipeline (agent synthesis, execution, review gates)
5. Result flows back: `"Preparing now, estimated 25 minutes, ticket #4071"`
6. Agent continues

---

## 5. The WorkRequest Envelope

Every cross-ensemble message uses a standardized envelope:

```java
public record WorkRequest(
    String requestId,           // Correlation + idempotency key
    String from,                // Requesting ensemble name
    String task,                // Shared task or tool name to execute
    String context,             // Natural language input/context
    Priority priority,          // CRITICAL / HIGH / NORMAL / LOW
    Duration deadline,          // Caller's SLA ("I need this within...")
    DeliverySpec delivery,      // How and where to return the result
    String traceContext,        // W3C traceparent for distributed tracing
    CachePolicy cachePolicy,    // USE_CACHED / FORCE_FRESH
    String cacheKey             // Optional, for result caching
) {}

public record DeliverySpec(
    DeliveryMethod method,      // WEBSOCKET / QUEUE / TOPIC / WEBHOOK / STORE / BROADCAST_CLAIM / NONE
    String address              // Method-specific address
) {}
```

### Delivery methods

| Method | Address | Behavior |
|---|---|---|
| `WEBSOCKET` | `ws://maintenance:7329/ws` | Direct, real-time |
| `QUEUE` | `maintenance.results` | Durable point-to-point (Redis Streams, SQS) |
| `TOPIC` | `maintenance.results` | Durable pub/sub (Kafka); multiple consumers |
| `WEBHOOK` | `https://maintenance.internal/callback` | HTTP POST |
| `STORE` | Key in shared result store | Write to store; requester polls/subscribes |
| `BROADCAST_CLAIM` | Service name | Offer to all replicas; first to claim receives payload |
| `NONE` | -- | Fire and forget |

### Ingress methods

Work can arrive at an ensemble from multiple sources simultaneously:

| Ingress | Description |
|---|---|
| WebSocket | Direct from another ensemble (real-time) |
| Queue | Pull from durable queue (Kafka, SQS, Redis Streams) |
| HTTP API | `POST /api/work` (external systems, scripts, CI pipelines) |
| Topic subscription | React to events from other ensembles |
| Schedule | Internal cron/interval (proactive tasks) |

All ingress sources normalize to the same `WorkRequest` envelope before entering the
ensemble's priority queue.

---

## 6. Task Execution Modes

### Three modes for an ensemble's tasks

| Mode | Trigger | Output destination |
|---|---|---|
| **Shared** (reactive) | External WorkRequest | Response to requester via delivery spec |
| **Scheduled** (proactive) | Cron/interval | Broadcast to topic |
| **Internal** (private) | Part of the ensemble's own workflow | Internal state |

### Three request modes for the caller

| Mode | Behavior | Use case |
|---|---|---|
| **Await** | Block until result (like current delegation) | Critical path: "Can't continue without the parts" |
| **Async** | Submit and continue; result delivered later via callback | Non-critical: "Order towels when you get to it" |
| **Await with deadline** | Wait up to N; then continue with partial/no result | Balanced: "Wait 30 min, then proceed with what I know" |

---

## 7. "Bend, Don't Break" -- Capacity Management

### Principle

LLM tasks are not real-time request/response. They take seconds to hours. Everyone expects
latency. The default response to load is **accept and queue**, not reject:

```
Request arrives
  -> Is this ensemble alive?
     No  -> route to alternative (federation) or queue at network level
     Yes -> Accept into priority queue
            -> ACK with queue position + estimated completion time
            -> Process when capacity is available
            -> Deliver result when done
```

Rejection only happens at hard limits (queue itself is full -- the hotel is physically out
of rooms).

### Caller-side SLA

The limit is set by the requester, not the provider:

```json
{
  "type": "task_request",
  "requestId": "maint-7721",
  "task": "purchase-parts",
  "deadline": "PT30M",
  "priority": "HIGH"
}

{
  "type": "task_accepted",
  "requestId": "maint-7721",
  "queuePosition": 7,
  "estimatedCompletion": "PT45M"
}
```

When ETA exceeds the caller's deadline, the caller decides: accept the longer wait, cancel
and try another provider (federation), or continue without.

### Priority queuing

Requests are processed by priority (CRITICAL > HIGH > NORMAL > LOW). Within the same
priority, FIFO. Low-priority items age over time to prevent starvation (configurable).
Humans on the dashboard can re-prioritize individual requests.

### Operational profiles

```java
NetworkProfile sportingEvent = NetworkProfile.builder()
    .name("sporting-event-weekend")
    .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .ensemble("room-service", Capacity.replicas(3).maxConcurrent(80))
    .ensemble("maintenance", Capacity.replicas(1).maxConcurrent(10))
    .preload("kitchen", "inventory", "Extra beer and ice stocked")
    .build();

network.applyProfile(sportingEvent);
```

Profiles define expected capacity needs for known events. They set K8s HPA targets, activate
dormant ensembles, and pre-load shared memory. Profiles can be applied manually, on a
schedule, or via rules.

---

## 8. Human Participation

### Humans are participants, not controllers

The system runs autonomously. Humans connect when they want, observe, give direction, and
disconnect. The system does not depend on them.

### Interaction spectrum

| Level | Hotel example | Behavior |
|---|---|---|
| **Autonomous** | Housekeeping cleans after checkout | No human needed |
| **Advisory** | Manager says "prioritize VIP" | Human input welcomed but not required |
| **Notifiable** | "Water leak in 305" | Alert a human, proceed with best-effort |
| **Approvable** | Guest requests late checkout | Ask human if available, auto-approve on timeout |
| **Gated** | Opening the safe | Cannot proceed without human authorization |

### Role-based gated reviews

Some processes require specific human authorization:

```java
Task openSafe = Task.builder()
    .description("Open the hotel safe for cash reconciliation")
    .review(Review.builder()
        .prompt("Manager authorization required to open the safe")
        .requiredRole("manager")
        .timeout(Duration.ZERO)         // no timeout -- wait until a human decides
        .build())
    .build();
```

When a gated review fires and no qualified human is connected:
1. The review is queued
2. An optional out-of-band notification is sent (Slack, email, webhook)
3. The task waits
4. When a qualified human connects, they see the pending review immediately
5. They approve (or reject), and the task resumes

### Human directives

Humans can inject guidance into any ensemble they have access to:

```json
{
  "type": "directive",
  "to": "room-service",
  "from": "manager:human",
  "content": "Guest in 801 is VIP, prioritize all their requests"
}
```

Directives are non-blocking. They are injected as additional context for future task
executions.

### Control plane directives

Humans (or automated policies) can send control plane directives to change ensemble behavior
at runtime:

```json
{
  "type": "directive",
  "to": "kitchen",
  "from": "cost-policy:automated",
  "action": "SET_MODEL_TIER",
  "value": "FALLBACK"
}
```

This switches the ensemble to a cheaper LLM model without restarting. The ensemble has
configurable model tiers:

```java
Ensemble.builder()
    .chatLanguageModel(gpt4)            // primary
    .fallbackModel(gpt4Mini)            // cheaper fallback
    .build();
```

### Late-join catches humans up

The existing late-join snapshot mechanism (v2.1.0 `hello` + `snapshotTrace`) extends to the
network level. When a human connects to the dashboard, they receive the current state of all
ensembles they have access to. Live events start streaming immediately.

---

## 9. Discovery and Capability Enumeration

### Kubernetes-native discovery

Each ensemble is a K8s Deployment + Service. They find each other by DNS name:

```
Namespace: hotel-downtown
  +-- Service: kitchen        (ws://kitchen:7329/ws)
  +-- Service: room-service   (ws://room-service:7329/ws)
  +-- Service: maintenance    (ws://maintenance:7329/ws)
  +-- Service: front-desk     (ws://front-desk:7329/ws)
  +-- Service: dashboard      (http://dashboard:7400)
```

K8s provides DNS, health checks, load balancing, and namespace-based compartmentalization.
The framework does not build a custom service registry.

### Capability registration

When an ensemble starts, it publishes its shared capabilities:

```json
{
  "type": "ensemble_register",
  "name": "kitchen",
  "capabilities": {
    "sharedTasks": [
      { "name": "prepare-meal", "description": "Prepare a meal as specified" }
    ],
    "sharedTools": [
      { "name": "check-inventory", "description": "Check ingredient availability" },
      { "name": "dietary-check", "description": "Verify allergen safety" }
    ]
  }
}
```

### Capability-based discovery

Ensembles can discover capabilities by name or by semantic description:

```java
// By name
NetworkTool.from("kitchen", "check-inventory")

// By capability query -- find whoever provides it
NetworkTool.discover("check-inventory")

// Dynamic catalog -- resolve at execution time
Task.builder()
    .tools(NetworkToolCatalog.all())           // all tools on the network
    .tools(NetworkToolCatalog.tagged("food"))   // filtered by tag
    .build();
```

`NetworkToolCatalog.all()` resolves at task execution time, not build time. A new ensemble
comes online and its tools are immediately available to every agent on the network.

### Federation (cross-cluster)

Multiple K8s clusters (or namespaces) form a federation. Each is a **realm** -- a trust and
discovery boundary.

```
Federation: "Hotel Chain"
  +-- Realm: hotel-downtown  (K8s namespace)
  +-- Realm: hotel-airport   (K8s namespace, same or different cluster)
  +-- Realm: hotel-beach     (K8s namespace, different region)
```

Within a federation, ensembles can discover and use capabilities from other realms. This
enables elastic capacity sharing: when Hotel A's kitchen is at capacity during a conference,
it can route overflow to Hotel B's kitchen.

Capacity advertisement:

```json
{
  "type": "capacity_update",
  "ensemble": "kitchen",
  "realm": "hotel-airport",
  "status": "available",
  "currentLoad": 0.2,
  "maxConcurrent": 10,
  "shareable": true
}
```

`shareable: true` means this ensemble's spare capacity is available to other realms.

---

## 10. Distributed Tracing and Telemetry

### W3C Trace Context in the wire protocol (mandatory)

Every cross-ensemble message carries trace context:

```json
{
  "type": "task_request",
  "requestId": "maint-7721",
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    "tracestate": "agentensemble=maintenance"
  }
}
```

This is always present regardless of whether the user has OpenTelemetry configured.

### OpenTelemetry integration (optional module)

`agentensemble-telemetry-opentelemetry` creates OTel spans at key points:

| Span | When |
|---|---|
| `ensemble.run` | Root span for an ensemble execution |
| `task.execute` | Per-task child span |
| `llm.call` | Per-LLM-interaction child span (with token count attributes) |
| `tool.execute` | Per-tool-call child span |
| `network.delegate` | CLIENT span when calling another ensemble |
| `network.handle` | SERVER span when receiving a cross-ensemble request |

Spans carry AgentEnsemble-specific attributes:

```
agentensemble.ensemble.name = "maintenance"
agentensemble.task.description = "Fix boiler in building 2"
agentensemble.agent.role = "Senior Maintenance Engineer"
agentensemble.delegation.target = "procurement"
```

The user deploys their choice of backend: Jaeger, Grafana Tempo, Zipkin, Datadog. The
framework is backend-agnostic.

### ExecutionTrace correlation

The existing `ExecutionTrace` gains:
- `traceId` field linking to the distributed trace
- Cross-ensemble `DelegationTrace` (extends the existing agent-level `DelegationTrace`)
- `parentTraceId` on receiving ensemble's trace

`agentensemble-viz` can show: "This trace was part of a cross-ensemble delegation from
maintenance" with a link to the full distributed trace in the external trace viewer.

### Scaling metrics

Each ensemble exposes Prometheus/Micrometer metrics for K8s HPA:

```
agentensemble_active_tasks{ensemble="front-desk"} 8
agentensemble_queued_requests{ensemble="front-desk"} 12
agentensemble_max_concurrent{ensemble="front-desk"} 10
agentensemble_capacity_utilization{ensemble="front-desk"} 0.95
```

### Token cost tracking

Token counts are attributes on OTel spans and in `ExecutionTrace`. Aggregate cost is
available via Micrometer gauges. Hard budgets are not enforced by the framework in v3.0.0;
cost control is achieved via control plane directives that switch LLM model tiers.

---

## 11. Error Handling and Resilience

### Framework handles semantics, infrastructure handles transport

| Concern | Owner |
|---|---|
| Timeouts (per cross-ensemble call) | Framework |
| Retry policies (transient vs business error distinction) | Framework |
| Circuit breakers (per remote ensemble) | Framework |
| Fallback strategies (alternative provider, degraded response) | Framework |
| TLS, connection pooling, load balancing | Infrastructure (K8s, Istio) |
| Health checks, auto-scaling | Infrastructure (K8s HPA) |

### Timeout configuration

```java
NetworkTask.from("procurement", "purchase-parts")
    .timeout(Duration.ofMinutes(30))
    .connectTimeout(Duration.ofSeconds(10))
```

### Retry policies

```java
NetworkTask.from("procurement", "purchase-parts")
    .retryPolicy(RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Duration.ofSeconds(5), Duration.ofMinutes(1))
        .retryOn(ConnectionFailure.class, TimeoutException.class)
        .noRetryOn(TaskFailureResponse.class)
        .build())
```

Retry transient failures (connection lost, timeout). Do not retry business errors
(procurement says "no vendors available").

### Circuit breakers

```java
NetworkTask.from("procurement", "purchase-parts")
    .circuitBreaker(CircuitBreaker.builder()
        .failureThreshold(5)
        .windowDuration(Duration.ofMinutes(1))
        .halfOpenAfter(Duration.ofMinutes(5))
        .build())
```

### Fallback

```java
NetworkTask.from("procurement", "purchase-parts")
    .onFailure(Fallback.delegateTo("procurement-backup", "purchase-parts"))
```

---

## 12. Versioning and Schema Evolution

### Natural language is the compatibility layer

The contract between ensembles is **natural language**: task descriptions and outputs. The
LLM in each ensemble interprets the response regardless of exact wording. Minor changes in
how an ensemble phrases its output do not break callers.

### No explicit schema versioning

- Shared task/tool contracts are defined by name + natural language description
- If semantics change fundamentally, use a new task name (not a version bump)
- Structured output types (Java records) are optional; when present, use
  `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility

---

## 13. Testing

### Three-tier testing pyramid

```
          /\
         /  \  C. Chaos drills (rare, staging environment)
        /    \     Real ensembles, real humans, controlled failure injection
       /------\
      /        \  B. Simulation (regular, cheap)
     /          \     Full network, simulated LLMs, scenario-driven
    /------------\
   /              \  A. Component tests (frequent, fast)
  /                \     Stubs, contract tests, unit tests per ensemble
 /------------------\
```

### A. Component tests -- test the alarms

Test each ensemble in isolation. Mock cross-ensemble calls:

```java
NetworkTask procurementStub = NetworkTask.stub("procurement", "purchase-parts",
    "Ordered from SupplyCo, PO #4821, delivery Thursday");

Ensemble maintenance = Ensemble.builder()
    .task(Task.builder()
        .description("Fix boiler in building 2")
        .tools(procurementStub)
        .build())
    .build();

EnsembleOutput output = maintenance.run();
```

Contract tests verify both sides independently:

```java
// Maintenance side: verify request format
NetworkTask recorder = NetworkTask.recording("procurement", "purchase-parts");
// ... run ensemble ...
assertThat(recorder.lastRequest()).contains("valve", "building 2");

// Procurement side: verify response format
EnsembleOutput output = procurementEnsemble.run(Map.of(
    "request", "Order replacement valve model X-420, urgent"));
assertThat(output.getRaw()).contains("PO #", "delivery");
```

### B. Simulation -- computer-model the evacuation

```java
Simulation sim = Simulation.builder()
    .network(hotelNetwork)
    .scenario(Scenario.builder()
        .name("Conference peak load")
        .load("front-desk", LoadProfile.ramp(0, 200, Duration.ofMinutes(30)))
        .failure("kitchen", FailureProfile.downAt(Duration.ofMinutes(15),
            Duration.ofMinutes(5)))
        .latency("procurement", LatencyProfile.multiply(3.0))
        .build())
    .chatModel(SimulationChatModel.fast())
    .timeCompression(60)
    .build();

SimulationResult result = sim.run();
result.getBottlenecks();
result.getFailureCascades();
result.getCapacityReport();
result.getTokenEstimate();
```

### C. Chaos engineering -- run the drill with real people

Built into the framework, not bolted on:

```java
ChaosExperiment experiment = ChaosExperiment.builder()
    .name("Kitchen outage during dinner rush")
    .against(hotelNetwork)
    .at(Duration.ofMinutes(5), Fault.kill("kitchen"))
    .at(Duration.ofMinutes(10), Fault.restore("kitchen"))
    .at(Duration.ofMinutes(3), Fault.latency("procurement", Duration.ofSeconds(30)))
    .expect(Assertion.circuitBreakerOpens("room-service", "kitchen",
        within(Duration.ofSeconds(30))))
    .expect(Assertion.fallbackActivated("room-service",
        within(Duration.ofMinutes(1))))
    .expect(Assertion.noDataLoss())
    .build();

ChaosReport report = experiment.run();
```

### Framework-provided test utilities

- `NetworkTask.stub(name, taskName, response)` -- canned response
- `NetworkTask.recording(name, taskName)` -- records requests for assertion
- `NetworkTool.stub(name, toolName, result)` -- same for tools
- `SimulationChatModel.fast()` -- generates realistic-shaped responses without real LLM calls
- `ChaosExperiment` builder -- fault injection with assertions

---

## 14. Audit Trail

### Leveled auditing (like CaptureMode)

```java
public enum AuditLevel {
    OFF,        // No audit trail (dev/test)
    MINIMAL,    // Cross-ensemble requests and responses only
    STANDARD,   // + human decisions, review gates, priority changes
    FULL        // + LLM prompts/responses, tool I/O, memory reads/writes
}
```

Configurable per ensemble or at the network level:

```java
EnsembleNetwork.builder()
    .auditLevel(AuditLevel.STANDARD)
    .ensemble("accounting", Ensemble.builder()
        .auditLevel(AuditLevel.FULL)
        .build())
    .build();
```

### Dynamic rules

Audit level can escalate based on conditions:

```java
AuditPolicy policy = AuditPolicy.builder()
    .defaultLevel(AuditLevel.MINIMAL)
    .rule(AuditRule.when("capacity_utilization > 0.8")
        .escalateTo(AuditLevel.STANDARD).on("kitchen"))
    .rule(AuditRule.when("task_failed")
        .escalateTo(AuditLevel.FULL).on("*")
        .duration(Duration.ofMinutes(10)))
    .rule(AuditRule.when("human_connected AND role == 'manager'")
        .escalateTo(AuditLevel.STANDARD).on("*"))
    .rule(AuditRule.schedule("18:00-22:00")
        .escalateTo(AuditLevel.STANDARD).on("kitchen", "room-service"))
    .build();
```

Trigger types: metric-driven, event-driven, time-based, human-triggered. Escalations are
temporary and revert when the condition clears or the duration expires.

### Audit sink SPI

```java
.auditSink(AuditSink.log())            // SLF4J structured logging
.auditSink(AuditSink.database(ds))     // JDBC -- immutable append-only
.auditSink(AuditSink.eventStream())    // Kafka for downstream consumers
```

All audit records are immutable, append-only, timestamped, and correlatable via trace ID.

---

## 15. Ordering, Idempotency, and Caching

### Idempotency (mandatory)

Every cross-ensemble request carries a caller-generated `requestId`. If the receiver sees
the same `requestId` twice, it returns the cached result instead of re-executing. The
idempotency cache has a configurable TTL.

### Result caching (optional)

Callers can opt into result caching with a `cacheKey` and `maxAge`:

```json
{
  "requestId": "rs-8801",
  "task": "check-inventory",
  "cacheKey": "kitchen:inventory:2026-03-06",
  "cachePolicy": "USE_CACHED",
  "maxAge": "PT1H"
}
```

The cache is backed by a pluggable shared store (SPI):

```java
.resultCache(ResultCache.inMemory())      // dev/test
.resultCache(ResultCache.redis(client))   // production
```

### Ordering

Within a single WebSocket connection, TCP guarantees ordering. Across reconnections or
federation, ordering is not guaranteed. For most use cases, each cross-ensemble request is
independent. When ordering matters, the caller uses the async mode's `onComplete` callback
to chain dependent requests.

---

## 16. Shared State Consistency

### Per-scope configurable consistency model

```java
network.sharedMemory("guest-preferences", SharedMemory.builder()
    .store(MemoryStore.embeddings(embeddingModel, store))
    .consistency(Consistency.EVENTUAL)
    .build());

network.sharedMemory("room-assignments", SharedMemory.builder()
    .store(MemoryStore.redis(client))
    .consistency(Consistency.LOCKED)
    .lockProvider(LockProvider.redis(client))
    .build());

network.sharedMemory("inventory-count", SharedMemory.builder()
    .store(MemoryStore.redis(client))
    .consistency(Consistency.OPTIMISTIC)
    .build());
```

| Model | Behavior | Use case |
|---|---|---|
| `EVENTUAL` | Last-write-wins, no coordination | Context, preferences, notes |
| `LOCKED` | Distributed lock before write | Room assignments, exclusive access |
| `OPTIMISTIC` | Compare-and-swap, retry on conflict | Counters, inventory |
| `EXTERNAL` | Framework does not manage; user's tools handle it | Database-backed state |

The `LockProvider` is an SPI: Redis, ZooKeeper, database advisory locks.

---

## 17. Lifecycle and Graceful Shutdown

### Ensemble lifecycle states

```
STARTING -> READY -> DRAINING -> STOPPED
```

| State | Behavior | K8s readiness probe |
|---|---|---|
| `STARTING` | Connecting to network, registering capabilities | `false` |
| `READY` | Accepting and processing work | `true` |
| `DRAINING` | Stop pulling new work; finish in-flight; deliver results | `false` |
| `STOPPED` | All in-flight complete; de-registered; connections closed | N/A (pod exits) |

### Drain behavior

When draining (SIGTERM, human directive, or profile switch):
1. K8s readiness probe flips to `false` (Service stops routing new connections)
2. Ensemble stops pulling new work from the durable queue
3. In-flight tasks continue until completion or drain timeout
4. Scheduled tasks stop running
5. Results for completed in-flight work are delivered
6. Unprocessed work remains in the durable queue for other replicas

```java
Ensemble.builder()
    .drainTimeout(Duration.ofMinutes(5))
    .build();
```

K8s `terminationGracePeriodSeconds` should match `drainTimeout`.

### K8s integration endpoints

The existing `WebSocketServer` gains health and lifecycle endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /api/health/live` | Liveness probe: is the process alive? |
| `GET /api/health/ready` | Readiness probe: is the ensemble accepting work? |
| `POST /api/lifecycle/drain` | Trigger DRAINING state (used by K8s `preStop` hook) |
| `GET /api/status` | Status endpoint (existing, extended with lifecycle state) |

---

## 18. Durable Transport

### Two transport modes (SPI-backed)

**Simple mode** (dev, single JVM):
- In-process queues (`ConcurrentLinkedQueue`)
- Direct WebSocket for request/response
- No external infrastructure
- Default for local development

**Durable mode** (production, K8s):
- External request queue: Redis Streams, SQS, Kafka (pluggable)
- External result store: Redis (pluggable)
- WebSocket used for streaming events and human interaction
- Survives pod restarts, supports horizontal scaling

```java
EnsembleNetwork.builder()
    .transport(Transport.websocket())                    // simple mode (default)
    .transport(Transport.durable(                        // production mode
        RequestQueue.redis(redisClient),
        ResultStore.redis(redisClient)))
    .build();
```

In durable mode, the request and response paths are decoupled:
- **Request path**: WorkRequest goes to a durable queue, any pod picks it up
- **Response path**: Result is written to the shared result store keyed by `requestId`,
  or delivered via the caller-specified delivery method

This means the pod that processes the request may be different from the pod that received
it, and the pod that delivers the result may be different from the pod that processed it.
The `requestId` is the correlation key that holds it together.

---

## 19. Module Structure

| Module | Purpose | Required |
|---|---|---|
| `agentensemble-core` | Task, Ensemble, Agent, workflow engine (unchanged) | Yes |
| `agentensemble-network` | **New**: EnsembleNetwork, NetworkTask, NetworkTool, WorkRequest, capability sharing, discovery, priority queue, lifecycle | Optional |
| `agentensemble-web` | Extended: human portal, role-based scoping, directives, multi-ensemble dashboard | Optional |
| `agentensemble-telemetry-opentelemetry` | **New**: OTel span integration, W3C trace context propagation | Optional |
| `agentensemble-chaos` | **New**: ChaosExperiment, fault injection, simulation | Optional (test scope) |
| `agentensemble-transport-redis` | **New**: Redis-backed queue, result store, lock provider, result cache | Optional |
| `agentensemble-transport-kafka` | **New**: Kafka-backed queue and topic delivery | Optional |
| `agentensemble-memory` | Existing: MemoryStore SPI (extended with consistency models) | Optional |
| `agentensemble-review` | Existing: ReviewHandler SPI (extended with requiredRole) | Optional |
| `agentensemble-viz` | Extended: /network route, multi-ensemble dashboard, simulation view | Optional |
| `agentensemble-metrics-micrometer` | Existing: Micrometer metrics (extended with network metrics) | Optional |
| `agentensemble-devtools` | Existing: DagExporter (extended with network topology) | Optional |

---

## 20. Wire Protocol Extensions

Building on the v2.1.0 protocol (Section 4 of design doc 16), the following message types
are added for network communication:

### Ensemble -> Network (registration)

| Message | Purpose |
|---|---|
| `ensemble_register` | Register capabilities (shared tasks/tools) on the network |
| `ensemble_deregister` | De-register when shutting down |
| `capacity_update` | Periodic capacity advertisement (load, queue depth, shareable) |

### Ensemble -> Ensemble (work)

| Message | Purpose |
|---|---|
| `task_request` | WorkRequest: request execution of a shared task |
| `task_accepted` | ACK with queue position and ETA |
| `task_progress` | Optional streaming progress update during execution |
| `task_response` | Work result (completed, failed, or rejected) |
| `tool_request` | Request execution of a shared tool |
| `tool_response` | Tool result |

### Human -> Ensemble (interaction)

| Message | Purpose |
|---|---|
| `directive` | Non-blocking guidance or control plane command |
| `query` | Request information from an ensemble |
| `query_response` | Response to a query |
| `review_decision` | Existing: human approves/edits/exits a review gate |

### Ensemble -> Human (notification)

| Message | Purpose |
|---|---|
| `notification` | Alert sent to a qualified human |
| `review_requested` | Existing: review gate waiting for human decision |

### Network -> All (coordination)

| Message | Purpose |
|---|---|
| `profile_applied` | Operational profile change notification |
| `capability_query` | Discovery: who provides this capability? |
| `capability_response` | Discovery: list of providers |

---

## 21. Security Considerations

### Authentication and authorization

The framework defines an SPI for authentication and authorization but does not implement
specific mechanisms in v3.0.0. The expected production deployment uses K8s infrastructure:

- **mTLS** via service mesh (Istio, Linkerd) for ensemble-to-ensemble authentication
- **K8s Network Policies** for namespace-level access control
- **RBAC** for human roles (integrated with the organization's identity provider)

The `requiredRole` field on review gates is enforced by the framework. The mapping from
authenticated user to role is provided by the user's auth integration.

### Localhost binding (existing)

For local development, the existing localhost-only binding from v2.1.0 `WebDashboard` is
retained. Non-localhost binding logs a warning.

### Secret management

Different ensembles may use different LLM API keys. The framework does not manage secrets;
K8s Secrets and environment variables are the expected mechanism. The wire protocol must
never include API keys or credentials.

---

## 22. Deployment Model

### Kubernetes manifests (example)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kitchen
  namespace: hotel-downtown
spec:
  replicas: 2
  template:
    spec:
      terminationGracePeriodSeconds: 300
      containers:
      - name: kitchen
        image: hotel/kitchen-ensemble:latest
        ports:
        - containerPort: 7329
        env:
        - name: ENSEMBLE_NAME
          value: "kitchen"
        - name: REDIS_URL
          value: "redis://redis:6379"
        livenessProbe:
          httpGet:
            path: /api/health/live
            port: 7329
        readinessProbe:
          httpGet:
            path: /api/health/ready
            port: 7329
        lifecycle:
          preStop:
            httpGet:
              path: /api/lifecycle/drain
              port: 7329
---
apiVersion: v1
kind: Service
metadata:
  name: kitchen
  namespace: hotel-downtown
spec:
  selector:
    app: kitchen
  ports:
  - port: 7329
    targetPort: 7329
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: kitchen-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: kitchen
  minReplicas: 1
  maxReplicas: 10
  metrics:
  - type: Pods
    pods:
      metric:
        name: agentensemble_queued_requests
      target:
        type: AverageValue
        averageValue: 5
```

---

## 23. Phased Delivery Plan

### Phase 1: Foundation (v3.0.0-alpha)

- Long-running ensemble mode (`Ensemble.start()`)
- `shareTask()` / `shareTool()` on Ensemble builder
- `NetworkTask` / `NetworkTool` implementations (WebSocket transport)
- WorkRequest envelope and wire protocol extensions
- Capability handshake on WebSocket connect
- Simple mode transport (in-process, direct WebSocket)
- K8s health and lifecycle endpoints

### Phase 2: Durable Transport and Delivery (v3.0.0-beta)

- Durable queue integration (Redis Streams, Kafka)
- Pluggable delivery methods (Queue, Topic, Webhook, Store, BroadcastClaim)
- Pluggable ingress methods (Queue, HTTP API, Topic subscription)
- Result caching with shared store
- Idempotency with TTL-based cache
- Priority queuing with aging
- Scheduled/proactive tasks

### Phase 3: Human Participation and Observability (v3.0.0-rc)

- Multi-ensemble dashboard (`/network` route in viz)
- Human directives (non-blocking guidance)
- Role-based gated reviews (`requiredRole`)
- OpenTelemetry integration module
- Leveled audit trail with dynamic rules
- Control plane directives (model tier switching, profile application)

### Phase 4: Advanced (v3.0.0)

- Shared memory with configurable consistency (EVENTUAL/LOCKED/OPTIMISTIC)
- Federation (cross-namespace, cross-cluster capability sharing)
- Operational profiles with scheduled switching
- Capability-based discovery and `NetworkToolCatalog`
- Simulation mode (`SimulationChatModel`, time compression, scenario builder)
- Chaos engineering (`ChaosExperiment`, fault injection, assertions)

---

## 24. Design Decisions

### Why not a central conductor/orchestrator?

The initial design explored a top-down conductor that orchestrates a DAG of ensembles.
This was rejected in favor of the peer-to-peer mesh model because:
- Real systems are decentralized (room service talks to maintenance directly)
- A central conductor is a single point of failure
- The mesh model scales naturally with K8s Service discovery
- Humans are participants, not controllers

### Why WebSocket as the real-time transport?

Bidirectional communication over a single connection. The existing v2.1.0 `agentensemble-web`
module already uses WebSocket for streaming events and review decisions. Extending it to
ensemble-to-ensemble communication is natural.

### Why a separate durable transport layer?

WebSocket connections are ephemeral. Pod restarts, network blips, and horizontal scaling all
break connections. For reliable work delivery, the durable queue (Redis Streams, Kafka) is
the backbone. WebSocket is used for real-time events and human interaction -- not for
critical work delivery.

### Why natural language contracts instead of typed schemas?

LLM-based agents interpret natural language. The output of a cross-ensemble task is natural
language (optionally with structured output). Schema versioning is unnecessary because the
LLM is the compatibility layer. This dramatically simplifies inter-ensemble contracts.

### Why "bend, don't break"?

LLM tasks are inherently async. A task taking 5 minutes is normal. Rejecting requests under
load is worse than queuing them. The caller-side SLA (deadline) puts the routing intelligence
where it belongs -- with the requester, who knows their own constraints.

### Why built-in chaos engineering?

External chaos tools (Chaos Monkey, Litmus) operate at the infrastructure level. They cannot
inject application-level faults (drop specific message types, simulate LLM timeout, degrade
specific capabilities). The framework owns the network layer and can inject precise,
semantic faults.
