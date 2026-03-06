# Active Context

## Current Work Focus

Issue #132 (feat: WebReviewHandler -- real implementation replacing stub, v2.1.0) is
complete on branch `feat/132-web-review-handler`.

### Issue #132 summary

**Breaking change**: `ReviewHandler.web(URI)` factory removed from `agentensemble-review`;
the stub `WebReviewHandler` in that module deleted. The real implementation has always been
`net.agentensemble.web.WebReviewHandler` in `agentensemble-web`, obtained via
`WebDashboard.reviewHandler()`.

Key changes:
- `ReviewHandler.web(URI)` factory and the URI-based `WebReviewHandler` stub removed from
  `agentensemble-review` (breaking change -- no longer throws `UnsupportedOperationException`)
- `ReviewHandler` Javadoc updated to direct users to `WebDashboard.reviewHandler()`
- `AutoApproveReviewHandlerTest` updated to remove the 4 web() stub tests
- `ConnectionManager.resolveReview()` now logs at DEBUG when an unknown reviewId is received
  (covers the race between late browser decisions and already-timed-out reviews)
- `WebReviewHandlerTest` extended with 2 new tests:
  - `review_concurrentReviews_eachFutureResolvesIndependently()` -- 3 parallel virtual threads,
    resolved in reverse order, all return correct decision
  - `resolveReview_unknownReviewId_afterTimeout_isIgnoredWithoutException()`
- `WebReviewGateIntegrationTest` created (4 tests, real embedded server + Java WS client):
  - CONTINUE, EDIT, EXIT_EARLY decision flows
  - Timeout flow with `review_timed_out` broadcast verification
- `docs/guides/review.md` updated: removed `ReviewHandler.web(URI)` stub section and
  "not yet implemented" language; Browser-Based Review section now describes the live
  `WebReviewHandler` accurately

## Next Steps

- Open PR for `feat/132-web-review-handler` against main
- Continue with v2.1.0 viz live mode (Group I):
  - agentensemble-viz WebSocket client + `/live` route + incremental `liveReducer` state machine
  - Live timeline/flow updates
  - Review approval UI (modal with Approve/Edit/Exit Early + countdown)

## Key Design Decisions (Issue #132)

- Breaking change chosen over deprecation: `ReviewHandler.web(URI)` was always a stub
  that threw UnsupportedOperationException, so callers had no working code to migrate;
  removing it cleanly is better than emitting a deprecation warning for years
- Real `WebReviewHandler` lives in `agentensemble-web` (not `agentensemble-review`) because
  it depends on `ConnectionManager`, `MessageSerializer`, and the Javalin server -- none of
  which belong in the lightweight review module
- Canonical entry point is `WebDashboard.reviewHandler()`; it is the only way to obtain a
  working `WebReviewHandler`
- `ConnectionManager.resolveReview()` logs at DEBUG for unknown reviewId rather than WARN
  because it is an expected race condition (late browser decision after timeout)

## Important Patterns and Preferences

### agentensemble-web module (v2.1.0)

- `WebDashboard.onPort(port)` -- zero-config; `WebDashboard.builder()` for full config
- `Ensemble.builder().webDashboard(dashboard)` -- single call wires listener + review
  handler + lifecycle hooks
- `EnsembleDashboard` interface: `streamingListener()`, `reviewHandler()`,
  `start()`, `stop()`, `isRunning()`, `onEnsembleStarted(...)`, `onEnsembleCompleted(...)`
- `WebSocketStreamingListener` broadcasts and appends to snapshot after each event
- `ConnectionManager.noteEnsembleStarted(ensembleId, startedAt)` clears snapshot and
  records metadata for the hello message
- `ConnectionManager.appendToSnapshot(json)` adds each broadcast message to the late-join log
- Late-join hello message: `HelloMessage(ensembleId, startedAt, snapshotTrace)` where
  `snapshotTrace` is a `JsonNode` array of all past broadcast messages (or null if empty)
- agentensemble-review is now `api` dependency in agentensemble-web (not compileOnly)

### v2.0.0 EnsembleOutput API (Issue #111)
- `output.isComplete()` -- true only when all tasks ran to completion
- `output.getExitReason()` -- COMPLETED, USER_EXIT_EARLY, TIMEOUT, ERROR
- `output.completedTasks()` -- same as getTaskOutputs(); always safe
- `output.lastCompletedOutput()` -- Optional<TaskOutput> last completed
- `output.getOutput(researchTask)` -- identity-based lookup; pass the same Task instance

### v2.0.0 Review API (Issues #108/#109/#110)
- ReviewHandler + ReviewPolicy is the v2.0.0 review API
- Ensemble.builder().reviewHandler(ReviewHandler) + .reviewPolicy(ReviewPolicy)
- Task.builder().review(Review) / .beforeReview(Review)
- HumanInputTool.of() for mid-task clarification (DURING_EXECUTION gate)

### v2 Task-First API (Issues #104/#105)
- Task.of(description) -- zero-ceremony, default expectedOutput, no agent required
- Ensemble.run(model, tasks...) -- static factory, single-line ensemble execution
- Ensemble.builder().chatLanguageModel(model) -- ensemble-level LLM for synthesis
