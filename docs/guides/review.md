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

---

## Browser-Based Review with WebDashboard

The `agentensemble-web` module (v2.1.0+) provides `WebDashboard`, which embeds a
Javalin WebSocket server directly in the JVM process. It replaces the console prompt
with a browser-based review panel and simultaneously streams the live execution timeline
to every connected browser client. The `WebReviewHandler` returned by
`WebDashboard.reviewHandler()` broadcasts a `review_requested` message over WebSocket when
a gate fires, blocks the calling virtual thread, and resumes when the browser sends a
`review_decision` (or the configured timeout expires).

### Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.agentensemble:agentensemble-core:2.1.0")
    implementation("net.agentensemble:agentensemble-web:2.1.0")
    implementation("net.agentensemble:agentensemble-review:2.1.0")
}
```

### Wiring

Use `.webDashboard(WebDashboard)` instead of `.reviewHandler(...)`. This single builder
call registers both the streaming listener and the `WebReviewHandler`:

```java
import java.time.Duration;
import net.agentensemble.review.Review;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.web.WebDashboard;

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Draft a press release")
        .expectedOutput("A polished press release")
        .review(Review.required())      // gate fires after this task
        .build())
    .task(Task.of("Translate to Spanish"))
    .webDashboard(WebDashboard.builder()
        .port(7329)
        .reviewTimeout(Duration.ofMinutes(5))
        .onTimeout(OnTimeoutAction.CONTINUE)
        .build())
    .build()
    .run();
```

Connect to `ws://localhost:7329/ws` using the agentensemble-viz dashboard client
(`npx @agentensemble/viz --live ws://localhost:7329/ws`) or any WebSocket-capable browser
tool. When the review gate fires, connected clients receive a `review_requested` message and
can send back a `review_decision` with **approve**, **edit**, or **exit_early**.

### Review timeout behavior

The `onTimeout` option on `WebDashboard.builder()` maps to the same `OnTimeoutAction`
enum used by `Review.builder()`:

| `onTimeout` | Effect |
|-------------|--------|
| `CONTINUE` | Continue as if the human approved (default) |
| `EXIT_EARLY` | Stop the pipeline; `output.getExitReason()` is `USER_EXIT_EARLY` |
| `FAIL` | Throw `ReviewTimeoutException` |

### Combining with `ReviewPolicy`

`.webDashboard()` sets the `ReviewHandler` on the ensemble. All existing `ReviewPolicy`
and per-task `Review` configuration still applies:

```java
Ensemble.builder()
    .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
    .webDashboard(WebDashboard.onPort(7329))   // handles all review gates in the browser
    ...
```

**Full documentation:** [Live Dashboard Guide](live-dashboard.md) | [Live Dashboard Example](../examples/live-dashboard.md)

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

## Tool-Level Approval Gates

In addition to task-level gates, individual tools can request human approval
**before executing a dangerous or irreversible action** -- for example, before running a
shell command, overwriting a file, or sending a destructive HTTP request. This gate fires
inside the ReAct tool-call loop, mid-execution.

### Enabling on Built-in Tools

The three built-in tools that support this pattern expose a `requireApproval(boolean)` builder
option (default: `false`):

```java
// Require approval before executing any subprocess
ProcessAgentTool tool = ProcessAgentTool.builder()
    .name("shell")
    .description("Runs shell commands")
    .command("sh", "-c")
    .requireApproval(true)
    .build();

// Require approval before writing any file
FileWriteTool writeTool = FileWriteTool.builder(outputDir)
    .requireApproval(true)
    .build();

// Require approval before sending any HTTP request
HttpAgentTool apiTool = HttpAgentTool.builder()
    .name("production_api")
    .description("Calls the production API")
    .url("https://api.production.example.com")
    .method("DELETE")
    .requireApproval(true)
    .build();
```

The same `ReviewHandler` configured on the ensemble is reused:

```java
Ensemble.builder()
    .chatLanguageModel(model)
    .reviewHandler(ReviewHandler.console())   // handles both task-level and tool-level gates
    .task(Task.builder()
        .description("Clean up old data")
        .agent(Agent.builder()
            .role("Operator")
            .goal("Perform system maintenance")
            .llm(model)
            .tools(List.of(tool, writeTool))
            .build())
        .build())
    .build()
    .run();
```

**A `ReviewHandler` must be configured when `requireApproval(true)` is set.** If no handler
is present, an `IllegalStateException` is thrown at execution time (fail-fast). This differs from
`HumanInputTool`, which silently auto-approves when no handler is set. See
[Distinction from HumanInputTool](#distinction-from-humaninputtool) below.

### Implementing in Custom Tools

Extend `AbstractAgentTool` and call `requestApproval()` inside `doExecute()` before
performing the action:

```java
public class DangerousTool extends AbstractAgentTool {

    private final boolean requireApproval;

    @Override
    protected ToolResult doExecute(String input) {
        String command = parseCommand(input);

        if (requireApproval) {
            if (rawReviewHandler() == null) {
                throw new IllegalStateException(
                    "Tool '" + name() + "' requires approval but no ReviewHandler is configured. "
                    + "Add .reviewHandler(ReviewHandler.console()) to the ensemble builder.");
            }
            ReviewDecision decision = requestApproval("Execute: " + command);
            if (decision instanceof ReviewDecision.ExitEarly) {
                // Return failure -- lets the agent adapt rather than stopping the pipeline
                return ToolResult.failure("Rejected by reviewer: " + command);
            }
            if (decision instanceof ReviewDecision.Edit edit) {
                command = edit.revisedOutput();  // use the reviewer's revision
            }
            // ReviewDecision.Continue: proceed normally
        }

        return executeCommand(command);
    }
}
```

**ExitEarly vs ExitEarlyException**: `requestApproval()` returns `ReviewDecision`, not an
exception. Built-in tools return `ToolResult.failure()` on `ExitEarly`, which allows the agent
to adapt and produce a final answer. Custom tools can alternatively throw
`ExitEarlyException` directly to stop the entire pipeline immediately -- choose based on
your use case.

### Timeout Configuration

The no-argument overload uses `Review.DEFAULT_TIMEOUT` (5 minutes) and
`Review.DEFAULT_ON_TIMEOUT` (`EXIT_EARLY`), consistent with task-level gates:

```java
ReviewDecision decision = requestApproval("Execute: " + command);
```

To customize, use the overload that accepts timeout and on-timeout action:

```java
ReviewDecision decision = requestApproval(
    "Execute: " + command,
    Duration.ofMinutes(2),
    OnTimeoutAction.CONTINUE);
```

### Edit Semantics per Tool

Each tool interprets `ReviewDecision.Edit` differently:

| Tool | Edit behavior |
|------|--------------|
| `ProcessAgentTool` | `edit.revisedOutput()` replaces the **input** sent to the subprocess |
| `FileWriteTool` | `edit.revisedOutput()` replaces the **file content** written |
| `HttpAgentTool` | `edit.revisedOutput()` replaces the **request body** sent |
| Custom tool | Caller decides; document your tool's Edit contract |

### Parallel Tool Execution

When the agent executor runs multiple tools concurrently in the same ReAct turn and both
request approval via a `ConsoleReviewHandler`, the prompts are serialized via a shared lock
(`AbstractAgentTool.CONSOLE_APPROVAL_LOCK`) to prevent interleaved console output. The second
tool waits for the first reviewer interaction to complete before printing its prompt.

Non-console handlers (e.g., auto-approve, custom webhook) are not serialized -- concurrent
requests proceed independently.

### @Tool-Annotated Objects

Tool-level approval is **only available to `AbstractAgentTool` subclasses**. Objects annotated
with `@Tool` go through `LangChain4jToolAdapter` and cannot call `requestApproval()`. Do not
attempt to retrofit `@Tool` objects with approval logic -- implement an `AbstractAgentTool`
subclass instead.

### Distinction from HumanInputTool

| | `HumanInputTool` | `requireApproval` on `AbstractAgentTool` |
|--|--|--|
| **Purpose** | Agent asks the human a question | Tool requests human approval before an action |
| **When** | Agent decides when to invoke | Always fires before the flagged action |
| **No handler configured** | Silently auto-approves | Throws `IllegalStateException` (fail-fast) |
| **Timing** | `DURING_EXECUTION` | `DURING_EXECUTION` |
| **ExitEarly handling** | Throws `ExitEarlyException` (stops pipeline) | Built-in tools return `ToolResult.failure()` (agent adapts) |
| **Works with** | Any task tool list | `AbstractAgentTool` subclasses only |

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
