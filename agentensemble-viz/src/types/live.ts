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
  /** Client-side epoch ms when the request was received (for countdown). */
  receivedAt: number;
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
  /** Pending review requests not yet answered. */
  pendingReviews: LiveReviewRequest[];
  /** True after ensemble_completed is received. */
  ensembleComplete: boolean;
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
  timeoutMs: number;
  onTimeout: string;
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
  | TokenMessage;

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
