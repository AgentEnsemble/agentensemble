# Phase Review Examples

These examples show how to attach quality gates to phases using deterministic handlers.
No LLM is required to run these examples.

---

## Self-retry with deterministic reviewer

A research phase retries until the output passes a simple length check.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

import java.util.concurrent.atomic.AtomicInteger;

// Track which attempt we are on
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

// Review task: approves when output is long enough
Task reviewTask = Task.builder()
    .description("Quality gate")
    .handler(ctx -> {
        String output = ctx.contextOutputs().isEmpty()
            ? "" : ctx.contextOutputs().getLast().getRaw();
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

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.PhaseReviewDecision;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

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

Phase research = Phase.of("research", gatherTask);   // no review on research itself

// Writing task
Task draftTask = Task.builder()
    .description("Write a draft based on the research")
    .expectedOutput("Draft document")
    .handler(ctx -> ToolResult.success("Draft based on research"))
    .build();

// Writing review: first call requests predecessor retry; second call approves
Task writingReviewTask = Task.builder()
    .description("Evaluate the draft quality")
    .handler(ctx -> {
        int call = writingReviewCall.incrementAndGet();
        if (call == 1) {
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

Task workTask = Task.builder()
    .description("Fetch data from source")
    .expectedOutput("Raw data")
    .handler(ctx -> ToolResult.success("ERROR: source unavailable"))
    .build();

Task reviewTask = Task.builder()
    .description("Validate output")
    .handler(ctx -> {
        String output = ctx.contextOutputs().isEmpty()
            ? "" : ctx.contextOutputs().getLast().getRaw();
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

For AI-powered review, provide an LLM-backed agent and instruct it on the output format:

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseReview;

Task aiReviewTask = Task.builder()
    .description("""
        Evaluate the research output below.

        Criteria:
        - At least 5 distinct sources cited
        - Quantitative data for every major claim
        - Minimum 3 paragraphs

        If ALL criteria are met, respond with exactly: APPROVE
        Otherwise, respond with: RETRY: <specific actionable feedback on what to improve>
        """)
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

The AI reviewer automatically sees the phase's task outputs via prior context. The LLM
evaluates them against the criteria and returns `APPROVE` or `RETRY: <feedback>`.
