# Active Context

## Current Work

Branch: `feature/phase-review-retry`

Phase-level review and retry with feedback injection. Phases can now have a quality gate
that evaluates output and optionally re-runs the phase (with reviewer feedback injected
into task prompts) or requests a predecessor phase re-run.

## Completed This Session

### Implementation (commit afd1279)

**New types:**
- `PhaseReviewDecision` (agentensemble-review): sealed interface with four record
  implementations -- Approve, Retry(feedback), RetryPredecessor(phaseName, feedback),
  Reject(reason). `parse(String)` handles text format; `toText()` serialises back.
- `PhaseReview` (agentensemble-core/workflow): configuration object with `task`,
  `maxRetries` (default 2), `maxPredecessorRetries` (default 2). Hand-written builder
  (avoids Lombok @Builder.Default scoping issue with custom build() methods).

**Task changes:**
- Three new phase-retry fields: `revisionFeedback`, `priorAttemptOutput`, `attemptNumber`
  (all default null/0, framework-internal).
- `withRevisionFeedback(feedback, priorOutput, attempt)` copy factory uses `toBuilder()`.

**AgentPromptBuilder:**
- `## Revision Instructions (Attempt N)` section injected before `## Task` when
  `task.getRevisionFeedback()` is non-blank. Includes `### Feedback` and optionally
  `### Previous Output` subsections.

**Phase:**
- New optional `review` field (PhaseReview, default null).

**PhaseDagExecutor:**
- `runPhaseWithRetry()`: loop around phase execution. After each attempt, runs the
  review task as a synthetic single-task phase (`__review__<phaseName>`). Parses the
  decision and either approves, self-retries (rebuilds tasks with feedback), handles
  predecessor retry, or throws.
- Global state committed only after review approves. Intermediate outputs discarded.
- Predecessor retry: removes stale predecessor outputs from globalTaskOutputs and
  allTaskOutputs, re-runs predecessor with feedback, commits new outputs, rebuilds
  priorOutputsSnapshot, resets current phase to original tasks.
- Context resolution on retries: reviewPrior maps BOTH original and current-attempt
  task identities to current outputs, so review task context() refs resolve correctly
  even when task objects were rebuilt with feedback (different identity).

**Tests (72 new):**
- `PhaseReviewDecisionTest` (31 tests): factories, toText, parse, round-trips
- `PhaseReviewTest` (11 tests): builder, static factories, validation
- `TaskRevisionFeedbackTest` (12 tests): withRevisionFeedback, defaults, immutability
- `AgentPromptBuilderRevisionTest` (9 tests): revision section present/absent, ordering
- `PhaseReviewIntegrationTest` (9 tests): self-retry, max-retry exhaustion, predecessor
  retry, rejection, successor execution after approval

**Documentation:**
- `docs/design/21-phase-review.md`: design doc covering all components
- `docs/guides/phase-review.md`: user guide with three reviewer types
- `docs/examples/phase-review.md`: runnable code examples
- `PhaseReviewExample.java` in agentensemble-examples (3 patterns, no API key for 1+2)
- `mkdocs.yml` updated (guide, example, design doc entries)

## Status
- Full CI build: PASSING (`./gradlew build :agentensemble-core:javadoc :agentensemble-review:javadoc`)
- All 72 new tests: PASSING
- Branch: `feature/phase-review-retry`
- Ready for PR

## Key Design Decisions

### Review-as-task
The reviewer is just a Task (AI, deterministic, or human-review). No parallel reviewer
SPI needed. The review task type determines the reviewer behaviour.

### Text decision format
Review tasks output text (`APPROVE`, `RETRY: feedback`, `RETRY_PREDECESSOR name: feedback`,
`REJECT: reason`). `PhaseReviewDecision.parse(String)` handles case-insensitive matching.
Unrecognised text defaults to APPROVE (safe).

### Delayed global state commit
Intermediate attempt outputs are NOT written to globalTaskOutputs/allTaskOutputs until
review approves. This avoids polluting the global state with discarded attempts and makes
the retry loop clean.

### Scoped predecessor retry
Only the predecessor and the reviewing phase re-run. Other successors of the predecessor
that already completed are NOT re-run. This is documented as a known limitation.

### PhaseReview hand-written builder
Lombok @Builder.Default generates `$set` fields in the builder. When combined with a
custom `build()` method, javac cannot find those fields (annotation processing order).
Solution: hand-write the builder class entirely.

## Next Steps
- Open PR for feature/phase-review-retry
- Consider: Ensemble.run(Phase...) zero-ceremony factory for phase pipelines
