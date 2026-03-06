# Active Context

## Current Focus

Issues #133 and #134 are complete on branch `feat/133-134-viz-live-mode`.

- **#133** (Viz live mode -- WebSocket client + incremental state machine): Done
- **#134** (Viz live timeline and flow view updates): Done

## Key accomplishments

### Issue #133 -- WebSocket client + state machine
- `src/types/live.ts`: Wire protocol types (ServerMessage discriminated union, ClientMessage, LiveState, LiveTask, ConnectionStatus, LiveAction)
- `src/utils/liveReducer.ts`: Pure reducer handling all 9 server message types + connection lifecycle actions; 37 unit tests pass
- `src/contexts/LiveServerContext.tsx`: React context managing WebSocket lifecycle, exponential backoff reconnect (1s/2s/4s/8s/16s/30s cap); 17 unit tests pass
- `src/components/shared/ConnectionStatusBar.tsx`: Green/amber/red status bar with ae-pulse dot for connecting state; 15 unit tests pass
- `src/pages/LivePage.tsx`: /live route page; auto-connects from ?server= query param; wraps content in LiveServerProvider
- `src/pages/LoadTrace.tsx`: Added "Connect to live server" form that navigates to /live?server=<url>
- `src/main.tsx`: BrowserRouter with /live -> LivePage and /* -> App; added react-router-dom dependency
- `src/pages/LoadTrace.tsx`: useNavigate-based navigation to /live?server=<url>

### Issue #134 -- Live timeline and flow view updates
- `src/pages/TimelineView.tsx`: Added isLive prop; LiveTimelineView renders from LiveServerContext; task bars appear on task_started; running bars grow via rAF; bars lock on task_completed; failed bars render red; tool markers positioned at receivedAt; "Follow latest" toggle with auto-scroll + re-engage at right edge; 19 unit tests pass
- `src/pages/FlowView.tsx`: Added isLive prop; LiveFlowViewInner builds synthetic DagModel via buildSyntheticDagModel; applies liveStatus overrides to ReactFlow nodes; 16 unit tests pass (TaskNode component level)
- `src/utils/liveDag.ts`: buildSyntheticDagModel (sequential chain / parallel deps); buildLiveStatusMap (running/failed/completed); liveTaskNodeId; 14 unit tests pass
- `src/components/live/LiveHeader.tsx`: Header bar with ensemble ID, workflow, task count, Flow/Timeline toggle
- `src/components/graph/TaskNode.tsx`: Added liveStatus prop; running = blue + ae-pulse + ae-node-pulse; failed = red; completed = agent color
- `src/index.css`: ae-pulse and ae-node-pulse CSS keyframe animations; live node status classes

## Test Summary
- 163 tests pass across 9 test files
- Build: TypeScript + Vite build clean (exit 0)

## Next Steps
- Open PR for feat/133-134-viz-live-mode against main
- Issue #135 (Review approval UI -- H2): depends on H1 (WebReviewHandler real implementation) and I1 (this branch)
- Issue #129 epic notes: G1 (agentensemble-web module) done; G2 (WebSocketStreamingListener) done; I1 done; I2 done. Outstanding: H1 (WebReviewHandler real impl), H2 (review UI), J (docs/examples)
