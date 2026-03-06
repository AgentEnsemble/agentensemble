# Human-in-the-Loop Review

This example demonstrates how to add review gates to a pipeline, allowing a human
to approve, edit, or stop execution at key checkpoints.

---

## After-Execution Review Gate

The most common pattern: the human reviews each task's output before it flows downstream.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())
    .task(Task.builder()
        .description("Research the latest AI trends in 2025")
        .expectedOutput("A comprehensive research summary")
        .review(Review.required())       // pause after this task
        .build())
    .task(Task.builder()
        .description("Write a blog post based on the research")
        .expectedOutput("A 600-word blog post")
        .build())
    .build()
    .run();

if (output.getExitReason() == ExitReason.USER_EXIT_EARLY) {
    System.out.println("Stopped by reviewer after task 1.");
} else {
    System.out.println("Blog post: " + output.getRaw());
}
```

**Console interaction:**

```
== Review Required =============================================
Task:   Research the latest AI trends in 2025
Output: The AI landscape in 2025 has been dominated by...
---
[c] Continue  [e] Edit  [x] Exit early  (auto-x in 4:59) > e
Enter revised output (press Enter when done):
The research is incomplete. Please add more detail on regulation.

```

The edited output replaces the original and is passed to the blog writing task.

---

## Ensemble-Level Policy (Review Every Task)

Apply a review gate to every task without modifying each individual task:

```java
EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())
    .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
    .task(Task.of("Research AI regulation changes"))
    .task(Task.of("Draft a compliance checklist"))
    .task(Task.of("Write an executive summary"))
    .build()
    .run();

System.out.println("Completed " + output.getTaskOutputs().size() + " of 3 tasks");
System.out.println("Exit reason: " + output.getExitReason());
```

Any task can be exempted from the policy:

```java
Task quickTask = Task.builder()
    .description("Format the checklist as Markdown")
    .expectedOutput("Markdown table")
    .review(Review.skip())    // skip review for this task
    .build();
```

---

## Before-Execution Gate (Confirmation)

Require confirmation before executing a sensitive operation:

```java
Task dangerousTask = Task.builder()
    .description("Archive and compress all old log files")
    .expectedOutput("Compressed archive path")
    .beforeReview(Review.required("This will move log files. Confirm before proceeding."))
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())
    .task(dangerousTask)
    .build()
    .run();
```

If the reviewer presses `x`, the task never runs and the pipeline stops immediately.

---

## HumanInputTool (Mid-Task Clarification)

The agent pauses during execution to ask a question:

```java
import net.agentensemble.tool.HumanInputTool;

Task task = Task.builder()
    .description(
        "Research AI governance frameworks. If you are uncertain about the scope, "
        + "ask the human for clarification before proceeding.")
    .expectedOutput("A targeted governance report")
    .tools(HumanInputTool.of())
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())
    .task(task)
    .build()
    .run();
```

The agent's question appears in the console and the human's response is fed back
into the ReAct loop as the tool result.

---

## Timeout Configuration

For unattended pipelines, configure what happens when no human responds:

```java
// Continue automatically after 30 seconds of inactivity
Review autoReview = Review.builder()
    .timeout(Duration.ofSeconds(30))
    .onTimeout(OnTimeoutAction.CONTINUE)
    .build();

// Stop the pipeline if no response in 10 minutes
Review safetyReview = Review.builder()
    .timeout(Duration.ofMinutes(10))
    .onTimeout(OnTimeoutAction.EXIT_EARLY)
    .build();
```

---

## CI / Testing

For automated pipelines and tests, use `ReviewHandler.autoApprove()`:

```java
ReviewHandler handler = testing
    ? ReviewHandler.autoApprove()
    : ReviewHandler.console();

Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(handler)
    .reviewPolicy(ReviewPolicy.AFTER_LAST_TASK)
    ...
```

---

## Browser-Based Approval

v2.1.0 introduces a browser-based review handler via the `agentensemble-web` module.
Instead of blocking on the console, review gates display an interactive approval panel
in the browser alongside the live execution timeline.

Add the dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.agentensemble:agentensemble-core:2.1.0")
    implementation("net.agentensemble:agentensemble-web:2.1.0")
    implementation("net.agentensemble:agentensemble-review:2.1.0")
}
```

Use `.webDashboard()` instead of `.reviewHandler()`:

```java
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.web.WebDashboard;

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Draft a press release for the product launch")
        .expectedOutput("A polished press release ready for distribution")
        .review(Review.required())          // pauses here for browser approval
        .build())
    .task(Task.builder()
        .description("Translate the press release to Spanish")
        .expectedOutput("Spanish-language press release")
        .build())
    .webDashboard(WebDashboard.builder()
        .port(7329)
        .reviewTimeout(Duration.ofMinutes(5))
        .onTimeout(OnTimeoutAction.CONTINUE)
        .build())
    .build()
    .run();
```

Open `http://localhost:7329` in a browser before running the ensemble. When the review gate
fires after the first task, the browser displays an approval panel:

```
+------------------------------------------------------+
| Review Required                                       |
+------------------------------------------------------+
| Task: Draft a press release for the product launch    |
|                                                       |
| Output:                                               |
| FOR IMMEDIATE RELEASE                                 |
| [Company] Announces Major Product Launch...           |
|                                                       |
| [Approve]  [Edit]  [Exit Early]                       |
|                                                       |
| Auto-continue in 4:58 ...                             |
+------------------------------------------------------+
```

The reviewer can:
- **Approve** -- the press release is passed to the translation task unchanged
- **Edit** -- inline editing in the browser; the revised text is used downstream
- **Exit Early** -- the pipeline stops; `output.getExitReason()` returns `USER_EXIT_EARLY`

The `.webDashboard()` call wires both the streaming listener (live task timeline) and the
`WebReviewHandler` (browser approval) in a single builder call. No separate process or npm
command is needed -- the server is embedded in the JVM.

**Full documentation:** [Live Dashboard Guide](../guides/live-dashboard.md) | [Live Dashboard Example](live-dashboard.md)
