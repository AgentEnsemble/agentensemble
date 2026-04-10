# Task Configuration Reference

All fields available on `Task.builder()`. Fields marked "Synthesis" are used to configure
the synthesized agent when no explicit `agent` is set.

## Convenience Factories

| Method | Description |
|---|---|
| `Task.of(String description)` | Creates a task with a default `expectedOutput`. Agent is synthesized at runtime. |
| `Task.of(String description, String expectedOutput)` | Creates a task with an explicit expected output. Agent is synthesized at runtime. |

---

## Builder Fields

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `name` | `String` | No | `null` | Optional logical name for this task. Used by the Ensemble Control API (Phase 2+) for Level 2 task-override matching and Level 3 `$name` context references. Also exposed via `GET /api/capabilities`. Names must be non-blank when set. |
| `description` | `String` | Yes | -- | What the agent should do. Supports `{variable}` template placeholders. |
| `expectedOutput` | `String` | Yes | `"Produce a complete and accurate response to the task."` (when using `Task.of(String)`) | What the output should look like. Included in the agent's prompt. Supports `{variable}` placeholders. |
| `agent` | `Agent` | No | `null` | Explicit agent assigned to this task. When null, an agent is synthesized from the description using the ensemble's `AgentSynthesizer`. |
| `chatLanguageModel` | `ChatModel` | No | `null` | Per-task LLM override (Synthesis). When set and no explicit agent is provided, this model is used for the synthesized agent, taking precedence over the ensemble-level `chatLanguageModel`. |
| `tools` | `List<Object>` | No | `[]` | Tools available to the synthesized agent (Synthesis). Each entry must be an `AgentTool` or an object with `@Tool`-annotated methods. When an explicit agent is set, configure tools on the `Agent` builder instead. |
| `maxIterations` | `Integer` | No | `null` | Max tool-call iterations for the synthesized agent (Synthesis). `null` uses the default (25). Must be `> 0` when set. When an explicit agent is set, configure `maxIterations` on the `Agent` builder instead. |
| `context` | `List<Task>` | No | `[]` | Prior tasks whose outputs are injected into this task's prompt. Sequential workflow enforces ordering; parallel and hierarchical do not. |
| `outputType` | `Class<?>` | No | `null` | Java class to deserialize the agent's output into. When set, the agent is prompted for JSON matching the schema. Supported: records, POJOs, `Map<K,V>`, enums, `List<T>`, scalar wrappers. Unsupported: primitives, `void`, top-level arrays. |
| `maxOutputRetries` | `int` | No | `3` | Number of retry attempts if structured output parsing fails. `0` disables retries. Only meaningful when `outputType` is set. |
| `inputGuardrails` | `List<InputGuardrail>` | No | `[]` | Validation hooks that run before the LLM call. Each guardrail receives a `GuardrailInput` and returns `GuardrailResult.success()` or `GuardrailResult.failure(reason)`. The first failure throws `GuardrailViolationException` and prevents any LLM call. |
| `outputGuardrails` | `List<OutputGuardrail>` | No | `[]` | Validation hooks that run after the agent produces a response. Each guardrail receives a `GuardrailOutput` (with raw text and optionally the parsed object). The first failure throws `GuardrailViolationException`. |
| `memoryScopes` | `List<MemoryScope>` | No | `[]` | Named memory scopes this task reads from and writes to. Requires `Ensemble.builder().memoryStore(MemoryStore)`. Declare with `.memory(String)`, `.memory(String...)`, or `.memory(MemoryScope)` on the builder. At task startup entries are retrieved from each scope and injected into the prompt; at completion the output is stored into each scope. See [Memory guide](../guides/memory.md). |
| `review` | `Review` | No | `null` | After-execution review gate configuration. When set, a review gate fires after the agent completes this task. Use `Review.required()` to always fire, `Review.skip()` to suppress even when the ensemble policy would fire, or `Review.builder()` for custom timeout / on-timeout action. Requires `Ensemble.builder().reviewHandler(...)`. See [Review guide](../guides/review.md). |
| `beforeReview` | `Review` | No | `null` | Before-execution review gate configuration. When set, a review gate fires before the agent begins executing. `ReviewDecision.Edit` is treated as Continue (no output exists yet). Requires `Ensemble.builder().reviewHandler(...)`. See [Review guide](../guides/review.md). |

---

## Validation

Applied at `build()` time:

- `description` must not be null or blank
- `expectedOutput` must not be null or blank
- `agent` is optional (null is valid in v2)
- `maxIterations` must be `> 0` when set (null means "use default")
- `tools`: each element must be an `AgentTool` or have `@Tool`-annotated methods
- `outputType` must not be a primitive, `void`, or a top-level array type (when set)
- `maxOutputRetries` must be `>= 0`

At `Ensemble.run()` time:

- All context tasks must appear earlier in the ensemble's task list (sequential workflow only)
- No circular context dependencies
- Each task must have an LLM source: explicit `agent`, task-level `chatLanguageModel`, or ensemble-level `chatLanguageModel`

---

## LLM Resolution (when no explicit agent)

The LLM for a synthesized agent is resolved in this order:

1. `task.chatLanguageModel` (if set)
2. `ensemble.chatLanguageModel` (if set)
3. `ValidationException` if neither is available

---

## Template Variables

Both `description` and `expectedOutput` support `{variable}` substitution, resolved at
`ensemble.run(Map<String, String> inputs)` time.

Use `{{variable}}` to include a literal `{variable}` without substitution.

---

## Examples

**Zero-ceremony:**

```java
Task task = Task.of("Research the latest developments in AI agents");
```

**With per-task LLM and tools:**

```java
Task task = Task.builder()
    .description("Research {topic} using web search")
    .expectedOutput("A 400-word summary")
    .chatLanguageModel(gpt4oMiniModel)
    .tools(List.of(new WebSearchTool()))
    .maxIterations(15)
    .build();
```

**With explicit agent (power-user):**

```java
Task task = Task.builder()
    .description("Research the competitive landscape for {company}")
    .expectedOutput("A structured competitive analysis")
    .agent(researcher)
    .context(List.of(priorTask))
    .outputType(CompetitiveReport.class)
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
