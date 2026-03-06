# Human-in-the-Loop Review

AgentEnsemble v2.0.0 supports **human-in-the-loop review gates** that can pause pipeline
execution at three timing points to collect human approval, corrections, or clarification.

---

## Overview

Review gates are opt-in. You configure a `ReviewHandler` on the ensemble and declare
where gates should fire on individual tasks.

```java
EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())          // how to ask the human
    .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)     // when to ask
    .task(Task.of("Research AI trends"))
    .task(Task.of("Write a report"))
    .build()
    .run();

if (output.getExitReason() == ExitReason.USER_EXIT_EARLY) {
    System.out.println("Pipeline stopped early after "
        + output.getTaskOutputs().size() + " task(s)");
}
```

---

## Review Timing Points

### After Execution (most common)

The gate fires **after** the agent completes a task, before output is passed to the next task.
The reviewer sees the task description and the agent's output, then decides what to do.

```java
Task task = Task.builder()
    .description("Write a blog post about AI trends")
    .expectedOutput("A 500-word blog post")
    .review(Review.required())    // gate fires after this task
    .build();
```

**Decisions available:**

| Input | Result |
|-------|--------|
| `c` or Enter | **Continue** -- output passed forward unchanged |
| `e` | **Edit** -- reviewer types replacement text; downstream tasks receive the revised output |
| `x` | **ExitEarly** -- pipeline stops; completed tasks (including this one) are in `EnsembleOutput` |

---

### Before Execution

The gate fires **before** the agent begins executing. The reviewer sees the task description
and approves or cancels execution.

```java
Task task = Task.builder()
    .description("Delete all cached data")
    .expectedOutput("Confirmation message")
    .beforeReview(Review.required("Review carefully before proceeding"))
    .build();
```

**Decisions:**

- `c` or `e` (Edit before execution is treated as Continue) → task executes
- `x` → task does not execute; pipeline stops with all previously completed tasks

---

### During Execution (HumanInputTool)

An agent can pause mid-execution to ask the human a question by using the built-in
`HumanInputTool`:

```java
Task task = Task.builder()
    .description("Research AI trends and ask for direction if unsure")
    .expectedOutput("A targeted research report")
    .tools(HumanInputTool.of())
    .build();
```

When the agent invokes `human_input`, the ensemble's `ReviewHandler` is called with
`ReviewTiming.DURING_EXECUTION`. The human's text response is returned to the agent
as the tool result, resuming the ReAct loop.

---

## Review Configuration

### `Review.required()`

Always fires the gate. Useful to override `ReviewPolicy.NEVER` for specific tasks.

```java
.review(Review.required())
.review(Review.required("Please approve the output before passing it downstream"))
```

### `Review.skip()`

Never fires the gate, even if the ensemble policy would.

```java
.review(Review.skip())   // suppress review for this task
```

### `Review.builder()`

Fine-grained control over timeout and timeout behavior:

```java
.review(Review.builder()
    .timeout(Duration.ofMinutes(10))
    .onTimeout(OnTimeoutAction.CONTINUE)
    .build())
```

**`OnTimeoutAction` values:**

| Value | Behavior on timeout |
|-------|---------------------|
| `CONTINUE` | Continue as if the human approved |
| `EXIT_EARLY` | Stop the pipeline (default) |
| `FAIL` | Throw `ReviewTimeoutException` |

---

## Ensemble-Level Review Policy

`ReviewPolicy` controls when the after-execution gate fires for tasks that do **not** have
an explicit `.review()` configuration:

| Policy | When gate fires |
|--------|----------------|
| `NEVER` (default) | Only on tasks with `.review(Review.required())` |
| `AFTER_EVERY_TASK` | After every task; tasks with `.review(Review.skip())` are exempt |
| `AFTER_LAST_TASK` | Only after the final task in the pipeline |

```java
Ensemble.builder()
    .reviewHandler(ReviewHandler.autoApprove())
    .reviewPolicy(ReviewPolicy.AFTER_LAST_TASK)
    ...
```

---

## Built-in ReviewHandler Implementations

### `ReviewHandler.console()`

CLI implementation. Blocks on stdin, displays a countdown timer. Suitable for
interactive terminal pipelines.

```java
.reviewHandler(ReviewHandler.console())
```

The prompt displayed:

```
== Review Required =============================================
Task:   Write a blog post about AI trends
Output: The AI landscape in 2025 has seen rapid progress in...
---
[c] Continue  [e] Edit  [x] Exit early  (auto-x in 4:59) >
```

The countdown updates in-place without scrolling the terminal.

### `ReviewHandler.autoApprove()`

Always returns Continue without blocking. Use in CI pipelines and automated tests.

```java
.reviewHandler(ReviewHandler.autoApprove())
```

### `ReviewHandler.autoApproveWithDelay(Duration)`

Returns Continue after a configurable delay. Use in tests that need to simulate
realistic human timing.

```java
.reviewHandler(ReviewHandler.autoApproveWithDelay(Duration.ofMillis(100)))
```

### `ReviewHandler.web(URI)` (stub)

Design placeholder for webhook-based review. Not yet implemented; always throws
`UnsupportedOperationException`.

---

## Handling Partial Results

When a reviewer chooses ExitEarly, the pipeline stops and `EnsembleOutput` contains
only the tasks that completed:

```java
EnsembleOutput output = ensemble.run();

System.out.println("Exit reason: " + output.getExitReason());
// ExitReason.COMPLETED or ExitReason.USER_EXIT_EARLY

System.out.println("Tasks completed: " + output.getTaskOutputs().size());
System.out.println("Final output: " + output.getRaw());
```

`output.getRaw()` is the output of the **last completed task**, even in an early exit.

---

## Custom ReviewHandler

Implement the `ReviewHandler` functional interface to integrate with any external system:

```java
ReviewHandler slackHandler = request -> {
    String message = String.format(
        "Task '%s' needs review. Output: %s",
        request.taskDescription(),
        request.taskOutput());
    String response = slackClient.postAndWait(message, request.timeout());
    return switch (response.toLowerCase()) {
        case "approve" -> ReviewDecision.continueExecution();
        case "edit" -> ReviewDecision.edit(slackClient.getEditedText());
        default -> ReviewDecision.exitEarly();
    };
};

Ensemble.builder()
    .reviewHandler(slackHandler)
    ...
```

---

## Task-Level Override Summary

Task-level review configuration always overrides the ensemble policy:

| Scenario | Gate fires? |
|----------|------------|
| Ensemble: `NEVER`, task: none | No |
| Ensemble: `NEVER`, task: `Review.required()` | **Yes** |
| Ensemble: `AFTER_EVERY_TASK`, task: none | Yes |
| Ensemble: `AFTER_EVERY_TASK`, task: `Review.skip()` | **No** |
| Ensemble: `AFTER_LAST_TASK`, first task: none | No |
| Ensemble: `AFTER_LAST_TASK`, last task: none | Yes |
