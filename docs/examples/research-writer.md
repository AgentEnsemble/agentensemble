# Example: Research and Writer Pipeline

A classic two-agent sequential workflow where a researcher gathers information and a writer turns it into a blog post. This is the same pattern used by the included `ResearchWriterExample` application.

---

## What It Does

1. **Researcher** receives a topic and produces a structured research summary
2. **Writer** receives the topic and the researcher's summary, and produces a polished blog post

---

## Full Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

import java.util.List;
import java.util.Map;

public class ResearchWriterExample {

    public static void main(String[] args) {
        String topic = args.length > 0 ? String.join(" ", args) : "AI agents in enterprise software";

        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // 1. Define agents
        var researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Find accurate, well-structured information on any given topic")
            .background("You are a veteran researcher with expertise in technology and business. " +
                        "You write concise, factual summaries backed by evidence.")
            .llm(model)
            .build();

        var writer = Agent.builder()
            .role("Content Writer")
            .goal("Write engaging, well-structured blog posts from research material")
            .background("You are an experienced technology writer. You translate complex research " +
                        "into clear, engaging prose for a professional audience.")
            .llm(model)
            .responseFormat("Use markdown with clear headings (##) and bullet points where appropriate.")
            .build();

        // 2. Define tasks
        var researchTask = Task.builder()
            .description("Research the latest developments in {topic}. " +
                         "Cover current state, key players, recent breakthroughs, and near-term outlook.")
            .expectedOutput("A 400-500 word structured summary with clear sections: " +
                            "Current State, Key Players, Recent Developments, and Outlook.")
            .agent(researcher)
            .build();

        var writeTask = Task.builder()
            .description("Write a professional blog post about {topic} based on the provided research.")
            .expectedOutput("A 600-800 word blog post in markdown format. Include: an engaging title, " +
                            "an introduction, three main sections with subheadings, and a conclusion.")
            .agent(writer)
            .context(List.of(researchTask))  // writer receives the researcher's output
            .build();

        // 3. Build and run the ensemble
        EnsembleOutput output = Ensemble.builder()
            .agent(researcher)
            .agent(writer)
            .task(researchTask)
            .task(writeTask)
            .workflow(Workflow.SEQUENTIAL)
            .build()
            .run(Map.of("topic", topic));

        // 4. Display results
        System.out.println("=".repeat(60));
        System.out.println("FINAL BLOG POST");
        System.out.println("=".repeat(60));
        System.out.println(output.getRaw());
        System.out.println();
        System.out.printf("Completed in %s | Total tool calls: %d%n",
            output.getTotalDuration(), output.getTotalToolCalls());
    }
}
```

---

## Running the Included Example

```bash
git clone https://github.com/AgentEnsemble/agentensemble.git
cd agentensemble
export OPENAI_API_KEY=your-api-key

# Default topic (AI agents in enterprise software)
./gradlew :agentensemble-examples:run

# Custom topic
./gradlew :agentensemble-examples:run --args="quantum computing applications in finance"
```

---

## Variations

### Adding Web Search

Give the researcher a web search tool to find current information:

```java
var researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, current information using web search")
    .llm(model)
    .tools(List.of(new WebSearchTool(searchClient)))
    .maxIterations(10)
    .build();
```

### Using Memory

Add short-term memory so the writer automatically receives the researcher's output without explicit `context`:

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)  // no context() needed when using shortTerm memory
    .memory(EnsembleMemory.builder().shortTerm(true).build())
    .build()
    .run(Map.of("topic", topic));
```

### Adding an Editor

Add a third agent to review and polish the blog post:

```java
var editor = Agent.builder()
    .role("Senior Editor")
    .goal("Polish writing for clarity, flow, and accuracy")
    .llm(model)
    .build();

var editTask = Task.builder()
    .description("Review and polish the blog post about {topic}")
    .expectedOutput("The polished blog post with any corrections and improvements applied")
    .agent(editor)
    .context(List.of(writeTask))
    .build();

Ensemble.builder()
    .agent(researcher).agent(writer).agent(editor)
    .task(researchTask).task(writeTask).task(editTask)
    .build()
    .run(Map.of("topic", topic));
```
