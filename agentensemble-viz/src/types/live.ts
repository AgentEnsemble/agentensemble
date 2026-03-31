/**
 * TypeScript types for the AgentEnsemble live execution mode.
 *
 * These types cover:
 * - The WebSocket wire protocol (server->client and client->server messages)
 * - The incremental live state maintained by liveReducer
 * - The connection lifecycle
 *
 * Wire protocol messages mirror the Java classes in net.agentensemble.web.protocol.
 */

// ========================
// Connection
// ========================

/** WebSocket connection lifecycle status. */
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

// ========================
// Live task state
// ========================

/** Execution status of a single task in live mode. */
export type LiveTaskStatus = 'running' | 'completed' | 'failed';

/** A live tool call record appended when a tool_called message arrives. */
export interface LiveToolCall {
  toolName: string;
  durationMs: number;
  outcome: string;
  /** Client-side epoch ms when the message was received, used for timeline positioning. */
  receivedAt: number;
  /** 1-based task index from the server. */
  taskIndex: number;
  /** Arguments passed to the tool as a JSON string. Null when unavailable. */
  toolArguments: string | null;
  /** Text result returned by the tool. Null when unavailable. */
  toolResult: string | null;
  /** Structured output from the tool. Null when not applicable. */
  structuredResult: unknown | null;
}

/**
 * Incremental state of a single task, built from task_started / task_completed /
 * task_failed / tool_called / token messages.
 */
export interface LiveTask {
  /** 1-based task index as sent by the server (matches Java TaskStartedMessage.taskIndex). */
  taskIndex: number;
  taskDescription: string;
  agentRole: string;
  status: LiveTaskStatus;
  /** ISO-8601 timestamp from task_started. */
  startedAt: string;
  /** ISO-8601 timestamp from task_completed. Null while task is still running. */
  completedAt: string | null;
  /** ISO-8601 timestamp from task_failed. Null unless task failed. */
  failedAt: string | null;
  /** Actual duration in ms from task_completed message. Null while running. */
  durationMs: number | null;
  tokenCount: number | null;
  toolCallCount: number | null;
  toolCalls: LiveToolCall[];
  /** Failure reason from task_failed message. Null unless task failed. */
  reason: string | null;
  /**
   * Accumulated streaming token chunks from token messages.
   * Stored as an array of chunks (not a concatenated string) to keep appending O(1).
   * Join with {@code .join('')} for display.
   * Present while the task is running and a StreamingChatModel is configured.
   * Cleared to undefined when task_completed arrives (the final output is authoritative).
   */
  streamingOutput?: string[];
  /**
   * Task input context captured from the task_input message (IO-002).
   * Contains the assembled context, agent metadata, and tool names that the agent
   * was given before its first LLM call. Undefined until the task_input message arrives.
   */
  taskInput?: LiveTaskInput;
}

/**
 * Task input context captured from the TaskInputEvent (IO-002).
 * Represents what the agent was given to work with at the start of a task.
 */
export interface LiveTaskInput {
  taskIndex: number;
  taskDescription: string;
  expectedOutput: string;
  agentRole: string;
  agentGoal: string;
  agentBackground: string;
  toolNames: string[];
  assembledContext: string;
}

// ========================
// Live delegation state
// ========================

/** Execution status of a single delegation in live mode. */
export type LiveDelegationStatus = 'active' | 'completed' | 'failed';

/**
 * Live state for a single agent-to-agent delegation, built from
 * delegation_started / delegation_completed / delegation_failed messages.
 *
 * Used by LiveTimelineView to render indented worker lanes beneath the
 * parent (delegating) agent lane in HIERARCHICAL workflow runs.
 */
export interface LiveDelegation {
  /** Unique correlation ID shared across start, completed, and failed events. */
  delegationId: string;
  /** Role of the agent that initiated the delegation (typically "Manager"). */
  delegatingAgentRole: string;
  /** Role of the worker agent receiving the delegated task. */
  workerRole: string;
  /** Description of the delegated sub-task. */
  taskDescription: string;
  /** Current status of this delegation. */
  status: LiveDelegationStatus;
  /** Client-side epoch ms when delegation_started was received. */
  startedAt: number;
  /** Client-side epoch ms when delegation_completed or delegation_failed was received. */
  endedAt: number | null;
  /** Duration in ms from delegation_completed message. Null until completed. */
  durationMs: number | null;
  /** Failure reason from delegation_failed message. Null unless failed. */
  reason: string | null;
}

// ========================
// Completed run (archived from live state when a new run starts)
// ========================

/**
 * A snapshot of a completed ensemble run archived when the next run starts.
 * Used to render stacked read-only timeline sections in the live view.
 */
export interface CompletedRun {
  /** Ensemble ID of the completed run, or null when not yet received. */
  ensembleId: string | null;
  /** Workflow strategy used for this run. */
  workflow: string | null;
  /** ISO-8601 timestamp when this run started. */
  startedAt: string | null;
  /** ISO-8601 timestamp when this run completed. Null when the run ended without ensemble_completed. */
  completedAt: string | null;
  /** Total expected task count from ensemble_started. */
  totalTasks: number;
  /**
   * Snapshot of the tasks at the time the run was archived.
   * The tasks array itself is shallow-copied; LiveTask objects within (and their nested
   * arrays such as toolCalls) are shared with the live state until they are replaced
   * by the next ensemble_started.
   */
  tasks: LiveTask[];
  /**
   * Snapshot of the delegations at the time the run was archived (HIERARCHICAL workflow).
   * The delegations array itself is shallow-copied; LiveDelegation objects within are
   * shared with the live state until they are replaced by the next ensemble_started.
   */
  delegations: LiveDelegation[];
}

// ========================
// Pending reviews
// ========================

/** A pending review request received from the server. */
export interface LiveReviewRequest {
  reviewId: string;
  taskDescription: string;
  taskOutput: string;
  timing: string;
  prompt: string | null;
  timeoutMs: number;
  onTimeout: string;
  /** Optional role that a human must have to approve this review. */
  requiredRole: string | null;
  /** Client-side epoch ms when the request was received (for countdown). */
  receivedAt: number;
}

// ========================
// Live conversation state
// ========================

/** A single message in a live agent conversation. */
export interface LiveConversationMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  toolCalls?: Array<{ name: string; arguments: string }>;
  toolName?: string;
  /** Client-side epoch ms when this message was received. */
  timestamp: number;
}

/**
 * A single LLM iteration within a conversation (IO-005).
 * Groups the input messages sent to the LLM with its response and metadata.
 */
export interface LiveIteration {
  iterationIndex: number;
  /** Messages sent to the LLM (from LlmIterationStartedMessage). */
  inputMessages: LiveConversationMessage[];
  /** Response type: 'FINAL_ANSWER' or 'TOOL_CALLS'. */
  responseType?: string;
  /** LLM response text (final answer or intermediate). */
  responseText?: string | null;
  /** Tool call requests made by the LLM. */
  toolRequests?: Array<{ name: string; arguments: string }>;
  /** Input token count for this iteration. */
  inputTokens?: number;
  /** Output token count for this iteration. */
  outputTokens?: number;
  /** LLM latency in ms. */
  latencyMs?: number;
  /** Whether this iteration is still in progress (started but not completed). */
  pending: boolean;
}

/** Live conversation state for a single agent+task execution. */
export interface LiveConversation {
  agentRole: string;
  taskDescription: string;
  iterationIndex: number;
  messages: LiveConversationMessage[];
  /** True between iteration_started and iteration_completed (agent is "thinking"). */
  isThinking: boolean;
  /**
   * Iteration-grouped view of the conversation (IO-005).
   * Each entry represents one LLM ReAct iteration with its input messages,
   * response, and metadata. Built alongside the flat messages array.
   */
  iterations: LiveIteration[];
}

// ========================
// Live file change state
// ========================

/** A file change event from a coding tool. */
export interface LiveFileChange {
  filePath: string;
  changeType: 'CREATED' | 'MODIFIED' | 'DELETED';
  linesAdded: number;
  linesRemoved: number;
  agentRole: string;
  /** Client-side epoch ms when this event was received. */
  timestamp: number;
}

// ========================
// Live metrics state
// ========================

/** A snapshot of metrics for a single agent at a point in time. */
export interface LiveMetricsSnapshot {
  agentRole: string;
  inputTokens: number;
  outputTokens: number;
  llmLatencyMs: number;
  iterationCount: number;
  /** Client-side epoch ms when this snapshot was received. */
  timestamp: number;
}

// ========================
// Live state
// ========================

/**
 * Full incremental live state. This grows as messages arrive over the WebSocket.
 * Maintained by liveReducer and exposed via LiveServerContext.
 */
export interface LiveState {
  /** WebSocket connection status. */
  connectionStatus: ConnectionStatus;
  /** WebSocket server URL (ws://...). Null until the first connect() call. */
  serverUrl: string | null;
  ensembleId: string | null;
  workflow: string | null;
  /** ISO-8601 timestamp when the ensemble started. */
  startedAt: string | null;
  /** ISO-8601 timestamp when the ensemble completed. Null until ensemble_completed. */
  completedAt: string | null;
  /** Total expected task count from ensemble_started. */
  totalTasks: number;
  /** Tasks accumulated in task_started arrival order. */
  tasks: LiveTask[];
  /**
   * Delegations accumulated in delegation_started arrival order (HIERARCHICAL workflow).
   * Each entry tracks one manager-to-worker delegation with live status.
   */
  delegations: LiveDelegation[];
  /** Pending review requests not yet answered. */
  pendingReviews: LiveReviewRequest[];
  /** True after ensemble_completed is received. */
  ensembleComplete: boolean;
  /**
   * Archived completed runs, in chronological order (oldest first).
   * Each entry is a snapshot of a prior run captured when the next run's
   * ensemble_started message was received. Used to render stacked read-only
   * timeline sections above the active run.
   */
  completedRuns: CompletedRun[];
  /**
   * Live conversations keyed by "agentRole:taskDescription".
   * Built from llm_iteration_started / llm_iteration_completed messages.
   */
  conversations: Record<string, LiveConversation>;
  /** File change events from coding tools. */
  fileChanges: LiveFileChange[];
  /** Metrics history snapshots, capped at 1000 entries. */
  metricsHistory: LiveMetricsSnapshot[];
}

// ========================
// Server -> Client wire messages
// ========================

/**
 * Sent when a client connects. Provides current execution state for late joiners.
 *
 * If the ensemble has not started yet, ensembleId, startedAt, and snapshotTrace may
 * all be null (the Java record uses @JsonInclude(NON_NULL) so absent fields are omitted
 * from the JSON and arrive as undefined, which is safely treated as null here).
 *
 * snapshotTrace is a JSON array of all ServerMessages broadcast since the run started.
 * The browser replays this array through liveReducer to restore state for late joiners.
 */
export interface HelloMessage {
  type: 'hello';
  /** Null if no ensemble run has started yet. */
  ensembleId: string | null;
  /** ISO-8601 timestamp when the run started. Null if no run has started yet. */
  startedAt: string | null;
  /**
   * JSON array of all ServerMessages broadcast since the run started, for late-join replay.
   * Null if no messages have been broadcast yet (empty snapshot).
   */
  snapshotTrace: unknown[] | null;
  /**
   * Recent LLM iteration snapshots for conversation hydration on late-join.
   * Each snapshot pairs a started message with its corresponding completed message.
   * Absent/undefined when no iterations have been recorded or the server does not
   * support this field (backward compatibility).
   */
  recentIterations?: IterationSnapshot[];
}

/** Sent when Ensemble.run() begins. */
export interface EnsembleStartedMessage {
  type: 'ensemble_started';
  ensembleId: string;
  startedAt: string;
  totalTasks: number;
  workflow: string;
}

/** Mirrors TaskStartEvent. */
export interface TaskStartedMessage {
  type: 'task_started';
  taskIndex: number;
  totalTasks: number;
  taskDescription: string;
  agentRole: string;
  startedAt: string;
}

/** Mirrors TaskCompleteEvent. */
export interface TaskCompletedMessage {
  type: 'task_completed';
  taskIndex: number;
  totalTasks: number;
  taskDescription: string;
  agentRole: string;
  completedAt: string;
  durationMs: number;
  tokenCount: number;
  toolCallCount: number;
}

/** Mirrors TaskFailedEvent. */
export interface TaskFailedMessage {
  type: 'task_failed';
  taskIndex: number;
  taskDescription: string;
  agentRole: string;
  failedAt: string;
  reason: string;
}

/**
 * Mirrors ToolCallEvent. Sent after each tool execution within an agent's ReAct loop.
 *
 * Note: taskIndex is 0 when the task index is unavailable from the event (the Java
 * WebSocketStreamingListener sends 0 because ToolCallEvent does not expose a task index).
 * The reducer uses agentRole to infer the target task when taskIndex is 0.
 *
 * Note: outcome is null when the tool outcome is unknown (e.g. the tool threw before
 * producing a result). toolArguments, toolResult, and structuredResult are null when
 * not applicable.
 */
export interface ToolCalledMessage {
  type: 'tool_called';
  agentRole: string;
  /** 1-based task index, or 0 when unknown. */
  taskIndex: number;
  toolName: string;
  durationMs: number;
  /** Execution outcome: "SUCCESS", "FAILURE", or null when unknown. */
  outcome: string | null;
  /** Arguments passed to the tool as a JSON string. Null when unavailable. */
  toolArguments: string | null;
  /** Text result returned by the tool. Null when unavailable. */
  toolResult: string | null;
  /** Structured output from the tool. Null when not applicable. */
  structuredResult: unknown | null;
}

/** Mirrors DelegationStartedEvent. */
export interface DelegationStartedMessage {
  type: 'delegation_started';
  delegationId: string;
  delegatingAgentRole: string;
  workerRole: string;
  taskDescription: string;
}

/** Mirrors DelegationCompletedEvent. */
export interface DelegationCompletedMessage {
  type: 'delegation_completed';
  delegationId: string;
  delegatingAgentRole: string;
  workerRole: string;
  durationMs: number;
}

/** Mirrors DelegationFailedEvent. */
export interface DelegationFailedMessage {
  type: 'delegation_failed';
  delegationId: string;
  delegatingAgentRole: string;
  workerRole: string;
  reason: string;
}

/** Sent when a review gate fires. Browser must respond before timeoutMs elapses. */
export interface ReviewRequestedMessage {
  type: 'review_requested';
  reviewId: string;
  taskDescription: string;
  taskOutput: string;
  timing: string;
  prompt: string | null;
  /** Milliseconds before timeout; 0 means wait indefinitely. */
  timeoutMs: number;
  onTimeout: string;
  /** Optional role that a human must have to approve. Null when any human can approve. */
  requiredRole: string | null;
}

/** Sent when the review timeout expires. */
export interface ReviewTimedOutMessage {
  type: 'review_timed_out';
  reviewId: string;
  action: string;
}

/** Sent when Ensemble.run() returns normally. */
export interface EnsembleCompletedMessage {
  type: 'ensemble_completed';
  ensembleId: string;
  completedAt: string;
  durationMs: number;
  exitReason: string;
  totalTokens: number;
  totalToolCalls: number;
}

/** Sent every 15 seconds to keep the connection alive. */
export interface HeartbeatMessage {
  type: 'heartbeat';
  serverTimeMs: number;
}

/** Server response to a client ping message. */
export interface PongMessage {
  type: 'pong';
}

/**
 * Sent for each token emitted by a StreamingChatModel during the final agent response.
 *
 * Token messages are ephemeral -- they are NOT added to the late-join snapshot.
 * The authoritative final output is delivered in task_completed.
 *
 * Mirrors the Java {@code TokenMessage} record in net.agentensemble.web.protocol.
 */
export interface TokenMessage {
  type: 'token';
  /** The text fragment emitted by the streaming model. */
  token: string;
  /** The role of the agent that produced this token. */
  agentRole: string;
  /**
   * The description of the task being executed.
   * Together with agentRole, uniquely identifies the task in parallel workflows
   * where multiple tasks may share the same agent role.
   */
  taskDescription: string;
  /** ISO-8601 server-side timestamp when this message was sent. */
  sentAt: string;
}

// ========================
// Conversation streaming messages
// ========================

/** DTO for a single message in the LLM conversation buffer. */
export interface MessageDto {
  role: string;
  content: string | null;
  toolCalls: Array<{ name: string; arguments: string }> | null;
  toolName: string | null;
}

/** Sent at the start of each ReAct iteration (before LLM call). */
export interface LlmIterationStartedMessage {
  type: 'llm_iteration_started';
  agentRole: string;
  taskDescription: string;
  iterationIndex: number;
  messages: MessageDto[];
  /**
   * Total number of messages produced so far for this conversation,
   * including messages that may have been evicted from the capped wire buffer.
   * Present when the server sends a sliding-window buffer; absent for
   * backwards compatibility with older servers that send the full history.
   */
  totalMessageCount?: number;
}

/**
 * Pairs an LlmIterationStartedMessage with its corresponding completed message
 * for one LLM iteration. Used in the hello message to provide late-joining
 * clients with conversation history.
 *
 * The completed field is null when the iteration is still in progress
 * (the LLM has been called but has not yet responded).
 */
export interface IterationSnapshot {
  started: LlmIterationStartedMessage;
  /** Null/undefined when the iteration is still in progress (omitted by @JsonInclude(NON_NULL) on the wire). */
  completed?: LlmIterationCompletedMessage | null;
}

/** Sent when the LLM responds in a ReAct iteration. */
export interface LlmIterationCompletedMessage {
  type: 'llm_iteration_completed';
  agentRole: string;
  taskDescription: string;
  iterationIndex: number;
  responseType: 'TOOL_CALLS' | 'FINAL_ANSWER';
  responseText: string | null;
  toolRequests: Array<{ name: string; arguments: string }> | null;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
}

// ========================
// File change messages
// ========================

/** Sent when a coding tool modifies a file. */
export interface FileChangedMessage {
  type: 'file_changed';
  agentRole: string;
  filePath: string;
  changeType: 'CREATED' | 'MODIFIED' | 'DELETED';
  linesAdded: number;
  linesRemoved: number;
  timestamp: string;
}

// ========================
// Metrics messages
// ========================

/** Sent with cumulative metrics after each LLM iteration. */
export interface MetricsSnapshotMessage {
  type: 'metrics_snapshot';
  agentRole: string;
  taskIndex: number;
  inputTokens: number;
  outputTokens: number;
  llmLatencyMs: number;
  toolExecutionTimeMs: number;
  iterationCount: number;
  costEstimate: string | null;
}

/**
 * Sent by the server after context assembly, before the first LLM call (IO-002).
 * Captures the full assembled input context for the agent.
 */
export interface TaskInputMessage {
  type: 'task_input';
  taskIndex: number;
  taskDescription: string;
  expectedOutput: string;
  agentRole: string;
  agentGoal: string;
  agentBackground: string;
  toolNames: string[];
  assembledContext: string;
}

/** All messages the server can send to the client. */
export type ServerMessage =
  | HelloMessage
  | EnsembleStartedMessage
  | TaskStartedMessage
  | TaskCompletedMessage
  | TaskFailedMessage
  | ToolCalledMessage
  | DelegationStartedMessage
  | DelegationCompletedMessage
  | DelegationFailedMessage
  | ReviewRequestedMessage
  | ReviewTimedOutMessage
  | EnsembleCompletedMessage
  | HeartbeatMessage
  | PongMessage
  | TokenMessage
  | LlmIterationStartedMessage
  | LlmIterationCompletedMessage
  | FileChangedMessage
  | MetricsSnapshotMessage
  | TaskInputMessage;

// ========================
// Client -> Server wire messages
// ========================

/** Sent by the browser in response to a review_requested message. */
export interface ReviewDecisionMessage {
  type: 'review_decision';
  reviewId: string;
  decision: 'CONTINUE' | 'EDIT' | 'EXIT_EARLY';
  /** Only present when decision === 'EDIT'. */
  revisedOutput?: string;
}

/** Client keepalive. Server responds with pong. */
export interface PingMessage {
  type: 'ping';
}

/** All messages the client can send to the server. */
export type ClientMessage = ReviewDecisionMessage | PingMessage;

// ========================
// Live state lifecycle actions
// ========================

/**
 * Actions that drive LiveState transitions for connection lifecycle management.
 * These are dispatched by LiveServerContext to update connection status and
 * inject incoming messages into the state machine.
 */
export type LiveAction =
  | { type: 'CONNECTING'; serverUrl: string }
  | { type: 'CONNECTED' }
  | { type: 'DISCONNECTED' }
  | { type: 'ERROR'; error: string }
  | { type: 'RESET' }
  | { type: 'MESSAGE'; message: ServerMessage }
  /**
   * Optimistically removes a review from pendingReviews after the user submits a
   * decision. Dispatched by LiveServerContext.sendDecision() immediately after
   * sending the review_decision wire message.
   */
  | { type: 'RESOLVE_REVIEW'; reviewId: string };
