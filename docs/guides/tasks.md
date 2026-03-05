# Tasks

A task is a unit of work, optionally assigned to an explicit agent. When no agent is
declared, the framework synthesizes one automatically from the task description.

---

## Zero-ceremony (recommended)

The simplest way to create a task requires only a description:

```java
// Agent synthesized automatically from the description
Task task = Task.of("Research the latest developments in quantum computing");
```

Or with a custom expected output:

```java
Task task = Task.of(
    "Research the latest developments in quantum computing",
    "A 400-word summary covering key breakthroughs and near-term outlook");
```

Run immediately:

```java
EnsembleOutput result = Ensemble.run(model, task);
```

---

## Full builder

For fine-grained control, use the builder:

```java
Task task = Task.builder()
    .description("Analyse the sales data for {region} in {year}")
    .expectedOutput("A structured analysis listing three growth drivers with evidence")
    .chatLanguageModel(model)                    // optional: per-task LLM
    .tools(List.of(new CalculatorTool()))        // optional: per-task tools
    .maxIterations(20)                           // optional: per-task iteration cap
    .build();
```

---

## Description

The `description` field tells the agent what to do. It supports `{variable}` placeholders
resolved at `ensemble.run(Map)` time:

```java
Task task = Task.builder()
    .description("Analyse the sales data for {region} in {year} and identify the top three growth drivers")
    .expectedOutput("A structured analysis listing three growth drivers with supporting evidence")
    .chatLanguageModel(model)
    .build();
```

Resolved at run time:

```java
ensemble.run(Map.of("region", "EMEA", "year", "2025"));
```

---

## Expected Output

The `expectedOutput` field describes the desired output format and is included in the
agent's prompt:

```java
Task task = Task.builder()
    .description("Summarise the key findings from the attached research")
    .expectedOutput("A 200-word executive summary with three bullet points for key findings and one recommendation")
    .chatLanguageModel(model)
    .build();
```

When using `Task.of(String)`, a sensible default is applied automatically.

---

## Explicit Agent (power-user)

For full control over the agent persona, bind an explicit `Agent`:

```java
Agent researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, well-structured information on any topic")
    .background("You are a veteran researcher with 15 years of experience.")
    .llm(model)
    .build();

Task task = Task.builder()
    .description("Research quantum computing breakthroughs in 2025")
    .expectedOutput("A 400-word summary")
    .agent(researcher)   // explicit: disables synthesis for this task
    .build();
```

When an explicit agent is set, `chatLanguageModel`, `tools`, and `maxIterations` on the
task are stored but not applied to the agent (configure those fields on the `Agent` builder
instead).

---

## Per-task LLM

Use a different LLM for specific tasks without declaring an explicit agent:

```java
Task cheapTask = Task.builder()
    .description("Summarize this paragraph")
    .expectedOutput("A two-sentence summary")
    .chatLanguageModel(gpt4oMiniModel)   // cheaper model for simple tasks
    .build();

Task thoroughTask = Task.builder()
    .description("Analyse this complex financial model")
    .expectedOutput("A detailed analysis with risk assessment")
    .chatLanguageModel(gpt4oModel)       // powerful model for complex tasks
    .build();
```

When set, the task-level LLM takes precedence over the ensemble-level `chatLanguageModel`.

---

## Per-task Tools

Declare tools directly on the task. The synthesized agent receives these tools:

```java
Task task = Task.builder()
    .description("Research AI trends using web search and calculate the growth rate")
    .expectedOutput("A report with trend data and calculated figures")
    .chatLanguageModel(model)
    .tools(List.of(new WebSearchTool(), new CalculatorTool()))
    .build();
```

---

## Per-task maxIterations

Cap the number of tool-call iterations for a specific task:

```java
Task task = Task.builder()
    .description("Find the top 5 AI papers published this week")
    .expectedOutput("A list of 5 paper titles with one-sentence summaries")
    .chatLanguageModel(model)
    .tools(List.of(new WebSearchTool()))
    .maxIterations(10)   // stop after 10 tool calls
    .build();
```

Default: `25` (inherited from Agent when synthesized).

---

## Context from Previous Tasks

A task can reference prior task outputs as context using the `context` field:

```java
Task researchTask = Task.builder()
    .description("Research the latest AI breakthroughs")
    .expectedOutput("A list of key findings")
    .chatLanguageModel(model)
    .build();

Task writeTask = Task.builder()
    .description("Write a blog post based on the AI research")
    .expectedOutput("A 600-word blog post")
    .chatLanguageModel(model)
    .context(List.of(researchTask))   // depends on researchTask
    .build();
```

Context is injected into the writing agent's user prompt as prior task outputs.

---

## Structured Output

Request typed output by setting `outputType`:

```java
record ResearchReport(String title, List<String> findings, String conclusion) {}

Task task = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A structured research report")
    .chatLanguageModel(model)
    .outputType(ResearchReport.class)
    .build();

EnsembleOutput result = Ensemble.run(model, task);
ResearchReport report = result.getTaskOutputs().get(0).getParsedOutput(ResearchReport.class);
```

---

## Guardrails

Input guardrails run before the LLM call; output guardrails run after:

```java
Task task = Task.builder()
    .description("Summarize the customer feedback")
    .expectedOutput("A concise summary")
    .chatLanguageModel(model)
    .inputGuardrails(List.of(input -> {
        if (input.taskDescription().length() < 10)
            return GuardrailResult.failure("Task description too short");
        return GuardrailResult.success();
    }))
    .outputGuardrails(List.of(output -> {
        if (output.rawOutput().contains("offensive"))
            return GuardrailResult.failure("Output contains inappropriate content");
        return GuardrailResult.success();
    }))
    .build();
```
