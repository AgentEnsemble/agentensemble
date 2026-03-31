# 27 - Viz Observability: Tool & Agent I/O Visibility

**Status:** Complete (all five issues implemented)  
**Version:** 1.1.0  
**Date:** 2026-03-31

## 1. Motivation

The `agentensemble-viz` live dashboard shows the _flow_ of execution -- which tasks
are running, which agents are active, when tools are called, and when tasks complete.
However, it lacks insight into the _content_ of those interactions:

- **What arguments were passed to a tool?** The viz shows "calculator called" but not
  that `{"expression": "42 * 17"}` was the input.
- **What did the tool return?** The viz shows duration and name but not the result text.
- **What was the agent asked to do?** The task description is visible, but the full
  assembled context (upstream outputs, expected output, agent goal/background, available
  tools) is not surfaced as a first-class concept.
- **What did the LLM actually say?** The iteration events carry the full message buffer
  and response text, but the viz does not render them in an inspectable conversation view.
- **Late-joining clients miss conversation history.** LLM iteration messages are
  broadcast as ephemeral events, so a user who opens the dashboard mid-execution sees no
  prior reasoning chain.

This design addresses all five gaps with three Java-side changes and two viz-side
changes, organized as five issues (IO-001 through IO-005):

- [#285](https://github.com/AgentEnsemble/agentensemble/issues/285) IO-001: Enrich ToolCallEvent with task context and outcome
- [#286](https://github.com/AgentEnsemble/agentensemble/issues/286) IO-002: Add TaskInputEvent for first-class agent input capture
- [#287](https://github.com/AgentEnsemble/agentensemble/issues/287) IO-003: Persist LLM iteration data in late-join snapshots
- [#288](https://github.com/AgentEnsemble/agentensemble/issues/288) IO-004: Viz tool call detail panel with formatted I/O
- [#289](https://github.com/AgentEnsemble/agentensemble/issues/289) IO-005: Viz agent conversation thread view

## 2. Current State

### 2.1 Data Already Flowing

The Java event system and WebSocket protocol already carry rich I/O data:

| Data | Java Event Field | Wire Message Field |
|------|------------------|--------------------|
| Tool arguments | `ToolCallEvent.toolArguments()` | `ToolCalledMessage.toolArguments` |
| Tool result text | `ToolCallEvent.toolResult()` | `ToolCalledMessage.toolResult` |
| Tool structured result | `ToolCallEvent.structuredResult()` | `ToolCalledMessage.structuredResult` |
| LLM input (message buffer) | `LlmIterationStartedEvent.messages()` | `LlmIterationStartedMessage.messages` |
| LLM response text | `LlmIterationCompletedEvent.responseText()` | `LlmIterationCompletedMessage.responseText` |
| LLM tool requests | `LlmIterationCompletedEvent.toolRequests()` | `LlmIterationCompletedMessage.toolRequests` |
| Task output | `TaskCompleteEvent.taskOutput()` | `TaskCompletedMessage.output` |

### 2.2 Gaps

| Gap | Root Cause |
|-----|-----------|
| Tool calls not associated with tasks | `ToolCallEvent` has no `taskIndex`; listener hardcodes `0` |
| Tool success/failure not distinguished | `ToolCallEvent` has no `outcome`; listener sends `null` |
| No first-class "agent input" concept | No event captures the assembled task context before the first LLM call |
| Late-join clients miss conversations | `LlmIterationStarted/CompletedMessage` sent via `broadcastEphemeral()` |
| Viz does not render tool I/O details | Data arrives but no UI component displays it |
| Viz does not render conversation threads | Data arrives but no dedicated conversation view exists |

## 3. Design

### 3.1 IO-001: Enrich ToolCallEvent

**Module:** `agentensemble-core`, `agentensemble-web`

Add two fields to `ToolCallEvent`:

```java
public record ToolCallEvent(
        String toolName,
        String toolArguments,
        String toolResult,
        Object structuredResult,
        String agentRole,
        Duration duration,
        int taskIndex,       // NEW
        String outcome        // NEW: "SUCCESS" or "FAILURE"
) {}
```

**Emission changes in `AgentExecutor`:**

- `executeSingleTool()`: wrap tool execution in try/catch; set outcome based on whether
  the tool threw. Pass the task index from the execution context.
- `executeParallelTools()`: same pattern for parallel execution.

**Wire message update:**

`WebSocketStreamingListener.onToolCall()` uses `event.taskIndex()` and `event.outcome()`
instead of the current hardcoded `0` and `null`.

`ToolCalledMessage` already has `taskIndex` and `outcome` fields -- no wire format change
needed.

**Backward compatibility:** The record gains trailing fields. Existing
`EnsembleListener.onToolCall()` implementations receive the enriched event without code
changes since they already accept `ToolCallEvent`.

### 3.2 IO-002: TaskInputEvent

**Module:** `agentensemble-core`, `agentensemble-web`, `agentensemble-viz`

New event capturing the fully assembled agent input at task start:

```java
public record TaskInputEvent(
        int taskIndex,
        String taskDescription,
        String expectedOutput,
        String agentRole,
        String agentGoal,
        String agentBackground,
        List<String> toolNames,
        String assembledContext
) {}
```

- `assembledContext` is the complete prompt context string built from upstream task
  outputs and any additional context configured on the task.

**Listener extension:**

```java
default void onTaskInput(TaskInputEvent event) {}
```

**Firing point:** In `AgentExecutor`, after assembling the context (merging upstream
outputs into the task context) but before the first LLM call. This is distinct from
`TaskStartEvent` which fires earlier at the workflow level.

**Wire protocol:**

New `TaskInputMessage` implementing `ServerMessage`:

```java
public record TaskInputMessage(
        int taskIndex,
        String taskDescription,
        String expectedOutput,
        String agentRole,
        String agentGoal,
        String agentBackground,
        List<String> toolNames,
        String assembledContext,
        Instant sentAt
) implements ServerMessage {}
```

JSON type name: `task_input`.

**Viz types:**

```typescript
export interface TaskInputMessage {
  type: 'task_input';
  taskIndex: number;
  taskDescription: string;
  expectedOutput: string;
  agentRole: string;
  agentGoal: string;
  agentBackground: string | null;
  toolNames: string[];
  assembledContext: string;
  sentAt: string;
}
```

**Reducer:** Store on `LiveTask` as `taskInput?: TaskInputMessage`.

### 3.3 IO-003: Persist LLM Iterations in Late-Join Snapshots

**Module:** `agentensemble-web`

**Problem:** `broadcastEphemeral()` means late-joining clients never see prior LLM
iterations. The conversation history is lost.

**Solution:**

1. `WebSocketStreamingListener` maintains a per-task ring buffer of the last N
   iteration pairs (`LlmIterationStarted` + `LlmIterationCompleted`), where N defaults
   to 5 and is configurable via `WebDashboard.builder().maxSnapshotIterations(int)`.

2. The `hello` snapshot message gains an optional `recentIterations` field:

```java
public record HelloMessage(
        // ... existing fields ...
        List<IterationSnapshot> recentIterations  // NEW
) implements ServerMessage {}
```

Where `IterationSnapshot` pairs a started + completed message for one iteration.

3. When a new client connects and receives the `hello` message, the viz `liveReducer`
   hydrates the conversation state from `recentIterations`.

4. The ring buffer is cleared when a new ensemble run starts.

### 3.4 IO-004: Viz Tool Call Detail Panel -- COMPLETE

**Module:** `agentensemble-viz`

Expand tool call entries in the live conversation/timeline view:

- **Expandable card:** Click a tool call to expand and see:
  - `toolArguments` rendered as syntax-highlighted JSON
  - `toolResult` rendered as formatted text (auto-detected JSON gets highlighting)
  - `structuredResult` rendered as a collapsible JSON tree if present
  - `outcome` badge: green "SUCCESS" or red "FAILURE"
  - `durationMs` shown as a timing badge
- **Task association:** Group tool calls under their parent task using `taskIndex`
- **Collapsed state:** Shows tool name, outcome badge, and duration as a compact row

### 3.5 IO-005: Viz Agent Conversation Thread View -- COMPLETE

**Module:** `agentensemble-viz`

New `AgentConversationPanel` component showing the full ReAct reasoning chain per task:

- **Header:** Task input summary from `TaskInputEvent` -- agent role, goal, available
  tools, assembled context (collapsible)
- **Iteration cards:** Each LLM iteration rendered as:
  1. **Input section:** Messages sent to the LLM (system prompt, user message, prior
     assistant/tool messages) -- rendered as chat bubbles with role labels
  2. **Output section:** LLM response -- either final answer text or tool call requests
  3. **Tool results:** If the iteration produced tool calls, show the tool results
     inline before the next iteration
- **Metadata:** Token usage (input/output) and latency per iteration
- **Navigation:** Click an iteration to jump to it; current iteration highlighted

## 4. Issue Dependency Graph

```
IO-001 (enrich ToolCallEvent)     --+
                                    +--> IO-004 (viz tool panel)
IO-002 (TaskInputEvent)           --+
                                    +--> IO-005 (viz conversation view)
IO-003 (late-join snapshots)      --+
```

IO-001, IO-002, and IO-003 are independent Java-side changes.
IO-004 and IO-005 depend on the Java work being complete.

## 5. Testing Strategy

### IO-001
- **Unit:** `ToolCallEventTest` verifying new fields; `AgentExecutorTest` verifying
  taskIndex and outcome are populated correctly for both success and failure cases
- **Integration:** `WebSocketStreamingListenerTest` verifying the wire message contains
  real taskIndex and outcome values

### IO-002
- **Unit:** `TaskInputEventTest`; verify event fired from `AgentExecutor` with correct
  assembled context
- **Integration:** `WebSocketStreamingListenerTest` verifying `TaskInputMessage`
  broadcast; end-to-end test that the event fires during a real ensemble run

### IO-003
- **Unit:** Ring buffer capacity and eviction; `HelloMessage` serialization with
  `recentIterations`
- **Integration:** Connect a second WebSocket client mid-execution and verify it
  receives iteration history in the hello snapshot

### IO-004 / IO-005
- **Unit:** Component tests with mock data verifying rendering of tool arguments, tool
  results, conversation messages, token badges
- **E2E:** Playwright test verifying tool detail expansion and conversation thread
  rendering during a live ensemble run

## 6. Design Decisions

| Decision | Rationale |
|----------|-----------|
| Add fields to existing `ToolCallEvent` rather than a new event | The information is intrinsically part of the tool call; a separate event would fragment the lifecycle |
| `TaskInputEvent` is separate from `TaskStartEvent` | `TaskStartEvent` fires at the workflow level before context assembly; `TaskInputEvent` fires at the executor level after context is assembled -- they serve different purposes |
| Ring buffer for iteration snapshots rather than full history | Unbounded storage of all iterations could consume significant memory for long-running ensembles; a configurable cap keeps it bounded |
| Ephemeral flag retained for real-time iteration messages | Late-join snapshot handles history; real-time messages still skip the snapshot buffer for performance |
| `outcome` as String rather than enum in the event record | Keeps the callback package free of additional enum types; the values are a closed set ("SUCCESS", "FAILURE") documented in Javadoc |
