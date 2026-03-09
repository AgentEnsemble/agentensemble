# v3.0.0 Ensemble Network: Design Discussion Notes

This document captures the full design conversation that produced the v3.0.0 Ensemble
Network architecture. It preserves the reasoning, decisions, alternatives considered, and
the user's specific inputs that shaped the design.

Date: March 6, 2026
Branch: `docs/v3-ensemble-network-design`

---

## 1. Origin: "How do I orchestrate multiple ensembles?"

The starting question was about v3.0.0 multi-ensemble orchestration. The initial framing
was "conductor" -- continuing the musical analogy (ensemble = musicians, conductor =
coordinator).

The user's clarification: ensembles run in different clusters/jobs/tasks. The WebSocket
layer acts as a control plane. The goal is orchestrating across multiple ensembles to share
data and state.

## 2. The Hotel Analogy

The user introduced the hotel analogy: a hotel is made up of departments (accommodation,
food, cleaning, maintenance, front-of-house, accounting). Each department could be an
ensemble. It is decentralized -- room service talks directly to maintenance without going
through a "manager."

**Key user input**: "The hotel is open 24/7/365, but humans come and go."

This shifted the design from a top-down conductor model to a **peer-to-peer mesh** where
ensembles are autonomous services.

## 3. Rejection of Central Conductor

The initial proposal was a centralized conductor that orchestrates a DAG of ensembles. The
user rejected this in favor of decentralized peer-to-peer communication because:
- Real systems are decentralized (room service talks to maintenance directly)
- The hotel runs when the manager goes home
- Humans should not be required for normal operation

## 4. Sharing Tasks and Tools

**User insight**: "An ensemble could share a task or share a tool to a different ensemble."

This led to the two sharing primitives:
- **Shared Task** (NetworkTask): delegate a complete multi-step process
- **Shared Tool** (NetworkTool): let another ensemble's agent use a specific capability

**User insight**: "The maintenance department delegates to the procurement department the
purchasing of spare parts -- but the maintenance department is the beneficiary of the output."

This clarified that cross-ensemble delegation is fundamentally different from tool calls.
It is the difference between "borrow a tool" and "hire a department."

## 5. Human Participation Model

**User insight**: "We still need to expose it to humans. The hotel still runs when the
manager goes home and doesn't interact."

**User insight on gated reviews**: "A manager is required in addition to regular staff to
open the safe where money is stored."

This led to the interaction spectrum:
- Autonomous -> Advisory -> Notifiable -> Approvable -> Gated
- `requiredRole` field on review gates
- No-timeout mode for gated reviews (wait indefinitely for qualified human)

## 6. Discovery and Federation

**User insight**: "Can we just discover ensembles across regions that are in our realm that
expose tooling we need?"

This led to capability-based discovery and the `NetworkToolCatalog` concept.

**User insight**: "Within the chain of hotels, we're able to dynamically gather resources
from other hotels in times of high-load, or share resources out during downtime."

This led to the federation model with capacity advertisement and elastic overflow.

## 7. Kubernetes as Substrate

**User insight**: "Let's assume we deploy multiple ensembles in a single Kubernetes cluster
-- that gives us the compartmentalization and DNS."

This grounded the design in K8s, eliminating the need for a custom service registry. The
framework provides health endpoints and metrics; K8s handles everything else.

---

## 8. Ten Design Gap Topics

The user and I worked through 10 architectural topics before writing any documents.

### Topic 1: Distributed Tracing

**Decision**: OpenTelemetry SDK for instrumentation + W3C trace context in the wire
protocol (mandatory). Backend-agnostic (Jaeger, Tempo, Zipkin, Datadog). The user initially
asked "why not Jaeger?" -- clarified that OTel is the SDK, Jaeger is the backend. They
work together. Jaeger's own client libraries are deprecated in favor of OTel.

### Topic 2: Error Handling and Resilience

**Decision**: Framework handles semantics (timeouts, retry policies with transient vs
business error distinction, circuit breakers, fallbacks). Infrastructure handles transport
(TLS, load balancing, health checks, auto-scaling).

**User addition**: "Out of capacity" scenario -- if the check-in desk is busy, we should
be able to scale deployments. Decision: framework exposes metrics (queue depth, active
tasks, capacity utilization); K8s HPA handles scaling; ensembles stay stateless.

### Topic 3: Versioning and Schema Evolution

**User insight**: "The important thing isn't the underlying process, it's the output of the
task. Maintenance doesn't care what procurement does, as long as the thing gets ordered."

**Decision**: No explicit schema versioning. Natural language is the compatibility layer.
The LLM interprets the output regardless of exact wording. New semantics = new task name.
Structured output optional with forward-compatible deserialization.

### Topic 4: Testing

**User insight**: "You can't spin up an entire hotel, but you can mock the functionality
out and check the contracts."

**User insight on simulation**: "What would happen if?" tooling -- simulation mode for
capacity planning and resilience analysis.

**User insight on chaos engineering**: "Can't set fire to the hotel, but you can test the
alarms, sprinklers, and evacuation plans. In aircraft design, evacuation is modeled by
computers but also tested by people."

**Decision**: Three-tier testing: component (stubs/contracts), simulation (SimulationChatModel,
time compression, scenarios), chaos engineering (built-in ChaosExperiment with fault
injection and assertions). Chaos is built into the framework, not bolted on.

### Topic 5: Backpressure / Capacity

**User insight**: "Instead of rejecting a request because we're overloaded, if we have a
queue of requests we can prioritize them, and the others sit in a backlog."

**User insight**: "Define some limit -- maybe that limit is set by the requester: unless
you can service my request in X minutes, I'm going elsewhere."

**Decision**: "Bend, don't break" principle. Accept and queue by default, reject only at
hard limits. Caller-side SLA (deadline) vs provider ETA. Caller decides to wait/reroute/
cancel. Operational profiles for anticipated load changes.

### Topic 6: Audit Trail

**User insight**: "Auditing should be definable depending on needs, akin to log level."

**User insight**: "Allow rules for dynamic logging -- when load is high, I want to log more
LLM stuff. In steady state, I don't need that."

**Decision**: Leveled (OFF/MINIMAL/STANDARD/FULL) with dynamic rules for escalation based
on metrics, events, schedules, and human presence. Temporary escalations revert
automatically. Pluggable sink SPI.

### Topic 7: Ordering and Idempotency

**User insight**: "The client should be able to specify if they want the cached result or
not. Should the cache be local or shared (Redis)?"

**Decision**: Idempotency keys mandatory in the protocol. Separate result caching (opt-in,
caller-controlled key + TTL, pluggable shared store).

**User insight on durable queues**: "If the queue is inside a single pod, we might lose it
on restart. This seems like we need an external durable queue."

This led to the durable transport architecture and the "asymmetric routing" concept: the
pod that processes a request may differ from the one that received it.

**User insight on the WorkRequest envelope**: Work items should contain: work request,
deadline, priority, delivery method, delivery address. "Call me back at this number when
you have a hotel room available."

**User insight on delivery methods**: Kafka/message queue as a return method. Also:
"Broadcast a message to the service asking who wants the data and giving it to them
directly" -- the BROADCAST_CLAIM delivery method.

**User insight on proactive tasks**: "We run a task every minute despite no queued work,
and broadcast who needs a room -- like a ticket seller on the street." This led to
scheduled/proactive tasks with broadcast delivery.

### Topic 8: Lifecycle / Graceful Shutdown

**User insight**: "Should work be submitted by a separate mechanism? If we don't expect
request-response in real-time, does it matter if we submit via queue or HTTP?"

**Decision**: STARTING/READY/DRAINING/STOPPED lifecycle states. K8s health/readiness/
preStop integration. Pluggable ingress (WebSocket, queue, HTTP API, topic subscription,
schedule). Durable queue means pod death does not lose work.

### Topic 9: Shared State Consistency

**User insight**: "We need multiple options and allow the actual user to choose. Another
option here is locking."

**Decision**: Per-scope configurable consistency: EVENTUAL (last-write-wins for advisory
context), LOCKED (distributed lock for exclusive access), OPTIMISTIC (compare-and-swap for
counters), EXTERNAL (framework does not manage; tools connect to external systems).
LockProvider SPI (Redis, ZooKeeper, in-memory).

### Topic 10: Cost / Token Budget

**User insight**: "Token costs are part of tracing. Budgets are nice to have but not
required. The interesting thought is: switch to a cheaper model if we exceed X. Or better:
a signal from a control plane allowing the user to send a message to fallback mode."

**Decision**: Token costs tracked in OTel spans and Micrometer metrics. No hard budgets in
v3.0.0. Control plane directives for model tier switching (human-triggered or rule-based).
Ties into operational profiles.

---

## 9. Deliverables Produced

| Document | Location | Description |
|---|---|---|
| Design document | `docs/design/18-ensemble-network.md` | Full engineering spec (24 sections) |
| Issue breakdown | `docs/design/18-ensemble-network-issues.md` | 30 issues across 4 phases |
| White paper | `docs/whitepaper/ensemble-network-architecture.md` | Academic-style paper |
| Blog post | `docs/blog/ensemble-network.md` | Accessible introduction |
| Book | `docs/book/` (19 files) | 15 chapters + 3 appendices |
| Book PDF | `docs/book/ensemble-network-book.pdf` | Generated via pandoc |
| Design notes | This file | Conversation record |

All on branch `docs/v3-ensemble-network-design`, not pushed.
