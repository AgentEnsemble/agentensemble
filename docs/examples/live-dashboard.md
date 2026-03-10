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

## Multi-Run Batch Processor

Attach a single `WebDashboard` to multiple sequential ensemble runs and watch all runs
accumulate in the live timeline. Each completed run appears as a stacked read-only section
above the active run, so you can compare durations and tool usage across iterations
without losing history.

```java
import java.nio.file.Path;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.web.WebDashboard;

// One dashboard for all runs. traceExportDir auto-exports each run as
// traces/{ensembleId}.json so you can load them in the viz's static mode later.
WebDashboard dashboard = WebDashboard.builder()
    .port(7329)
    .maxRetainedRuns(10)                         // keep up to 10 runs in the snapshot
    .traceExportDir(Path.of("./traces"))         // auto-export each run's trace
    .build();

List<String> topics = List.of("AI trends", "Quantum computing", "Climate tech");

for (String topic : topics) {
    Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Research " + topic))
        .task(Task.of("Write a summary of " + topic + " research"))
        .webDashboard(dashboard)
        .build()
        .run();
    // Browser shows run N completed above run N+1 as it starts
}

// After all runs: browse trace files in ./traces/ or load them in the viz
```

Open `http://localhost:7329` and connect to the live server. As each run completes,
the timeline adds a new read-only section showing that run's tasks. The Flow View
provides a run selector dropdown to inspect the DAG of any past run.

## Configuration

```java
import java.nio.file.Path;
import java.time.Duration;
import net.agentensemble.web.WebDashboard;
import net.agentensemble.review.OnTimeoutAction;

WebDashboard dashboard = WebDashboard.builder()
    .port(7329)                                    // default: 7329
    .host("localhost")                             // default: localhost (local-only)
    .reviewTimeout(Duration.ofMinutes(5))          // default: 5 minutes
    .onTimeout(OnTimeoutAction.CONTINUE)           // default: CONTINUE
    .maxRetainedRuns(10)                           // default: 10
    .traceExportDir(Path.of("./traces"))           // default: null (disabled)
    .build();
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `port` | `int` | required | Listening TCP port. |
| `host` | `String` | `"localhost"` | Network interface to bind. |
| `reviewTimeout` | `Duration` | 5 minutes | Timeout for browser review decisions. |
| `onTimeout` | `OnTimeoutAction` | `CONTINUE` | Action when review times out. |
| `maxRetainedRuns` | `int` | `10` | Maximum number of completed runs retained in the late-join snapshot. |
| `traceExportDir` | `Path` | `null` | Directory for automatic per-run trace export. Null disables export. |

See `docs/guides/live-dashboard.md` for the full configuration reference.
