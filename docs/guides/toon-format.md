# TOON Context Format

AgentEnsemble can serialize structured data in LLM prompts using
[TOON](https://github.com/toon-format/spec) (Token-Oriented Object Notation)
instead of JSON, reducing token usage by **30-60%**.

---

## Why TOON?

Every time AgentEnsemble sends context to an LLM -- prior task outputs, tool
results, memory entries -- that data is serialized as text and counted against
the model's token limit. JSON is verbose: curly braces, quotes around every key,
commas, colons, and brackets all consume tokens with no semantic value.

TOON is a compact, human-readable format designed specifically for LLM contexts.
It combines YAML-like indentation with CSV-like tabular arrays:

**JSON** (47 tokens):
```json
{"items":[{"sku":"A1","qty":2,"price":9.99},{"sku":"B2","qty":1,"price":14.5}]}
```

**TOON** (19 tokens):
```yaml
items[2]{sku,qty,price}:
  A1,2,9.99
  B2,1,14.5
```

In multi-agent pipelines where structured data flows between tasks, the savings
compound across every context injection and tool call iteration.

---

## Setup

### 1. Add the JToon Dependency

TOON support requires the [JToon](https://github.com/toon-format/toon-java)
library on your runtime classpath. AgentEnsemble does not bundle it -- you opt in
by adding it to your project:

=== "Gradle (Kotlin DSL)"

    ```kotlin
    dependencies {
        implementation("dev.toonformat:jtoon:1.0.9")
    }
    ```

=== "Gradle (Groovy DSL)"

    ```gradle
    dependencies {
        implementation 'dev.toonformat:jtoon:1.0.9'
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>dev.toonformat</groupId>
        <artifactId>jtoon</artifactId>
        <version>1.0.9</version>
    </dependency>
    ```

### 2. Enable TOON on the Ensemble

```java
import net.agentensemble.format.ContextFormat;

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .contextFormat(ContextFormat.TOON)
    .task(researchTask)
    .task(writingTask)
    .build()
    .run();
```

That's it. All structured data flowing to the LLM will now use TOON instead of
JSON.

---

## What Gets Formatted

When `contextFormat` is set to `TOON`, the following data is serialized in TOON
format:

| Data | Where | Impact |
|---|---|---|
| Context from prior tasks | User prompt (context section) | Medium -- depends on task output size |
| Tool execution results | Tool result messages in conversation | High -- tool results are often large JSON payloads |
| Memory entries | User prompt (memory sections) | Medium -- structured memory content |
| Execution trace export | `ExecutionTrace.toToon()` | Low -- export only, not sent to LLM |

!!! note "Structured output schemas stay in JSON"
    When a task has an `outputType` configured, the JSON schema included in the
    prompt remains in JSON format. This is intentional -- the LLM needs to
    produce JSON output that the framework can parse, so the schema must be in
    the same format.

---

## Execution Trace Export

`ExecutionTrace` provides TOON export methods alongside the existing JSON ones:

```java
EnsembleOutput result = ensemble.run();

// JSON (always available)
result.getTrace().toJson(Path.of("trace.json"));

// TOON (requires JToon on classpath)
result.getTrace().toToon(Path.of("trace.toon"));

// As strings
String jsonTrace = result.getTrace().toJson();
String toonTrace = result.getTrace().toToon();
```

---

## Error Handling

If you set `contextFormat(ContextFormat.TOON)` but JToon is not on the classpath,
`Ensemble.build()` fails immediately with a `ValidationException`:

```
TOON context format requires the JToon library on the classpath.
Add to your build:
  Gradle: implementation("dev.toonformat:jtoon:1.0.9")
  Maven:  <dependency><groupId>dev.toonformat</groupId><artifactId>jtoon</artifactId><version>1.0.9</version></dependency>
```

Similarly, calling `ExecutionTrace.toToon()` without JToon throws an
`IllegalStateException` with the same dependency instructions.

---

## When to Use JSON vs TOON

| Scenario | Recommended Format |
|---|---|
| Long multi-agent pipelines with rich context | TOON -- savings compound across tasks |
| Tool-heavy workflows (many ReAct iterations) | TOON -- tool results dominate token usage |
| Structured output tasks | JSON schema stays in JSON regardless; context can be TOON |
| Debugging / human inspection of prompts | JSON -- more familiar for most developers |
| Maximum model compatibility | JSON -- all models handle JSON well; TOON is newer |
| Cost-sensitive production workloads | TOON -- 30-60% fewer tokens means 30-60% lower cost |

---

## Full Example

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.format.ContextFormat;

public class ToonFormatExample {

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        Task research = Task.builder()
            .description("Research the latest developments in {topic}")
            .expectedOutput("A structured summary with key findings and statistics")
            .build();

        Task analysis = Task.builder()
            .description("Analyze the research and identify the top 3 trends")
            .expectedOutput("A ranked list of trends with supporting evidence")
            .context(java.util.List.of(research))
            .build();

        Task report = Task.builder()
            .description("Write an executive summary of {topic} trends")
            .expectedOutput("A concise 500-word executive summary")
            .context(java.util.List.of(research, analysis))
            .build();

        EnsembleOutput result = Ensemble.builder()
            .chatLanguageModel(model)
            .contextFormat(ContextFormat.TOON)  // 30-60% fewer tokens
            .task(research)
            .task(analysis)
            .task(report)
            .input("topic", "generative AI")
            .verbose(true)
            .build()
            .run();

        System.out.println(result.getRaw());

        // Export trace in TOON format (also compact)
        result.getTrace().toToon(java.nio.file.Path.of("trace.toon"));
    }
}
```

See the [TOON Format example](../examples/toon-format.md) for a complete
walkthrough.