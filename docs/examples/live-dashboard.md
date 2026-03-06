# Live Execution Dashboard

Real-time visualization and browser-based monitoring for ensemble runs.

## Prerequisites

The live dashboard requires the `agentensemble-web` module. Add it alongside the BOM
(see [Installation](../getting-started/installation.md) for BOM setup):

```gradle
dependencies {
    implementation(platform("net.agentensemble:agentensemble-bom:<version>"))
    implementation("net.agentensemble:agentensemble-web")
}
```

## Quick Start

### Java side

```java
import net.agentensemble.core.Ensemble;
import net.agentensemble.core.EnsembleOutput;
import net.agentensemble.core.Task;
import net.agentensemble.web.WebDashboard;
import dev.langchain4j.model.chat.ChatLanguageModel;

// model is any LangChain4j ChatLanguageModel; see installation docs for setup.
ChatLanguageModel model = /* your model */;

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

## Streaming Output

Enable token-by-token streaming of the final agent response in the dashboard. Tokens arrive
over WebSocket as `token` messages and appear live in the **Live Output** section of the task
detail panel.

```java
StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

WebDashboard dashboard = WebDashboard.onPort(7329);

Ensemble.builder()
    .chatLanguageModel(syncModel)               // for tool-loop iterations
    .streamingChatLanguageModel(streamingModel) // for final answers
    .task(Task.of("Write a research report on quantum computing"))
    .webDashboard(dashboard)
    .build()
    .run();
```

Click any running task bar in the timeline to open the detail panel. While the agent is
generating its final answer, a **Live Output** section appears showing the text as it
streams, with a pulsing cursor.

Token messages are **ephemeral** -- they are not stored in the late-join snapshot. A client
that connects mid-stream will see the task as running and receive new tokens from that
point forward; the full output is available via `task_completed` when the task finishes.

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
import java.time.Duration;
import net.agentensemble.web.WebDashboard;
import net.agentensemble.web.OnTimeoutAction;

WebDashboard dashboard = WebDashboard.builder()
    .port(7329)                          // default: 7329
    .host("localhost")                   // default: localhost (local-only)
    .reviewTimeout(Duration.ofMinutes(5)) // default: 5 minutes
    .onTimeout(OnTimeoutAction.CONTINUE)  // default: CONTINUE
    .build();
```

See `docs/guides/live-dashboard.md` for the full configuration reference.
