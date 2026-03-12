# Phase Review Examples

These examples show how to attach quality gates to phases using deterministic handlers.
No LLM is required to run the deterministic examples.

---

## Self-retry with deterministic reviewer

A research phase retries until the output passes a length check. The review task
uses `.context()` to read the summarizeTask's output.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

AtomicInteger attempt = new AtomicInteger(0);

// Work task: produces a longer output on subsequent attempts
Task researchTask = Task.builder()
    .description("Research the topic")
    .expectedOutput("A detailed report")
    .handler(ctx -> {
        int n = attempt.incrementAndGet();
        String output = n == 1
            ? "Short answer."
            : "Comprehensive answer with multiple sections. [sources: A, B, C]";
        return ToolResult.success(output);
    })
    .build();

// Review task: declares .context() to read the research output, then checks its length.
// The review task MUST declare .context() to access the phase task outputs.
Task reviewTask = Task.builder()
    .description("Quality gate")
    .context(List.of(researchTask))   // required: read the research task output
    .handler(ctx -> {
        String output = ctx.contextOutputs().getFirst().getRaw();
        if (output.length() < 50) {
            return ToolResult.success(
                PhaseReviewDecision.retry("Output too short. Expand each section.").toText());
        }
        return ToolResult.success(PhaseReviewDecision.approve().toText());
    })
    .build();

Phase research = Phase.builder()
    .name("research")
    .task(researchTask)
    .review(PhaseReview.of(reviewTask, 3))   // up to 3 self-retries
    .build();

EnsembleOutput output = Ensemble.builder()
    .phase(research)
    .build()
    .run();

System.out.println("Attempts: " + attempt.get()); // 2
System.out.println("Output: " + output.getRaw());  // the comprehensive answer
```

---

## Predecessor retry

The writing phase discovers the research was insufficient and requests a research redo.
The writing review task uses `.context()` to read the draft task's output, then evaluates
whether the research backing is strong enough.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

AtomicInteger researchAttempt = new AtomicInteger(0);
AtomicInteger writingReviewCall = new AtomicInteger(0);

// Research task: minimal output on first run, richer output on second
Task gatherTask = Task.builder()
    .description("Gather research data")
    .expectedOutput("Research findings")
    .handler(ctx -> ToolResult.success(
        "Research v" + researchAttempt.incrementAndGet()))
    .build();

Phase research = Phase.of("research", gatherTask);

// Writing task
Task draftTask = Task.builder()
    .description("Write a draft based on the research")
    .expectedOutput("Draft document")
    .context(List.of(gatherTask))   // draft task reads research output
    .handler(ctx -> ToolResult.success("Draft based on research"))
    .build();

// Writing review task: reads the draft via .context(), then decides whether
// research needs to be re-done or the draft is acceptable.
Task writingReviewTask = Task.builder()
    .description("Evaluate draft quality and research backing")
    .context(List.of(draftTask))   // required: read the draft to evaluate
    .handler(ctx -> {
        int call = writingReviewCall.incrementAndGet();
        if (call == 1) {
            // Determine the research was insufficient based on the draft
            return ToolResult.success(
                PhaseReviewDecision.retryPredecessor("research",
                    "Need more comprehensive research data").toText());
        }
        return ToolResult.success(PhaseReviewDecision.approve().toText());
    })
    .build();

Phase writing = Phase.builder()
    .name("writing")
    .after(research)
    .task(draftTask)
    .review(PhaseReview.builder()
        .task(writingReviewTask)
        .maxPredecessorRetries(1)
        .build())
    .build();

EnsembleOutput output = Ensemble.builder()
    .phase(research)
    .phase(writing)
    .build()
    .run();

System.out.println("Research ran: " + researchAttempt.get() + " time(s)"); // 2
System.out.println("Writing review ran: " + writingReviewCall.get() + " time(s)"); // 2
```

---

## Rejection: stopping the pipeline

The review task rejects the phase when the output is fundamentally unusable.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.List;

Task workTask = Task.builder()
    .description("Fetch data from source")
    .expectedOutput("Raw data")
    .handler(ctx -> ToolResult.success("ERROR: source unavailable"))
    .build();

Task reviewTask = Task.builder()
    .description("Validate output")
    .context(List.of(workTask))   // read the work task output
    .handler(ctx -> {
        String output = ctx.contextOutputs().getFirst().getRaw();
        if (output.startsWith("ERROR:")) {
            return ToolResult.success(
                PhaseReviewDecision.reject("Source unavailable: " + output).toText());
        }
        return ToolResult.success(PhaseReviewDecision.approve().toText());
    })
    .build();

Phase fetchPhase = Phase.builder()
    .name("fetch")
    .task(workTask)
    .review(PhaseReview.of(reviewTask, 0))  // 0 retries: review once and accept/reject
    .build();

try {
    Ensemble.builder()
        .phase(fetchPhase)
        .build()
        .run();
} catch (TaskExecutionException e) {
    System.out.println("Pipeline stopped: " + e.getMessage());
    // "Phase 'fetch' was rejected by review: Source unavailable: ERROR: source unavailable"
}
```

---

## AI reviewer (with LLM)

For AI-powered review, declare `.context()` so the LLM sees the phase output, and
instruct it on the response format in the task description:

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.List;

// The review task sees the summarizeTask output via .context(), which becomes the
// "## Context from Previous Tasks" section in the LLM's prompt.
Task aiReviewTask = Task.builder()
    .description("""
        Evaluate the research summary provided above.

        Criteria:
        - At least 5 distinct sources cited
        - Quantitative data for every major claim
        - Minimum 3 paragraphs

        If ALL criteria are met, respond with exactly: APPROVE
        Otherwise, respond with: RETRY: <specific actionable feedback on what to improve>
        """)
    .context(List.of(summarizeTask))   // required: LLM sees phase output in its prompt
    .build();

Phase research = Phase.builder()
    .name("research")
    .task(gatherTask)
    .task(summarizeTask)
    .review(PhaseReview.builder()
        .task(aiReviewTask)
        .maxRetries(2)
        .build())
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(research)
    .build()
    .run();
```

The LLM sees the research summary under `## Context from Previous Tasks` and evaluates
it against the criteria. It returns `APPROVE` or `RETRY: <feedback>`, which the framework
parses and acts on.

---

## Human reviewer (with console)

The review task echoes the phase output (so the human can read it) and pauses at a
`Review.required()` gate for the human to type their decision:

```java
import net.agentensemble.Task;
import net.agentensemble.review.Review;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.List;

Task humanReviewTask = Task.builder()
    .description("Human quality review")
    .context(List.of(summarizeTask))   // read the output for the human to see
    .handler(ctx -> {
        // Echo the phase output as this task's output -- the console review gate
        // will display it alongside the review prompt
        return ToolResult.success(ctx.contextOutputs().getFirst().getRaw());
    })
    .review(Review.required(
        "Is this research output sufficient?\n"
        + "Type APPROVE, RETRY: <feedback>, or REJECT: <reason>"))
    .build();

Phase research = Phase.builder()
    .name("research")
    .task(gatherTask)
    .task(summarizeTask)
    .review(PhaseReview.of(humanReviewTask))
    .build();
```

The human sees the research output in the console and types their decision. For example:
- `APPROVE` — research is accepted, writing phase starts
- `RETRY: Need more depth on section 3, add quantitative data` — research re-runs with
  that text injected as `## Revision Instructions` in each task's LLM prompt
- `REJECT: Data quality is too poor to proceed` — pipeline stops
