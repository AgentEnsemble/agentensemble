# 16 - Live Execution Dashboard (v2.1.0)

This document specifies the design for the Live Execution Dashboard: a real-time browser GUI
that streams ensemble execution events as they happen, renders a live timeline and dependency
graph, and allows humans to issue review decisions directly from the browser.

This is the implementation specification for v2.1.0. It extends the existing `agentensemble-viz`
npm package and introduces a new `agentensemble-web` Java module.

---

## 1. Motivation

The current visualization toolchain is post-hoc: the developer runs an ensemble, the
`agentensemble-devtools` module writes a `.trace.json` file, and the `agentensemble-viz` viewer
renders a static timeline from that file after the fact.

The live dashboard changes the model in two ways:

1. **Real-time streaming**: events fired by `EnsembleListener` (task start/complete/failed,
   tool calls, delegation events) are pushed to the browser as they occur. The timeline and
   flow graph update incrementally, giving the developer a live view of execution progress.

2. **Browser-based review approval**: the `WebReviewHandler` implementation (currently a stub
   that throws `UnsupportedOperationException`) is completed. When a review gate fires, the
   browser receives the review request, displays an approval UI, and sends the human's decision
   (`Continue`, `Edit`, `ExitEarly`) back to the JVM. The calling thread blocks until the
   decision arrives or the timeout expires.

Together, these make the browser the primary interactive GUI for an ensemble run -- useful for
long-running agentic pipelines where developers want to watch progress and intervene at key
checkpoints.

---

## 2. Architecture Overview

```
JVM (agentensemble-web)                     Browser (agentensemble-viz)
-----------------------------               ---------------------------------
Ensemble.run()
  -> EnsembleListener events
  -> WebSocketStreamingListener
       -> WebSocketServer (embedded)  <--WebSocket-->  Live mode client
            -> ConnectionManager            <--JSON messages-->  State machine
                                                         -> TimelineView (live)
                                                         -> FlowView (live)
  -> SequentialWorkflowExecutor
       -> review gate fires
       -> WebReviewHandler             --review request-->  Review approval UI
            -> CompletableFuture             <--decision--  Approve / Edit / Exit
```

The JVM hosts an embedded WebSocket server. The browser connects to it. All communication
flows over a single bidirectional WebSocket connection per browser tab.

---

## 3. New Gradle Module: `agentensemble-web`

### 3.1 Module identity

```
module:     agentensemble-web
artifact:   net.agentensemble:agentensemble-web
package:    net.agentensemble.web
scope:      optional (users add as runtimeOnly or testImplementation)
```

Like `agentensemble-devtools` and `agentensemble-review`, this module is optional. Users add it
only when they want the live dashboard. The core module has no compile-time dependency on it.

### 3.2 Dependencies

| Dependency | Why |
|-----------|-----|
| `agentensemble-core` (api) | Access to EnsembleListener, event types, ExecutionTrace |
| `agentensemble-review` (compileOnly) | Access to ReviewHandler, ReviewRequest, ReviewDecision |
| Embedded WebSocket server library | See Section 3.3 |
| Jackson databind | Already on classpath via LangChain4j; used for JSON message serialization |
| Lombok (compileOnly) | Consistent with all other modules |

### 3.3 WebSocket server library

**Choice: Javalin** (`io.javalin:javalin`, lightweight Jetty wrapper).

Rationale:
- Single dependency (pulls in Jetty, which is already commonly on Java classpaths)
- Native WebSocket support with a clean API
- Does not require a Servlet container; starts an embedded server in one call
- Minimal surface area -- only the WebSocket and static-file-serving features are needed
- Alternative considered: Java's built-in `com.sun.net.httpserver` lacks WebSocket support
- Alternative considered: Undertow is lower-level, requiring more boilerplate

### 3.4 Public API surface

```java
// Entry point: users register a dashboard via Ensemble.builder()
WebDashboard dashboard = WebDashboard.builder()
    .port(7329)                // default: 7329 (same port as existing viz CLI)
    .host("localhost")         // default: localhost (local-only binding)
    .reviewTimeout(Duration.ofMinutes(5))   // default: 5 minutes
    .onTimeout(OnTimeoutAction.CONTINUE)    // default: CONTINUE
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Research AI trends")
        .expectedOutput("Research report")
        .review(Review.required())
        .build())
    .webDashboard(dashboard)   // wires both streaming listener and web review handler
    .build()
    .run();
```

`Ensemble.builder().webDashboard(WebDashboard)` is the single registration point. Internally
this wires:
1. A `WebSocketStreamingListener` to the ensemble's listener chain
2. A `WebReviewHandler` as the ensemble's review handler (overrides any previously registered handler)

The server starts automatically when the first event arrives and stops after the ensemble
completes. Users can also start it manually via `dashboard.start()` / `dashboard.stop()`.

---

## 4. Wire Protocol

All messages are UTF-8 JSON. Every message has a `type` field discriminating the message kind.
The server sends events to the client. The client sends review decisions to the server.

### 4.1 Server -> Client messages

#### `hello`
Sent when a client connects. Provides current execution state for late joiners.

```json
{
  "type": "hello",
  "ensembleId": "uuid",
  "startedAt": "2026-03-05T14:00:00Z",
  "snapshotTrace": { /* ExecutionTrace JSON, may be partial */ }
}
```

#### `ensemble_started`
Sent when `Ensemble.run()` begins.

```json
{
  "type": "ensemble_started",
  "ensembleId": "uuid",
  "startedAt": "2026-03-05T14:00:00Z",
  "totalTasks": 4,
  "workflow": "SEQUENTIAL"
}
```

#### `task_started`
Mirrors `TaskStartEvent`.

```json
{
  "type": "task_started",
  "taskIndex": 1,
  "totalTasks": 4,
  "taskDescription": "Research AI trends",
  "agentRole": "Senior Research Analyst",
  "startedAt": "2026-03-05T14:00:01Z"
}
```

#### `task_completed`
Mirrors `TaskCompleteEvent`.

```json
{
  "type": "task_completed",
  "taskIndex": 1,
  "totalTasks": 4,
  "taskDescription": "Research AI trends",
  "agentRole": "Senior Research Analyst",
  "completedAt": "2026-03-05T14:00:45Z",
  "durationMs": 44000,
  "tokenCount": 1842,
  "toolCallCount": 3
}
```

#### `task_failed`
Mirrors `TaskFailedEvent`.

```json
{
  "type": "task_failed",
  "taskIndex": 1,
  "taskDescription": "Research AI trends",
  "agentRole": "Senior Research Analyst",
  "failedAt": "2026-03-05T14:00:45Z",
  "reason": "MaxIterationsExceededException: ..."
}
```

#### `tool_called`
Mirrors `ToolCallEvent`.

```json
{
  "type": "tool_called",
  "agentRole": "Senior Research Analyst",
  "taskIndex": 1,
  "toolName": "web_search",
  "durationMs": 1200,
  "outcome": "SUCCESS"
}
```

#### `delegation_started`
Mirrors `DelegationStartedEvent`.

```json
{
  "type": "delegation_started",
  "delegationId": "uuid",
  "delegatingAgentRole": "Lead Researcher",
  "workerRole": "Content Writer",
  "taskDescription": "Write a blog post about..."
}
```

#### `delegation_completed`
Mirrors `DelegationCompletedEvent`.

```json
{
  "type": "delegation_completed",
  "delegationId": "uuid",
  "delegatingAgentRole": "Lead Researcher",
  "workerRole": "Content Writer",
  "durationMs": 32000
}
```

#### `delegation_failed`
Mirrors `DelegationFailedEvent`.

```json
{
  "type": "delegation_failed",
  "delegationId": "uuid",
  "delegatingAgentRole": "Lead Researcher",
  "workerRole": "Content Writer",
  "reason": "Guard rejected: agent cannot delegate to itself"
}
```

#### `review_requested`
Sent when a review gate fires. The browser must respond with a `review_decision` message before
the `timeoutMs` elapses, or the `onTimeout` action is applied automatically.

```json
{
  "type": "review_requested",
  "reviewId": "uuid",
  "taskDescription": "Research AI trends",
  "taskOutput": "The AI landscape in 2025...",
  "timing": "AFTER_EXECUTION",
  "prompt": null,
  "timeoutMs": 300000,
  "onTimeout": "CONTINUE"
}
```

#### `review_timed_out`
Sent when the review timeout expires (before the `onTimeout` action is applied).

```json
{
  "type": "review_timed_out",
  "reviewId": "uuid",
  "action": "CONTINUE"
}
```

#### `ensemble_completed`
Sent when `Ensemble.run()` returns normally.

```json
{
  "type": "ensemble_completed",
  "ensembleId": "uuid",
  "completedAt": "2026-03-05T14:05:00Z",
  "durationMs": 300000,
  "exitReason": "COMPLETED",
  "totalTokens": 12500,
  "totalToolCalls": 15
}
```

#### `token`
Sent for each token emitted by a `StreamingChatModel` during the final agent response.
Only emitted when streaming is configured (see Section 4.3).

Token messages are **not** added to the late-join snapshot. They are ephemeral: a client
that joins mid-stream receives the task as `running` and accumulates new tokens from that
point. The authoritative final output arrives in `task_completed`.

```json
{
  "type": "token",
  "token": "Hello ",
  "agentRole": "Senior Research Analyst",
  "sentAt": "2026-03-05T14:00:15.123Z"
}
```

#### `heartbeat`
Sent every 15 seconds to keep the connection alive.

```json
{
  "type": "heartbeat",
  "serverTimeMs": 1741212300000
}
```

### 4.3 Streaming Output

Token-by-token streaming of the final agent response is opt-in. When a
`StreamingChatModel` is configured, `AgentExecutor` streams the final LLM call and fires
`EnsembleListener.onToken(TokenEvent)` for each received token. The
`WebSocketStreamingListener` translates each `TokenEvent` into a `TokenMessage` and
broadcasts it over WebSocket.

**Resolution order** (first non-null wins):
1. `Agent.builder().streamingLlm(model)` -- agent-level
2. `Task.builder().streamingChatLanguageModel(model)` -- task-level
3. `Ensemble.builder().streamingChatLanguageModel(model)` -- ensemble-level

**Scope**: streaming only applies to the direct LLM-to-answer path
(`executeWithoutTools`). Tool-loop iterations remain synchronous because the full
response must be inspected to detect tool-call requests.

### 4.2 Client -> Server messages

#### `review_decision`
Sent by the browser in response to a `review_requested` message.

```json
{
  "type": "review_decision",
  "reviewId": "uuid",
  "decision": "CONTINUE"
}
```

```json
{
  "type": "review_decision",
  "reviewId": "uuid",
  "decision": "EDIT",
  "revisedOutput": "The AI landscape in 2025 has been dominated by..."
}
```

```json
{
  "type": "review_decision",
  "reviewId": "uuid",
  "decision": "EXIT_EARLY"
}
```

#### `ping`
Client keepalive.

```json
{ "type": "ping" }
```

Server responds with:

```json
{ "type": "pong" }
```

---

## 5. Server Implementation

### 5.1 `WebSocketServer`

Package-private class. Started once per `WebDashboard` lifecycle. Hosts:
- WebSocket endpoint at `ws://localhost:{port}/ws`
- Static file handler serving the built `agentensemble-viz` assets at `http://localhost:{port}/`
  (the viz npm package build output is embedded in the JAR via Gradle resource copying)
- `/api/status` endpoint returning current server state (JSON)

```java
// Package-private; managed by WebDashboard
class WebSocketServer {
    void start(int port, String host);
    void stop();
    void broadcast(String messageJson);  // to all connected clients
    void send(String sessionId, String messageJson);  // to one client
}
```

### 5.2 `ConnectionManager`

Tracks connected WebSocket sessions. Thread-safe. Handles:
- New connections: sends `hello` with current trace snapshot (for late joiners)
- Disconnections: removes session; if a `review_requested` is pending for this session,
  falls back to `onTimeout` action
- `broadcast(message)`: sends to all sessions

### 5.3 `WebSocketStreamingListener`

Implements `EnsembleListener`. Each callback method serializes the event to a JSON message
and calls `connectionManager.broadcast(json)`.

```java
public class WebSocketStreamingListener implements EnsembleListener {
    @Override public void onTaskStart(TaskStartEvent event)          { broadcast(toJson(event)); }
    @Override public void onTaskComplete(TaskCompleteEvent event)     { broadcast(toJson(event)); }
    @Override public void onTaskFailed(TaskFailedEvent event)         { broadcast(toJson(event)); }
    @Override public void onToolCall(ToolCallEvent event)             { broadcast(toJson(event)); }
    @Override public void onDelegationStarted(DelegationStartedEvent event)   { broadcast(toJson(event)); }
    @Override public void onDelegationCompleted(DelegationCompletedEvent event) { broadcast(toJson(event)); }
    @Override public void onDelegationFailed(DelegationFailedEvent event)     { broadcast(toJson(event)); }
}
```

Thread safety: broadcast is called from multiple virtual threads (parallel workflow). The
underlying `ConnectionManager.broadcast()` uses a thread-safe session set and concurrent
iteration.

### 5.4 `WebReviewHandler`

Implements `ReviewHandler`. Replaces the current stub that throws `UnsupportedOperationException`.

```java
public class WebReviewHandler implements ReviewHandler {
    @Override
    public ReviewDecision review(ReviewRequest request) {
        String reviewId = UUID.randomUUID().toString();
        CompletableFuture<ReviewDecision> pending = new CompletableFuture<>();
        pendingReviews.put(reviewId, pending);

        connectionManager.broadcast(toReviewRequestJson(reviewId, request));

        try {
            return pending.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            connectionManager.broadcast(toReviewTimedOutJson(reviewId, request));
            return applyTimeout(request.onTimeoutAction());
        } finally {
            pendingReviews.remove(reviewId);
        }
    }
}
```

When the browser sends a `review_decision` message, the WebSocket message handler resolves
the corresponding `CompletableFuture` via `pendingReviews.get(reviewId).complete(decision)`.

**Concurrent review gates** (parallel workflow with multiple simultaneous review gates):
each gate generates a distinct `reviewId`. The browser queues them. The JVM has one
`CompletableFuture` per `reviewId`. Each resolves independently.

**Disconnection during pending review**: if the session that received the `review_requested`
disconnects, the `ConnectionManager` resolves the pending `CompletableFuture` with
`applyTimeout(request.onTimeoutAction())` so the JVM thread is not blocked indefinitely.

---

## 6. Viz Live Mode

### 6.1 New entry point

The viewer gains a second entry mode alongside "Load Trace" (historical):

- **Historical mode** (`/trace`): existing file-upload or CLI server file-listing flow
- **Live mode** (`/live?server=ws://localhost:7329/ws`): connects to a running ensemble

The landing page gains a "Connect to live server" input alongside the existing file upload.

### 6.2 WebSocket client

A React context (`LiveServerContext`) manages the WebSocket connection:

```typescript
interface LiveServerState {
  status: 'disconnected' | 'connecting' | 'connected' | 'error';
  serverUrl: string | null;
  trace: Partial<ExecutionTrace>;  // grows as events arrive
  pendingReviews: ReviewRequest[];
}
```

Connection management:
- Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s)
- Status bar in the UI (connected/disconnected/reconnecting)
- On reconnect: server sends `hello` with current snapshot, client rebuilds state from it

### 6.3 Incremental state machine

Events arriving over WebSocket are reduced into a growing `Partial<ExecutionTrace>`:

```typescript
function liveReducer(state: LiveState, message: ServerMessage): LiveState {
  switch (message.type) {
    case 'ensemble_started': return initTrace(state, message);
    case 'task_started':     return addTaskStarted(state, message);
    case 'task_completed':   return updateTaskCompleted(state, message);
    case 'task_failed':      return updateTaskFailed(state, message);
    case 'tool_called':      return appendToolCall(state, message);
    case 'delegation_started':   return addDelegation(state, message);
    case 'delegation_completed': return updateDelegation(state, message);
    case 'review_requested': return addPendingReview(state, message);
    case 'review_timed_out': return removePendingReview(state, message);
    case 'ensemble_completed': return finalizeTrace(state, message);
    default: return state;
  }
}
```

The resulting `Partial<ExecutionTrace>` is compatible with the existing `TimelineView` and
`FlowView` components (they already tolerate missing fields).

### 6.4 Live Timeline View

The existing `TimelineView` component is adapted for live rendering:

- Tasks appear as soon as `task_started` arrives (bar starts, right edge grows in real-time)
- Task bars grow as time passes (requestAnimationFrame to animate the right edge)
- Tool call markers appear when `tool_called` arrives
- Completed tasks lock their bar width from the duration in `task_completed`
- Failed tasks render in red
- A "Follow latest" toggle (default: on) auto-scrolls the time axis to show the most recent
  activity; the user can disable it to inspect earlier tasks

### 6.5 Live Flow View

The existing `FlowView` (ReactFlow DAG) is adapted for live node status:

- Nodes render immediately from the DAG structure (available from `ensemble_started` or
  derived from `task_started` events)
- Node color/status updates as events arrive:
  - Pending: gray
  - Running: blue (pulsing animation)
  - Completed: agent color (same as historical mode)
  - Failed: red

### 6.6 Review Approval UI

When `review_requested` arrives, a modal or slide-in panel appears:

```
+------------------------------------------------------+
| Review Required                                       |
+------------------------------------------------------+
| Task: Research AI trends                              |
|                                                       |
| Output:                                               |
| The AI landscape in 2025 has been dominated by...     |
|                                                       |
| [Approve]  [Edit]  [Exit Early]                       |
|                                                       |
| Auto-continue in 4:58 ...                             |
+------------------------------------------------------+
```

**Edit flow**: clicking Edit replaces the output text with a `<textarea>` pre-filled with the
current output. The user edits and clicks Submit.

**Timeout countdown**: a countdown bar and timer (`4:58`) counts down from `timeoutMs`. When
it reaches zero, the `onTimeout` action is shown ("Auto-continue" or "Auto-exit").

**Multiple concurrent reviews** (parallel workflow): reviews are queued. Only one is shown at
a time. A badge shows the queue depth (`+2 pending`).

The browser sends a `review_decision` message when the user clicks Approve / Submit / Exit
Early, or when the timeout expires (client-side timeout fires a CONTINUE or EXIT_EARLY decision).

---

## 7. Security Considerations

### 7.1 Localhost-only binding

By default, the server binds to `localhost` (127.0.0.1 / ::1). This means only processes
on the same machine can connect. This is the correct default for a developer tool.

Users who want remote access must explicitly set `host("0.0.0.0")` in `WebDashboard.builder()`.
When a non-localhost host is configured, the server logs a warning at startup.

### 7.2 No authentication by default

For the localhost-only default, no authentication is required. The risk surface is limited
to processes running on the same machine as the developer.

For remote access (`host("0.0.0.0")`), authentication is deferred to the user's network
layer (VPN, reverse proxy with auth, etc.). The `WebDashboard` builder does not expose an
auth API in v2.1.0; this is noted as a future extension point.

### 7.3 Origin validation

The WebSocket server validates the `Origin` header to prevent cross-site WebSocket hijacking.
When `host` is `localhost`, only `localhost` origins are accepted.

---

## 8. Dependency Graph (Issues)

```
G1 (agentensemble-web module + WebSocket server + protocol)
 |
 +-- G2 (WebSocketStreamingListener)
 |     |
 |     +-- I1 (Viz: live mode + WebSocket client + incremental state)
 |           |
 |           +-- I2 (Viz: live timeline + flow view updates)
 |
 +-- H1 (WebReviewHandler -- real implementation)
       |
       +-- H2 (Viz: review approval UI)
```

G1 is the critical path. Once G1 is merged, the G2/H1 tracks can proceed in parallel.
I1 requires G2 to be in place. H2 requires H1 and I1 (needs the WebSocket client and
the review message types).

---

## 9. Issue Breakdown

### Group G: WebSocket Infrastructure

**G1: `agentensemble-web` module -- WebSocket server + protocol** (foundational)
- New Gradle module with Javalin-based embedded WebSocket server
- JSON message protocol (all message types from Section 4)
- `ConnectionManager`: session tracking, broadcast, late-join snapshot
- `WebDashboard` public API: builder, `start()`, `stop()`, `Ensemble.builder().webDashboard()`
- Auto-start on first event, auto-stop on `ensemble_completed` or JVM shutdown hook
- Heartbeat every 15 seconds
- Localhost-only binding by default; origin validation
- Tests: server lifecycle, connection management, protocol serialization, late-join sync,
  heartbeat, multi-client broadcast

**G2: `WebSocketStreamingListener` -- bridge callbacks to WebSocket** (depends on G1)
- Implements `EnsembleListener`; serializes all 7 event types to protocol messages
- Sends `ensemble_started` when wired into `WebDashboard` lifecycle
- Sends `ensemble_completed` when `Ensemble.run()` returns
- Thread-safe: called from multiple virtual threads in parallel workflows
- Tests: each event type serializes to correct message type; multi-client broadcast;
  parallel workflow concurrent events; exception isolation (listener error does not stop execution)

### Group H: Web-Based Review

**H1: `WebReviewHandler` -- real implementation** (depends on G1)
- Replaces the stub `UnsupportedOperationException` in `agentensemble-review`
- Sends `review_requested` over WebSocket; blocks on `CompletableFuture` with timeout
- Deserializes `review_decision` message from browser to `ReviewDecision`
- Handles timeout: broadcasts `review_timed_out`, applies `onTimeout` action
- Handles disconnection during pending review: resolves future with timeout action
- Handles concurrent review gates: per-reviewId `CompletableFuture` map
- Tests: continue/edit/exit-early approval flow; timeout with CONTINUE; timeout with EXIT_EARLY;
  timeout with FAIL; disconnect during pending review; concurrent reviews

**H2: Viz review approval UI** (depends on H1, I1)
- Review modal/panel triggered by `review_requested` message
- Approve, Edit (textarea + submit), Exit Early controls
- Timeout countdown bar + timer display
- `onTimeout` label (`Auto-continue in X:XX` or `Auto-exit in X:XX`)
- Review queue: badge showing pending count; dequeue when decision sent
- Sends `review_decision` message over WebSocket
- Tests: render with all three decisions; edit flow (textarea pre-filled, submit sends edit);
  timeout countdown reaches zero; queue badge; multiple reviews

### Group I: Live Visualization

**I1: Viz -- live mode + WebSocket client + incremental state** (depends on G2)
- `LiveServerContext` React context: connection management, reconnect with backoff
- `/live` route with `?server=ws://...` query param
- Landing page: "Connect to live server" input alongside existing file upload
- `liveReducer` incremental state machine (all message types)
- `hello` snapshot import: rebuilds state from server's late-join trace snapshot
- Connection status bar (connected / disconnected / reconnecting)
- Tests: reducer for each message type; reconnect logic; late-join snapshot import;
  connection status transitions

**I2: Viz -- live timeline + flow view updates** (depends on I1)
- `TimelineView`: incremental rendering from live state (tasks appear on `task_started`,
  bars grow in real-time via `requestAnimationFrame`, lock on `task_completed`)
- `FlowView`: node status updates (pending/running/completed/failed colors; pulsing animation
  for running nodes)
- "Follow latest" toggle for timeline auto-scroll
- Tool call markers appear on `tool_called`
- Tests: task bar appears on task_started; bar locks on task_completed; failed task renders red;
  tool marker appears; follow-latest scrolls to newest task

### Group J: Documentation + Examples

All documentation updates from Section 10, plus:
- New runnable example: `LiveDashboardExample.java` + `runLiveDashboard` Gradle task
- Integration tests: end-to-end WebSocket test (embedded server + browser-sim client)

---

## 10. Documentation Updates

### New

| File | Description |
|------|-------------|
| `docs/guides/live-dashboard.md` | Full guide: installation, quick start, `WebDashboard` config, live mode, review approval |
| `docs/examples/live-dashboard.md` | Annotated walkthrough of the live dashboard example |
| `docs/design/16-live-dashboard.md` | This document |

### Updated

| File | What changes |
|------|-------------|
| `docs/guides/visualization.md` | New "Live Mode (v2.1.0)" section; updated installation table to include `agentensemble-web` |
| `docs/guides/review.md` | `WebReviewHandler` section: remove "not yet implemented", add usage example |
| `docs/examples/human-in-the-loop.md` | Add "Browser-Based Approval (v2.1.0)" section |
| `docs/getting-started/installation.md` | `agentensemble-web` as optional dependency |
| `docs/reference/ensemble-configuration.md` | `webDashboard` builder field |
| `docs/design/13-future-roadmap.md` | Phase 11 (v2.1.0) section |
| `mkdocs.yml` | Nav entries for new guide, example, design doc |
| `README.md` | Live dashboard mention in roadmap and features |

---

## 11. API Design Decisions

### Why `webDashboard()` as a single builder method?

Wiring streaming and review separately would be error-prone. A developer could add
`WebSocketStreamingListener` but forget to set `reviewHandler(new WebReviewHandler(...))`.
The single `webDashboard(WebDashboard)` call wires both atomically at build time.

### Why does the server host the viz static files?

This eliminates the need for the user to start a separate npm process. The `agentensemble-web`
JAR embeds the built `agentensemble-viz` dist at compile time (Gradle copies the npm build
output into the JAR resources). Opening `http://localhost:7329` in a browser shows the live
dashboard immediately -- zero additional setup.

### Why Javalin instead of a lighter WebSocket library?

Javalin is the lightest option that handles both WebSocket and static-file serving in a
single dependency. A lower-level library (e.g., TooTallNate/Java-WebSocket) would require
a separate HTTP server for the static files.

### Why `CompletableFuture` for review blocking?

The `ReviewHandler.review()` contract is synchronous (blocking). The calling thread is a
Java 21 virtual thread, so blocking is cheap. `CompletableFuture.get(timeout, unit)` provides
clean timeout semantics with a single code path for both the normal case (decision arrives)
and the timeout case.

### Why not Server-Sent Events (SSE) instead of WebSocket?

Review approval requires bidirectional communication: the server sends the review request, the
browser sends the decision back. SSE is server-to-client only. WebSocket handles both directions
in a single connection, which simplifies the client and avoids the need for a separate HTTP
POST endpoint for decisions.

---

## 12. Migration from `WebReviewHandler` Stub

Before v2.1.0, `ReviewHandler.web(URI)` throws `UnsupportedOperationException`. In v2.1.0,
the factory is updated:

```java
// Before (throws UnsupportedOperationException):
ReviewHandler.web(URI.create("http://localhost:7329"))

// After (v2.1.0 -- fully functional):
// Prefer the WebDashboard API:
Ensemble.builder().webDashboard(WebDashboard.onPort(7329))

// Or, if wiring separately:
WebDashboard dashboard = WebDashboard.builder().port(7329).build();
Ensemble.builder()
    .reviewHandler(dashboard.reviewHandler())
    .listener(dashboard.streamingListener())
    ...
```

The `ReviewHandler.web(URI)` factory is retained for backward compatibility but its behavior
changes: it now constructs a `WebReviewHandler` connected to the given server URI rather than
throwing. The `WebDashboard` API is the recommended approach.
