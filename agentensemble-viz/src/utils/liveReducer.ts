/**
 * Incremental state machine for the live execution mode.
 *
 * liveReducer is a pure function: given the current LiveState and a ServerMessage,
 * it returns the next LiveState. It never mutates state in place.
 *
 * liveActionReducer wraps it and also handles connection lifecycle actions
 * (CONNECTING, CONNECTED, DISCONNECTED, ERROR, RESET, MESSAGE).
 * LiveServerContext uses liveActionReducer as its useReducer handler.
 */

import type {
  LiveState,
  LiveTask,
  LiveDelegation,
  LiveConversation,
  LiveConversationMessage,
  CompletedRun,
  ServerMessage,
  HelloMessage,
  EnsembleStartedMessage,
  TaskStartedMessage,
  TaskCompletedMessage,
  TaskFailedMessage,
  ToolCalledMessage,
  DelegationStartedMessage,
  DelegationCompletedMessage,
  DelegationFailedMessage,
  TokenMessage,
  ReviewRequestedMessage,
  ReviewTimedOutMessage,
  EnsembleCompletedMessage,
  LlmIterationStartedMessage,
  LlmIterationCompletedMessage,
  FileChangedMessage,
  MetricsSnapshotMessage,
  LiveAction,
} from '../types/live.js';

/** The initial live state before any connection is established. */
export const initialLiveState: LiveState = {
  connectionStatus: 'disconnected',
  serverUrl: null,
  ensembleId: null,
  workflow: null,
  startedAt: null,
  completedAt: null,
  totalTasks: 0,
  tasks: [],
  delegations: [],
  pendingReviews: [],
  ensembleComplete: false,
  completedRuns: [],
  conversations: {},
  fileChanges: [],
  metricsHistory: [],
};

// ========================
// Internal helpers
// ========================

/**
 * Find the array index of the most recent task matching the given taskIndex.
 * Returns -1 if not found.
 *
 * Searches from the end so that parallel workflows with re-used taskIndex values
 * match the most recent entry.
 */
export function findTaskArrayIndex(tasks: LiveTask[], taskIndex: number): number {
  for (let i = tasks.length - 1; i >= 0; i--) {
    if (tasks[i].taskIndex === taskIndex) return i;
  }
  return -1;
}

/** Create a running LiveTask from a task_started message. */
function makeRunningTask(msg: TaskStartedMessage): LiveTask {
  return {
    taskIndex: msg.taskIndex,
    taskDescription: msg.taskDescription,
    agentRole: msg.agentRole,
    status: 'running',
    startedAt: msg.startedAt,
    completedAt: null,
    failedAt: null,
    durationMs: null,
    tokenCount: null,
    toolCallCount: null,
    toolCalls: [],
    reason: null,
  };
}

// ========================
// Message handlers
// ========================

function applyHello(state: LiveState, msg: HelloMessage): LiveState {
  // If a snapshot trace is provided (late-join), replay all past ServerMessages
  // through liveReducer to rebuild state deterministically.
  // The snapshot is a JSON array of all messages broadcast since the run started.
  if (Array.isArray(msg.snapshotTrace) && msg.snapshotTrace.length > 0) {
    // Start from a clean live state, preserving connection metadata for this session.
    const snapshotBase: LiveState = {
      ...initialLiveState,
      connectionStatus: 'connected',
      serverUrl: state.serverUrl,
      ensembleId: msg.ensembleId,
      startedAt: msg.startedAt,
    };

    return msg.snapshotTrace.reduce<LiveState>((acc, rawMsg) => {
      // Trust the server to send well-formed messages; cast at the boundary.
      const serverMsg = rawMsg as ServerMessage;
      return liveReducer(acc, serverMsg);
    }, snapshotBase);
  }

  return {
    ...state,
    connectionStatus: 'connected',
    ensembleId: msg.ensembleId,
    startedAt: msg.startedAt,
  };
}

function applyEnsembleStarted(state: LiveState, msg: EnsembleStartedMessage): LiveState {
  // If the current run has tasks (i.e. at least one task_started was received), archive it
  // into completedRuns before resetting. Skip archiving when tasks is empty to avoid
  // creating empty CompletedRun entries from race conditions (a second ensemble_started
  // arriving before the first task_started has been dispatched).
  let updatedCompletedRuns = state.completedRuns;
  if (state.tasks.length > 0) {
    const archivedRun: CompletedRun = {
      ensembleId: state.ensembleId,
      workflow: state.workflow,
      startedAt: state.startedAt,
      completedAt: state.completedAt,
      totalTasks: state.totalTasks,
      // Shallow copy of the tasks array -- each LiveTask is already immutable
      tasks: [...state.tasks],
      // Shallow copy of the delegations array -- each LiveDelegation is already immutable
      delegations: [...state.delegations],
    };
    updatedCompletedRuns = [...state.completedRuns, archivedRun];
  }

  return {
    ...state,
    ensembleId: msg.ensembleId,
    workflow: msg.workflow,
    startedAt: msg.startedAt,
    totalTasks: msg.totalTasks,
    tasks: [],
    delegations: [],
    pendingReviews: [],
    ensembleComplete: false,
    completedAt: null,
    completedRuns: updatedCompletedRuns,
    conversations: {},
    fileChanges: [],
    metricsHistory: [],
  };
}

function applyTaskStarted(state: LiveState, msg: TaskStartedMessage): LiveState {
  const totalTasks = Math.max(state.totalTasks, msg.totalTasks);

  // Deduplicate: if a task with this index is already running, ignore the duplicate
  const existingIdx = findTaskArrayIndex(state.tasks, msg.taskIndex);
  if (existingIdx !== -1 && state.tasks[existingIdx].status === 'running') {
    return { ...state, totalTasks };
  }

  return {
    ...state,
    totalTasks,
    tasks: [...state.tasks, makeRunningTask(msg)],
  };
}

function applyTaskCompleted(state: LiveState, msg: TaskCompletedMessage): LiveState {
  const idx = findTaskArrayIndex(state.tasks, msg.taskIndex);
  if (idx === -1) return state;

  const updated: LiveTask = {
    ...state.tasks[idx],
    status: 'completed',
    completedAt: msg.completedAt,
    durationMs: msg.durationMs,
    tokenCount: msg.tokenCount,
    toolCallCount: msg.toolCallCount,
    // Clear streaming output: task_completed carries the authoritative final output.
    // The streaming buffer is no longer needed once the task finishes.
    streamingOutput: undefined,
  };

  const tasks = [...state.tasks];
  tasks[idx] = updated;
  return { ...state, tasks };
}

/**
 * Append a streaming token to the matching running task.
 *
 * Matching priority:
 * 1. Most recent running task with matching agentRole AND taskDescription (most precise;
 *    handles parallel workflows where the same role name is shared across tasks).
 * 2. Most recent running task with matching agentRole only.
 * 3. Most recent running task regardless of role (last-resort fallback).
 *
 * Tokens are stored as an array of chunks to keep appending O(1) and avoid the
 * quadratic cost of repeated string concatenation for long streamed outputs.
 */
function applyToken(state: LiveState, msg: TokenMessage): LiveState {
  let idx = -1;

  // Priority 1: match by both agentRole and taskDescription
  for (let i = state.tasks.length - 1; i >= 0; i--) {
    if (
      state.tasks[i].status === 'running' &&
      state.tasks[i].agentRole === msg.agentRole &&
      state.tasks[i].taskDescription === msg.taskDescription
    ) {
      idx = i;
      break;
    }
  }

  // Priority 2: match by agentRole only
  if (idx === -1) {
    for (let i = state.tasks.length - 1; i >= 0; i--) {
      if (state.tasks[i].status === 'running' && state.tasks[i].agentRole === msg.agentRole) {
        idx = i;
        break;
      }
    }
  }

  // Priority 3: most recent running task regardless of role
  if (idx === -1) {
    for (let i = state.tasks.length - 1; i >= 0; i--) {
      if (state.tasks[i].status === 'running') {
        idx = i;
        break;
      }
    }
  }

  if (idx === -1) return state;

  const existing = state.tasks[idx].streamingOutput ?? [];
  const updated: LiveTask = {
    ...state.tasks[idx],
    streamingOutput: [...existing, msg.token],
  };
  const tasks = [...state.tasks];
  tasks[idx] = updated;
  return { ...state, tasks };
}

function applyTaskFailed(state: LiveState, msg: TaskFailedMessage): LiveState {
  const idx = findTaskArrayIndex(state.tasks, msg.taskIndex);
  if (idx === -1) return state;

  const updated: LiveTask = {
    ...state.tasks[idx],
    status: 'failed',
    failedAt: msg.failedAt,
    reason: msg.reason,
  };

  const tasks = [...state.tasks];
  tasks[idx] = updated;
  return { ...state, tasks };
}

function applyToolCalled(state: LiveState, msg: ToolCalledMessage): LiveState {
  // Try to find the task by taskIndex first (1-based from server).
  // When taskIndex is 0 (unknown -- the Java WebSocketStreamingListener sends 0 because
  // ToolCallEvent does not expose a task index), fall back to the most recently started
  // running task matching the same agentRole, then to any running task.
  let idx = msg.taskIndex !== 0 ? findTaskArrayIndex(state.tasks, msg.taskIndex) : -1;

  if (idx === -1) {
    // Fallback 1: most recent running task for this agentRole
    for (let i = state.tasks.length - 1; i >= 0; i--) {
      if (state.tasks[i].status === 'running' && state.tasks[i].agentRole === msg.agentRole) {
        idx = i;
        break;
      }
    }
  }

  if (idx === -1) {
    // Fallback 2: most recent running task regardless of role
    for (let i = state.tasks.length - 1; i >= 0; i--) {
      if (state.tasks[i].status === 'running') {
        idx = i;
        break;
      }
    }
  }

  if (idx === -1) return state;

  const tool = {
    toolName: msg.toolName,
    durationMs: msg.durationMs,
    // outcome is null when the server could not determine it; normalize to 'UNKNOWN'
    outcome: msg.outcome ?? 'UNKNOWN',
    receivedAt: Date.now(),
  };

  const updated: LiveTask = {
    ...state.tasks[idx],
    toolCalls: [...state.tasks[idx].toolCalls, tool],
  };

  const tasks = [...state.tasks];
  tasks[idx] = updated;
  return { ...state, tasks };
}

function applyReviewRequested(state: LiveState, msg: ReviewRequestedMessage): LiveState {
  return {
    ...state,
    pendingReviews: [
      ...state.pendingReviews,
      {
        reviewId: msg.reviewId,
        taskDescription: msg.taskDescription,
        taskOutput: msg.taskOutput,
        timing: msg.timing,
        prompt: msg.prompt,
        timeoutMs: msg.timeoutMs,
        onTimeout: msg.onTimeout,
        requiredRole: msg.requiredRole ?? null,
        receivedAt: Date.now(),
      },
    ],
  };
}

function applyReviewTimedOut(state: LiveState, msg: ReviewTimedOutMessage): LiveState {
  return {
    ...state,
    pendingReviews: state.pendingReviews.filter((r) => r.reviewId !== msg.reviewId),
  };
}

function applyEnsembleCompleted(state: LiveState, msg: EnsembleCompletedMessage): LiveState {
  return {
    ...state,
    completedAt: msg.completedAt,
    ensembleComplete: true,
  };
}

// ========================
// Conversation handlers
// ========================

/** Key for conversation lookup: "agentRole:taskDescription" */
function conversationKey(agentRole: string, taskDescription: string): string {
  return `${agentRole}:${taskDescription}`;
}

/**
 * Convert wire MessageDto objects to LiveConversationMessage objects.
 * Performs smart deduplication: only returns messages that are new compared
 * to the existing conversation's message count.
 *
 * When totalMessageCount is present and existingCount exceeds wireMessages.length,
 * the server is sending a capped sliding-window buffer. In that case, return null
 * to signal that the caller should replace (not append) the stored messages.
 */
function convertMessages(
  wireMessages: LlmIterationStartedMessage['messages'],
  existingCount: number,
  totalMessageCount: number | undefined,
): { messages: LiveConversationMessage[]; replace: boolean } {
  const now = Date.now();

  // Capped buffer detection: when the server provides totalMessageCount and the
  // client already has more messages than the wire buffer contains, the server
  // has evicted older messages. Treat the wire buffer as a sliding window and
  // replace the stored conversation entirely.
  if (totalMessageCount !== undefined && existingCount > wireMessages.length) {
    const replaced = wireMessages.map((m) => ({
      role: m.role as LiveConversationMessage['role'],
      content: m.content,
      toolCalls: m.toolCalls ?? undefined,
      toolName: m.toolName ?? undefined,
      timestamp: now,
    }));
    return { messages: replaced, replace: true };
  }

  // Normal path: the wire messages are the full (or growing) buffer; skip
  // messages we already have and return only new ones for appending.
  const newMessages = wireMessages.slice(existingCount);
  const converted = newMessages.map((m) => ({
    role: m.role as LiveConversationMessage['role'],
    content: m.content,
    toolCalls: m.toolCalls ?? undefined,
    toolName: m.toolName ?? undefined,
    timestamp: now,
  }));
  return { messages: converted, replace: false };
}

function applyLlmIterationStarted(state: LiveState, msg: LlmIterationStartedMessage): LiveState {
  const key = conversationKey(msg.agentRole, msg.taskDescription);
  const existing = state.conversations[key];
  const existingCount = existing?.messages.length ?? 0;
  const { messages: newMessages, replace } = convertMessages(
    msg.messages,
    existingCount,
    msg.totalMessageCount,
  );

  let messages: LiveConversationMessage[];
  if (replace) {
    // Capped buffer: the server evicted older messages — replace entirely
    messages = newMessages;
  } else {
    messages = existing ? [...existing.messages, ...newMessages] : newMessages;
  }

  const conversation: LiveConversation = {
    agentRole: msg.agentRole,
    taskDescription: msg.taskDescription,
    iterationIndex: msg.iterationIndex,
    messages,
    isThinking: true,
  };

  return {
    ...state,
    conversations: { ...state.conversations, [key]: conversation },
  };
}

function applyLlmIterationCompleted(state: LiveState, msg: LlmIterationCompletedMessage): LiveState {
  const key = conversationKey(msg.agentRole, msg.taskDescription);
  const existing = state.conversations[key];
  if (!existing) return state;

  const now = Date.now();

  // Append the assistant's response as a conversation message
  const assistantMessage: LiveConversationMessage = {
    role: 'assistant',
    content: msg.responseText,
    toolCalls: msg.toolRequests ?? undefined,
    timestamp: now,
  };

  // Cap conversation at 500 messages
  const MAX_MESSAGES = 500;
  let messages = [...existing.messages, assistantMessage];
  if (messages.length > MAX_MESSAGES) {
    messages = messages.slice(messages.length - MAX_MESSAGES);
  }

  const conversation: LiveConversation = {
    ...existing,
    iterationIndex: msg.iterationIndex,
    messages,
    isThinking: false,
  };

  return {
    ...state,
    conversations: { ...state.conversations, [key]: conversation },
  };
}

// ========================
// File change handlers
// ========================

function applyFileChanged(state: LiveState, msg: FileChangedMessage): LiveState {
  // Prefer server-provided timestamp; fall back to client time
  let timestamp = Date.now();
  if (msg.timestamp != null) {
    const parsed = typeof msg.timestamp === 'number' ? msg.timestamp : Date.parse(msg.timestamp);
    if (!Number.isNaN(parsed)) timestamp = parsed;
  }
  const change = {
    filePath: msg.filePath,
    changeType: msg.changeType,
    linesAdded: msg.linesAdded,
    linesRemoved: msg.linesRemoved,
    agentRole: msg.agentRole,
    timestamp,
  };
  return { ...state, fileChanges: [...state.fileChanges, change] };
}

// ========================
// Metrics handlers
// ========================

function applyMetricsSnapshot(state: LiveState, msg: MetricsSnapshotMessage): LiveState {
  const snapshot = {
    agentRole: msg.agentRole,
    inputTokens: msg.inputTokens,
    outputTokens: msg.outputTokens,
    llmLatencyMs: msg.llmLatencyMs,
    iterationCount: msg.iterationCount,
    timestamp: Date.now(),
  };
  // Cap at 1000 entries
  const history = [...state.metricsHistory, snapshot];
  return {
    ...state,
    metricsHistory: history.length > 1000 ? history.slice(history.length - 1000) : history,
  };
}

// ========================
// Delegation handlers
// ========================

/**
 * Append a new active delegation when delegation_started arrives.
 *
 * Uses client-side Date.now() for startedAt since the server does not
 * include a timestamp in DelegationStartedMessage. This is sufficient for
 * positioning the delegation bar relative to task_started timestamps.
 */
function applyDelegationStarted(state: LiveState, msg: DelegationStartedMessage): LiveState {
  const delegation: LiveDelegation = {
    delegationId: msg.delegationId,
    delegatingAgentRole: msg.delegatingAgentRole,
    workerRole: msg.workerRole,
    taskDescription: msg.taskDescription,
    status: 'active',
    startedAt: Date.now(),
    endedAt: null,
    durationMs: null,
    reason: null,
  };
  return { ...state, delegations: [...state.delegations, delegation] };
}

/**
 * Mark the matching delegation as completed when delegation_completed arrives.
 *
 * Derives endedAt from startedAt + durationMs so the rendered bar reflects
 * the server-measured worker duration rather than client-side wall-clock time
 * (which would include network and processing latency).
 */
function applyDelegationCompleted(state: LiveState, msg: DelegationCompletedMessage): LiveState {
  let idx = -1;
  for (let i = state.delegations.length - 1; i >= 0; i--) {
    if (state.delegations[i].delegationId === msg.delegationId) {
      idx = i;
      break;
    }
  }
  if (idx === -1) return state;

  const updated: LiveDelegation = {
    ...state.delegations[idx],
    status: 'completed',
    endedAt: state.delegations[idx].startedAt + msg.durationMs,
    durationMs: msg.durationMs,
  };
  const delegations = [...state.delegations];
  delegations[idx] = updated;
  return { ...state, delegations };
}

/**
 * Mark the matching delegation as failed when delegation_failed arrives.
 *
 * Searches from the end to match the most recent delegation with the same
 * ID (handles edge cases where duplicate IDs are sent due to retries).
 */
function applyDelegationFailed(state: LiveState, msg: DelegationFailedMessage): LiveState {
  let idx = -1;
  for (let i = state.delegations.length - 1; i >= 0; i--) {
    if (state.delegations[i].delegationId === msg.delegationId) {
      idx = i;
      break;
    }
  }
  if (idx === -1) return state;

  const updated: LiveDelegation = {
    ...state.delegations[idx],
    status: 'failed',
    endedAt: Date.now(),
    reason: msg.reason,
  };
  const delegations = [...state.delegations];
  delegations[idx] = updated;
  return { ...state, delegations };
}

// ========================
// Main reducer
// ========================

/**
 * Reduce a single ServerMessage into the next LiveState.
 *
 * This is a pure function: it never mutates its arguments and always returns
 * a new object reference when any part of the state changes.
 *
 * Delegation, heartbeat, and pong messages carry no state change for core
 * live rendering and are handled by the default no-op branch.
 */
export function liveReducer(state: LiveState, message: ServerMessage): LiveState {
  switch (message.type) {
    case 'hello':
      return applyHello(state, message);
    case 'ensemble_started':
      return applyEnsembleStarted(state, message);
    case 'task_started':
      return applyTaskStarted(state, message);
    case 'task_completed':
      return applyTaskCompleted(state, message);
    case 'task_failed':
      return applyTaskFailed(state, message);
    case 'tool_called':
      return applyToolCalled(state, message);
    case 'review_requested':
      return applyReviewRequested(state, message);
    case 'review_timed_out':
      return applyReviewTimedOut(state, message);
    case 'ensemble_completed':
      return applyEnsembleCompleted(state, message);
    case 'token':
      return applyToken(state, message);
    case 'delegation_started':
      return applyDelegationStarted(state, message);
    case 'delegation_completed':
      return applyDelegationCompleted(state, message);
    case 'delegation_failed':
      return applyDelegationFailed(state, message);
    case 'llm_iteration_started':
      return applyLlmIterationStarted(state, message);
    case 'llm_iteration_completed':
      return applyLlmIterationCompleted(state, message);
    case 'file_changed':
      return applyFileChanged(state, message);
    case 'metrics_snapshot':
      return applyMetricsSnapshot(state, message);
    // heartbeat, pong: no state change needed.
    default:
      return state;
  }
}

/**
 * Reduce a LiveAction (connection lifecycle or incoming message) into the next LiveState.
 *
 * Used as the useReducer handler in LiveServerContext. Delegates MESSAGE actions
 * to liveReducer.
 */
export function liveActionReducer(state: LiveState, action: LiveAction): LiveState {
  switch (action.type) {
    case 'CONNECTING':
      return { ...state, connectionStatus: 'connecting', serverUrl: action.serverUrl };
    case 'CONNECTED':
      return { ...state, connectionStatus: 'connected' };
    case 'DISCONNECTED':
      return { ...state, connectionStatus: 'disconnected' };
    case 'ERROR':
      return { ...state, connectionStatus: 'error' };
    case 'RESET':
      // Preserve serverUrl so the reconnect loop knows where to reconnect to
      return { ...initialLiveState, serverUrl: state.serverUrl };
    case 'MESSAGE':
      return liveReducer(state, action.message);
    case 'RESOLVE_REVIEW':
      // Optimistic removal: the user has submitted a decision and the review is no longer
      // pending. The server will also send review_timed_out or simply stop tracking it.
      return {
        ...state,
        pendingReviews: state.pendingReviews.filter((r) => r.reviewId !== action.reviewId),
      };
    default:
      return state;
  }
}
