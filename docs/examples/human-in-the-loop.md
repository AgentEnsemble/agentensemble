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

## Tool-Level Approval

Beyond task-level gates, individual tools can request human approval before executing a
dangerous or irreversible action. This fires inside the ReAct loop, before the tool's
actual operation.

```java
import net.agentensemble.tools.process.ProcessAgentTool;
import net.agentensemble.tools.io.FileWriteTool;
import net.agentensemble.review.ReviewHandler;

// Require approval before executing any subprocess
ProcessAgentTool shell = ProcessAgentTool.builder()
    .name("shell")
    .description("Executes shell commands on the host system")
    .command("sh", "-c")
    .requireApproval(true)
    .build();

// Require approval before writing any file
FileWriteTool writer = FileWriteTool.builder(Path.of("/workspace"))
    .requireApproval(true)
    .build();

var agent = Agent.builder()
    .role("Operator")
    .goal("Perform system maintenance tasks")
    .llm(model)
    .tools(List.of(shell, writer))
    .build();

var task = Task.builder()
    .description("Clean up temporary files and write a summary")
    .expectedOutput("Maintenance report")
    .agent(agent)
    .build();

// The SAME ReviewHandler handles both task-level and tool-level gates
EnsembleOutput output = Ensemble.builder()
    .task(task)
    .reviewHandler(ReviewHandler.console())    .build()
    .run();
```
When the agent calls `shell` with `rm -rf /tmp/cache`, the console will prompt:

```
== Review Required =============================================
Task:   Execute command: sh -c
Input:  rm -rf /tmp/cache
---
[c] Continue  [e] Edit  [x] Exit early  (auto-x in 4:59) >
```

**Decisions:**

- `c` or Enter -- execute the command as-is
- `e` -- type a replacement input (e.g. `rm -rf /tmp/cache/old-only`) to run instead
- `x` -- reject the action; the tool returns a failure result and the agent adapts

No handler configured with `requireApproval(true)` raises `IllegalStateException` at
execution time -- a deliberate fail-fast to prevent accidental unreviewed execution. Add
`.reviewHandler(ReviewHandler.console())` to the ensemble builder to resolve it.

See [Tool-Level Approval Gates](../guides/review.md#tool-level-approval-gates) in the review
guide for full documentation including custom tool implementation and parallel execution notes.
---

## Browser-Based Approval

The `agentensemble-web` module provides a browser-based review handler.
Instead of blocking on the console, review gates display an interactive approval panel
in the browser alongside the live execution timeline.

Add the dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.agentensemble:agentensemble-core:{{ae_version}}")
    implementation("net.agentensemble:agentensemble-web:{{ae_version}}")
    implementation("net.agentensemble:agentensemble-review:{{ae_version}}")
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
fires after the first task, a modal panel appears as a modal overlay over the live dashboard:

```
+------------------------------------------------------+
| Review Required                              [AFTER] |
+------------------------------------------------------+
| Task                                                  |
| Draft a press release for the product launch          |
|                                                       |
| Output                                                |
| FOR IMMEDIATE RELEASE                                 |
| [Company] Announces Major Product Launch...           |
| (scrollable if long)                                  |
|                                                       |
|======================================================| <- countdown bar (amber)
|                                                       |
| Auto-continue in 4:58                                 |
|                                                       |
| [   Approve   ]  [   Edit   ]  [  Exit Early  ]      |
+------------------------------------------------------+
```

The **[AFTER]** badge in the header reflects the review timing
(`BEFORE`, `AFTER`, or `DURING` execution). If a custom prompt was set on the
`Review` object, it appears between the task description and the output.

### Actions

**Approve**

Clicking Approve sends a `CONTINUE` decision to the server over WebSocket. The panel
closes immediately and the pipeline resumes. The press release is passed to the
translation task unchanged.

**Edit**

Clicking Edit replaces the read-only output display with a pre-filled `<textarea>`.
The reviewer edits the text and clicks Submit. An `EDIT` decision is sent with the
revised text, which replaces the original task output for all downstream tasks.
Clicking Cancel returns to the read-only view without sending anything.

**Exit Early**

Clicking Exit Early shows a confirmation step:
`"Are you sure? This will stop the pipeline."`
Clicking Confirm Exit sends an `EXIT_EARLY` decision. The pipeline stops and
`output.getExitReason()` returns `USER_EXIT_EARLY`. Clicking Cancel returns to
the main view.

### Timeout Countdown

A smooth amber progress bar counts down from the configured `reviewTimeout`. The
label below the bar reads either `"Auto-continue in X:XX"` or `"Auto-exit in X:XX"`
depending on the configured `onTimeout` action. The bar uses a CSS animation for
smooth visual feedback without per-frame JavaScript.

When the timeout expires on the server, the panel shows a brief message:
- `"Timed out -- continuing"` (when `onTimeout` is `CONTINUE`)
- `"Timed out -- exiting"` (when `onTimeout` is `EXIT_EARLY`)

The panel then closes after 2 seconds. The server applies the timeout action
independently of the client display; the countdown is advisory only.

### Concurrent Reviews (Parallel Workflows)

In parallel workflows, multiple review gates can fire simultaneously. The panel
always shows the oldest pending review first (FIFO). When additional reviews are
waiting, a badge below the panel shows how many are queued:

```
+------------------------------------------------------+
| Review Required                              [AFTER] |
+------------------------------------------------------+
| Task: Analyze market segment A                        |
| ...                                                   |
+------------------------------------------------------+
            +2 pending
```

After the current review is resolved, the next review is shown automatically.

### Wiring

The `.webDashboard()` call wires both the streaming listener (live task timeline) and
the `WebReviewHandler` (browser approval) in a single builder call. No separate
process or npm command is needed -- the server is embedded in the JVM.

**Full documentation:** [Live Dashboard Guide](../guides/live-dashboard.md) | [Live Dashboard Example](live-dashboard.md)
