# Ensemble Network: A Distributed Architecture for Autonomous Multi-Agent Systems

**Abstract** -- Current multi-agent AI frameworks treat agent orchestration as a
single-process concern: agents, tasks, and tools are defined, executed, and destroyed
within one program boundary. This works for bounded problems but fails to model the
continuous, decentralized, human-augmented systems that real-world applications demand.
We present the Ensemble Network, an architecture where autonomous groups of AI agents
(ensembles) run as long-lived services, share complex tasks and tools across service
boundaries, and allow human participants to observe, direct, and gate critical decisions
without being required for the system to operate. The architecture is infrastructure-native,
designed for Kubernetes deployment with durable message queues, distributed tracing, and
elastic scaling. We describe the core abstractions -- cross-ensemble delegation, the
WorkRequest envelope, pluggable transport and delivery mechanisms, and the "bend, don't
break" capacity management principle -- and compare them to existing approaches in the
multi-agent systems landscape.

---

## 1. Introduction

The past two years have seen rapid development in multi-agent AI frameworks. Projects such
as CrewAI [1], AutoGen [2], LangGraph [3], and numerous others provide abstractions for
defining agents with roles, goals, and tools, then orchestrating their execution on tasks.
These frameworks have demonstrated that multi-agent collaboration produces higher-quality
outputs than single-agent approaches for complex problems.

However, a fundamental limitation persists: these frameworks treat the agent ensemble as a
**transient, in-process construct**. An ensemble is created, executes its tasks, returns
results, and is garbage-collected. The agents exist only for the duration of the run.

Real-world AI systems do not work this way. Consider the operational structure of a hotel.
It comprises departments -- front desk, housekeeping, kitchen, room service, maintenance,
procurement, accounting -- each with its own staff, expertise, and operational autonomy.
These departments:

- **Run continuously**, handling work as it arrives
- **Communicate laterally**, not through a central coordinator (room service calls the
  kitchen directly)
- **Share capabilities** (maintenance delegates parts ordering to procurement)
- **Operate with human augmentation** (a manager observes, gives direction, and handles
  decisions requiring human authority -- but the hotel does not stop when the manager goes
  home)

No existing multi-agent framework models this. The Model Context Protocol (MCP) [4]
addresses tool-level interoperability -- an agent can call a function exposed by an MCP
server -- but it does not address ensemble-level interoperability. The difference is between
borrowing a tool and hiring a department.

This paper describes the Ensemble Network, an architecture that extends the AgentEnsemble
framework [5] to support distributed, autonomous, human-augmented multi-ensemble systems.

---

## 2. Related Work

### 2.1 Multi-Agent Frameworks

**CrewAI** [1] provides a "crew" abstraction: a group of agents with roles and goals that
execute tasks sequentially or in parallel. Crews are single-process, synchronous, and
ephemeral. There is no mechanism for one crew to delegate work to another crew running in a
different process.

**AutoGen** [2] models multi-agent conversation as a group chat. Agents exchange messages
within a shared conversation context. The framework supports asynchronous execution within
a single process but does not provide cross-process agent communication or service-oriented
deployment.

**LangGraph** [3] represents agent workflows as directed graphs with conditional edges.
Nodes are individual agent steps; edges are transitions. The graph executes within a single
process. LangGraph Cloud provides hosted execution but does not support peer-to-peer
communication between independently deployed graphs.

**LangChain** [6] provides foundational abstractions (chains, agents, tools) but does not
address multi-agent orchestration or cross-service communication.

All of the above operate within a single process boundary. None supports:
- Autonomous, long-running agent services
- Cross-service delegation of complex multi-step tasks
- Human participation as an optional, non-blocking concern
- Infrastructure-native deployment with elastic scaling

### 2.2 Model Context Protocol (MCP)

MCP [4] defines a client-server protocol for tool and resource sharing between AI
applications. An MCP server exposes tools (functions with typed schemas) and resources
(data sources); an MCP client discovers and calls them.

MCP addresses **tool-level interoperability**: one agent can use a tool hosted by another
service. It does not address **task-level interoperability**: one agent delegating a
complex, multi-step workflow to another service. In MCP, the caller invokes a function and
receives an immediate result. In the Ensemble Network, the caller delegates a task that may
involve multiple agents, multiple LLM calls, review gates requiring human approval, and
sub-delegations to other ensembles.

The key differences:

| Aspect | MCP | Ensemble Network |
|---|---|---|
| Granularity | Tool call (function) | Task delegation (multi-step workflow) |
| Execution model | Synchronous function call | Async with priority queuing |
| State | Stateless | Shared memory scopes with configurable consistency |
| Human involvement | Not addressed | Role-gated reviews, directives, observability |
| Deployment | Client-server | Peer-to-peer mesh |
| Failure handling | Caller retries | Circuit breakers, fallback, durable queues |

The Ensemble Network is complementary to MCP. An ensemble could expose its shared tools as
an MCP server, or consume MCP servers as tools. The architectures operate at different
levels of abstraction.

### 2.3 Microservice Orchestration

The Ensemble Network borrows concepts from microservice architecture:

- **Service discovery** (Consul, K8s DNS) maps to ensemble capability registration
- **Circuit breakers** (Hystrix, Resilience4j) map to cross-ensemble resilience
- **Message queues** (Kafka, RabbitMQ) map to durable transport
- **Distributed tracing** (OpenTelemetry, Jaeger) maps to cross-ensemble trace correlation

The novelty is applying these patterns to AI agent ensembles where:
- The "API contract" is natural language, not typed schemas
- Tasks are inherently long-running (seconds to hours)
- Human participation is a first-class concern
- The "business logic" is non-deterministic (LLM-driven)

---

## 3. Architecture

### 3.1 Core Concepts

**Ensemble**: An autonomous group of AI agents and tasks that runs as a long-lived service.
An ensemble has a name, a set of capabilities it shares with the network, and a priority
queue for incoming work.

**Shared Task**: A named task that an ensemble exposes for other ensembles to trigger. The
target ensemble runs the full task with its own agents, tools, memory, and review gates.
This is the primary mechanism for cross-ensemble delegation.

**Shared Tool**: A named tool that an ensemble exposes for other ensembles' agents to call
directly during their reasoning loop. The tool executes in the owning ensemble's context
and returns a result to the calling agent.

**WorkRequest**: A standardized message envelope carrying a work item between ensembles.
Contains the request identifier, task or tool name, natural language context, priority,
caller-defined deadline, delivery specification, trace context, and caching directives.

**EnsembleNetwork**: The shared infrastructure connecting ensembles. Provides transport
(WebSocket for real-time, durable queues for reliability), shared state (memory scopes
with configurable consistency), and the human portal (dashboard for observation and
interaction).

### 3.2 Cross-Ensemble Delegation

Cross-ensemble delegation is the core differentiator. It extends the existing agent-to-agent
delegation pattern (where Agent A delegates a sub-task to Agent B within an ensemble) to the
ensemble level.

When an agent in ensemble A needs work done by ensemble B:

1. The agent calls a `NetworkTask` (which implements the standard tool interface)
2. The framework serializes a `WorkRequest` with the task context
3. The request is delivered to ensemble B (via WebSocket or durable queue)
4. Ensemble B runs its complete task pipeline: agent synthesis, LLM execution, tool calls,
   review gates, and potentially sub-delegations to other ensembles
5. The result flows back to ensemble A via the delivery method specified in the WorkRequest
6. The agent in ensemble A receives the result and continues its reasoning loop

The calling agent does not know or care that the tool it called was a remote ensemble. The
framework abstracts the network boundary behind the same tool interface used for local tools.

This creates a natural delegation hierarchy. A maintenance ensemble delegates parts ordering
to a procurement ensemble, which may delegate shipping logistics to a logistics ensemble.
Each ensemble runs its own complex process. The originator receives only the final output.

### 3.3 The WorkRequest Envelope

Every cross-ensemble message uses a standardized envelope:

```
WorkRequest:
  requestId       -- Correlation and idempotency key
  from            -- Requesting ensemble name
  task            -- Shared task or tool name
  context         -- Natural language input
  priority        -- CRITICAL / HIGH / NORMAL / LOW
  deadline        -- Caller's SLA
  delivery        -- Method + address for returning the result
  traceContext    -- W3C traceparent for distributed tracing
  cachePolicy     -- USE_CACHED / FORCE_FRESH
  cacheKey        -- Optional, for result caching
```

The delivery specification decouples the response path from the request path. A work
request can arrive via WebSocket and the result can be delivered to a Kafka topic, a
webhook, or a shared result store. This supports the "call me back at this number when you
have a hotel room available" pattern: the requester specifies how they want to be contacted,
and the provider handles the work and delivers the result accordingly.

Delivery methods include: direct WebSocket, durable queue (Redis Streams, SQS), pub/sub
topic (Kafka), HTTP webhook, shared result store, broadcast-claim (offer to all replicas
of the requesting service; first to claim receives the payload), and fire-and-forget.

### 3.4 Transport Layers

The architecture separates real-time communication from reliable work delivery:

**WebSocket** is used for real-time events (streaming task progress, human dashboard
updates, review gate notifications) and for simple-mode request/response in development
environments. WebSocket connections are ephemeral and do not survive pod restarts.

**Durable queues** (Redis Streams, Kafka, SQS) are used for reliable work delivery in
production. Work requests are enqueued durably; any pod of the target ensemble can pick up
and process the work. Results are written to a shared result store or delivered via the
specified delivery method. This decoupling means the pod that processes a request may be
different from the pod that received it.

Both transport layers are pluggable via an SPI. A simple in-process transport is provided
for local development and testing (no external infrastructure required).

---

## 4. Capacity Management: "Bend, Don't Break"

A key architectural principle is that LLM tasks are inherently asynchronous. A task taking
five minutes is normal. An ensemble delegating work to another ensemble expects latency.

The default response to load is therefore **accept and queue**, not reject. When a work
request arrives:

1. The ensemble accepts it into its priority queue
2. Returns an acknowledgment with queue position and estimated completion time
3. Processes it when capacity is available
4. Delivers the result via the specified delivery method

Rejection occurs only at hard limits (queue physically full -- the hotel is out of rooms).

The decision to wait, reroute, or cancel belongs to the **caller**, not the provider.
Each work request carries a caller-defined deadline. When the provider's estimated
completion time exceeds the deadline, the caller can:
- Accept the longer wait
- Cancel and try an alternative provider (e.g., another realm in the federation)
- Continue without the result

This inverts the traditional backpressure model. The provider is honest about its capacity;
the caller makes routing decisions based on its own constraints.

**Priority queuing** ensures that critical work is processed first. Within the same priority
level, work is processed in FIFO order. Low-priority items age over time (configurable) to
prevent starvation. Human operators can re-prioritize individual items via the dashboard.

**Operational profiles** allow pre-planned capacity adjustments for anticipated load changes
(e.g., a conference, a seasonal peak). A profile specifies replica counts, concurrent task
limits, and shared memory pre-loading for each ensemble. Profiles can be applied manually,
on a schedule, or via automated rules.

---

## 5. Human Participation

### 5.1 Humans as Optional Participants

The system operates autonomously. Humans are not required for operation -- they are
participants who connect, observe, direct, and disconnect at will. The system continues
running when no humans are connected.

This is modeled on the hotel: the manager comes and goes, but the hotel runs 24/7.

When a human connects to the network dashboard, the existing late-join snapshot mechanism
delivers the current state of all accessible ensembles. Live events stream in real-time.
When the human disconnects, nothing changes for the ensembles.

### 5.2 Interaction Spectrum

Human interaction exists on a spectrum from fully autonomous to fully gated:

| Level | Behavior | Review configuration |
|---|---|---|
| Autonomous | No human needed | No review gate |
| Advisory | Human input welcomed but not required | Directive (non-blocking) |
| Notifiable | Alert a human, proceed with best effort | Notification |
| Approvable | Ask human if available, auto-approve on timeout | Review with timeout |
| Gated | Cannot proceed without human authorization | Review with required role, no timeout |

The gated level is critical for regulated processes. A task can require authorization from
a human with a specific role (e.g., "manager"). If no qualified human is connected, the
task queues the review, optionally sends an out-of-band notification (Slack, email), and
waits indefinitely. When a qualified human connects, they see the pending review and can
approve or reject.

### 5.3 Directives

Humans can inject non-blocking guidance into ensembles. A directive is additional context
that influences future task executions without pausing current work:

```
"Guest in 801 is VIP, prioritize all their requests"
```

Control plane directives modify ensemble behavior at runtime:

```
"Switch to fallback LLM model" (cost management)
"Apply sporting-event-weekend profile" (capacity management)
```

---

## 6. Distributed Tracing and Observability

### 6.1 W3C Trace Context Propagation

Every cross-ensemble message carries W3C Trace Context headers (`traceparent` and
`tracestate`). This is mandatory in the wire protocol regardless of whether the user has
deployed an OpenTelemetry collector.

When a maintenance ensemble delegates parts ordering to procurement, the trace context
propagates. The resulting distributed trace shows the full chain: maintenance task ->
cross-ensemble delegation -> procurement task -> procurement's tool calls -> result returned
to maintenance.

### 6.2 OpenTelemetry Integration

An optional module creates OpenTelemetry spans at framework boundaries: ensemble execution,
task execution, LLM calls, tool calls, cross-ensemble delegation (CLIENT span), and
cross-ensemble request handling (SERVER span). Spans carry domain-specific attributes
(ensemble name, agent role, task description, delegation target).

The user deploys their choice of backend (Jaeger, Grafana Tempo, Zipkin, Datadog). The
framework is backend-agnostic.

### 6.3 Adaptive Audit Trail

Audit logging operates at configurable levels (OFF, MINIMAL, STANDARD, FULL) that can be
set per ensemble or at the network level. Dynamic rules escalate the audit level based on
conditions: metric thresholds (capacity utilization > 80%), events (task failure), time
windows (dinner rush hours), or human presence (manager connected to dashboard).

Escalations are temporary and revert when the triggering condition clears. This balances
the cost of detailed logging against the need for deep visibility during incidents.

---

## 7. Testing and Simulation

### 7.1 Three-Tier Testing Approach

Testing a distributed multi-ensemble system requires approaches at three levels:

**Component tests** verify each ensemble in isolation. The framework provides stub and
recording implementations of `NetworkTask` and `NetworkTool` that replace remote ensembles
with canned responses or request recorders. Contract tests verify that both sides of an
inter-ensemble boundary agree on the expected input/output format.

**Simulation** models the full network with simulated LLMs (fast, cheap, deterministic
responses with configurable characteristics), time compression (run simulated hours in real
minutes), and scenario definitions (load profiles, failure injection, latency injection).
Simulation answers capacity planning questions: "How many kitchen replicas do we need for
a 500-person conference?" and resilience questions: "What cascades when procurement goes
offline for 5 minutes?"

**Chaos engineering** injects controlled faults into a running network. Unlike external
chaos tools that operate at the infrastructure level (pod kill, network partition), the
framework's built-in chaos capabilities operate at the application level: drop specific
message types, simulate LLM timeout on a specific ensemble, degrade a specific shared
capability. This precision enables meaningful assertions: "circuit breaker opens within
30 seconds" or "fallback activates within 1 minute."

### 7.2 Versioning and Schema Evolution

The contract between ensembles is natural language: task descriptions and outputs. The
LLM in each ensemble interprets responses regardless of exact wording. This eliminates
the schema versioning problem that plagues traditional microservice architectures.

When the semantics of a shared task change fundamentally, the convention is to use a new
task name rather than a version number. Structured output types (Java records) are optional
and use forward-compatible deserialization (`ignoreUnknown = true`).

---

## 8. Infrastructure-Native Deployment

The architecture is designed for Kubernetes deployment. Each ensemble is a K8s Deployment
fronted by a K8s Service. K8s provides:

- **Service discovery via DNS** -- ensembles find each other by name
- **Health management** -- liveness and readiness probes on the ensemble's HTTP endpoints
- **Horizontal scaling** -- HPA watches ensemble-specific metrics (queue depth, active tasks,
  capacity utilization)
- **Namespace-based compartmentalization** -- namespaces serve as trust/discovery boundaries
  (realms)
- **Graceful lifecycle** -- SIGTERM triggers the ensemble's drain mode; `preStop` hook flips
  readiness to false; `terminationGracePeriodSeconds` matches the drain timeout

The framework does not build a custom service registry, load balancer, or health check
system. It exposes the right metrics and lifecycle endpoints; K8s handles the rest.

**Federation** extends this to multiple clusters or namespaces. Ensembles in different
realms can discover and use each other's capabilities. This enables elastic capacity sharing:
a hotel with idle kitchen capacity can serve meal preparation requests from a hotel at peak
load.

---

## 9. Shared State and Consistency

Cross-ensemble shared memory is configurable per scope:

- **Eventual consistency** (last-write-wins): suitable for advisory context like guest
  preferences and interaction notes
- **Distributed locking**: suitable for exclusive-access state like room assignments
- **Optimistic concurrency** (compare-and-swap): suitable for counters and inventory
- **External**: the framework does not manage the state; the user's tools and external
  systems provide consistency guarantees

The backing store is pluggable (Redis, database, in-memory for testing). The lock provider
is also pluggable (Redis, ZooKeeper, database advisory locks).

---

## 10. Comparison with Existing Approaches

| Capability | CrewAI | AutoGen | LangGraph | MCP | Ensemble Network |
|---|---|---|---|---|---|
| Multi-agent orchestration | Yes | Yes | Yes | No | Yes |
| Cross-process communication | No | No | Cloud only | Yes (tools) | Yes (tasks + tools) |
| Long-running services | No | No | No | Yes (server) | Yes |
| Human-in-the-loop | Limited | Yes (chat) | Limited | No | Full spectrum |
| Distributed tracing | No | No | No | No | OTel integration |
| Durable work delivery | No | No | No | No | Queue + result store |
| Chaos engineering | No | No | No | No | Built-in |
| Elastic scaling | No | No | No | No | K8s-native |
| Natural language contracts | N/A | N/A | N/A | Typed schemas | Yes |

---

## 11. Future Work

Several areas are identified for investigation beyond the initial release:

- **Semantic capability matching**: using embeddings to match work requests to capabilities
  by meaning rather than exact name
- **Autonomous ensemble spawning**: the network detects demand for an unserved capability
  and automatically deploys a new ensemble to serve it
- **Cross-organization federation**: ensembles owned by different organizations communicating
  through a trust-brokered protocol
- **Formal verification of delegation chains**: static analysis to prove that a network of
  ensembles cannot enter a delegation cycle or deadlock
- **Cost-optimal routing**: when multiple ensembles provide the same capability at different
  costs (different LLM providers/tiers), route to minimize cost while meeting the deadline

---

## 12. Conclusion

The Ensemble Network architecture addresses a gap in the multi-agent AI landscape:
production-grade, distributed, human-augmented orchestration of autonomous agent ensembles.
By treating ensembles as long-lived services that share tasks and tools over a network,
rather than as transient in-process constructs, the architecture enables AI systems that
mirror the operational structure of real organizations.

The key insight is that cross-ensemble delegation -- where one ensemble hands off a complex,
multi-step task to another ensemble and receives only the final output -- is fundamentally
different from tool calls or function invocations. It is the difference between borrowing
a tool and hiring a department.

The architecture is infrastructure-native (Kubernetes), transport-agnostic (WebSocket for
real-time, durable queues for reliability), and human-optional (the system runs
autonomously; humans participate when they choose to). The "bend, don't break" capacity
management principle, caller-side SLA negotiation, and natural language contracts reflect
the reality that LLM-based systems are inherently asynchronous, non-deterministic, and
human-augmented.

---

## References

[1] CrewAI. https://github.com/crewAIInc/crewAI

[2] AutoGen. https://github.com/microsoft/autogen

[3] LangGraph. https://github.com/langchain-ai/langgraph

[4] Model Context Protocol. https://modelcontextprotocol.io

[5] AgentEnsemble. https://github.com/AgentEnsemble/agentensemble

[6] LangChain. https://github.com/langchain-ai/langchain
