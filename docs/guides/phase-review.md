# Phase Review and Retry

Phase review lets you attach a quality gate to any phase in your pipeline. After the
phase's tasks complete, a review task evaluates the output and decides whether to accept
it, request a retry with feedback, or reject the phase entirely.

The reviewer is just a `Task` — AI-powered, deterministic, or human — using the same
infrastructure as every other task in the framework.

---

## Quick start

```java
// 1. Define the review task
Task reviewTask = Task.builder()
    .description("Evaluate the research output. "
        + "If sufficient, respond with: APPROVE\n"
        + "If insufficient, respond with: RETRY: <specific feedback>")
    .build();

// 2. Attach a PhaseReview to the phase
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

## Three reviewer types

### AI reviewer

Use a task with an LLM-backed agent. Instruct the LLM on the expected output format in
the task description:

```java
Task reviewTask = Task.builder()
    .description("Evaluate the research output.\n"
        + "Criteria:\n"
        + "- At least 5 distinct sources cited\n"
        + "- Quantitative data for every major claim\n\n"
        + "If the output meets all criteria, respond with exactly: APPROVE\n"
        + "Otherwise, respond with: RETRY: <specific actionable feedback>")
    .build();
```

### Deterministic reviewer

Use a task with a `handler` for programmatic quality checks:

```java
Task reviewTask = Task.builder()
    .description("Quality gate")
    .handler(ctx -> {
        String output = ctx.contextOutputs().isEmpty()
            ? "" : ctx.contextOutputs().getLast().getRaw();

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

Use a task with a `Review` gate that pauses for console input:

```java
Task reviewTask = Task.builder()
    .description("Review the research output above.")
    .handler(ctx -> ctx.contextOutputs().isEmpty()
        ? ToolResult.success("")
        : ToolResult.success(ctx.contextOutputs().getLast().getRaw()))
    .review(Review.required(
        "Type APPROVE, RETRY: <feedback>, or REJECT: <reason>"))
    .build();
```

---

## Feedback injection

On each retry, the reviewer's feedback is injected into every task in the phase as a
`## Revision Instructions` section in the LLM prompt. The LLM sees:

```
## Revision Instructions (Attempt 2)
This task is being re-executed based on reviewer feedback.

### Feedback
Need more depth on quantum computing applications. Include at least 3 peer-reviewed sources.

### Previous Output
[prior attempt output]

## Task
Research the latest developments in quantum computing...
```

The original task description is unchanged. The LLM uses the feedback to improve its
next response.

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
// Writing review: if research was lacking, request research redo
Task writingReviewTask = Task.builder()
    .description("Evaluate the draft. If the research backing is weak, "
        + "respond with: RETRY_PREDECESSOR research: <feedback for the research phase>")
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
| `task` | required | The review task |
| `maxRetries` | 2 | Maximum self-retries (0 = no retries, just review) |
| `maxPredecessorRetries` | 2 | Maximum predecessor retries per predecessor |
