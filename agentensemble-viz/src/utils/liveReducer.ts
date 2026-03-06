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
  ServerMessage,
  HelloMessage,
  EnsembleStartedMessage,
  TaskStartedMessage,
  TaskCompletedMessage,
  TaskFailedMessage,
  ToolCalledMessage,
  ReviewRequestedMessage,
  ReviewTimedOutMessage,
  EnsembleCompletedMessage,
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
  pendingReviews: [],
  ensembleComplete: false,
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
  // If a snapshot trace is provided (late-join), rebuild tasks from it
  if (msg.snapshotTrace && typeof msg.snapshotTrace === 'object') {
    const snap = msg.snapshotTrace as Record<string, unknown>;
    const taskTraces = Array.isArray(snap['taskTraces'])
      ? (snap['taskTraces'] as unknown[])
      : [];

    const tasks: LiveTask[] = taskTraces.map((tt, i) => {
      const t = tt as Record<string, unknown>;
      return {
        taskIndex: i,
        taskDescription: typeof t['taskDescription'] === 'string' ? t['taskDescription'] : '',
        agentRole: typeof t['agentRole'] === 'string' ? t['agentRole'] : '',
        status: 'completed' as const,
        startedAt: typeof t['startedAt'] === 'string' ? t['startedAt'] : new Date().toISOString(),
        completedAt: typeof t['completedAt'] === 'string' ? t['completedAt'] : null,
        failedAt: null,
        durationMs: null,
        tokenCount: null,
        toolCallCount: null,
        toolCalls: [],
        reason: null,
      };
    });

    return {
      ...state,
      connectionStatus: 'connected',
      ensembleId: msg.ensembleId,
      startedAt: msg.startedAt,
      tasks,
      totalTasks: tasks.length,
    };
  }

  return {
    ...state,
    connectionStatus: 'connected',
    ensembleId: msg.ensembleId,
    startedAt: msg.startedAt,
  };
}

function applyEnsembleStarted(state: LiveState, msg: EnsembleStartedMessage): LiveState {
  return {
    ...state,
    ensembleId: msg.ensembleId,
    workflow: msg.workflow,
    startedAt: msg.startedAt,
    totalTasks: msg.totalTasks,
    tasks: [],
    pendingReviews: [],
    ensembleComplete: false,
    completedAt: null,
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
  const idx = findTaskArrayIndex(state.tasks, msg.taskIndex);
  if (idx === -1) return state;

  const tool = {
    toolName: msg.toolName,
    durationMs: msg.durationMs,
    outcome: msg.outcome,
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
    // delegation_started, delegation_completed, delegation_failed, heartbeat, pong:
    // No state change needed for core live rendering.
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
    default:
      return state;
  }
}
