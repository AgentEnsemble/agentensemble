# Visualization Example

This example shows how to export both a pre-execution DAG and a post-execution trace
for a parallel workflow, then open them in the `agentensemble-viz` trace viewer.

## Full Example

```java
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.devtools.EnsembleDevTools;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.workflow.Workflow;

import java.nio.file.Path;
import java.util.Map;

public class VisualizationExample {

    public static void main(String[] args) {
        // Configure agents
        Agent researcher = Agent.builder()
            .role("Researcher")
            .goal("Gather comprehensive information on the topic")
            .llm(openAiModel)
            .build();

        Agent analyst = Agent.builder()
            .role("Analyst")
            .goal("Analyze data and identify key insights")
            .llm(openAiModel)
            .build();

        Agent writer = Agent.builder()
            .role("Writer")
            .goal("Produce a well-structured written output")
            .llm(openAiModel)
            .build();

        // Configure tasks (research and analysis run in parallel, then writing depends on both)
        Task researchTask = Task.builder()
            .description("Research {topic} developments in 2025")
            .expectedOutput("A comprehensive research summary")
            .agent(researcher)
            .build();

        Task analysisTask = Task.builder()
            .description("Analyze market trends related to {topic}")
            .expectedOutput("A market trend analysis")
            .agent(analyst)
            .build();

        Task writeTask = Task.builder()
            .description("Write a report combining research and analysis on {topic}")
            .expectedOutput("A polished 500-word report")
            .agent(writer)
            .context(researchTask, analysisTask)  // depends on both
            .build();

        // Build the ensemble with PARALLEL workflow and STANDARD capture mode
        Ensemble ensemble = Ensemble.builder()
            .agent(researcher)
            .agent(analyst)
            .agent(writer)
            .task(researchTask)
            .task(analysisTask)
            .task(writeTask)
            .workflow(Workflow.PARALLEL)
            .captureMode(CaptureMode.STANDARD)  // enables LLM conversation capture
            .build();

        Path tracesDir = Path.of("./traces/");

        // 1. Export the dependency graph BEFORE running (shows the planned execution)
        Path dagFile = EnsembleDevTools.exportDag(ensemble, tracesDir);
        System.out.println("DAG exported:   " + dagFile);

        // 2. Run the ensemble
        EnsembleOutput output = ensemble.run(Map.of("topic", "AI agents"));

        // 3. Export the execution trace AFTER running (shows what actually happened)
        Path traceFile = EnsembleDevTools.exportTrace(output, tracesDir);
        System.out.println("Trace exported: " + traceFile);

        System.out.println();
        System.out.println("Open the viewer:");
        System.out.println("  npx @agentensemble/viz " + tracesDir);
    }
}
```

## What You'll See

### Flow View

After opening the viewer, the Flow View shows the planned dependency graph:

- **Researcher** (L0) and **Analyst** (L0) at the same level — they run in parallel
- **Writer** (L1) connected to both by dependency arrows — it waits for both to finish
- The critical path is highlighted: `Researcher -> Writer` or `Analyst -> Writer`
  (whichever is the longest chain)

### Timeline View

The Timeline View shows the actual execution:

- A swimlane for **Researcher**, **Analyst**, and **Writer**
- The Researcher and Analyst bars overlap (running concurrently)
- The Writer bar starts after both finish
- Within each task bar: indigo sub-bars for LLM iterations, tool markers at the bottom
- Click any element for details: prompts, full LLM conversation, tool inputs/outputs, token counts

## Using the Convenience Method

Instead of separate `exportDag` and `exportTrace` calls, you can use the combined method:

```java
EnsembleDevTools.ExportResult result = EnsembleDevTools.export(
    ensemble,
    output,
    Path.of("./traces/")
);
System.out.println(result.describe());
// DAG exported to:   ./traces/ensemble-dag-20260305-090000.dag.json
// Trace exported to: ./traces/ensemble-trace-20260305-090000.trace.json
```

## Pre-execution Only

You can export the DAG before running to inspect the planned execution:

```java
// No execution needed -- just analyze the configuration
DagModel dag = EnsembleDevTools.buildDag(ensemble);
System.out.println("Parallel groups: " + dag.getParallelGroups());
// [[0, 1], [2]]  -- tasks 0 and 1 at level 0 (parallel), task 2 at level 1

System.out.println("Critical path: " + dag.getCriticalPath());
// [0, 2] or [1, 2]

// Or export to file for the viewer
EnsembleDevTools.exportDag(ensemble, Path.of("./traces/"));
```

## Running the Viewer

**Homebrew (macOS and Linux):**

```bash
# Install once
brew install agentensemble/tap/agentensemble-viz

# Run
agentensemble-viz ./traces/
```

**npx (no installation required):**

```bash
npx @agentensemble/viz ./traces/
```

**Custom port:**

```bash
PORT=8080 agentensemble-viz ./traces/
# or: PORT=8080 npx @agentensemble/viz ./traces/
```

The viewer opens at `http://localhost:7329` (or your custom port) and auto-discovers all
`.dag.json` and `.trace.json` files in the directory.
