# Task Configuration Reference

All fields available on `Task.builder()`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `description` | `String` | Yes | -- | What the agent should do. Supports `{variable}` template placeholders. |
| `expectedOutput` | `String` | Yes | -- | What the output should look like. Quality guidance for the agent. Supports `{variable}` placeholders. |
| `agent` | `Agent` | Yes | -- | The agent assigned to execute this task. |
| `context` | `List<Task>` | No | `[]` | Prior tasks whose outputs are injected into this task's agent prompt. Sequential workflow only. |
| `outputType` | `Class<?>` | No | `null` | Java class to deserialize the agent's output into. When set, the agent is prompted for JSON and the result is parsed automatically. Supported: records, POJOs, `Map<K,V>`, enums, `List<T>`, scalar wrappers (`Boolean`, `Integer`, `Long`, `Double`). Unsupported: primitives, `void`, top-level arrays. |
| `maxOutputRetries` | `int` | No | `3` | Number of retry attempts if structured output parsing fails. `0` disables retries. Only meaningful when `outputType` is set. |
| `inputGuardrails` | `List<InputGuardrail>` | No | `[]` | Validation hooks that run before the LLM call. Each guardrail receives a `GuardrailInput` and returns `GuardrailResult.success()` or `GuardrailResult.failure(reason)`. The first failure throws `GuardrailViolationException` and prevents any LLM call. |
| `outputGuardrails` | `List<OutputGuardrail>` | No | `[]` | Validation hooks that run after the agent produces a response. Each guardrail receives a `GuardrailOutput` (with raw text and optionally the parsed object). The first failure throws `GuardrailViolationException`. |
| `memoryScopes` | `List<MemoryScope>` | No | `[]` | Named memory scopes this task reads from and writes to. Requires `Ensemble.builder().memoryStore(MemoryStore)`. Declare with `.memory(String)`, `.memory(String...)`, or `.memory(MemoryScope)` on the builder. At task startup entries are retrieved from each scope and injected into the prompt; at completion the output is stored into each scope. See [Memory guide](../guides/memory.md). |

---

## Validation

The following validations are applied at `build()` time:

- `description` must not be null or blank
- `expectedOutput` must not be null or blank
- `agent` must not be null
- `outputType` must not be a primitive, `void`, or a top-level array type (when set)
- `maxOutputRetries` must be `>= 0`
- `inputGuardrails` defaults to an empty immutable list (no-op)
- `outputGuardrails` defaults to an empty immutable list (no-op)

At `Ensemble.run()` time:
- All context tasks must appear earlier in the ensemble's task list (sequential workflow)
- No circular context dependencies
- The assigned agent must be registered with the ensemble

---

## Template Variables

Both `description` and `expectedOutput` support `{variable}` substitution. Variables are resolved by calling `ensemble.run(Map<String, String> inputs)`.

Use `{{variable}}` to include a literal `{variable}` in the text without substitution.

See the [Template Variables guide](../guides/template-variables.md).

---

## Context Injection

Context task outputs are injected into the agent's user prompt as a "Context from prior tasks" section. The agent sees the task description and the full raw output of each context task.

When short-term memory is enabled, the `context` field is not required -- all prior outputs within the run are automatically injected.

---

## Example

```java
var researchTask = Task.builder()
    .description("Research the competitive landscape for {company} in the {industry} sector")
    .expectedOutput("A structured competitive analysis with three to five key competitors, " +
                    "their strengths, weaknesses, and market position")
    .agent(researcher)
    .build();

var strategyTask = Task.builder()
    .description("Develop a market entry strategy for {company} based on the competitive research")
    .expectedOutput("A 500-word strategic recommendation with three concrete action items")
    .agent(strategist)
    .context(List.of(researchTask))
    .build();
```

---

## Immutability

`Task` is an immutable value object. Use `toBuilder()` to create modified copies:

```java
Task modifiedTask = task.toBuilder()
    .description("Research {topic} with a focus on regulatory implications")
    .build();
```
