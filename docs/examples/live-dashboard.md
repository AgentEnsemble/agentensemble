# Live Execution Dashboard

Real-time visualization and browser-based monitoring for ensemble runs.

## Prerequisites

The live dashboard requires the `agentensemble-web` module:

```gradle
dependencies {
    implementation("net.agentensemble:agentensemble-web")
}
```

## Quick Start

### Java side

```java
import net.agentensemble.web.WebDashboard;

WebDashboard dashboard = WebDashboard.onPort(7329);

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Research the latest AI trends")
        .expectedOutput("A comprehensive research report")
        .build())
    .webDashboard(dashboard)   // wires streaming + optional review handler
    .build()
    .run();
```

### Browser side

Navigate to the live dashboard and connect:

```
http://localhost:7329/live?server=ws://localhost:7329/ws
```

Or open `http://localhost:7329` in your browser and use the "Connect to live server" form on the landing page.

## What You See

The live dashboard has two views accessible via the header toggle:

### Timeline View (default)

A real-time Gantt chart showing:

- **Task bars** that appear the moment a `task_started` event fires
- **Growing right edges** that animate via `requestAnimationFrame` while tasks are running
- **Locked bar widths** once `task_completed` is received (uses actual `durationMs`)
- **Red bars** for failed tasks
- **Tool call markers** at the timestamp each `tool_called` event arrives
- **"Follow latest"** toggle (on by default) that auto-scrolls to the most recent activity

Click any task bar to open the detail panel showing status, timing, and tool calls.

### Flow View

A dependency graph showing:

- **Gray placeholder nodes** before any tasks start
- **Blue pulsing nodes** for running tasks (CSS keyframe animation, no JS setInterval)
- **Agent-colored nodes** for completed tasks (same palette as historical mode)
- **Red nodes** for failed tasks

## Connection Status Bar

The green/amber/red status bar below the header shows:

| Color  | Meaning |
|--------|---------|
| Green  | Connected to the WebSocket server |
| Amber  | Connecting or reconnecting (pulsing animation) |
| Red    | Disconnected or connection error |

If the connection drops, the client automatically reconnects with exponential backoff (1s, 2s, 4s, 8s, 16s, capped at 30s).

## Configuration

```java
WebDashboard dashboard = WebDashboard.builder()
    .port(7329)                          // default: 7329
    .host("localhost")                   // default: localhost (local-only)
    .reviewTimeout(Duration.ofMinutes(5)) // default: 5 minutes
    .onTimeout(OnTimeoutAction.CONTINUE)  // default: CONTINUE
    .build();
```

See `docs/guides/live-dashboard.md` for the full configuration reference.
