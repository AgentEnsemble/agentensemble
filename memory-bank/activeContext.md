# Active Context

## Current Focus

Issue #61 (Token-by-token streaming via StreamingChatModel) is complete on branch
`feature/61-streaming-output`. PR is ready for review and merge.

- **#61** (Streaming Output: token-by-token streaming via StreamingChatModel, v2.1.0): Done
- **#132** (WebReviewHandler -- real implementation replacing stub, v2.1.0): Done (merged to main)
- **#133** (Viz live mode -- WebSocket client + incremental state machine): Done
- **#134** (Viz live timeline and flow view updates): Done

## Key accomplishments

### Issue #61 -- Streaming Output (branch: feature/61-streaming-output)

New opt-in token-by-token streaming of the final agent response using LangChain4j's
`StreamingChatModel` (LangChain4j 1.11.0 API). Streaming flows through the EnsembleListener
callback system and the WebSocket wire protocol to the viz dashboard.

**Core (agentensemble-core):**
- `TokenEvent` record: `token` + `agentRole` fields
- `EnsembleListener.onToken(TokenEvent)` default no-op + `Ensemble.builder().onToken(handler)` lambda
- `streamingChatLanguageModel(StreamingChatModel)` on `Ensemble.builder()` (ensemble-level)
- `streamingChatLanguageModel(StreamingChatModel)` on `Task.builder()` (task-level override)
- `streamingLlm(StreamingChatModel)` on `Agent.builder()` (agent-level override)
- `ExecutionContext.streamingChatModel()` accessor + `fireToken()` dispatch + 11-arg `of()` factory
- `AgentExecutor.resolveStreamingModel()` -- priority chain: agent > task > ensemble
- `AgentExecutor.executeStreaming()` -- `StreamingChatResponseHandler` fires `TokenEvent` per token
- `AgentExecutor.executeWithoutTools()` updated to use streaming when resolved; tool-loop remains sync

**Wire protocol (agentensemble-web):**
- `TokenMessage` record (type=`token`, token, agentRole, sentAt)
- Registered in `ServerMessage` sealed interface + `@JsonSubTypes`
- `WebSocketStreamingListener.onToken()` broadcasts via `broadcastEphemeral()` -- NOT added to snapshot

**Viz dashboard (agentensemble-viz):**
- `TokenMessage` interface added to `ServerMessage` discriminated union in `live.ts`
- `streamingOutput?: string` field added to `LiveTask`
- `liveReducer.applyToken()` accumulates tokens by agentRole; `applyTaskCompleted()` clears it
- `TimelineView.LiveTaskDetailPanel` shows **Live Output** section with pulsing cursor
- `LiveTimelineView` uses `selectedTaskIndex` (not stale snapshot) for live updates

**Tests added:** 7 AgentExecutorStreamingTest + 6 TokenEventTest + 2 ProtocolSerializationTest +
  4 WebSocketStreamingListenerTest + 7 liveReducer.test.ts = 26 new tests
- Total viz tests: 173 (was 166)

## Test Summary
- agentensemble-viz: 173 tests pass across 9 test files
- agentensemble-core: all tests pass, JaCoCo coverage maintains thresholds
- agentensemble-web: all tests pass including new token tests

## Next Steps
- Issue #61 PR ready for review
- Issue #129 epic: G1 done; G2 done; H1 (#132) done; I1 (#133) done; I2 (#134) done; #61 done.
  Outstanding: H2 (review approval UI), J (docs/examples)
- H2 depends on H1 (done) and I1 (done) -- can now be started

## Key Design Decisions (Issue #61)

- Streaming only on `executeWithoutTools` path: tool-loop iterations require full responses
  to detect tool-call requests; streaming only makes sense when the agent produces a direct answer
- Resolution order: agent > task > ensemble to give maximum flexibility without conflicts
- `TokenMessage` is NOT added to the late-join snapshot: tokens are ephemeral; `task_completed`
  carries the authoritative final output; late joiners see the task as running and receive only
  future tokens from that point forward
- `broadcastEphemeral()` vs `broadcast()`: separate method to avoid forgetting to skip snapshot
- `selectedTaskIndex` (number) vs `selectedTask` (LiveTask) in LiveTimelineView: using index
  means the detail panel always reads from current `liveState.tasks` so streaming updates appear
  without requiring the user to re-click the task bar

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
