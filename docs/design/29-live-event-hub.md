# Design Doc: Distributed Live Event Hub

**Status:** Implemented (3.x)
**Modules:** `agentensemble-web`, `agentensemble-web-hub`, `agentensemble-viz`
**Related:** Design doc 16 (Live Dashboard), Design doc 28 (Ensemble Control API)

## 1. Motivation

The embedded [`WebDashboard`](16-live-dashboard.md) handles a single process well. As soon as a deployment runs multiple AgentEnsemble processes, the model breaks down:

- Two processes cannot bind the same port.
- Distinct ports give the browser N isolated UIs.
- Late-join snapshots are scoped to one dashboard instance.
- Producers cannot scale horizontally without losing observability.

The `/network` route in `agentensemble-viz` already does browser-side multiplexing for the "I have N independent embedded dashboards" case. That's useful but weak for late-join, auth, and history: each producer is a separate WebSocket and a fresh page load reconstructs from N independent `hello` messages.

We need a server-side aggregation hub.

## 2. Goals

- Many producer processes publish AgentEnsemble live events without owning a browser-facing port.
- A central `LiveEventHub` accepts events from many publishers and serves the existing visualization wire protocol to a single browser connection.
- Events groupable/filterable by `producerId`, `serviceName`, `instanceId`, `host`, `runId`, `ensembleId`, `workflow`, task/agent, and free-form `tags`.
- Producers can join/disconnect/restart/scale horizontally without UI churn.
- Late-joining browsers receive a coherent snapshot across all active and recent producers.
- Existing embedded `WebDashboard.onPort()` behavior is preserved byte-for-byte.
- Review gates work transparently across the hub (browser submits decisions to the hub; hub routes them back to the originating publisher).

## 3. Non-goals (phase 1)

- Durable event log / persisted history beyond in-memory retention.
- Browser auth, ingress auth, mTLS, signed envelopes.
- Cross-producer correlation (delegation edges that span producers, distributed traces).
- Redis / Kafka publisher modules. Mirror the existing `agentensemble-transport-kafka` layout when they land.

## 4. Architecture

```
┌────────────────┐    LiveEventEnvelope     ┌─────────────────┐
│ ensemble #1    │ ───────────────────────► │                 │ ◄── ws://hub/ws ─── browser
│ (publisher)    │ ◄────── review_decision ─│  LiveEventHub   │
└────────────────┘                          │                 │
┌────────────────┐                          │  ProducerRegistry│
│ ensemble #2    │ ───────────────────────► │  per-producer    │
│ (publisher)    │ ◄──── (reverse channel) ─│   ConnectionMgr  │
└────────────────┘                          └─────────────────┘
```

Three roles:

- **Embedded dashboard** (existing): owns its own port, broadcasts to browsers directly.
- **Publisher** (new): `WebDashboard.builder().publisher(...)`. No port. Streams to a hub.
- **Hub** (new): `LiveEventHub.builder().port(...)`. No ensemble. Aggregates publishers; serves browsers.

## 5. Key trade-offs

### Envelope wrapping

The hub broadcasts a `LiveEventEnvelope { producer, sequence, receivedAt, message }` rather than annotating the existing per-message types with optional `producerId` fields.

**Why:**

- The embedded `/live` wire format stays untouched (zero regression risk for existing tests and browsers).
- The hub treats the inner `message` as opaque `JsonNode`, so future `ServerMessage` subtypes ride along without hub changes.
- Producer attribution is uniform across all events.

**Cost:**

- Browser-side hub mode needs a thin top-level reducer (`hubReducer`) that strips envelopes and dispatches inner messages through the existing single-producer reducer.

### Per-producer `ConnectionManager` for snapshot state

The hub reuses `ConnectionManager` as a per-producer snapshot store: each `ProducerState` holds a `ConnectionManager` with zero registered sessions. We exploit only its `appendToSnapshot` + `noteEnsembleStarted` + iteration ring buffer behavior.

**Why:**

- Reuses all the existing retention/eviction logic for free.
- Maintains a clear separation: the hub has one outer `ConnectionManager` for browser fan-out, and N inner ones for per-producer late-join state.

**Cost:**

- `ConnectionManager` had to become public so the hub package can reference it.

### Hub-orchestrated review fan-in

Browser-submitted review decisions route through the hub back to the originating publisher's `RemoteReviewHandler`.

**Why:**

- Ensembles in publisher mode have identical review semantics to embedded mode (timeout, on-timeout action, decision mapping).
- Browsers only need to know about the hub; they don't need to discover or connect to individual publisher processes.

**Cost:**

- Requires a return channel on the publisher's transport. WebSocket transport supports it; HTTP does not. The builder rejects review handlers when paired with HTTP-only publishers.
- The hub keeps a `pendingReviewIds` set per producer state to route decisions correctly.

## 6. Wire protocol additions

All under `net.agentensemble.web.protocol`. Every new record is `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown = true)`. Existing wire types are not modified.

| Type | Direction | Discriminator |
|---|---|---|
| `ProducerInfo` | inside envelopes | (no discriminator — embedded record) |
| `LiveEventEnvelope` | hub → browser | `event` |
| `HubHelloMessage` | hub → browser | `hub_hello` |
| `ProducerJoinedMessage` | hub → browser | `producer_joined` |
| `ProducerLeftMessage` | hub → browser | `producer_left` |
| `ReviewRequestedForwardMessage` | publisher ↔ hub | n/a (not in `ServerMessage`) |
| `ReviewDecisionForwardMessage` | hub → publisher | n/a (not in `ServerMessage`) |

## 7. Refactoring

### `LiveEventSink` extraction

`WebSocketStreamingListener` previously called `ConnectionManager.broadcast()` + `appendToSnapshot()` + `recordIteration*()` directly. Those methods are now reached through a `LiveEventSink` interface implemented by `ConnectionManager` (embedded) and by every `LiveEventPublisher` (remote).

The listener doesn't know whether its sink is local or remote. In publisher mode, the sink is an `AbstractLiveEventPublisher` which wraps each call in a `LiveEventEnvelope` and hands it to the underlying transport.

### `WebDashboard.Builder.publisher(LiveEventPublisher)`

Single additive builder method. When set:

- `start()` calls `publisher.start()` and skips the embedded `WebSocketServer`.
- `streamingListener()` targets the publisher.
- `reviewHandler()` returns a `RemoteReviewHandler`.
- `onEnsembleStarted/Completed` lifecycle hooks publish through the same sink.

When unset (default), behavior is identical to 3.0.

## 8. State retention

| Setting | Default | Purpose |
|---|---|---|
| `maxRetainedProducers` | 50 | Hard cap on the registry. Excess evicts least-recently-seen inactive producer first. |
| `maxRetainedRunsPerProducer` | 10 | Per-producer snapshot run cap (inherited from `ConnectionManager`). |
| `maxSnapshotIterationsPerProducer` | 5 | Per-producer per-task LLM iteration ring buffer cap. |
| `evictionIdleAfter` | 30 min | Inactive producers older than this are evicted on the periodic sweep. |

## 9. Viz changes

`agentensemble-viz` gains a `/hub` route with:

- `src/types/hub.ts` — `ProducerInfo`, `LiveEventEnvelope`, `HubHelloMessage`, `HubState`, etc.
- `src/utils/hubReducer.ts` — top-level reducer that routes each envelope through the existing `liveReducer` keyed by `producerId`.
- `src/contexts/HubServerContext.tsx` — single WS connection; mirrors `LiveServerContext`.
- `src/pages/HubPage.tsx` — producer sidebar + per-producer detail.

Existing `/live` and `/network` routes are untouched.

## 10. Open questions for follow-up phases

- Server-side hub broadcast filtering by `producerId` / `tag` (browsers filter client-side in phase 1).
- Cross-producer correlation views (delegation edges that span processes).
- Durable event store with replay window beyond in-memory retention.
- Auth on `/ingress` and `/ws` (currently inherits the same localhost-origin policy as the embedded dashboard).
- Producer-side back-pressure beyond drop-oldest on the bounded queue.
