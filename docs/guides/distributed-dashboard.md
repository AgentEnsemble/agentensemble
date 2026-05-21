# Distributed Live Dashboard

When more than one process runs AgentEnsemble work, the embedded [`WebDashboard`](live-dashboard.md) becomes awkward: each process binds its own port, browsers see only one stream at a time, and late-join snapshots are scoped to a single dashboard instance. AgentEnsemble 3.x ships a central **`LiveEventHub`** plus a **`LiveEventPublisher`** abstraction that solves this. Many producer processes stream their live events to one hub, and a browser connects once to see a coherent merged view of all active and recent runs.

This guide covers when to use the distributed dashboard, the wire model, and the available transports.

## When to use which dashboard

| Deployment shape | Use |
|---|---|
| Single process, single ensemble | Embedded — [`WebDashboard.onPort(7329)`](live-dashboard.md) |
| Single process, browser also opens viz to multiple ensembles by URL | Embedded + browser-side [`/network`](network-dashboard.md) multiplexing |
| Many processes, one merged UI, late-join sees all producers | **This guide:** central `LiveEventHub` + `LiveEventPublisher` |

The distributed hub is server-side aggregation. The `/network` route does the same thing on the browser side; it's simpler when producers already expose individual dashboards but doesn't give you a single late-join snapshot, single browser-side auth boundary, or pluggable transports.

## Module setup

Hub host (the process running the central hub):

```gradle
dependencies {
    implementation(platform("net.agentensemble:agentensemble-bom:<version>"))
    implementation("net.agentensemble:agentensemble-web-hub")
}
```

Publisher process (each ensemble process):

```gradle
dependencies {
    implementation(platform("net.agentensemble:agentensemble-bom:<version>"))
    implementation("net.agentensemble:agentensemble-web") // provides the publisher SPI
}
```

The publisher process does not depend on `agentensemble-web-hub`; only the central host does.

## Booting a hub

```java
import net.agentensemble.web.hub.LiveEventHub;

LiveEventHub hub = LiveEventHub.builder()
    .port(7400)
    .host("localhost")
    .maxRetainedProducers(50)
    .maxRetainedRunsPerProducer(10)
    .build();
hub.start();
```

The hub now exposes:

| Endpoint | Purpose |
|---|---|
| `ws://host:7400/ws` | Browser-facing WebSocket. Sends `hub_hello` on connect, then enveloped live events. |
| `ws://host:7400/ingress` | Publisher-facing WebSocket. Bidirectional; required for review-gate fan-in. |
| `POST /api/hub/ingress` | One-way HTTP ingress. Pairs with `HttpLiveEventPublisher`. No review fan-in. |
| `GET  /api/hub/producers` | JSON list of currently known producers. |

Open `http://host:7400/hub?server=ws://host:7400/ws` in a browser. The page lists producers in a sidebar and shows per-producer task timelines on the right.

## Running a publisher

The publisher process keeps its existing `Ensemble.builder().webDashboard(dashboard)` wiring. The only change is on the dashboard builder:

```java
import net.agentensemble.web.WebDashboard;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.publisher.WebSocketLiveEventPublisher;

ProducerInfo info = ProducerInfo.of(
    "svc-a-instance-1",          // producerId — required, stable across restarts
    "svc-a",                     // serviceName — groups horizontally scaled replicas
    "instance-1",                // instanceId — e.g. K8s pod name
    "node-12.us-east");          // host — hostname or pod IP

WebSocketLiveEventPublisher publisher = WebSocketLiveEventPublisher.connect(
    URI.create("ws://hub.internal:7400/ingress"), info);

WebDashboard dashboard = WebDashboard.builder()
    .port(0)                     // ignored in publisher mode
    .publisher(publisher)
    .build();

Ensemble.builder()
    .chatLanguageModel(model)
    .webDashboard(dashboard)     // same wiring as embedded mode
    .task(Task.of("Research AI trends"))
    .build()
    .run();
```

In publisher mode the dashboard does not bind a port. `actualPort()` returns `-1`. The embedded Control API (REST run submission, REST review, etc.) is not exposed — those endpoints belong on the hub or on a separate embedded dashboard.

## `ProducerInfo` conventions

| Field | Used for | Stability |
|---|---|---|
| `producerId` | Identity. Hub keys all per-producer state on this. | **Must be stable across restarts** so reconnects re-attach to the retained snapshot. Typical pattern: `${serviceName}-${instanceId}`. |
| `serviceName` | Grouping replicas in the UI and in filter chips. | Stable across replicas; constant per deployment. |
| `instanceId` | Disambiguating replicas. | Per-process; OK for it to change between restarts. |
| `host` | Display only. | Hostname or pod IP. |
| `version` | Display only. | Application version. |
| `tags` | Free-form labels (environment, region, etc.). | Map<String,String>. |

## Late-join across producers

When a browser connects to the hub it receives a `hub_hello` message carrying:

- The full roster of currently known producers.
- A flattened `snapshotTrace` array of every retained `LiveEventEnvelope` across all producers, in chronological order.
- Recent LLM iteration snapshots keyed by `producerId` for conversation panel hydration.

The browser-side reducer (`hubReducer`) walks the trace and routes each envelope through the existing single-producer reducer (`liveReducer`), keyed by `producerId`. The result: a late-joining browser reconstructs the full state of every retained producer without restart.

Retention defaults: 50 producers, 10 runs per producer. Idle producers are evicted 30 minutes after their last activity. Override with `LiveEventHub.builder().maxRetainedProducers(...).maxRetainedRunsPerProducer(...).evictionIdleAfter(...)`.

## Review-gate fan-in

When a publisher's ensemble fires a review gate, the publisher-side `RemoteReviewHandler` (wired automatically when you pass a publisher to `WebDashboard.builder()`):

1. Generates a `reviewId` and registers a `CompletableFuture`.
2. Publishes the `review_requested` event upstream; the hub re-broadcasts it to all browsers wrapped in an envelope so they see which producer needs attention.
3. Blocks on the future for the configured `reviewTimeout`.

When a browser submits a `review_decision` over the hub's `/ws` socket, the hub looks up which producer owns the matching `reviewId` and forwards the decision back over that producer's `/ingress` channel. The publisher dispatches the decision to its `RemoteReviewHandler`, which completes the blocked future. From the ensemble's point of view, the review gate behaves identically to embedded mode.

The HTTP transport (`HttpLiveEventPublisher`) is **one-way only** — there is no reverse channel for decisions. Pair it only with ensembles that do not have review gates.

## Transports

| Transport | Class | Review fan-in | Best for |
|---|---|---|---|
| WebSocket | `WebSocketLiveEventPublisher` | ✓ | Long-running ensemble processes; durable connection; review gates. |
| HTTP | `HttpLiveEventPublisher` | ✗ | Short-lived processes, batch jobs, environments where WebSocket is not available. |
| In-memory | `InMemoryLiveEventPublisher` | ✓ | Tests, same-JVM hub setups (one process hosts both publisher dashboards and the hub). |

All publishers extend `AbstractLiveEventPublisher`, which handles envelope construction, sequence numbering, and serialization. Custom transports (Redis, Kafka) are out of scope for phase 1 — extend `AbstractLiveEventPublisher`.

## Producer scaling and reconnect

A producer that disconnects and reconnects with the same `producerId` re-attaches to its retained snapshot — the hub treats the reconnection as a continuation, not a new producer. Browsers see no UI churn.

A producer that scales horizontally (two replicas) must use distinct `producerId` values. Use a stable `serviceName` so the UI can group them.

The `WebSocketLiveEventPublisher` auto-reconnects on disconnect with exponential backoff (1s, 2s, 4s, 8s, 16s, capped at 30s). Envelopes published while the socket is down are buffered (default queue capacity 1024, drop-oldest on overflow). For workflows that can't tolerate any drops, prefer durable transports (out of scope for phase 1).

## Backward compatibility

The embedded `WebDashboard.onPort(7329)` path is unchanged. The existing wire protocol records (`HelloMessage`, `TaskStartedMessage`, etc.) are untouched. The browser's `/live` route still receives the original single-producer message shapes and the existing reducer handles them unchanged.

The hub's wire format adds four new `ServerMessage` discriminators (`event`, `hub_hello`, `producer_joined`, `producer_left`) but they only appear on the hub's `/ws` endpoint — embedded dashboards never emit them.

## See also

- [Live Dashboard](live-dashboard.md) — embedded single-process dashboard
- [Network Dashboard](network-dashboard.md) — browser-side multi-ensemble multiplexing
- [Ensemble Control API](ensemble-control-api.md)
- Design: [Distributed Live Event Hub](../design/29-live-event-hub.md)
