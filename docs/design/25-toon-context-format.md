# 25 -- TOON Context Format: Token-Efficient LLM Serialization

This document specifies how AgentEnsemble integrates the TOON (Token-Oriented
Object Notation) serialization format as a configurable alternative to JSON for
structured data sent to LLMs, reducing token usage by 30-60%.

TOON is an optional, user-activated feature. JSON remains the default for full
backward compatibility.

---

## 1. Motivation

AgentEnsemble sends structured data to LLMs in several places:

- **Prompt building**: Context from prior tasks, memory entries, and tool
  descriptions are serialized into prompts.
- **Tool output**: Tool execution results are returned as strings that the LLM
  processes in subsequent reasoning steps.
- **Task hand-offs**: In sequential/hierarchical workflows, one agent's output
  becomes another agent's context.
- **Trace export**: Execution traces are serialized for analysis and debugging.

All of these currently use JSON. JSON is verbose -- curly braces, quotes around
every key, commas, colons, and brackets consume tokens that carry no semantic
value for the LLM. In long multi-agent pipelines with rich context, this overhead
compounds quickly.

[TOON](https://github.com/toon-format/spec) (spec v3.0.3) is a compact,
human-readable serialization format designed specifically for LLM contexts. It
combines YAML-like indentation with CSV-like tabular arrays and achieves
**30-60% token reduction** versus JSON while remaining fully parseable.

JSON:
```json
{"items":[{"sku":"A1","qty":2,"price":9.99},{"sku":"B2","qty":1,"price":14.5}]}
```

TOON:
```
items[2]{sku,qty,price}:
  A1,2,9.99
  B2,1,14.5
```

[JToon](https://github.com/toon-format/toon-java) (`dev.toonformat:jtoon`) is
the Java implementation. It is MIT-licensed, available on Maven Central, requires
Java 17+, and supports Jackson annotations -- which AgentEnsemble already uses.

---

## 2. Design Goals

1. **Opt-in**: JSON remains the default. Users who want TOON add one dependency
   and one builder call.
2. **Fail-fast**: If `TOON` is selected but JToon is not on the classpath,
   `Ensemble.build()` fails immediately with a clear error message.
3. **Single configuration point**: One `contextFormat` field on
   `Ensemble.builder()` controls all serialization points. No per-task or
   per-tool configuration is needed (or exposed) in v1.
4. **Optional dependency**: JToon is `compileOnly` in `agentensemble-core`. Users
   add the runtime dependency themselves when they choose TOON.
5. **Extensible**: The `ContextFormat` enum and `ContextFormatter` interface allow
   future formats without changing the public API.

---

## 3. Concepts

| Term | Description |
|---|---|
| **ContextFormat** | Enum (`JSON`, `TOON`) selecting the serialization format for LLM-facing structured data. |
| **ContextFormatter** | Interface with `format(Object)` and `formatJson(String)` methods. |
| **JsonContextFormatter** | Built-in implementation wrapping Jackson `ObjectMapper`. Always available. |
| **ToonContextFormatter** | Implementation wrapping `JToon.encode()`. Loaded via reflection; requires JToon on classpath. |
| **ContextFormatters** | Factory class that resolves the correct `ContextFormatter` for a given `ContextFormat`. |

---

## 4. ContextFormat Enum

```java
package net.agentensemble.format;

/**
 * Serialization format for structured data included in LLM prompts.
 *
 * <p>JSON is the default and is always available. TOON provides 30-60%
 * token reduction but requires the {@code dev.toonformat:jtoon} library
 * on the classpath.
 */
public enum ContextFormat {

    /** Standard JSON serialization (default). Always available. */
    JSON,

    /**
     * TOON (Token-Oriented Object Notation) serialization.
     * Requires {@code dev.toonformat:jtoon} on the runtime classpath.
     */
    TOON
}
```

---

## 5. ContextFormatter Interface

```java
package net.agentensemble.format;

/**
 * Serializes structured data for inclusion in LLM prompts.
 *
 * <p>Implementations are obtained via {@link ContextFormatters#forFormat(ContextFormat)}.
 */
public interface ContextFormatter {

    /**
     * Serialize a Java object to the target format.
     *
     * @param value any Java object (Map, List, record, POJO, etc.)
     * @return formatted string; never null
     */
    String format(Object value);

    /**
     * Convert a JSON string to the target format.
     *
     * <p>For the JSON formatter this is a no-op (returns the input).
     * For TOON this parses the JSON and re-encodes it as TOON.
     *
     * @param json a valid JSON string
     * @return formatted string; never null
     */
    String formatJson(String json);
}
```

---

## 6. Implementations

### JsonContextFormatter

Always available. Uses the shared Jackson `ObjectMapper` for `format(Object)`.
`formatJson(String)` returns the input unchanged.

### ToonContextFormatter

Loaded via reflection to avoid a hard dependency on JToon. On first use,
`ContextFormatters` checks for `dev.toonformat.jtoon.JToon` on the classpath
and instantiates `ToonContextFormatter` via a private constructor.

`format(Object)` delegates to `JToon.encode(value)`.
`formatJson(String)` delegates to `JToon.encodeJson(json)`.

---

## 7. Ensemble Wiring

`Ensemble.builder()` gains a `contextFormat` field:

```java
Ensemble.builder()
    .chatLanguageModel(model)
    .contextFormat(ContextFormat.TOON)  // opt-in to TOON
    .task(task1)
    .task(task2)
    .build()
    .run();
```

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `contextFormat` | `ContextFormat` | No | `JSON` | Serialization format for structured data in LLM prompts. `TOON` requires `dev.toonformat:jtoon` on classpath. |

At `build()` time, if `contextFormat` is `TOON`, the builder verifies the JToon
class is loadable. If not, it throws a `ValidationException` with a clear message
including the Maven/Gradle coordinates to add.

The resolved `ContextFormatter` is stored in `ExecutionContext` and passed to all
components that serialize data for the LLM.

---

## 8. Integration Points

### 8.1 AgentPromptBuilder

The prompt builder receives the `ContextFormatter` from `ExecutionContext`. When
building the user prompt:

- **Context from previous tasks**: Task output strings that contain structured
  data are formatted via `contextFormatter.format(...)` before inclusion.
- **Memory entries**: Structured content from memory entries is formatted via the
  configured formatter.
- **Structured output schema**: JSON schema descriptions remain in JSON (the LLM
  needs to produce JSON for parsing), but context data around them uses the
  configured format.

### 8.2 AgentExecutor (Tool Output)

When a tool returns structured data (JSON), the executor can optionally reformat
it via `contextFormatter.formatJson(toolResultText)` before appending it to the
conversation as a `ToolExecutionResultMessage`.

This is the highest-impact integration point -- tool results often contain large
JSON payloads that consume significant context window space over multiple
iterations of the ReAct loop.

### 8.3 SequentialWorkflowExecutor (Task Hand-offs)

Task outputs passed as context to subsequent tasks flow through the prompt
builder (8.1), so no additional changes are needed in the workflow executor
itself.

### 8.4 ExecutionTrace

`ExecutionTrace` gains companion export methods:

```java
/** Serialize this trace to TOON format. */
public String toToon() { ... }

/** Write this trace to a file in TOON format. */
public void toToon(Path outputPath) { ... }
```

These methods use JToon directly and are only available when JToon is on the
classpath. They throw `IllegalStateException` with dependency instructions if
JToon is missing.

---

## 9. Dependency Configuration

### Version Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
jtoon = "1.0.9"

[libraries]
jtoon = { group = "dev.toonformat", name = "jtoon", version.ref = "jtoon" }
```

### Core Module (`agentensemble-core/build.gradle.kts`)

```kotlin
compileOnly(libs.jtoon)  // available at compile time, optional at runtime
```

### User's Build File

```kotlin
// Add to your project when using ContextFormat.TOON
implementation("dev.toonformat:jtoon:1.0.9")
```

---

## 10. Testing Strategy

| Level | What |
|---|---|
| **Unit** | `JsonContextFormatter` serializes objects to JSON. |
| **Unit** | `ToonContextFormatter` serializes objects to TOON (with JToon on test classpath). |
| **Unit** | `ContextFormatters.forFormat(TOON)` fails with clear message when JToon absent. |
| **Unit** | `ContextFormatters.forFormat(JSON)` always succeeds. |
| **Unit** | `AgentPromptBuilder` uses configured formatter for context sections. |
| **Unit** | `ExecutionTrace.toToon()` produces valid TOON output. |
| **Unit** | `ExecutionTrace.toToon()` throws clear error when JToon absent. |
| **Integration** | `Ensemble.builder().contextFormat(TOON)` with JToon present: full run succeeds. |
| **Integration** | `Ensemble.builder().contextFormat(TOON)` without JToon: fails at build time. |
| **Integration** | Sequential workflow with TOON: context from task 1 formatted as TOON in task 2 prompt. |

---

## 11. Future Considerations

- **Per-task format override**: Allow individual tasks to use a different format
  than the ensemble default (e.g., JSON for a task that needs exact JSON in its
  output, TOON for everything else).
- **TOON delimiter configuration**: Expose `EncodeOptions` (tab vs comma vs pipe
  delimiter) on the ensemble builder for users who want maximum token savings
  with tab delimiters.
- **Bidirectional support**: If LLMs improve at producing TOON output, structured
  output parsing could accept TOON in addition to JSON.
- **Token savings metrics**: Track and report actual token savings from TOON vs
  JSON in `ExecutionMetrics`.