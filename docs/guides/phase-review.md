# Phase Review and Retry

Phase review lets you attach a quality gate to any phase in your pipeline. After the
phase's tasks complete, a review task evaluates the output and decides whether to accept
it, request a retry with feedback, or reject the phase entirely.

The reviewer is just a `Task` — AI-powered, deterministic, or human — using the same
infrastructure as every other task in the framework.

---

## Quick start

```java
// 1. Define the work tasks
Task gatherTask = Task.builder()
    .description("Gather research data on the topic")
    .expectedOutput("Research findings")
    .build();

Task summarizeTask = Task.builder()
    .description("Summarize the gathered research")
    .expectedOutput("Research summary")
    .context(List.of(gatherTask))
    .build();

// 2. Define the review task
//    Use .context() to reference the phase tasks so the reviewer can read their output.
Task reviewTask = Task.builder()
    .description("Evaluate the research summary. "
        + "If sufficient, respond with: APPROVE\n"
        + "If insufficient, respond with: RETRY: <specific feedback>")
    .context(List.of(summarizeTask))   // gives the reviewer access to the phase output
    .build();

// 3. Attach PhaseReview to the phase
Phase research = Phase.builder()
    .name("research")
    .task(gatherTask)
    .task(summarizeTask)
    .workflow(Workflow.PARALLEL)
    .review(PhaseReview.of(reviewTask))   // maxRetries defaults to 2
    .build();

Phase writing = Phase.builder()
    .name("writing")
    .after(research)   // only starts after research is approved
    .task(draftTask)
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(research)
    .phase(writing)
    .build()
    .run();
```

If the reviewer returns `RETRY: Need more depth`, the framework:

1. Re-runs the research phase with the feedback injected into each task prompt.
2. Re-runs the review task.
3. Repeats until approved or `maxRetries` is exhausted (the last output is accepted).

---

## How the review task reads phase outputs

The review task is a standard task. To access the outputs of the reviewed phase's tasks,
declare them in the review task's `.context()` list. The framework makes all prior
phase task outputs available as context for the review task, including outputs from retried
attempts (both original and rebuilt task object identities are mapped, so context resolution
works correctly across retries).

```java
Task gatherTask  = Task.builder().description("Gather data")...build();
Task analyzeTask = Task.builder().description("Analyze data")
    .context(List.of(gatherTask)).build();

// Review task declares context to read the phase outputs
Task reviewTask = Task.builder()
    .description("Quality gate")
    .context(List.of(gatherTask, analyzeTask))   // read both task outputs
    .handler(ctx -> {
        // ctx.contextOutputs() contains gatherTask and analyzeTask outputs in order
        String analysisOutput = ctx.contextOutputs().getLast().getRaw();
        if (analysisOutput.length() < 300) {
            return ToolResult.success(
                PhaseReviewDecision.retry("Analysis too brief. Expand all sections.").toText());
        }
        return ToolResult.success(PhaseReviewDecision.approve().toText());
    })
    .build();
```

When only the final phase output matters, reference only the last task:

```java
Task reviewTask = Task.builder()
    .description("Quality gate")
    .context(List.of(summarizeTask))   // only need the summary
    .handler(ctx -> {
        String output = ctx.contextOutputs().getFirst().getRaw();
        // evaluate output...
    })
    .build();
```

---

## Three reviewer types

### AI reviewer

Use a task with an LLM-backed agent. Declare `.context()` so the LLM sees the phase
output in its `## Context from Previous Tasks` prompt section. Instruct the LLM on the
expected response format:

```java
Task reviewTask = Task.builder()
    .description("Evaluate the research summary below.\n\n"
        + "Criteria:\n"
        + "- At least 5 distinct sources cited\n"
        + "- Quantitative data for every major claim\n\n"
        + "If ALL criteria are met, respond with exactly: APPROVE\n"
        + "Otherwise, respond with: RETRY: <specific actionable feedback>")
    .context(List.of(summarizeTask))   // LLM sees the summary in its prompt
    .build();
```

The LLM receives the phase output as a prior-task context section and evaluates it
against the stated criteria. Its response (`APPROVE` or `RETRY: <feedback>`) is parsed
into a `PhaseReviewDecision`.

### Deterministic reviewer

Use a task with a `handler` for programmatic quality checks. Declare `.context()` to
access the phase outputs inside the handler:

```java
Task reviewTask = Task.builder()
    .description("Quality gate")
    .context(List.of(summarizeTask))   // provides output via ctx.contextOutputs()
    .handler(ctx -> {
        String output = ctx.contextOutputs().getFirst().getRaw();

        if (output.length() < 500) {
            return ToolResult.success(
                PhaseReviewDecision.retry("Output too short. Expand each section.").toText());
        }
        if (!output.contains("source") && !output.contains("reference")) {
            return ToolResult.success(
                PhaseReviewDecision.retry("No sources cited. Add at least 3.").toText());
        }
        return ToolResult.success(PhaseReviewDecision.approve().toText());
    })
    .build();
```

### Human reviewer

Use a task with a `Review` gate that pauses for console input. Declare `.context()` and
echo the output so the human can read it before deciding:

```java
Task reviewTask = Task.builder()
    .description("Review the research output below and decide on quality.")
    .context(List.of(summarizeTask))
    .handler(ctx -> {
        // Echo the phase output for the human to see during the review gate
        String output = ctx.contextOutputs().getFirst().getRaw();
        return ToolResult.success(output);
    })
    .review(Review.required(
        "Type APPROVE, RETRY: <feedback>, or REJECT: <reason>"))
    .build();
```

The human sees the phase output displayed in the console review gate, then types their
decision. The typed response is parsed as a `PhaseReviewDecision`.

---

## Feedback injection

When a retry is requested, the reviewer's feedback text is injected into every task in
the phase as a `## Revision Instructions` section in the LLM prompt, **before** the
`## Task` section:

```
## Revision Instructions (Attempt 2)
This task is being re-executed based on reviewer feedback.
Incorporate the feedback below into your response.

### Feedback
Need more depth on quantum computing applications. Include at least 3 peer-reviewed sources.

### Previous Output
[the raw output from the prior attempt]

## Task
Research the latest developments in quantum computing...
```

The original task description is unchanged. The LLM sees the feedback and its prior
output, enabling targeted improvement. Deterministic handler tasks receive the feedback
in the task prompt (visible in logs) but not in `ctx.description()` — for deterministic
tasks the handler makes its own decisions programmatically.

---

## What is and isn't controllable in the feedback prompt

### Controllable: the feedback content

The **text inside `### Feedback`** is 100% controlled by the reviewer — it is exactly
the string the review task returns after `RETRY:`:

```java
// Whatever you write here becomes the ### Feedback content
PhaseReviewDecision.retry("Need more depth on section 3. Add quantitative data.").toText()
// -> "RETRY: Need more depth on section 3. Add quantitative data."
```

You can write short one-liners or structured multi-point instructions:

```java
PhaseReviewDecision.retry("""
    The output is missing two key elements:
    1. Quantitative data -- add numbers/percentages for every major claim.
    2. Source citations -- cite at least 3 peer-reviewed papers.
    Keep the structure otherwise intact.
""").toText()
```

### Fixed: the prompt structure

The surrounding structure is determined by the framework:

| Element | Value | Controllable? |
|---|---|---|
| Section header | `## Revision Instructions (Attempt N)` | No |
| Preamble | "This task is being re-executed based on reviewer feedback. Incorporate the feedback below into your response." | No |
| Feedback label | `### Feedback` | No |
| Feedback content | _whatever the reviewer returns_ | **Yes** |
| Prior output label | `### Previous Output` | No |
| Prior output content | The task's raw output from the previous attempt | No |

### Full control via feedback text

If you need to change the framing (e.g. different tone or instructions for the LLM),
embed your custom instruction directly at the start of the feedback text:

```java
PhaseReviewDecision.retry("""
    IMPORTANT: Discard your previous approach entirely.

    The task requires a completely different structure:
    - Start with an executive summary (2 sentences)
    - Follow with detailed sections for each sub-topic
    - End with a bullet-point action list

    Specific gaps to address: the current output lacks quantitative data.
""").toText()
```

The LLM receives this text verbatim under `### Feedback` and will incorporate it.

---

## Retry limits

By default, a phase is allowed up to 2 self-retries (`maxRetries = 2`), meaning 3 total
attempts. When the limit is exhausted, the last output is accepted and the pipeline
continues.

```java
PhaseReview.builder()
    .task(reviewTask)
    .maxRetries(3)              // up to 3 retries (4 total attempts)
    .build();
```

To reject the phase and stop the pipeline when quality cannot be achieved, use `REJECT`:

```java
// In the review task handler:
if (criticalFailure) {
    return ToolResult.success(
        PhaseReviewDecision.reject("Data is corrupted. Pipeline cannot continue.").toText());
}
```

A rejection throws a `TaskExecutionException` and skips all downstream phases.

---

## Predecessor retry

A phase can request that a **direct predecessor** be re-run when it discovers the
predecessor's output was insufficient:

```java
// Writing review: if research was lacking, request research redo.
// Declare context on the review task to see the draft and evaluate research quality.
Task writingReviewTask = Task.builder()
    .description("Evaluate the draft. "
        + "If the research backing is weak or missing quantitative data, respond with:\n"
        + "RETRY_PREDECESSOR research: <feedback for the research phase>\n"
        + "If the draft quality is acceptable, respond with: APPROVE")
    .context(List.of(draftTask))   // read the draft to evaluate research backing
    .build();

Phase writing = Phase.builder()
    .name("writing")
    .after(research)
    .task(draftTask)
    .review(PhaseReview.builder()
        .task(writingReviewTask)
        .maxRetries(2)
        .maxPredecessorRetries(1)    // research can be retried once
        .build())
    .build();
```

When `RETRY_PREDECESSOR research: <feedback>` is returned:

1. The research phase is re-run with the feedback injected into its tasks.
2. The writing phase is re-run with the updated research outputs.
3. The writing review fires again.

The predecessor must be a **direct predecessor** listed in the phase's `.after()`. If the
named phase is not a direct predecessor, the decision is treated as `APPROVE`.

---

## Decision format reference

| Output text | Decision |
|---|---|
| `APPROVE` | Accept output, proceed |
| `RETRY: <feedback>` | Retry this phase with feedback |
| `RETRY_PREDECESSOR <name>: <feedback>` | Retry the named predecessor, then retry this phase |
| `REJECT: <reason>` | Fail this phase and stop downstream phases |

Parsing is case-insensitive. Unrecognised text is treated as `APPROVE`.
The colon split for `RETRY` and `REJECT` is on the **first** colon only, so feedback
text may contain additional colons (`RETRY: issue: too brief` → feedback = `issue: too brief`).

---

## Builder reference

```java
// Minimal (task + defaults)
PhaseReview.of(reviewTask)

// With custom max retries
PhaseReview.of(reviewTask, 3)

// Full control
PhaseReview.builder()
    .task(reviewTask)
    .maxRetries(3)
    .maxPredecessorRetries(2)
    .build()
```

| Field | Default | Description |
|---|---|---|
| `task` | required | The review task; use `.context()` to access phase outputs |
| `maxRetries` | 2 | Maximum self-retries (0 = review once, no retries) |
| `maxPredecessorRetries` | 2 | Maximum predecessor retries per predecessor |
