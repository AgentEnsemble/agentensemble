# Active Context

## Current Focus

Branch `fix/task-first-synthesis-fallback-and-template-role-extraction` is open to address
two interacting bugs in the task-first agent synthesis path:

**Problem A -- `LlmBasedAgentSynthesizer` silently swallowed ChatModel execution failures**
- The `catch (Exception e)` block in `LlmBasedAgentSynthesizer.synthesize()` caught
  `RuntimeException("Not implemented")` (the LangChain4j 1.11.0 default `doChat()` stub)
  and silently fell back to `TemplateAgentSynthesizer`, masking the real configuration error.

**Problem B -- `TemplateAgentSynthesizer` only scanned the first word of the task description**
- Task-first patterns like `"Role: Analyst. Analyse the market data"` or
  `"Based on the research, write a report"` started with words not in the verb-to-role table,
  so the role always degraded to the generic `"Agent"` fallback.

**Combined failure chain:**
1. `AgentSynthesizer.llmBased()` configured, test/deterministic model returns non-JSON or throws
2. Synthesis fails silently; fallback to template
3. Template derives role `"Agent"` because description starts with `"Role:"` or a preposition
4. Agent executes; `ChatModel.doChat()` default throws `RuntimeException("Not implemented")`
5. Error message: `Agent 'Agent' failed: Not implemented` -- misleading on both counts

**Fixes implemented (branch: `fix/task-first-synthesis-fallback-and-template-role-extraction`):**
- `LlmBasedAgentSynthesizer`: ChatModel execution call moved outside `try/catch`; only
  `JsonProcessingException` and `IllegalStateException` (parse/field-validation failures)
  fall back to template. RuntimeExceptions from the model propagate immediately.
- `TemplateAgentSynthesizer`: `extractRole()` now scans the first `VERB_SCAN_LIMIT` (8)
  words instead of just the first word. First match wins.
- `PassthroughChatModel`: updated to override `doChat(ChatRequest)` instead of
  `chat(ChatRequest)` to align with the LangChain4j 1.11.0 API convention.

**Tests added:**
- 7 new tests in `TemplateAgentSynthesizerTest`: verb-after-preposition, role-prefixed
  description, article-before-verb, preamble scan, beyond-scan-limit, second-word match
- 2 new tests in `LlmBasedAgentSynthesizerTest`: `modelThrowsRuntimeException_propagatesException`,
  `modelThrowsNotImplemented_propagatesException`
- Previous `synthesize_llmThrowsException_fallsBackToTemplate` test replaced with the two
  above to express the correct propagation contract

**Build status:** All agentensemble-core tests pass; `BUILD SUCCESSFUL`

Previous branches:
- `feat/135-viz-review-approval-ui` is open for Issue #135: Viz review approval UI (v2.1.0).

Issue #135 implementation complete:
- `RESOLVE_REVIEW` action added to `LiveAction` union in `live.ts`; handled in `liveActionReducer` in `liveReducer.ts`
- `sendDecision(reviewId, decision, revisedOutput?)` added to `LiveServerContext` (builds `ReviewDecisionMessage`, calls `sendMessage`, dispatches `RESOLVE_REVIEW` for optimistic removal)
- `ReviewApprovalPanel` component created at `src/components/live/ReviewApprovalPanel.tsx`:
  - Modal overlay with header (timing badge), task description, custom prompt, scrollable output
  - Three modes: view (Approve/Edit/Exit Early) / edit (textarea pre-filled with output) / exit-confirm (confirmation step) / timed-out (message for 2s then closes)
  - CSS-animated countdown bar (`ae-countdown-bar` class + `animation-duration`/`animation-delay` inline) + 1s JS interval for text label
  - "+N pending" badge when `additionalPendingCount > 0`
  - `key={review.reviewId}` in parent ensures clean remount for each new review
- `LivePage.tsx` integrated: renders `ReviewApprovalPanel` when `pendingReviews.length > 0` (FIFO, oldest first)
- `src/index.css`: `ae-countdown-bar` utility class + `ae-countdown-shrink` keyframe animation added
- 237 tests pass (30 new: 25 `ReviewApprovalPanel` unit tests + 5 `RESOLVE_REVIEW` reducer tests)
- `docs/examples/human-in-the-loop.md` "Browser-Based Approval" section updated with accurate interaction description (panel ASCII diagram, Actions sections, Timeout Countdown, Concurrent Reviews)

Previous branches:
- `fix/viz-cli-binary-asset-embedding` is open and ready for PR.

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
