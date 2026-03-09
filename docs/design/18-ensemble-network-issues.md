# 18 - Ensemble Network: Issue Breakdown

This document breaks down the Ensemble Network design (doc 18) into implementable issues,
grouped by phase. Each issue has a description, dependencies, and key deliverables.

References: [Design Document](18-ensemble-network.md) |
[White Paper](../whitepaper/ensemble-network-architecture.md)

---

## Phase 1: Foundation (v3.0.0-alpha)

### EN-001: Long-running ensemble mode (`Ensemble.start()`)

**Description**: Add a long-running execution mode to `Ensemble` where the ensemble starts,
registers on a port, and listens for incoming work indefinitely. The existing `Ensemble.run()`
(one-shot) is unchanged.

**Dependencies**: None (foundational)

**Key deliverables**:
- `Ensemble.start(int port)` method that starts the WebSocket server and enters READY state
- `Ensemble.stop()` for graceful shutdown
- `EnsembleLifecycleState` enum: STARTING, READY, DRAINING, STOPPED
- Readiness/liveness lifecycle (reuse existing `WebSocketServer` infrastructure)
- State transitions: STARTING -> READY on server bind; READY -> DRAINING on `stop()` or
  SIGTERM; DRAINING -> STOPPED after drain timeout
- `drainTimeout(Duration)` on builder
- Unit tests for lifecycle state transitions
- Integration test: start, accept connection, stop

---

### EN-002: `shareTask()` and `shareTool()` on Ensemble builder

**Description**: Add builder methods for declaring tasks and tools that this ensemble shares
with the network. Shared capabilities are stored and published during capability handshake.

**Dependencies**: EN-001

**Key deliverables**:
- `Ensemble.builder().shareTask(String name, Task task)` builder method
- `Ensemble.builder().shareTool(String name, AgentTool tool)` builder method
- `SharedCapability` record: name, description, type (TASK or TOOL)
- `Ensemble.getSharedCapabilities()` accessor
- Validation: shared task/tool names must be unique within an ensemble
- Unit tests for builder, validation, accessor

---

### EN-003: Capability handshake protocol

**Description**: When a WebSocket connection is established between two ensembles, the
server sends a `capability_hello` message listing its shared tasks and tools. This extends
the existing `hello` message.

**Dependencies**: EN-002

**Key deliverables**:
- `CapabilityHelloMessage` protocol message (extends or accompanies existing `HelloMessage`)
- Serialization/deserialization via `MessageSerializer`
- Server sends capabilities on new connection
- Client parses and stores remote capabilities
- Unit tests for protocol serialization
- Integration test: two ensembles connect, exchange capabilities

---

### EN-004: `NetworkTask` implementation (WebSocket transport)

**Description**: Implement `NetworkTask` -- an `AgentTool` that delegates a full task
execution to a remote ensemble over WebSocket.

**Dependencies**: EN-003

**Key deliverables**:
- `NetworkTask` class implementing `AgentTool`
- `NetworkTask.from(String ensemble, String taskName)` factory
- Sends `task_request` message over WebSocket
- Blocks on `CompletableFuture` until `task_response` arrives (AWAIT mode)
- Timeout support (connect timeout, execution timeout)
- `task_request` and `task_response` protocol messages
- Unit tests with mock WebSocket
- Integration test: ensemble A calls NetworkTask on ensemble B, gets result

---

### EN-005: `NetworkTool` implementation (WebSocket transport)

**Description**: Implement `NetworkTool` -- an `AgentTool` that executes a shared tool on a
remote ensemble over WebSocket. Lighter weight than `NetworkTask` (single tool call, not
full task pipeline).

**Dependencies**: EN-003

**Key deliverables**:
- `NetworkTool` class implementing `AgentTool`
- `NetworkTool.from(String ensemble, String toolName)` factory
- Sends `tool_request` message over WebSocket
- Blocks on `CompletableFuture` until `tool_response` arrives
- Timeout support
- `tool_request` and `tool_response` protocol messages
- Unit tests with mock WebSocket
- Integration test: agent in ensemble A calls NetworkTool on ensemble B

---

### EN-006: WorkRequest envelope and wire protocol extensions

**Description**: Define and implement the `WorkRequest` record and all new wire protocol
message types for cross-ensemble communication.

**Dependencies**: EN-004, EN-005

**Key deliverables**:
- `WorkRequest` record with all fields (requestId, from, task, context, priority, deadline,
  delivery, traceContext, cachePolicy, cacheKey)
- `Priority` enum (CRITICAL, HIGH, NORMAL, LOW)
- `DeliverySpec` record (method, address)
- `DeliveryMethod` enum (WEBSOCKET, QUEUE, TOPIC, WEBHOOK, STORE, BROADCAST_CLAIM, NONE)
- `CachePolicy` enum (USE_CACHED, FORCE_FRESH)
- Protocol message types: `task_request`, `task_accepted`, `task_progress`, `task_response`,
  `tool_request`, `tool_response`
- Jackson serialization/deserialization for all types
- Unit tests for all message types

---

### EN-007: Incoming work request handler

**Description**: When a long-running ensemble receives a `task_request` or `tool_request`,
it executes the corresponding shared task/tool and sends back the response.

**Dependencies**: EN-001, EN-002, EN-006

**Key deliverables**:
- Request dispatcher: routes incoming `task_request` to the matching shared task
- Request dispatcher: routes incoming `tool_request` to the matching shared tool
- Task execution: synthesize agent, run task pipeline, return output
- Tool execution: invoke tool, return result
- Error handling: return error response for unknown task/tool, execution failure
- Unit tests for dispatch and execution
- Integration test: full round-trip (request -> execute -> response)

---

### EN-008: K8s health and lifecycle endpoints

**Description**: Add HTTP endpoints for K8s liveness, readiness, and drain lifecycle
management.

**Dependencies**: EN-001

**Key deliverables**:
- `GET /api/health/live` -- liveness probe (process alive)
- `GET /api/health/ready` -- readiness probe (READY state only)
- `POST /api/lifecycle/drain` -- trigger DRAINING state
- `GET /api/status` extended with lifecycle state field
- Unit tests for each endpoint in each lifecycle state
- Documentation: K8s deployment manifest example

---

### EN-009: Test utilities -- stubs and recording

**Description**: Provide test doubles for `NetworkTask` and `NetworkTool` so users can test
ensembles in isolation without real network connections.

**Dependencies**: EN-004, EN-005

**Key deliverables**:
- `NetworkTask.stub(String ensemble, String task, String response)` -- canned response
- `NetworkTask.recording(String ensemble, String task)` -- records requests
- `NetworkTool.stub(String ensemble, String tool, String result)` -- canned result
- `NetworkTool.recording(String ensemble, String tool)` -- records calls
- `RecordingNetworkTask.lastRequest()`, `.requests()`, `.callCount()`
- Unit tests for all test utilities

---

## Phase 2: Durable Transport and Delivery (v3.0.0-beta)

### EN-010: Transport SPI and simple mode

**Description**: Define the transport SPI that abstracts the underlying messaging mechanism.
Implement the simple mode (in-process, direct WebSocket) as the default.

**Dependencies**: EN-006

**Key deliverables**:
- `Transport` SPI interface: `send(WorkRequest)`, `receive()`, `deliver(WorkResponse)`
- `SimpleTransport` implementation (in-process queues + WebSocket)
- `Transport.websocket()` factory
- Unit tests for SPI contract

---

### EN-011: Redis transport implementation

**Description**: Implement a Redis-backed transport using Redis Streams for durable request
queues and Redis for result storage.

**Dependencies**: EN-010

**Key deliverables**:
- `agentensemble-transport-redis` Gradle module
- `RedisRequestQueue` implementing request queue SPI
- `RedisResultStore` implementing result store SPI
- `Transport.durable(RequestQueue, ResultStore)` factory
- Consumer group support (multiple replicas reading from the same stream)
- TTL-based cleanup for processed messages
- Integration tests with embedded Redis (Testcontainers)

---

### EN-012: Kafka transport implementation

**Description**: Implement a Kafka-backed transport for durable topic-based delivery.

**Dependencies**: EN-010

**Key deliverables**:
- `agentensemble-transport-kafka` Gradle module
- `KafkaRequestQueue` implementing request queue SPI
- `KafkaTopicDelivery` for topic-based result delivery
- Consumer group support
- Integration tests with embedded Kafka (Testcontainers)

---

### EN-013: Pluggable delivery methods

**Description**: Implement the delivery method abstraction so work responses can be
delivered via the caller-specified method (WebSocket, queue, topic, webhook, store,
broadcast-claim).

**Dependencies**: EN-010, EN-011

**Key deliverables**:
- `DeliveryHandler` SPI: `deliver(DeliverySpec, WorkResponse)`
- Implementations: WebSocket, Queue, Topic, Webhook (HTTP POST), Store, BroadcastClaim
- `DeliveryMethod.NONE` handler (no-op)
- Registration mechanism for custom delivery handlers
- Unit tests for each delivery method
- Integration test: request via WebSocket, result via Kafka topic

---

### EN-014: Pluggable ingress methods

**Description**: Implement multiple ingress sources so work can arrive at an ensemble via
WebSocket, queue, HTTP API, or topic subscription.

**Dependencies**: EN-010, EN-011

**Key deliverables**:
- `Ingress` SPI: normalized `WorkRequest` production
- `WebSocketIngress` (existing, adapted)
- `QueueIngress` (pull from durable queue)
- `HttpIngress` (`POST /api/work` endpoint)
- `TopicIngress` (subscribe to Kafka/Redis topic)
- Multiple ingress sources active simultaneously
- Unit tests for each ingress type
- Integration test: submit work via HTTP, result via queue

---

### EN-015: Idempotency and result caching

**Description**: Implement idempotency (duplicate request detection) and optional result
caching with pluggable shared store.

**Dependencies**: EN-010, EN-011

**Key deliverables**:
- Idempotency check on `requestId` before task execution
- TTL-based idempotency cache (in-memory for simple mode, Redis for durable mode)
- `ResultCache` SPI with `inMemory()` and `redis()` factories
- `cacheKey` + `maxAge` support on WorkRequest
- `CachePolicy.USE_CACHED` / `FORCE_FRESH` handling
- Unit tests for deduplication and cache hit/miss
- Integration test: retry same requestId, verify single execution

---

### EN-016: Priority queue with aging

**Description**: Implement the internal priority queue that orders incoming work by priority
level with configurable aging for low-priority items.

**Dependencies**: EN-006

**Key deliverables**:
- `PriorityWorkQueue` with CRITICAL > HIGH > NORMAL > LOW ordering
- FIFO within same priority level
- Configurable age-based priority promotion (prevent starvation)
- `task_accepted` response with queue position and ETA estimate
- Queue depth metrics (Micrometer)
- Unit tests for ordering, aging, ETA calculation

---

### EN-017: Scheduled / proactive tasks

**Description**: Implement scheduled tasks that run on a cron/interval and broadcast their
results to a topic.

**Dependencies**: EN-010, EN-013

**Key deliverables**:
- `ScheduledTask` builder: name, task, schedule (cron or interval), broadcastTo topic
- `Ensemble.builder().scheduledTask(ScheduledTask)` builder method
- Scheduler integration (ScheduledExecutorService or similar)
- Broadcast result to specified topic via delivery handler
- Scheduled tasks stop during DRAINING state
- Unit tests for scheduling and broadcast
- Integration test: scheduled task fires, result appears on topic

---

### EN-018: Request modes (Await / Async / Deadline)

**Description**: Implement the three caller-side request modes for `NetworkTask` and
`NetworkTool`.

**Dependencies**: EN-004, EN-005, EN-013

**Key deliverables**:
- `RequestMode.AWAIT` -- block until result (existing behavior, formalized)
- `RequestMode.ASYNC` -- submit and return immediately; result delivered via callback
- `RequestMode.AWAIT_WITH_DEADLINE` -- block up to N, then continue
- `.mode(RequestMode)` on NetworkTask/NetworkTool builders
- `.onComplete(Consumer<WorkResponse>)` callback for ASYNC mode
- `.deadline(Duration)` and `.onDeadline(DeadlineAction)` for DEADLINE mode
- Unit tests for each mode
- Integration test: async mode with callback delivery

---

## Phase 3: Human Participation and Observability (v3.0.0-rc)

### EN-019: Multi-ensemble dashboard (viz /network route)

**Description**: Add a `/network` route to agentensemble-viz that shows all ensembles in
the network, their status, capabilities, and queue depth.

**Dependencies**: EN-001, EN-003, Phase 1 complete

**Key deliverables**:
- `/network` route in agentensemble-viz
- Network topology view (ensembles as nodes, connections as edges)
- Per-ensemble status: lifecycle state, queue depth, active tasks, capabilities
- Drill-down: click ensemble to see its internal task timeline/flow
- WebSocket connection to each ensemble (or aggregating portal)
- React components for network view
- Unit tests for network state rendering

---

### EN-020: Human directives

**Description**: Implement non-blocking human directives that inject guidance into an
ensemble's context for future task executions.

**Dependencies**: EN-001, Phase 1 complete

**Key deliverables**:
- `directive` wire protocol message type
- Dashboard UI: text input per ensemble for sending directives
- Ensemble-side: store active directives, inject as context in task execution
- Directive expiration (optional TTL)
- Control plane directives: `SET_MODEL_TIER`, `APPLY_PROFILE`
- Unit tests for directive storage and context injection
- Integration test: send directive via dashboard, verify context injection

---

### EN-021: Role-based gated reviews (`requiredRole`)

**Description**: Extend the existing review system with `requiredRole` so that certain
review gates can only be approved by humans with a specific role.

**Dependencies**: EN-001, existing review system

**Key deliverables**:
- `Review.builder().requiredRole(String role)` builder method
- `requiredRole` field in `review_requested` wire protocol message
- Dashboard: show pending reviews as not actionable if user lacks required role
- No-timeout mode: `timeout(Duration.ZERO)` means wait indefinitely
- Out-of-band notification SPI: `ReviewNotifier` interface
- `ReviewNotifier.slack(webhookUrl)` implementation
- Unit tests for role gating and infinite timeout
- Integration test: gated review queued, human connects with role, approves

---

### EN-022: OpenTelemetry integration module

**Description**: Create `agentensemble-telemetry-opentelemetry` module that creates OTel
spans at framework boundaries.

**Dependencies**: EN-006 (W3C trace context in protocol)

**Key deliverables**:
- `agentensemble-telemetry-opentelemetry` Gradle module
- OTel dependency: `io.opentelemetry:opentelemetry-api`
- Span creation: ensemble.run, task.execute, llm.call, tool.execute, network.delegate,
  network.handle
- W3C trace context extraction from WorkRequest and injection into outgoing requests
- AgentEnsemble-specific span attributes
- `ExecutionTrace.traceId` field for linking to external trace viewer
- Unit tests with in-memory span exporter
- Integration test: cross-ensemble delegation produces correlated spans

---

### EN-023: Leveled audit trail with dynamic rules

**Description**: Implement the audit trail system with configurable levels (OFF/MINIMAL/
STANDARD/FULL) and dynamic rule-based escalation.

**Dependencies**: Phase 1 complete

**Key deliverables**:
- `AuditLevel` enum: OFF, MINIMAL, STANDARD, FULL
- `AuditPolicy` builder with rules (metric, event, schedule, human-triggered)
- `AuditRule` with condition, target level, target ensemble(s), optional duration
- `AuditSink` SPI with `log()`, `database()`, `eventStream()` factories
- Per-ensemble and network-level audit level configuration
- Temporary escalation with automatic revert
- Audit records: immutable, timestamped, trace-ID-correlated
- Unit tests for rule evaluation, escalation, revert
- Integration test: task failure triggers FULL escalation for 10 minutes

---

### EN-024: Control plane directives (model tier switching)

**Description**: Implement control plane directives that modify ensemble behavior at
runtime, starting with LLM model tier switching.

**Dependencies**: EN-020

**Key deliverables**:
- `Ensemble.builder().fallbackModel(ChatModel)` builder method
- `SET_MODEL_TIER` directive handler: switches between primary and fallback models
- Directive applies to new tasks only (in-flight tasks continue with current model)
- `APPLY_PROFILE` directive handler: applies operational profile to the network
- Rule-based automatic directives (e.g., cost threshold triggers fallback)
- Unit tests for model switching
- Integration test: send directive, verify subsequent tasks use fallback model

---

## Phase 4: Advanced (v3.0.0)

### EN-025: Shared memory with configurable consistency

**Description**: Extend `MemoryStore` to support cross-ensemble shared memory with
configurable consistency models (EVENTUAL, LOCKED, OPTIMISTIC, EXTERNAL).

**Dependencies**: Phase 2 complete, EN-011

**Key deliverables**:
- `SharedMemory` builder: store, consistency model, lock provider
- `Consistency` enum: EVENTUAL, LOCKED, OPTIMISTIC, EXTERNAL
- `LockProvider` SPI with Redis and in-memory implementations
- `SharedMemory.builder().consistency(Consistency.LOCKED).lockProvider(...)` API
- Network-level shared memory registration
- Unit tests for each consistency model
- Integration test: two ensembles writing to LOCKED scope

---

### EN-026: Federation (cross-namespace capability sharing)

**Description**: Implement cross-realm discovery and capability sharing for ensembles in
different K8s namespaces or clusters.

**Dependencies**: EN-003, Phase 2 complete

**Key deliverables**:
- Realm concept: namespace as discovery boundary
- `capacity_update` protocol message with `realm` and `shareable` fields
- Cross-realm capability query and routing
- Routing hierarchy: local -> realm -> federation
- Load-based routing (prefer least-loaded provider)
- Unit tests for cross-realm discovery
- Integration test: ensemble in realm A uses capability from realm B

---

### EN-027: Operational profiles

**Description**: Implement operational profiles that allow pre-planned capacity adjustments
for anticipated load changes.

**Dependencies**: Phase 2 complete, EN-024

**Key deliverables**:
- `NetworkProfile` builder: name, per-ensemble capacity settings, pre-load directives
- `Capacity` configuration: replicas, maxConcurrent, dormant flag
- `network.applyProfile(profile)` method
- Scheduled profile switching (cron-based)
- Profile broadcasts `profile_applied` message to all ensembles
- Unit tests for profile construction and application
- Integration test: apply profile, verify capacity changes

---

### EN-028: Capability-based discovery and `NetworkToolCatalog`

**Description**: Implement dynamic capability discovery so ensembles can discover tools and
tasks by name or tag at execution time, not just at build time.

**Dependencies**: EN-003, Phase 2 complete

**Key deliverables**:
- `NetworkTool.discover(String toolName)` -- find any provider
- `NetworkToolCatalog.all()` -- all tools on the network
- `NetworkToolCatalog.tagged(String tag)` -- filtered by tag
- Tag support on `shareTask()` and `shareTool()`
- `capability_query` and `capability_response` protocol messages
- Resolution at task execution time (not build time)
- Unit tests for discovery and catalog
- Integration test: new ensemble comes online, its tools are immediately discoverable

---

### EN-029: Simulation mode

**Description**: Implement simulation tooling for modeling network behavior with simulated
LLMs, time compression, and scenario definitions.

**Dependencies**: Phase 2 complete

**Key deliverables**:
- `Simulation` builder: network, scenario, chatModel, timeCompression
- `Scenario` builder: load profiles, failure profiles, latency profiles per ensemble
- `SimulationChatModel.fast()` -- deterministic, fast, configurable response characteristics
- `LoadProfile`: steady, ramp, spike
- `FailureProfile`: downAt(time, duration)
- `LatencyProfile`: fixed, multiply
- `SimulationResult`: bottlenecks, failure cascades, capacity report, token estimate
- Time compression (1 hour = 1 minute)
- Unit tests for scenario construction
- Integration test: simulate peak load, verify capacity report

---

### EN-030: Chaos engineering (`ChaosExperiment`)

**Description**: Implement built-in chaos engineering for fault injection into running
networks with assertions on expected behavior.

**Dependencies**: Phase 2 complete

**Key deliverables**:
- `agentensemble-chaos` Gradle module (test scope)
- `ChaosExperiment` builder: name, target network, fault schedule, assertions
- `Fault` types: kill(ensemble), restore(ensemble), latency(ensemble, duration),
  dropMessages(ensemble, rate), degradeCapacity(ensemble, factor)
- `Assertion` types: circuitBreakerOpens, fallbackActivated, noDataLoss,
  allPendingRequestsResolve
- `ChaosReport`: passed, failures, timeline
- Fault injection at the transport layer (framework-controlled, not infrastructure)
- Unit tests for fault injection and assertion evaluation
- Integration test: inject kitchen failure, assert circuit breaker behavior

---

## Dependency Graph

```
Phase 1 (Foundation):
  EN-001 (long-running mode)
    +-- EN-002 (shareTask/shareTool)
    |     +-- EN-003 (capability handshake)
    |           +-- EN-004 (NetworkTask)
    |           +-- EN-005 (NetworkTool)
    |           +-- EN-006 (WorkRequest / protocol)
    |                 +-- EN-007 (incoming request handler)
    +-- EN-008 (K8s endpoints)
    +-- EN-009 (test utilities) -- depends on EN-004, EN-005

Phase 2 (Durable Transport):
  EN-010 (Transport SPI)
    +-- EN-011 (Redis transport)
    +-- EN-012 (Kafka transport)
    +-- EN-013 (delivery methods) -- depends on EN-010, EN-011
    +-- EN-014 (ingress methods) -- depends on EN-010, EN-011
  EN-015 (idempotency/caching) -- depends on EN-010, EN-011
  EN-016 (priority queue) -- depends on EN-006
  EN-017 (scheduled tasks) -- depends on EN-010, EN-013
  EN-018 (request modes) -- depends on EN-004, EN-005, EN-013

Phase 3 (Human + Observability):
  EN-019 (multi-ensemble dashboard) -- depends on Phase 1
  EN-020 (human directives) -- depends on Phase 1
  EN-021 (role-based reviews) -- depends on Phase 1
  EN-022 (OTel module) -- depends on EN-006
  EN-023 (audit trail) -- depends on Phase 1
  EN-024 (control plane directives) -- depends on EN-020

Phase 4 (Advanced):
  EN-025 (shared memory consistency) -- depends on Phase 2, EN-011
  EN-026 (federation) -- depends on EN-003, Phase 2
  EN-027 (operational profiles) -- depends on Phase 2, EN-024
  EN-028 (capability discovery) -- depends on EN-003, Phase 2
  EN-029 (simulation) -- depends on Phase 2
  EN-030 (chaos engineering) -- depends on Phase 2
```

---

## Critical Path

The critical path for Phase 1 is:

```
EN-001 -> EN-002 -> EN-003 -> EN-004/EN-005 (parallel) -> EN-006 -> EN-007
```

EN-008 (K8s endpoints) and EN-009 (test utilities) can be developed in parallel with the
main chain once their dependencies are met.

Phase 2's critical path is:

```
EN-010 -> EN-011 -> EN-013/EN-014 (parallel) -> EN-018
```

EN-015 (idempotency), EN-016 (priority queue), and EN-017 (scheduled tasks) can proceed
in parallel.

Phase 3 issues are largely independent and can proceed in parallel once Phase 1 is complete.

Phase 4 issues are largely independent and can proceed in parallel once Phase 2 is complete.
