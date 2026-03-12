# 21. Phase-Level Review and Retry

**Target release:** v0.x

Phase review allows any phase in a multi-phase pipeline to evaluate its own output and
decide whether to accept it, retry with feedback, request a predecessor retry, or reject
the phase entirely.

---

## 1. Problem Statement

Phases (see design doc 19) run tasks forward-only: once a phase completes, its outputs
are committed and downstream phases begin. There is no mechanism for:

- A phase evaluating whether its output meets quality criteria before unlocking successors.
- A phase re-running its tasks with reviewer-supplied improvement feedback.
- A downstream phase detecting that an upstream phase's output was insufficient and
  triggering a re-run of that upstream phase.

---

## 2. Design Goals

1. **Review-as-task**: The reviewer is just a `Task` — AI, deterministic, or human-review.
   No parallel reviewer abstraction needed. The full task infrastructure (tools, structured
   output, memory, human review gates) is available to the reviewer.

2. **Feedback injection**: On retry, each task in the phase receives the reviewer's feedback
   as a `## Revision Instructions` section prepended to its LLM prompt. The LLM sees both
   the feedback and its prior attempt output, enabling targeted improvement.

3. **Bounded loops**: `maxRetries` and `maxPredecessorRetries` prevent infinite loops. When
   exhausted, the last output is accepted and the pipeline continues.

4. **Non-breaking**: No review gate on a phase means no behaviour change from v0.x phases.
   All existing phase tests pass unchanged.

---

## 3. Component Overview

### 3.1 `PhaseReviewDecision` (agentensemble-review)

A sealed interface with four permitted record implementations:

| Decision | Text format | Meaning |
|---|---|---|
| `Approve` | `APPROVE` | Accept output, unlock successors |
| `Retry(feedback)` | `RETRY: <feedback>` | Re-run this phase with feedback |
| `RetryPredecessor(phaseName, feedback)` | `RETRY_PREDECESSOR <name>: <feedback>` | Re-run a named direct predecessor, then re-run this phase |
| `Reject(reason)` | `REJECT: <reason>` | Fail this phase, skip successors |

`PhaseReviewDecision.parse(String)` converts a raw text string (from the review task's
output) into a decision. `toText()` serialises a decision back to text for deterministic
handlers.

### 3.2 `PhaseReview` (agentensemble-core)

Configuration attached to a `Phase`:

```java
PhaseReview.builder()
    .task(myReviewTask)         // any Task type
    .maxRetries(2)              // self-retry limit (default 2)
    .maxPredecessorRetries(2)   // predecessor retry limit per predecessor (default 2)
    .build();
```

Static factories: `PhaseReview.of(task)` and `PhaseReview.of(task, maxRetries)`.

### 3.3 Task revision fields

Three new fields on `Task` (framework-internal, not for direct user configuration):

| Field | Type | Purpose |
|---|---|---|
| `revisionFeedback` | `String` | Reviewer feedback injected into the prompt |
| `priorAttemptOutput` | `String` | Raw output from the previous attempt |
| `attemptNumber` | `int` | 0 = first attempt, 1 = first retry, etc. |

`Task.withRevisionFeedback(feedback, priorOutput, attempt)` returns a copy with these
fields set. All other task fields are preserved unchanged.

### 3.4 AgentPromptBuilder revision section

When a task has `revisionFeedback` set, `AgentPromptBuilder.buildUserPrompt()` inserts a
`## Revision Instructions` section **before** the `## Task` section:

```
## Revision Instructions (Attempt N)
This task is being re-executed based on reviewer feedback.

### Feedback
<reviewer feedback>

### Previous Output
<prior attempt output>

## Task
<original task description>
```

### 3.5 `PhaseDagExecutor` retry loop

`PhaseDagExecutor.runPhaseWithRetry()` wraps each phase execution in a loop:

```
attempt = 0
loop:
  run phase (all tasks)
  run review task (single-task synthetic phase)
  parse review output → PhaseReviewDecision

  Approve      → commit output, break
  Retry        → if attempt < maxRetries: rebuild tasks with feedback, attempt++
                 else: log warn, commit last output, break
  Retry        → (predecessor) remove predecessor outputs, re-run predecessor,
  Predecessor    update globalTaskOutputs, reset currentPhase, attempt = -1
  Reject       → throw TaskExecutionException
```

Key invariant: outputs are committed to `globalTaskOutputs` / `allTaskOutputs` ONLY after
the review approves (or limits are exhausted). Intermediate attempt outputs are discarded.

---

## 4. Review Task Execution

The review task receives all outputs from the reviewed phase as prior context. The
`PhaseDagExecutor` builds a combined prior map that includes:

1. Outputs from all previously committed phases.
2. Outputs from the current phase attempt (by current-attempt task identity).
3. Outputs from the current phase attempt (by **original** task identity) — enables correct
   context resolution when tasks have been rebuilt with feedback (new object identities).

The review task is wrapped in a synthetic single-task `Phase` named
`__review__<phaseName>` and run through the same `phaseRunner` used for normal phases.
This means the review task has access to the full framework: agents, tools, memory, human
review gates.

---

## 5. Predecessor Retry

When a review returns `RetryPredecessor(phaseName, feedback)`:

1. The named phase must be a **direct predecessor** (in the reviewing phase's `.after()` list).
   If not found, the decision is treated as `Approve` with a warning.
2. The predecessor's committed outputs are removed from `globalTaskOutputs` and
   `allTaskOutputs`.
3. The predecessor is rebuilt with feedback-enhanced tasks and re-executed.
4. New predecessor outputs are committed to global state.
5. The reviewing phase is re-executed from attempt 0 with the updated prior snapshot.

**Scoped retry**: Only the predecessor and the reviewing phase re-execute. Other successors
of the predecessor that already completed are NOT re-run. Their outputs reflect the original
predecessor output, which is a documented limitation of the scoped retry model.

---

## 6. Thread Safety

- `globalTaskOutputs` and `allTaskOutputs` are thread-safe collections (synchronized map
  and synchronized list).
- `PhaseDagExecutor.runPhaseWithRetry()` runs on the phase's virtual thread. Predecessor
  retry modifies global state from this thread while the predecessor's virtual thread has
  already completed. No concurrent write hazard exists for the predecessor's entries.
- Other phases running in parallel with the reviewing phase use their own
  `priorOutputsSnapshot` (captured at submission time) and are not affected by the
  reviewing phase's global state mutations.

---

## 7. Limitations

- Successor invalidation: predecessor retry does NOT re-run phases that already completed
  using the old predecessor outputs (other than the reviewing phase itself).
- `RetryPredecessor` is constrained to direct predecessors only. Arbitrary DAG rewinding
  is not supported.
- The review task itself does not have a review gate (no meta-review). Nested reviews are
  not supported.
- Review tasks run synchronously within the phase's virtual thread, increasing total
  phase duration by the review task's execution time.

---

## 8. Text Decision Format Reference

Review tasks (AI, deterministic, or human) must produce output parseable by
`PhaseReviewDecision.parse(String)`:

```
APPROVE
RETRY: <feedback text>
RETRY_PREDECESSOR <phaseName>: <feedback text>
REJECT: <reason text>
```

Parsing is case-insensitive. Unrecognised text is treated as `APPROVE` (safe default).
The colon separator for `RETRY` splits on the FIRST colon, so feedback text may contain
additional colons.
