# Example: Structured Output

This example shows two approaches to controlling agent output format:

1. **Typed output with a Java record** -- the agent produces JSON that is automatically parsed into a strongly-typed object.
2. **Formatted text (Markdown)** -- the agent produces well-formatted prose using `expectedOutput` and `responseFormat` instructions, with no parsing required.

---

## Example 1: Typed JSON Output

Use `outputType` when you need the agent's output as a structured Java object -- for downstream processing, serialization, validation, or API responses.

### What It Does

1. **Researcher** produces a structured report (title, list of findings, conclusion)
2. The framework parses the agent's JSON into a `ResearchReport` record
3. The caller accesses individual fields via `getParsedOutput(ResearchReport.class)`

### Full Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;

import java.util.List;

public class StructuredOutputExample {

    record ResearchReport(String title, List<String> findings, String conclusion) {}

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        var researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Find accurate, well-structured information on any given topic")
            .llm(model)
            .build();

        var researchTask = Task.builder()
            .description("Research the most important developments in AI agents in 2025")
            .expectedOutput("A structured report with a title, a list of key findings, and a conclusion")
            .agent(researcher)
            .outputType(ResearchReport.class)  // instruct the agent to produce JSON
            .maxOutputRetries(3)               // retry up to 3 times if JSON is invalid (default)
            .build();

        EnsembleOutput output = Ensemble.builder()
            .agent(researcher)
            .task(researchTask)
            .build()
            .run();

        TaskOutput taskOutput = output.getTaskOutputs().get(0);

        // Raw text is always available
        System.out.println("Raw: " + taskOutput.getRaw());

        // Typed access to the parsed object
        ResearchReport report = taskOutput.getParsedOutput(ResearchReport.class);
        System.out.println("Title: " + report.title());
        System.out.println("Findings:");
        report.findings().forEach(f -> System.out.println("  - " + f));
        System.out.println("Conclusion: " + report.conclusion());
    }
}
```

### How It Works

When the task has `outputType` set:

1. The agent's user prompt gains an `## Output Format` section containing the JSON schema derived from `ResearchReport`.
2. After the agent produces its response, the framework extracts JSON from the raw text (handling prose, markdown fences, etc.).
3. The JSON is deserialized into `ResearchReport` using Jackson.
4. If parsing fails, a correction prompt is sent to the agent showing the error and asking it to try again (up to `maxOutputRetries` times).
5. If all retries are exhausted, `OutputParsingException` is thrown with the full error history.

### Supported Types

| Type | Schema Example |
|---|---|
| `String` | `"string"` |
| `int`, `long`, `Integer`, `Long` | `"integer"` |
| `double`, `float`, `Double`, `Float` | `"number"` |
| `boolean`, `Boolean` | `"boolean"` |
| `List<String>` | `["string"]` |
| `List<MyRecord>` | `[{...nested schema...}]` |
| `Map<String, String>` | `{"string": "string"}` |
| Enum | `"enum: VALUE1, VALUE2"` |
| Nested record/POJO | Inlined nested object |

---

## Example 2: Formatted Markdown Output

Use `expectedOutput` and `Agent.responseFormat` when you need well-structured prose (Markdown, bullet points, prose sections) without needing to parse the result into Java objects.

### What It Does

1. **Researcher** produces a research summary in plain text
2. **Writer** produces a polished Markdown blog post incorporating the research

### Full Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

import java.util.List;
import java.util.Map;

public class MarkdownOutputExample {

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // Researcher produces plain-text findings
        var researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Find accurate, well-sourced information on any given topic")
            .llm(model)
            .build();

        // Writer produces Markdown -- responseFormat enforces the format
        var writer = Agent.builder()
            .role("Content Writer")
            .goal("Write engaging, well-structured blog posts from research notes")
            .responseFormat(
                "Always format your response in Markdown. " +
                "Include a title (# heading), an introduction paragraph, " +
                "three sections with subheadings (## heading), and a conclusion.")
            .llm(model)
            .build();

        var researchTask = Task.builder()
            .description("Research the latest developments in {topic}")
            .expectedOutput("A factual summary of key developments, major players, and future outlook")
            .agent(researcher)
            .build();

        var writeTask = Task.builder()
            .description("Write a 700-word blog post about {topic} based on the research provided")
            .expectedOutput(
                "A 700-word blog post in Markdown format with: " +
                "an engaging title, introduction, three sections with subheadings, and a conclusion")
            .agent(writer)
            .context(List.of(researchTask))  // writer receives researcher's output
            .build();

        EnsembleOutput output = Ensemble.builder()
            .agent(researcher)
            .agent(writer)
            .task(researchTask)
            .task(writeTask)
            .workflow(Workflow.SEQUENTIAL)
            .build()
            .run(Map.of("topic", "AI agents in enterprise software"));

        // The final output is the writer's Markdown blog post
        System.out.println(output.getRaw());

        // Access individual task outputs
        for (TaskOutput t : output.getTaskOutputs()) {
            System.out.printf("[%s] completed in %s%n",
                t.getAgentRole(), t.getDuration());
        }
    }
}
```

### Key Points

- `responseFormat` on `Agent` appends formatting instructions to the system prompt, guiding the LLM's output style across all tasks assigned to that agent.
- `expectedOutput` on `Task` provides task-specific quality guidance, describing length, structure, and content requirements.
- The `raw` field of `TaskOutput` always contains the complete agent response as a string.

---

## Combining Both Approaches

You can mix structured and plain-text tasks in the same ensemble:

```java
// Task 1: structured output -- the researcher produces a parsed Java object
var researchTask = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A structured report")
    .agent(researcher)
    .outputType(ResearchReport.class)
    .build();

// Task 2: Markdown output -- the writer uses the research to produce prose
var writeTask = Task.builder()
    .description("Write a blog post based on the research")
    .expectedOutput("A 700-word blog post in Markdown")
    .agent(writer)
    .context(List.of(researchTask))  // receives the raw JSON as context
    // no outputType -- plain text result
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher).agent(writer)
    .task(researchTask).task(writeTask)
    .build()
    .run();

// Access the structured output from task 1
ResearchReport report = output.getTaskOutputs().get(0).getParsedOutput(ResearchReport.class);

// Access the Markdown from task 2
String blogPost = output.getTaskOutputs().get(1).getRaw();
```
