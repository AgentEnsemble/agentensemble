# Chapter 2: Ensembles as Services

## Scripts Versus Services

Every program falls into one of two categories. A **script** starts, does work, produces
output, and exits. A **service** starts, runs indefinitely, handles work as it arrives, and
continues until explicitly stopped.

A shell script that processes a CSV file is a script. A web server is a service. A cron
job that generates a daily report is a script. A database is a service. The distinction is
not about complexity -- some scripts are enormously complex, and some services are simple.
The distinction is about **lifecycle**: does the program own its own timeline, or does the
work arriving from the outside world own it?

Every multi-agent AI framework built to date produces scripts. You define agents and tasks,
call `run()`, wait for the output, and the agents cease to exist. The framework manages
the lifecycle: it creates agents, executes tasks, and tears everything down.

```java
// This is a script. It runs and exits.
EnsembleOutput output = Ensemble.run(model,
    Task.of("Research AI trends"),
    Task.of("Write a summary report"));
// Agents are gone. Ensemble is gone. Output is all that remains.
```

AgentEnsemble v2.x works this way. So does every other framework. It is the natural first
step: get multi-agent orchestration working, then worry about deployment models.

But a hotel is not a script. The kitchen does not run once, prepare all meals that will ever
be ordered, and shut down. The kitchen starts, waits for orders, prepares meals as they
arrive, and runs until someone decides to close the restaurant. The kitchen is a service.

## The Long-Running Ensemble

The Ensemble Network introduces a second execution mode. In addition to the existing
one-shot `run()`, an ensemble can now `start()`:

```java
Ensemble kitchen = Ensemble.builder()
    .name("kitchen")
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified")
        .expectedOutput("Confirmation with preparation details and timing")
        .build())
    .shareTool("check-inventory", inventoryTool)
    .build();

kitchen.start(7329);
```

When `start()` is called, the ensemble:

1. Starts a WebSocket server on the specified port
2. Transitions to the READY lifecycle state
3. Registers its shared capabilities (tasks and tools) so other ensembles can discover them
4. Begins accepting incoming work requests
5. Processes work through a priority queue
6. Runs indefinitely until explicitly stopped

The one-shot `run()` method is unchanged. Existing code continues to work exactly as before.
The long-running mode is additive -- a new capability, not a replacement.

## What an Ensemble Shares

A long-running ensemble declares what it offers to the network. There are two sharing
primitives.

### Shared Tasks

A shared task is a named, complete process that other ensembles can trigger. The target
ensemble runs the full task with its own agents, tools, memory, and review gates. The caller
hands off the work and gets back a result.

```java
.shareTask("prepare-meal", Task.builder()
    .description("Prepare a meal as specified by the order")
    .expectedOutput("Confirmation with preparation details, timing, and ticket number")
    .tools(ovenTool, grillerTool, platePresentationTool)
    .build())
```

When another ensemble triggers "prepare-meal," the kitchen ensemble synthesizes an agent
for the task (or uses an explicitly configured agent), runs the full execution pipeline --
including the ReAct loop, tool calls, structured output parsing, and any configured review
gates -- and returns the output.

This is fundamentally different from calling a function. A function call is atomic: call,
compute, return. A shared task is a delegation: "handle this for me, using whatever process
you use, and tell me the result." The caller does not know or care about the internal
process. It is like asking the kitchen to make a sandwich versus operating a sandwich-making
machine yourself.

### Shared Tools

A shared tool is a specific capability that other ensembles' agents can use directly in
their reasoning loop. The tool executes on the owning ensemble's side but returns results
to the calling agent.

```java
.shareTool("check-inventory", inventoryTool)
.shareTool("dietary-check", allergyCheckTool)
```

Shared tools are lighter weight than shared tasks. The calling agent maintains control of
its own reasoning process but borrows a specific capability from another ensemble. It is
the difference between hiring a department (shared task) and borrowing a tool (shared tool).

An agent in the room service ensemble might use `check-inventory` mid-reasoning to verify
that wagyu beef is in stock before placing a kitchen order. The agent decides what to do
with the result. It stays in its own ReAct loop.

## Consuming Shared Capabilities

Other ensembles consume shared capabilities using `NetworkTask` and `NetworkTool`, both of
which implement the existing `AgentTool` interface. This means agents do not know whether
a tool is local or remote. The abstraction is seamless.

```java
Ensemble roomService = Ensemble.builder()
    .name("room-service")
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest room service request")
        .tools(
            NetworkTask.from("kitchen", "prepare-meal"),
            NetworkTool.from("kitchen", "check-inventory"),
            NetworkTool.from("kitchen", "dietary-check"),
            NetworkTask.from("maintenance", "repair-request"))
        .build())
    .build();

roomService.start(7329);
```

When the room service agent's ReAct loop decides to call `prepare-meal`, the agent does
not know that it is sending a work request over a WebSocket connection to a kitchen pod in
a different Kubernetes deployment. It calls a tool, gets a result, and continues reasoning.
The framework handles serialization, transport, waiting, timeout, and deserialization.

## Three Task Modes

A long-running ensemble has three categories of tasks.

### Shared (Reactive)

Shared tasks are triggered by external work requests. They execute when another ensemble
(or a human, or an external system) asks for them.

"Kitchen, prepare a club sandwich" is reactive. Nobody asked the kitchen to prepare a
sandwich proactively -- it was triggered by a guest order relayed through room service.

### Scheduled (Proactive)

Scheduled tasks run on a timer, producing output that is broadcast to anyone listening.

```java
.scheduledTask(ScheduledTask.builder()
    .name("inventory-report")
    .task(Task.of("Check current inventory levels and report any shortages"))
    .schedule(Schedule.every(Duration.ofHours(1)))
    .broadcastTo("hotel.inventory")
    .build())
```

This is the ticket seller on the street. Nobody asked for the inventory report. The kitchen
produces it every hour and broadcasts it to the `hotel.inventory` topic. Procurement
subscribes to that topic and pre-orders supplies when levels are low. The front desk
subscribes to know whether the restaurant can offer the full menu.

Scheduled tasks introduce a **push** model alongside the existing **pull** model. Ensembles
do not just respond to requests -- they proactively produce and distribute information.

### Internal (Private)

Internal tasks are part of the ensemble's own operational workflow. They are not shared with
the network and are not visible to other ensembles. The kitchen's internal task for "plan
tomorrow's specials based on inventory" is private. It runs inside the kitchen ensemble and
its output stays inside the kitchen ensemble.

## The Network

When multiple ensembles start on a network, they form a mesh. Each ensemble is a node. The
connections between them are the shared task/tool relationships.

```
+-------------+    prepare-meal    +-------------+
| room-svc    |------------------>| kitchen      |
|             |<--check-inventory--|             |
+-------------+                    +-------------+
      |                                   |
      | repair-request                    | order-supplies
      v                                   v
+-------------+                    +-------------+
| maintenance |------------------>| procurement  |
|             |   purchase-parts   |             |
+-------------+                    +-------------+
```

This is not a centrally orchestrated pipeline. There is no "hotel main" program that
defines the execution order. Each ensemble runs independently. The connections emerge
from the shared capabilities each ensemble declares and the tools each ensemble consumes.

Room service calls kitchen when it needs to. Kitchen calls procurement when it needs to.
Maintenance calls procurement when it needs to. These are lateral, peer-to-peer
communications. No central coordinator.

## What Does Not Change

The one-shot execution model (`Ensemble.run()`) is entirely unchanged. Existing code,
existing tests, existing examples all work exactly as before. The v3.0.0 changes are
purely additive.

The core abstractions -- `Task`, `Agent`, `AgentTool`, `EnsembleOutput`, `ReviewHandler`,
`MemoryStore`, `ExecutionTrace` -- are unchanged. `NetworkTask` and `NetworkTool` implement
`AgentTool`. The existing ReAct loop, agent synthesis, structured output parsing, tool
execution, metrics, and tracing all work the same way.

The v2.x ensemble is a perfectly good building block. v3.0 wraps it in a service layer that
adds network communication, lifecycle management, and shared capabilities. The inner
execution engine is the same.

## From Script to Service: The Mental Model Shift

The hardest part of adopting this architecture is not the code. It is the mental model.

In the script model, you think in terms of workflows: "First, research. Then, write. Then,
edit." You define the pipeline, run it, get the output. You are the orchestrator.

In the service model, you think in terms of capabilities: "The kitchen can prepare meals.
Room service can handle guest orders. Maintenance can fix things." You define what each
department does, start them all, and let work flow through the network as it arrives. You
are not the orchestrator -- the work requests are.

This is the same shift that happened in software architecture when web applications moved
from monolithic request handlers to microservices. The monolith processes the entire request.
The microservice processes one domain concern and calls other services for the rest. The
shift is from "I control the flow" to "I declare my capabilities and respond to requests."

For AI agent systems, this shift is overdue.
