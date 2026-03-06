# Active Context

## Current Focus

Branch `fix/147-148-149-core-runtime-deps-and-context-identity` — three bugs fixed in
`agentensemble-core`, committed, ready for PR.

**Bug #147** — `agentensemble-memory` and `agentensemble-review` were declared `compileOnly`
in `agentensemble-core/build.gradle.kts` despite core referencing their types directly in 14+
source files. Fixed by changing both to `api`.

**Bug #148** — `Ensemble.resolveAgents()` created new `Task` instances for agentless tasks
(pass 1) but never re-mapped the context references on downstream tasks to the new identities
(missing pass 2). The workflow executor stores completed outputs by identity, so the stale
reference caused `gatherContextOutputs` to fail with a misleading null lookup, then NPE on
`contextTask.getAgent().getRole()` in the error handler. Fixed by adding a context re-mapping
pass (identical pattern to `resolveTasks()`), with map updates on each new identity so chained
deps (t1->t2->t3) all resolve correctly.

**Bug #149** — downstream effect of #148; once context identity is correct, synthesized agents
with task-level tools and models run through the normal `executeWithTools` path without issue.

**Null-safety**: Added `agentRole(Task)` helper to `SequentialWorkflowExecutor` and
`ParallelTaskCoordinator` as a defensive guard for error-handling paths.

**9 new integration tests** in `SequentialTaskFirstContextIntegrationTest` covering all
scenarios (2-task context dep, 3-task chain, ensemble LLM, mixed explicit/agentless,
context-in-prompt verification, tool loop, tools+context combined).

Previous focus: Branch `fix/viz-cli-binary-asset-embedding` is open and ready for PR.

The Homebrew-installed `agentensemble-viz` binary showed "Not Found" on every request.
Root cause: Bun's `--compile` bundler does not recursively embed entire directories
referenced via `new URL('./dist/', import.meta.url)`. All `readFileSync` calls using
dynamically-joined paths from `distDir` hit an absent filesystem in the compiled binary.

Fix implemented:
- `scripts/embed-dist.mjs`: build-time script that reads all non-source-map files from
  `dist/` and generates `dist-assets.js` with each file's content as a base64 string
  inside a JavaScript Map
- `dist-assets.js`: committed as an empty placeholder (empty Map); populated by
  `npm run embed` before binary compilation
- `cli.js`: statically imports `dist-assets.js`; decodes base64 into Buffers at startup;
  `useEmbedded=true` when map is populated (binary mode) -- serves from memory; `false`
  when placeholder (dev mode) -- falls back to filesystem
- `NO_OPEN=1` env var added to suppress browser opening in tests/CI
- Compile scripts updated: `build -> embed -> bun build --compile`
- `prepublishOnly` also runs embed for npm distribution

Previous focus: Issues #133 and #134 are complete on branch `feat/133-134-viz-live-mode`.
Issue #132 (WebReviewHandler real implementation) is complete on `main`.

- **#132** (WebReviewHandler -- real implementation replacing stub, v2.1.0): Done (merged to main)
- **#133** (Viz live mode -- WebSocket client + incremental state machine): Done
- **#134** (Viz live timeline and flow view updates): Done

## Key accomplishments

### Issue #132 -- WebReviewHandler (merged to main)

**Breaking change**: `ReviewHandler.web(URI)` factory removed from `agentensemble-review`;
the stub `WebReviewHandler` in that module deleted. The real implementation has always been
`net.agentensemble.web.WebReviewHandler` in `agentensemble-web`, obtained via
`WebDashboard.reviewHandler()`.

Key changes:
- `ReviewHandler.web(URI)` factory and the URI-based `WebReviewHandler` stub removed from
  `agentensemble-review`
- `ConnectionManager.resolveReview()` now logs at DEBUG when an unknown reviewId is received
- `WebReviewHandlerTest` extended with 2 new tests (concurrent reviews, unknown reviewId race)
- `WebReviewGateIntegrationTest` created (4 tests, real embedded server + Java WS client):
  CONTINUE, EDIT, EXIT_EARLY decision flows + timeout with `review_timed_out` broadcast

### Issue #133 -- WebSocket client + state machine
- `src/types/live.ts`: Wire protocol types (ServerMessage discriminated union, ClientMessage, LiveState, LiveTask, ConnectionStatus, LiveAction)
- `src/utils/liveReducer.ts`: Pure reducer handling all 9 server message types + connection lifecycle actions; 37 unit tests pass
- `src/contexts/LiveServerContext.tsx`: React context managing WebSocket lifecycle, exponential backoff reconnect (1s/2s/4s/8s/16s/30s cap); 17 unit tests pass
- `src/components/shared/ConnectionStatusBar.tsx`: Green/amber/red status bar with ae-pulse dot for connecting state; 15 unit tests pass
- `src/pages/LivePage.tsx`: /live route page; auto-connects from ?server= query param; wraps content in LiveServerProvider
- `src/pages/LoadTrace.tsx`: Added "Connect to live server" form that navigates to /live?server=<url>
- `src/main.tsx`: BrowserRouter with /live -> LivePage and /* -> App; added react-router-dom dependency

### Issue #134 -- Live timeline and flow view updates
- `src/pages/TimelineView.tsx`: Added isLive prop; LiveTimelineView renders from LiveServerContext; task bars appear on task_started; running bars grow via rAF; bars lock on task_completed; failed bars render red; tool markers positioned at receivedAt; "Follow latest" toggle with auto-scroll + re-engage at right edge; 19 unit tests pass
- `src/pages/FlowView.tsx`: Added isLive prop; LiveFlowViewInner builds synthetic DagModel via buildSyntheticDagModel; applies liveStatus overrides to ReactFlow nodes; 16 unit tests pass (TaskNode component level)
- `src/utils/liveDag.ts`: buildSyntheticDagModel (sequential chain / parallel deps); buildLiveStatusMap (running/failed/completed); liveTaskNodeId; 14 unit tests pass
- `src/components/live/LiveHeader.tsx`: Header bar with ensemble ID, workflow, task count, Flow/Timeline toggle
- `src/components/graph/TaskNode.tsx`: Added liveStatus prop; running = blue + ae-pulse + ae-node-pulse; failed = red; completed = agent color
- `src/index.css`: ae-pulse and ae-node-pulse CSS keyframe animations; live node status classes

## Test Summary
- agentensemble-viz: 166 tests pass across 9 test files; TypeScript + Vite build clean
- agentensemble-web: All existing tests pass; 6 new tests for #132 (2 unit + 4 integration)

## PR #144 Copilot Review Fixes (commit d1a7604)

Six issues addressed after Copilot review of PR #144:

1. **live.ts type alignment**: `HelloMessage.ensembleId`/`startedAt` made nullable (Java
   uses `@JsonInclude(NON_NULL)`); `LiveTask.taskIndex` doc corrected to 1-based; added
   missing `ToolCalledMessage` fields (`toolArguments`, `toolResult`, `structuredResult`,
   `outcome: string | null`) to match Java wire protocol.

2. **liveDag buildLiveStatusMap**: Removed `else` branch so completed tasks are truly
   absent from the map (doc said "NOT included"; impl was setting key to undefined).

3. **TimelineView LlmDetailPanel regression**: Restored `msg.toolCalls` and `msg.toolName`
   rendering in message history, and the per-interaction "Tool Calls" section that were
   lost during the live mode refactor.

4. **live-dashboard.md**: Added BOM dependency context, Java import statements, and
   imports to the configuration snippet so examples are copy/paste runnable.

5. **snapshotTrace replay**: Rewrote `applyHello` to treat `snapshotTrace` as
   `ServerMessage[]` and replay through `liveReducer` -- the Java `ConnectionManager`
   sends an array of broadcast messages, not an ExecutionTrace-shaped object.

6. **tool_called fallback**: Added agentRole-based fallback (then any running task) when
   `taskIndex` is 0 -- Java `WebSocketStreamingListener.onToolCall()` always sends 0;
   normalized null `outcome` to `'UNKNOWN'` at the boundary.

## Next Steps
- Issue #129 epic notes: G1 done; G2 done; H1 (#132) done; I1 (#133) done; I2 (#134) done. Outstanding: H2 (review approval UI), J (docs/examples)
- H2 depends on H1 (done) and I1 (done) -- can now be started
- PR #144 updated with Copilot review fixes; ready for merge

## Key Design Decisions (Issue #132)

- Breaking change chosen over deprecation: `ReviewHandler.web(URI)` was always a stub
  that threw UnsupportedOperationException, so callers had no working code to migrate;
  removing it cleanly is better than emitting a deprecation warning for years
- Real `WebReviewHandler` lives in `agentensemble-web` (not `agentensemble-review`) because
  it depends on `ConnectionManager`, `MessageSerializer`, and the Javalin server
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

### agentensemble-viz live mode (v2.1.0, Issues #133/#134)

- `/live?server=ws://localhost:7329/ws` is the entry point for live mode
- `LiveServerContext` manages WebSocket lifecycle with exponential backoff reconnect
- `liveReducer` is a pure function -- all server messages reduce into `LiveState`
- `buildSyntheticDagModel(liveState)` builds a disposable DagModel for the ReactFlow layout
- `TaskNode.liveStatus` drives color/animation without modifying the existing agent color system
- `ae-pulse` (opacity keyframe) and `ae-node-pulse` (box-shadow ring) are CSS-only animations

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
