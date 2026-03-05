# CaptureMode Examples

This page shows practical examples of using `CaptureMode` to collect richer execution data
for debugging, replay, and visualization.

---

## Activate without code changes

The simplest way to enable capture is via the JVM system property or environment variable.
No source code changes required -- ship once, debug on demand.

```bash
# FULL capture: writes ./traces/{run-id}.json automatically
java -Dagentensemble.captureMode=FULL -jar my-agent-app.jar

# STANDARD capture: message history + memory counts, no auto-export
AGENTENSEMBLE_CAPTURE_MODE=STANDARD java -jar my-agent-app.jar
```

---

## Programmatic FULL capture with custom export path

```java
import net.agentensemble.Ensemble;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.export.JsonTraceExporter;
import java.nio.file.Path;

Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .captureMode(CaptureMode.FULL)
    .traceExporter(new JsonTraceExporter(Path.of("audit/traces/")))
    .build();

EnsembleOutput output = ensemble.run();
// Trace auto-written to audit/traces/{ensembleId}.json
```

---

## Inspecting message history (STANDARD+)

```java
import net.agentensemble.trace.*;

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .task(task)
    .captureMode(CaptureMode.STANDARD)
    .build()
    .run();

ExecutionTrace trace = output.getTrace();

for (TaskTrace taskTrace : trace.getTaskTraces()) {
    System.out.println("=== Task: " + taskTrace.getTaskDescription() + " ===");

    for (LlmInteraction iteration : taskTrace.getLlmInteractions()) {
        System.out.println("--- Iteration " + iteration.getIterationIndex()
            + " [" + iteration.getResponseType() + "] ---");

        for (CapturedMessage msg : iteration.getMessages()) {
            String preview = msg.getContent() != null
                ? msg.getContent().substring(0, Math.min(120, msg.getContent().length()))
                : "(tool calls: " + msg.getToolCalls().size() + ")";
            System.out.printf("[%-9s] %s%n", msg.getRole(), preview);
        }
    }
}
```

Sample output:

```
=== Task: Research AI agent frameworks ===
--- Iteration 0 [TOOL_CALLS] ---
[system   ] You are a Senior Research Analyst. Your personal goal is: Conduct thorough research...
[user     ] ## Task
Research AI agent frameworks published in 2025.
[assistant] (tool calls: 1)
[tool     ] 1. LangChain4j -- Java LLM integration...
--- Iteration 1 [FINAL_ANSWER] ---
[system   ] You are a Senior Research Analyst...
[user     ] ## Task
Research AI agent frameworks published in 2025.
[assistant] Based on my research, here are the top AI agent frameworks...
```

---

## Inspecting enriched tool I/O (FULL)

```java
EnsembleOutput output = Ensemble.builder()
    .agent(agent)
    .task(task)
    .captureMode(CaptureMode.FULL)
    .build()
    .run();

ExecutionTrace trace = output.getTrace();

for (TaskTrace taskTrace : trace.getTaskTraces()) {
    for (LlmInteraction interaction : taskTrace.getLlmInteractions()) {
        for (ToolCallTrace toolCall : interaction.getToolCalls()) {
            System.out.println("Tool:      " + toolCall.getToolName());
            System.out.println("Arguments: " + toolCall.getArguments());

            if (toolCall.getParsedInput() != null) {
                // Structured access to tool arguments
                toolCall.getParsedInput().forEach((key, value) ->
                    System.out.println("  " + key + " = " + value));
            }

            System.out.println("Outcome:   " + toolCall.getOutcome());
            System.out.println("Duration:  " + toolCall.getDuration().toMillis() + "ms");
        }
    }
}
```

---

## Checking memory operation counts (STANDARD+ with memory enabled)

```java
import net.agentensemble.metrics.MemoryOperationCounts;
import net.agentensemble.memory.EnsembleMemory;

Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(task)
    .memory(EnsembleMemory.builder().shortTerm(true).build())
    .captureMode(CaptureMode.STANDARD)
    .build();

EnsembleOutput output = ensemble.run();

for (var taskOutput : output.getTaskOutputs()) {
    MemoryOperationCounts memOps = taskOutput.getMetrics().getMemoryOperations();
    System.out.println("Agent: " + taskOutput.getAgentRole());
    System.out.println("  STM writes:     " + memOps.getShortTermEntriesWritten());
    System.out.println("  LTM stores:     " + memOps.getLongTermStores());
    System.out.println("  LTM retrievals: " + memOps.getLongTermRetrievals());
    System.out.println("  Entity lookups: " + memOps.getEntityLookups());
}
```

---

## Reading the captureMode from a saved trace

When loading a trace JSON file, check `captureMode` before accessing STANDARD/FULL fields:

```java
// Check what depth of data was collected
ExecutionTrace trace = output.getTrace();
CaptureMode mode = trace.getCaptureMode();

if (mode.isAtLeast(CaptureMode.STANDARD)) {
    // Safe to read LlmInteraction.messages
    trace.getTaskTraces().forEach(t ->
        t.getLlmInteractions().forEach(i ->
            System.out.println("Messages in iteration " + i.getIterationIndex()
                + ": " + i.getMessages().size())));
}

if (mode.isAtLeast(CaptureMode.FULL)) {
    // Safe to read ToolCallTrace.parsedInput
    trace.getTaskTraces().forEach(t ->
        t.getLlmInteractions().forEach(i ->
            i.getToolCalls().forEach(tc ->
                System.out.println(tc.getToolName() + " parsedInput: " + tc.getParsedInput()))));
}
```

---

## Combining CaptureMode with other features

`CaptureMode` composes with all other `Ensemble` builder options:

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)

    // Verbose logging to INFO (orthogonal to CaptureMode)
    .verbose(true)

    // Cost estimation (orthogonal to CaptureMode)
    .costConfiguration(CostConfiguration.builder()
        .inputTokenRate(new BigDecimal("0.0000025"))
        .outputTokenRate(new BigDecimal("0.0000100"))
        .build())

    // CaptureMode: deep data collection
    .captureMode(CaptureMode.FULL)

    // Custom trace destination (overrides FULL auto-export default)
    .traceExporter(new JsonTraceExporter(Path.of("traces/")))

    .build();
```
