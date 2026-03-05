# CaptureMode

`CaptureMode` is an opt-in, zero-configuration toggle that transparently enables deeper data
collection across the entire agent execution pipeline -- LLM conversations, tool I/O, memory
operations, and delegation chains -- without requiring any changes to your agents, tasks, or tools.

## Capture levels

| Level | What is captured | Trace size |
|---|---|---|
| `OFF` (default) | Prompts, tool arguments and results, timing, token counts | Minimal |
| `STANDARD` | Everything in OFF plus: full LLM message history per iteration, memory operation counts | Moderate |
| `FULL` | Everything in STANDARD plus: auto-export to `./traces/`, enriched tool I/O with parsed JSON arguments | Larger |

`CaptureMode.OFF` has zero performance impact beyond the base trace infrastructure that is always
active. `CaptureMode` is orthogonal to `verbose` (logging) and `traceExporter` (export
destination); all three can be combined independently.

---

## Activating CaptureMode

There are three ways to activate CaptureMode. They are resolved in the following order --
the first match wins:

### 1. Programmatic (highest priority)

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .captureMode(CaptureMode.FULL)
    .build();

EnsembleOutput output = ensemble.run();

// Export trace manually if needed
output.getTrace().toJson(Path.of("run-trace.json"));
```

### 2. JVM system property (zero code change)

```bash
java -Dagentensemble.captureMode=FULL -jar my-agent-app.jar
```

This activates `FULL` capture on every run without modifying application code. Useful for
debugging in staging or investigating a specific production incident.

### 3. Environment variable (zero code change)

```bash
AGENTENSEMBLE_CAPTURE_MODE=STANDARD java -jar my-agent-app.jar
```

Useful for containerised deployments where you want to toggle capture without rebuilding the image.

---

## What STANDARD captures

### Full LLM message history per iteration

At `STANDARD`, every `LlmInteraction` records the complete message list that was sent to the LLM
for that iteration. This is available via `LlmInteraction.getMessages()` as a
`List<CapturedMessage>`.

```java
ExecutionTrace trace = output.getTrace();
TaskTrace taskTrace = trace.getTaskTraces().get(0);

for (LlmInteraction interaction : taskTrace.getLlmInteractions()) {
    System.out.println("Iteration " + interaction.getIterationIndex());
    for (CapturedMessage msg : interaction.getMessages()) {
        System.out.printf("  [%s] %s%n", msg.getRole(),
            msg.getContent() != null ? msg.getContent().substring(0, Math.min(80, msg.getContent().length())) : "(tool calls)");
    }
}
```

`CapturedMessage` fields:

| Field | Populated for | Description |
|---|---|---|
| `role` | All messages | `"system"`, `"user"`, `"assistant"`, or `"tool"` |
| `content` | system, user, assistant (final answer), tool | The text content |
| `toolCalls` | assistant (tool call request) | List of `{name, arguments}` maps |
| `toolName` | tool (result) | Name of the tool that produced the result |

This enables replay of the exact conversation the LLM had, step by step.

### Memory operation counts wired

At `OFF`, `MemoryOperationCounts` on `TaskMetrics` always has zero values because the counters are
never incremented. At `STANDARD`, a `MemoryOperationListener` is wired into `MemoryContext` so
that every STM write, LTM store, LTM retrieval, and entity lookup is counted:

```java
TaskMetrics metrics = taskOutput.getMetrics();
MemoryOperationCounts memOps = metrics.getMemoryOperations();
System.out.println("STM writes:       " + memOps.getShortTermEntriesWritten());
System.out.println("LTM stores:       " + memOps.getLongTermStores());
System.out.println("LTM retrievals:   " + memOps.getLongTermRetrievals());
System.out.println("Entity lookups:   " + memOps.getEntityLookups());
System.out.println("Memory time:      " + metrics.getMemoryRetrievalTime());
```

Memory operations are only populated when memory is actually configured on the ensemble.

---

## What FULL adds

### Auto-export to `./traces/`

When `captureMode == FULL` and no explicit `traceExporter` has been registered, a
`JsonTraceExporter` writing to `./traces/` is automatically activated. Each run writes a file
named `traces/{ensembleId}.json`.

This is equivalent to:

```java
Ensemble.builder()
    .captureMode(CaptureMode.FULL)
    .traceExporter(new JsonTraceExporter(Path.of("./traces/")))
    .build();
```

You can provide an explicit `traceExporter` to write to a different location and still benefit
from the rest of FULL capture:

```java
Ensemble.builder()
    .captureMode(CaptureMode.FULL)
    .traceExporter(new JsonTraceExporter(Path.of("/var/log/agents/traces/")))
    .build();
```

### Enriched tool I/O

At `FULL`, each `ToolCallTrace` includes a `parsedInput` field -- the tool's JSON arguments
parsed into a `Map<String, Object>`:

```java
for (LlmInteraction interaction : taskTrace.getLlmInteractions()) {
    for (ToolCallTrace toolCall : interaction.getToolCalls()) {
        System.out.println("Tool: " + toolCall.getToolName());
        System.out.println("Arguments (raw): " + toolCall.getArguments());
        if (toolCall.getParsedInput() != null) {
            System.out.println("Arguments (parsed): " + toolCall.getParsedInput());
        }
    }
}
```

`parsedInput` is `null` at `OFF` and `STANDARD`. At `FULL`, it is `null` only when the arguments
string cannot be parsed as a JSON object (e.g., malformed input from the LLM).

---

## CaptureMode and the trace schema

The `ExecutionTrace` carries a `captureMode` field so trace consumers can determine what depth of
data to expect without inspecting individual interactions:

```java
ExecutionTrace trace = output.getTrace();
System.out.println("Schema version: " + trace.getSchemaVersion()); // "1.1"
System.out.println("Capture mode:   " + trace.getCaptureMode());   // OFF / STANDARD / FULL
```

---

## Performance notes

| Mode | Overhead vs base trace |
|---|---|
| `OFF` | None -- same as not having CaptureMode at all |
| `STANDARD` | Memory proportional to conversation length (one `CapturedMessage` per message per iteration). For agents with many tool calls, this can be significant. |
| `FULL` | STANDARD overhead plus JSON parsing of every tool argument string (fast, but non-zero for very high throughput scenarios). |

Document trade-offs in your team runbook when enabling STANDARD or FULL in production.

---

## Example: debugging a run without code changes

You have a deployed application and want to inspect what the LLM was sending and receiving during
a specific run:

```bash
# Start the app with FULL capture (auto-exports to ./traces/)
java -Dagentensemble.captureMode=FULL -jar my-agent-app.jar
```

After the run completes, open `traces/{run-id}.json` in any JSON viewer. The trace contains:
- The complete message history the LLM saw in each iteration
- Every tool call with its input (raw and parsed) and output
- Token counts and latency per LLM call
- Memory operation counts if memory is configured

No redeployment required.
