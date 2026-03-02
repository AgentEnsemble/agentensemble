# Task Configuration Reference

All fields available on `Task.builder()`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `description` | `String` | Yes | -- | What the agent should do. Supports `{variable}` template placeholders. |
| `expectedOutput` | `String` | Yes | -- | What the output should look like. Quality guidance for the agent. Supports `{variable}` placeholders. |
| `agent` | `Agent` | Yes | -- | The agent assigned to execute this task. |
| `context` | `List<Task>` | No | `[]` | Prior tasks whose outputs are injected into this task's agent prompt. Sequential workflow only. |

---

## Validation

The following validations are applied at `build()` time:

- `description` must not be null or blank
- `expectedOutput` must not be null or blank
- `agent` must not be null

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
