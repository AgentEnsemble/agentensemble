# Your AI Agents Are Isolated Islands. Here's How to Build a Hotel.

Every multi-agent AI framework -- CrewAI, AutoGen, LangGraph, our own AgentEnsemble --
works the same way at its core. You define some agents, give them tasks, press go, get
results. The agents exist for the duration of the run and then disappear.

This is fine for bounded problems: "research this topic and write a report." But it does
not model how real work gets done.

Think about a hotel.

## The Hotel Model

A hotel is composed of departments: front desk, housekeeping, kitchen, room service,
maintenance, procurement, accounting. Each department is autonomous. It has its own staff,
its own expertise, its own processes.

These departments talk to each other directly. Room service calls the kitchen to prepare a
meal. Maintenance calls procurement to order spare parts. Nobody asks a central "manager
agent" to route every message.

The hotel runs 24/7/365. The manager comes in at 8am, walks around, checks on things, gives
some direction ("Guest in 801 is a VIP, prioritize their requests"), handles things that
need authority (opening the safe), and goes home at 6pm. The hotel does not stop when the
manager leaves. It keeps running.

This is what AgentEnsemble v3.0.0 builds. Each department is an **ensemble** -- an
autonomous group of AI agents that runs as a long-lived service. The departments communicate
over a network. Humans come and go.

## Two Things That Change Everything

### 1. Ensembles Share Tasks, Not Just Tools

MCP (Model Context Protocol) lets agents call tools hosted by other services. That is
useful, but it is function-level interoperability: "call this function, get a result."

We go higher. An ensemble can share a **full task** -- a complex, multi-step process -- for
other ensembles to delegate to.

When maintenance needs spare parts, it does not call a "create purchase order" function. It
delegates the entire procurement process to the procurement ensemble. Procurement runs its
own agents, calls its own tools, maybe even pauses for human approval on large orders. The
result flows back to maintenance: "Ordered from SupplyCo, PO #4821, delivery Thursday."

Maintenance does not know or care how procurement did it. It got the result. This is the
difference between borrowing a tool and hiring a department.

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
            // Uses kitchen's inventory tool directly
            NetworkTool.from("kitchen", "check-inventory"))
        .build())
    .build();

roomService.start(7329);
```

The agent in room service calls `prepare-meal` the same way it calls any tool. It does not
know that behind that tool call, an entire ensemble is running a multi-step process in
another Kubernetes pod.

### 2. Humans Are Participants, Not Controllers

Today's human-in-the-loop systems treat humans as gatekeepers: the task pauses, waits for
approval, times out if nobody is there. The system depends on a human being present.

In the hotel model, humans are **participants**. They connect when they want, observe
what is happening, give direction, handle things that require their authority, and
disconnect. The system keeps running without them.

The interaction spectrum:

- **Autonomous**: housekeeping cleans rooms after checkout. No human needed.
- **Advisory**: manager says "prioritize VIP." Non-blocking guidance.
- **Gated**: opening the hotel safe requires the manager's authorization. The task waits
  until a qualified human connects and approves.

The key distinction: the system never *requires* a human for normal operation. But when
something genuinely needs human authority -- a large purchase order, a security decision,
a compliance gate -- the system waits. It queues the review, optionally sends a Slack
notification, and when the right person connects to the dashboard, they see the pending
approval immediately.

## The Network

Each ensemble is a Kubernetes service. They find each other by DNS name. Communication
flows over WebSocket for real-time events and over durable queues (Kafka, Redis Streams)
for reliable work delivery.

```
Namespace: hotel-downtown
  +-- Service: kitchen
  +-- Service: room-service
  +-- Service: maintenance
  +-- Service: front-desk
  +-- Service: dashboard
```

Every cross-ensemble request is a **WorkRequest** -- a standardized envelope with the
request, priority, deadline, and delivery instructions:

```
"Order replacement valve for building 2 boiler.
 Priority: HIGH.
 Deadline: 30 minutes.
 Deliver result to: kafka://maintenance.results"
```

The provider always accepts the work (bend, don't break -- LLM tasks are async, everyone
expects latency). It returns an estimated completion time. The caller decides: wait, try
another provider, or continue without.

## What This Enables

**Elastic scaling**: Conference weekend? Apply the "high-load" profile. Kitchen scales to
3 replicas, front desk to 4. Off-peak? Scale back down. K8s HPA watches queue depth.

**Federation**: Hotel A is at capacity. Hotel B across town has idle kitchen capacity.
Overflow requests route to Hotel B automatically.

**Simulation**: Before the conference, simulate the expected load. "What happens if kitchen
goes down during peak dinner service?" Run a simulation with fake LLMs, time-compressed.
Get a capacity report.

**Chaos engineering**: Run a fire drill. Inject a kitchen failure at T+5 minutes. Assert
that room service's circuit breaker opens within 30 seconds and the fallback activates
within 1 minute. Built into the framework, not bolted on.

**Distributed tracing**: Maintenance delegates to procurement, which delegates to logistics.
Every step carries W3C trace context. Open Jaeger and see the full chain.

## The Core Insight

The fundamental shift is: **ensembles are services, not scripts.**

A script runs and exits. A service runs continuously, handles work, communicates with peers,
and survives restarts. Every multi-agent framework today builds scripts. We are building
services.

This is possible because of one key observation: the contract between services is natural
language. When maintenance asks procurement to order parts, the contract is not a typed JSON
schema that breaks when a field name changes. The contract is: "Order these parts. Tell me
the PO number and delivery date." The LLM on each side interprets it. Minor changes in
wording do not break callers. Schema versioning, the bane of microservice architectures,
does not apply.

## What is Next

The design document for the Ensemble Network is published alongside this post. It covers
the full architecture: sharing primitives, the WorkRequest envelope, durable transport,
human participation, distributed tracing, capacity management, testing, simulation, chaos
engineering, and the phased delivery plan.

The implementation starts with the foundation: long-running ensembles, shared tasks and
tools over WebSocket, and the WorkRequest protocol. Durable transport (Kafka, Redis),
the multi-ensemble dashboard, and advanced features (simulation, chaos, federation) follow
in subsequent phases.

If you are building AI systems that need to be always-on, multi-domain, and
human-augmented -- not just a script that runs and exits -- this is the architecture.

---

*AgentEnsemble is an open-source Java framework for multi-agent AI orchestration.
The Ensemble Network is planned for v3.0.0.*

*[GitHub](https://github.com/AgentEnsemble/agentensemble) |
[Design Document](../design/24-ensemble-network.md) |
[White Paper](../whitepaper/ensemble-network-architecture.md)*
