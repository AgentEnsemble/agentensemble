# Execution Graph Visualization

AgentEnsemble provides two developer tools for visualizing and debugging ensemble execution:

- **`agentensemble-devtools`** (Java) — exports dependency graphs and execution traces to JSON files
- **`agentensemble-viz`** (npm) — a local web application that reads those JSON files and renders interactive visualizations

## Overview

The viewer provides two views:

- **Flow View** — renders the task dependency graph before or after execution: which tasks run in parallel, which are sequential, which agents are involved, and the critical path
- **Timeline View** — renders a Gantt-chart-style execution timeline showing when each task ran, with drill-down into LLM calls, tool invocations, and agent conversation history

## Installation

### Java: agentensemble-devtools

Add `agentensemble-devtools` as a dependency. Use `test` or `runtime` scope since it is a development tool:

=== "Maven"

    ```xml
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-devtools</artifactId>
        <version>VERSION</version>
        <scope>test</scope>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    testImplementation("net.agentensemble:agentensemble-devtools:VERSION")
    // or for runtime use:
    // runtimeOnly("net.agentensemble:agentensemble-devtools:VERSION")
    ```

### Viewer: agentensemble-viz

No installation required. Use `npx`:

```bash
npx @agentensemble/viz ./traces/
```

Or install globally:

```bash
npm install -g @agentensemble/viz
agentensemble-viz ./traces/
```

## Quick Start

### 1. Export the dependency graph (pre-execution)

```java
import net.agentensemble.devtools.EnsembleDevTools;

Ensemble ensemble = Ensemble.builder()
    .agents(researcher, writer, analyst)
    .tasks(researchTask, analysisTask, writeTask)
    .workflow(Workflow.PARALLEL)
    .build();

// Export the planned dependency graph -- no execution needed
Path dagFile = EnsembleDevTools.exportDag(ensemble, Path.of("./traces/"));
System.out.println("DAG exported to: " + dagFile);
```

### 2. Run with capture mode enabled (for rich timeline data)

```java
EnsembleOutput output = ensemble.toBuilder()
    .captureMode(CaptureMode.STANDARD)   // captures LLM conversations
    .build()
    .run(Map.of("topic", "AI agents"));

// Export the execution trace
Path traceFile = EnsembleDevTools.exportTrace(output, Path.of("./traces/"));
System.out.println("Trace exported to: " + traceFile);
```

### 3. Or export both in one call

```java
EnsembleDevTools.ExportResult result = EnsembleDevTools.export(
    ensemble, output, Path.of("./traces/")
);
System.out.println(result.describe());
```

### 4. Open the viewer

```bash
npx @agentensemble/viz ./traces/
```

The viewer starts at `http://localhost:7329`, auto-loads available files from the traces directory,
and opens the browser automatically.

## EnsembleDevTools API

### `buildDag(Ensemble)`

Builds a `DagModel` from an ensemble configuration without executing it. Returns the model
for further inspection or custom serialization.

```java
DagModel dag = EnsembleDevTools.buildDag(ensemble);
System.out.println("Critical path: " + dag.getCriticalPath());
System.out.println("Parallel groups: " + dag.getParallelGroups());
```

### `exportDag(Ensemble, Path)`

Exports the dependency graph to a `*.dag.json` file in the given directory. Returns the path
of the written file.

```java
Path path = EnsembleDevTools.exportDag(ensemble, Path.of("./traces/"));
```

### `exportTrace(EnsembleOutput, Path)`

Exports the post-execution trace to a `*.trace.json` file. Requires that the ensemble was run
(i.e., `EnsembleOutput` is available). The trace is always present, but richer when `captureMode`
is `STANDARD` or `FULL`.

```java
Path path = EnsembleDevTools.exportTrace(output, Path.of("./traces/"));
```

### `export(Ensemble, EnsembleOutput, Path)` 

Convenience method that exports both the DAG and the trace in a single call.

```java
EnsembleDevTools.ExportResult result = EnsembleDevTools.export(
    ensemble, output, Path.of("./traces/")
);
// result.dagPath()   -- path to the .dag.json file
// result.tracePath() -- path to the .trace.json file
// result.describe()  -- human-readable summary
```

## Capture Modes

The richness of the Timeline View depends on the `captureMode` configured on the ensemble:

| Mode | What is captured | Timeline detail |
|------|-----------------|----------------|
| `OFF` (default) | Prompts, tool args/results, timing, token counts | Task bars, tool call markers |
| `STANDARD` | All of the above + full LLM message history per iteration | Full conversation drill-down |
| `FULL` | All of the above + enriched tool I/O with parsed JSON | Structured tool argument viewer |

```java
Ensemble.builder()
    .captureMode(CaptureMode.STANDARD)  // enables conversation history
    .build();
```

## Flow View

The Flow View renders the task dependency DAG:

- **Nodes** are colored by agent role
- **Edges** are dependency arrows (gray = normal, red = critical path)
- **L0, L1, ...** badges show the topological level (tasks at the same level can run in parallel)
- **CP** badge marks tasks on the critical path
- **Click a node** to open the detail panel showing task description, agent info, and execution metrics (when a trace is loaded alongside the DAG)

## Timeline View

The Timeline View renders a Gantt-chart-style execution timeline:

- One horizontal **swimlane per agent**
- **Task bars** (colored) show when each task ran
- **LLM call sub-bars** (indigo) show when each LLM iteration ran within a task
- **Tool call markers** (green/red/gray) show tool invocations at the bottom of each bar
- **Click any element** to open the detail panel:
    - Click a task bar: shows task description, prompts, metrics, final output, and LLM iteration list
    - Click an LLM sub-bar: shows the full message history (when captureMode >= STANDARD) and tool call details
    - Click a tool marker: shows tool name, arguments, result, outcome, and timing
- **Zoom slider** to expand the time axis for fine-grained inspection

## Viewer Distribution

The viewer is distributed as an npm package. Two usage modes:

**CLI mode (recommended):** Start the server pointing to a traces directory. The viewer auto-loads all `.dag.json` and `.trace.json` files it finds:

```bash
npx @agentensemble/viz ./traces/
```

**Drag-and-drop mode:** Open the viewer without a directory argument (or open `dist/index.html` directly) and drag JSON files into the browser window.

## File Formats

### DAG file (`.dag.json`)

Generated by `EnsembleDevTools.exportDag()`. Contains the task dependency structure, agent
metadata, topological levels, and critical path. Identified by `"type": "dag"` in the JSON.

### Trace file (`.trace.json`)

Generated by `EnsembleDevTools.exportTrace()` or automatically when `captureMode = FULL`.
Contains the full execution trace: all task traces, LLM interactions, tool calls, metrics,
delegation chains, and agent conversation history (when `captureMode >= STANDARD`).

## See Also

- [Execution metrics reference](../reference/ensemble-configuration.md#execution-metrics)
- [CaptureMode reference](../reference/ensemble-configuration.md#capturemode)
- [Visualization example](../examples/visualization.md)
