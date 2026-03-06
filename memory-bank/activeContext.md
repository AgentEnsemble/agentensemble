# Active Context

## Current Work Focus

Issue #131 (feat: WebSocketStreamingListener -- bridge callbacks to WebSocket, v2.1.0) is
complete on branch `feat/131-streaming-listener`.

### Issue #131 summary

- All 7 `EnsembleListener` methods bridge to WebSocket JSON messages (task start/complete/
  fail, tool_called, delegation started/completed/failed)
- `EnsembleDashboard` SPI extended with `onEnsembleStarted()` / `onEnsembleCompleted()`
  default lifecycle hooks
- `Ensemble.java` stores dashboard reference and calls hooks from `runWithInputs()`
- `WebDashboard` implements hooks to broadcast `ensemble_started` / `ensemble_completed`
- Late-join snapshot: `ConnectionManager` accumulates all broadcast messages in a
  `CopyOnWriteArrayList`; `onConnect()` sends them as a JSON array in the `hello` message
- Also applied Copilot review fixes from PR #138: heartbeat future cancellation,
  IPv6 loopback origin handling, null outcome for tool_called, -1 tokenCount sentinel,
  agentensemble-review promoted from compileOnly to api
- All 141+ tests pass; JaCoCo LINE >= 90% / BRANCH >= 75% both pass; full build clean

## Next Steps

- Open PR for `feat/131-streaming-listener` against main
- Continue with v2.1.0 viz live mode (Issue #132+: agentensemble-viz WebSocket client,
  live `/live` route, review approval UI)

## Key Design Decisions (Issue #131)

- `EnsembleDashboard.onEnsembleStarted/Completed()` added as default no-op interface
  methods -- backward-compatible; existing implementations not broken
- `Ensemble.dashboard` field stored alongside listener/reviewHandler so
  `runWithInputs()` can call lifecycle hooks before/after `executor.execute()`
- Snapshot format: JSON array of all broadcast messages (including `ensemble_started`)
  rather than a serialized `ExecutionTrace` -- simpler, and the client-side `liveReducer`
  can replay the array to reconstruct state
- `CopyOnWriteArrayList` chosen for snapshot messages: safe for concurrent appends from
  parallel workflow virtual threads; read (for hello) is a point-in-time snapshot
- `noteEnsembleStarted()` clears the snapshot before recording new metadata so
  sequential re-runs do not accumulate events across runs

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
