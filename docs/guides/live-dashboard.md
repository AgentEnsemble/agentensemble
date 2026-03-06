# Live Execution Dashboard

The `agentensemble-web` module embeds a WebSocket server directly into the JVM process.
It broadcasts real-time execution events to any browser connected to the server and
optionally exposes browser-based review gates that replace the console prompt with an
interactive approval panel.

---

## Overview

Add `agentensemble-web` to your project and attach a `WebDashboard` to an ensemble via
`.webDashboard()`. The dashboard:

- Streams `TaskStarted`, `TaskCompleted`, `TaskFailed`, `ToolCalled`, `DelegationStarted`,
  `DelegationCompleted`, and `DelegationFailed` events to every connected browser client
  as the ensemble executes.
- Optionally serves as the review handler for human-in-the-loop review gates. When a
  review gate fires, the browser shows an approval panel with the task output and
  **Approve**, **Edit**, and **Exit Early** controls. The JVM blocks until the browser
  decision arrives or the review times out.
- Sends a `Hello` greeting on WebSocket connect and a `Heartbeat` ping every 15 seconds
  to keep connections alive through proxies and load balancers.
- Runs entirely in-process. No external server, no Docker container, and no npm command
  is required.

---

## Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.agentensemble:agentensemble-core:2.1.0")
    implementation("net.agentensemble:agentensemble-web:2.1.0")

    // Add agentensemble-review only if you are using review gates.
    // agentensemble-web has a compileOnly reference to review; you must
    // add it explicitly to activate the WebReviewHandler.
    implementation("net.agentensemble:agentensemble-review:2.1.0")
}
```

```xml
<!-- pom.xml -->
<dependencies>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-core</artifactId>
        <version>2.1.0</version>
    </dependency>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-web</artifactId>
        <version>2.1.0</version>
    </dependency>
    <!-- Required if using browser-based review gates -->
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-review</artifactId>
        <version>2.1.0</version>
    </dependency>
</dependencies>
```

---

## Quick Start

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.web.WebDashboard;

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Research the latest AI trends"))
    .task(Task.of("Write a summary report"))
    .webDashboard(WebDashboard.onPort(7329))   // start server; stream all events
    .build()
    .run();
```

Open `http://localhost:7329` in a browser before or during execution. Events stream in
real time. The server shuts down automatically when the JVM exits via a registered
shutdown hook.

---

## Builder API

### `WebDashboard.onPort(int port)`

Factory method for the common case. Starts the server on `localhost` at the given port
with a 5-minute review timeout and `CONTINUE` on-timeout behavior.

```java
WebDashboard dashboard = WebDashboard.onPort(7329);
```

### `WebDashboard.builder()`

Full control over all options:

```java
WebDashboard dashboard = WebDashboard.builder()
    .port(7329)                              // (required) 0-65535; 0 = OS-assigned ephemeral
    .host("0.0.0.0")                         // (optional, default: "localhost")
    .reviewTimeout(Duration.ofMinutes(10))   // (optional, default: 5 minutes)
    .onTimeout(OnTimeoutAction.CONTINUE)     // (optional, default: CONTINUE)
    .build();
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `port` | `int` | required | Listening port. `0` lets the OS assign an ephemeral port. |
| `host` | `String` | `"localhost"` | Network interface to bind. Use `"0.0.0.0"` to accept all interfaces. |
| `reviewTimeout` | `Duration` | `Duration.ofMinutes(5)` | How long to wait for a browser review decision before applying `onTimeout`. |
| `onTimeout` | `OnTimeoutAction` | `CONTINUE` | What to do when a review times out: `CONTINUE`, `EXIT_EARLY`, or `FAIL`. |

### `OnTimeoutAction` values

| Value | Effect when review times out |
|-------|------------------------------|
| `CONTINUE` | Continue as if the human approved. |
| `EXIT_EARLY` | Stop the pipeline. `output.getExitReason()` returns `USER_EXIT_EARLY`. |
| `FAIL` | Throw `ReviewTimeoutException`. |

---

## Wiring to an Ensemble

Call `.webDashboard(WebDashboard)` on the `EnsembleBuilder`. This single call:

1. Starts the embedded server (if not already running).
2. Registers the `WebSocketStreamingListener` as an `EnsembleListener`.
3. Wires the `WebReviewHandler` as the ensemble's `ReviewHandler`.
4. Registers a JVM shutdown hook (idempotent across multiple ensembles).

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.Review;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.web.WebDashboard;

WebDashboard dashboard = WebDashboard.builder()
    .port(7329)
    .reviewTimeout(Duration.ofMinutes(5))
    .onTimeout(OnTimeoutAction.CONTINUE)
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Draft a press release for the product launch")
        .expectedOutput("A polished press release")
        .review(Review.required())      // pause here for browser approval
        .build())
    .task(Task.builder()
        .description("Translate the press release to Spanish")
        .expectedOutput("Spanish-language press release")
        .build())
    .webDashboard(dashboard)
    .build()
    .run();
```

When the review gate fires after the first task, all connected browsers display an approval
panel. The JVM blocks until one browser submits a decision or the `reviewTimeout` elapses.

---

## Browser-Based Review

When a review gate fires, the server broadcasts a `ReviewRequested` message to all connected
clients containing:

- `reviewId` -- a UUID identifying this specific gate.
- `taskDescription` -- the task description.
- `taskOutput` -- the agent's raw output.
- `timeoutMs` -- milliseconds until the review times out.

The browser displays an approval panel with three controls:

| Control | Browser sends | Effect |
|---------|--------------|--------|
| **Approve** | `{"type":"review_decision","reviewId":"...","decision":"approve"}` | Output passed downstream unchanged. |
| **Edit** | `{"type":"review_decision","reviewId":"...","decision":"edit","revisedOutput":"..."}` | Revised text used in place of the original output. |
| **Exit Early** | `{"type":"review_decision","reviewId":"...","decision":"exit_early"}` | Pipeline stops. `output.getExitReason()` returns `USER_EXIT_EARLY`. |

Only the **first** decision received for a given `reviewId` is used. Subsequent decisions
from other browser tabs are ignored.

---

## Accessing the Actual Port

When `port(0)` is used, the OS assigns a free ephemeral port. Retrieve it after the server
starts:

```java
WebDashboard dashboard = WebDashboard.builder()
    .port(0)
    .build();

// Start the server explicitly (or attach to an ensemble, which starts it automatically)
dashboard.start();
int assignedPort = dashboard.actualPort();
System.out.printf("Dashboard running at http://localhost:%d%n", assignedPort);
```

This is useful in tests and CI environments where a fixed port may already be in use.

---

## Lifecycle

The dashboard server follows a simple lifecycle:

```java
// Start explicitly (optional -- .webDashboard() starts it automatically)
dashboard.start();

// Check if running
boolean running = dashboard.isRunning();   // true after start()

// Stop explicitly (optional -- the JVM shutdown hook stops it automatically)
dashboard.stop();
```

A JVM shutdown hook is registered the first time `.start()` is called or `.webDashboard()`
is called on an ensemble builder. The hook is registered only once even if multiple ensembles
share the same `WebDashboard` instance.

---

## Sharing a Dashboard Across Multiple Ensembles

A single `WebDashboard` can be attached to multiple ensembles running sequentially or
concurrently. Each `.webDashboard()` call on a builder checks `isRunning()` and only
starts the server once:

```java
WebDashboard dashboard = WebDashboard.onPort(7329);

// First ensemble run -- server starts here
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Research trends"))
    .webDashboard(dashboard)
    .build()
    .run();

// Second ensemble run -- server already running, not restarted
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Write report"))
    .webDashboard(dashboard)
    .build()
    .run();
```

---

## Heartbeat

The server sends a `Heartbeat` message to every connected client every 15 seconds. This
keeps long-lived WebSocket connections alive through NAT gateways, reverse proxies, and
browser idle timeouts. No action is required on the server side; the browser client should
respond with a `Ping` message and the server will reply with a `Pong`.

---

## Origin Validation

The server validates the `Origin` header of each WebSocket upgrade request. Connections are
accepted when the request's origin host matches the configured server `host`, or when the
configured `host` is `"0.0.0.0"` (any origin accepted). All other origins are rejected with
HTTP 403.

For local development on `localhost` this requires no configuration. For production
deployments behind a reverse proxy, ensure the proxy preserves the `Origin` header, or
configure `host("0.0.0.0")` and apply origin validation at the proxy layer.

---

## WebSocket Protocol

All messages are JSON objects with a `"type"` discriminator field.

### Server-to-client messages

| Type | Description |
|------|-------------|
| `hello` | Sent on WebSocket connect. Contains `serverId` and `serverTime`. |
| `ensemble_started` | The ensemble began execution. |
| `task_started` | A task started. Contains `taskIndex`, `totalTasks`, `taskDescription`, `agentRole`. |
| `task_completed` | A task completed. Contains timing, `rawOutput`, `toolCallCount`. |
| `task_failed` | A task failed. Contains `errorMessage`. |
| `tool_called` | A tool was invoked. Contains `toolName`, `input`, `output`, `duration`. |
| `delegation_started` | A delegation began. Contains `delegationId`, `workerRole`. |
| `delegation_completed` | A delegation completed. Contains `delegationId`, timing. |
| `delegation_failed` | A delegation failed. Contains `delegationId`, `errorMessage`. |
| `review_requested` | A review gate fired. Contains `reviewId`, `taskDescription`, `taskOutput`, `timeoutMs`. |
| `review_timed_out` | A review gate timed out before a decision was received. Contains `reviewId`. |
| `ensemble_completed` | The ensemble run finished. Contains `exitReason`, `totalDuration`. |
| `heartbeat` | Sent every 15 seconds. Contains `serverTime`. |

### Client-to-server messages

| Type | Description |
|------|-------------|
| `review_decision` | Browser approval decision. Contains `reviewId`, `decision` (`"approve"`, `"edit"`, or `"exit_early"`), and optional `revisedOutput`. |
| `ping` | Client keepalive. Server responds with a `pong`. |

---

## EnsembleDashboard Interface

`WebDashboard` implements `net.agentensemble.dashboard.EnsembleDashboard`, a stable
interface in `agentensemble-core`. If you need to swap implementations (e.g., a no-op
stub in unit tests), depend on the interface rather than the concrete class:

```java
import net.agentensemble.dashboard.EnsembleDashboard;

public class MyService {
    private final EnsembleDashboard dashboard;

    public MyService(EnsembleDashboard dashboard) {
        this.dashboard = dashboard;
    }

    public EnsembleOutput runPipeline(ChatLanguageModel model) {
        return Ensemble.builder()
            .chatLanguageModel(model)
            .task(Task.of("Analyse data"))
            .webDashboard(dashboard)
            .build()
            .run();
    }
}
```

---

## Example: Ephemeral Port in Tests

```java
@Test
void dashboardStreamsTaskCompletedEvent() throws Exception {
    WebDashboard dashboard = WebDashboard.builder()
        .port(0)   // OS assigns a free port
        .build();

    Ensemble.builder()
        .chatLanguageModel(mockModel)
        .task(Task.of("Do something"))
        .webDashboard(dashboard)
        .build()
        .run();

    int port = dashboard.actualPort();
    // Connect a WebSocket test client to ws://localhost:{port} and assert messages
}
```

---

## Related Documentation

- [Human-in-the-Loop Review Guide](review.md) -- Review timing, policies, and timeout actions
- [Human-in-the-Loop Example](../examples/human-in-the-loop.md) -- Browser-based approval walkthrough
- [Live Dashboard Example](../examples/live-dashboard.md) -- Full annotated example
- [Design: Live Execution Dashboard](../design/16-live-dashboard.md) -- Architecture and protocol specification
