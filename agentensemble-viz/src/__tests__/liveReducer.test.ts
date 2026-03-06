import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  liveReducer,
  liveActionReducer,
  initialLiveState,
  findTaskArrayIndex,
} from '../utils/liveReducer.js';
import type { LiveState, ServerMessage, LiveAction } from '../types/live.js';

// ========================
// Fixtures
// ========================

const BASE_STATE: LiveState = {
  ...initialLiveState,
  connectionStatus: 'connected',
  ensembleId: 'ens-001',
  workflow: 'SEQUENTIAL',
  startedAt: '2026-03-05T14:00:00Z',
  totalTasks: 3,
};

function stateWithTask(taskIndex: number, status: 'running' | 'completed' | 'failed') {
  return {
    ...BASE_STATE,
    tasks: [
      {
        taskIndex,
        taskDescription: 'Task ' + taskIndex,
        agentRole: 'Agent A',
        status,
        startedAt: '2026-03-05T14:00:01Z',
        completedAt: status === 'completed' ? '2026-03-05T14:00:30Z' : null,
        failedAt: status === 'failed' ? '2026-03-05T14:00:30Z' : null,
        durationMs: status === 'completed' ? 29000 : null,
        tokenCount: status === 'completed' ? 500 : null,
        toolCallCount: status === 'completed' ? 2 : null,
        toolCalls: [],
        reason: status === 'failed' ? 'Out of retries' : null,
      },
    ],
  };
}

// ========================
// findTaskArrayIndex
// ========================

describe('findTaskArrayIndex', () => {
  it('returns -1 for empty tasks array', () => {
    expect(findTaskArrayIndex([], 0)).toBe(-1);
  });

  it('returns correct index when task is present', () => {
    const tasks = stateWithTask(1, 'running').tasks;
    expect(findTaskArrayIndex(tasks, 1)).toBe(0);
  });

  it('returns -1 when task index is not present', () => {
    const tasks = stateWithTask(1, 'running').tasks;
    expect(findTaskArrayIndex(tasks, 99)).toBe(-1);
  });

  it('returns the LAST matching index for duplicate taskIndex values', () => {
    const tasks = [
      { ...stateWithTask(0, 'completed').tasks[0] },
      { ...stateWithTask(0, 'running').tasks[0] },
    ];
    expect(findTaskArrayIndex(tasks, 0)).toBe(1);
  });
});

// ========================
// liveReducer
// ========================

describe('liveReducer', () => {
  describe('hello', () => {
    it('sets connectionStatus to connected and captures ensembleId and startedAt', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
      };
      const next = liveReducer(initialLiveState, msg);
      expect(next.connectionStatus).toBe('connected');
      expect(next.ensembleId).toBe('ens-abc');
      expect(next.startedAt).toBe('2026-03-05T14:00:00Z');
    });

    it('does not mutate input state', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
      };
      const before = { ...initialLiveState };
      liveReducer(initialLiveState, msg);
      expect(initialLiveState).toEqual(before);
    });

    it('rebuilds tasks by replaying snapshotTrace ServerMessage array for late joiners', () => {
      // snapshotTrace is a JSON array of all ServerMessages broadcast since run started.
      // The reducer replays them through liveReducer to restore state deterministically.
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: [
          {
            type: 'ensemble_started',
            ensembleId: 'ens-abc',
            startedAt: '2026-03-05T14:00:00Z',
            totalTasks: 2,
            workflow: 'SEQUENTIAL',
          },
          {
            type: 'task_started',
            taskIndex: 1,
            totalTasks: 2,
            taskDescription: 'Research trends',
            agentRole: 'Researcher',
            startedAt: '2026-03-05T14:00:01Z',
          },
          {
            type: 'task_completed',
            taskIndex: 1,
            totalTasks: 2,
            taskDescription: 'Research trends',
            agentRole: 'Researcher',
            completedAt: '2026-03-05T14:00:45Z',
            durationMs: 44000,
            tokenCount: 1842,
            toolCallCount: 3,
          },
          {
            type: 'task_started',
            taskIndex: 2,
            totalTasks: 2,
            taskDescription: 'Write report',
            agentRole: 'Writer',
            startedAt: '2026-03-05T14:00:46Z',
          },
        ],
      };
      const next = liveReducer(initialLiveState, msg);
      expect(next.tasks).toHaveLength(2);
      expect(next.tasks[0].taskDescription).toBe('Research trends');
      expect(next.tasks[0].agentRole).toBe('Researcher');
      expect(next.tasks[0].status).toBe('completed');
      expect(next.tasks[1].taskDescription).toBe('Write report');
      expect(next.tasks[1].status).toBe('running');
      expect(next.totalTasks).toBe(2);
      expect(next.workflow).toBe('SEQUENTIAL');
    });

    it('handles snapshotTrace as empty array (no messages yet)', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: [],
      };
      const next = liveReducer(initialLiveState, msg);
      expect(next.tasks).toHaveLength(0);
      expect(next.connectionStatus).toBe('connected');
    });
  });

  describe('ensemble_started', () => {
    it('resets tasks and sets ensemble metadata', () => {
      const stateWithOldTask = { ...initialLiveState, tasks: stateWithTask(0, 'completed').tasks };
      const msg: ServerMessage = {
        type: 'ensemble_started',
        ensembleId: 'ens-002',
        startedAt: '2026-03-05T15:00:00Z',
        totalTasks: 5,
        workflow: 'PARALLEL',
      };
      const next = liveReducer(stateWithOldTask, msg);
      expect(next.ensembleId).toBe('ens-002');
      expect(next.workflow).toBe('PARALLEL');
      expect(next.totalTasks).toBe(5);
      expect(next.tasks).toHaveLength(0);
      expect(next.pendingReviews).toHaveLength(0);
      expect(next.ensembleComplete).toBe(false);
      expect(next.completedAt).toBeNull();
    });
  });

  describe('task_started', () => {
    it('adds a running task', () => {
      const msg: ServerMessage = {
        type: 'task_started',
        taskIndex: 0,
        totalTasks: 3,
        taskDescription: 'Research AI trends',
        agentRole: 'Senior Researcher',
        startedAt: '2026-03-05T14:00:01Z',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.tasks).toHaveLength(1);
      expect(next.tasks[0].status).toBe('running');
      expect(next.tasks[0].taskDescription).toBe('Research AI trends');
      expect(next.tasks[0].agentRole).toBe('Senior Researcher');
      expect(next.tasks[0].startedAt).toBe('2026-03-05T14:00:01Z');
      expect(next.tasks[0].completedAt).toBeNull();
      expect(next.tasks[0].toolCalls).toHaveLength(0);
    });

    it('updates totalTasks when message carries a larger value', () => {
      const state = { ...BASE_STATE, totalTasks: 2 };
      const msg: ServerMessage = {
        type: 'task_started',
        taskIndex: 0,
        totalTasks: 5,
        taskDescription: 'Task A',
        agentRole: 'Agent A',
        startedAt: '2026-03-05T14:00:01Z',
      };
      const next = liveReducer(state, msg);
      expect(next.totalTasks).toBe(5);
    });

    it('ignores duplicate task_started for same running taskIndex', () => {
      const msg: ServerMessage = {
        type: 'task_started',
        taskIndex: 0,
        totalTasks: 3,
        taskDescription: 'Research AI trends',
        agentRole: 'Senior Researcher',
        startedAt: '2026-03-05T14:00:01Z',
      };
      const stateAfterFirst = liveReducer(BASE_STATE, msg);
      const stateAfterDuplicate = liveReducer(stateAfterFirst, msg);
      expect(stateAfterDuplicate.tasks).toHaveLength(1);
    });

    it('allows multiple different tasks', () => {
      const msg0: ServerMessage = {
        type: 'task_started',
        taskIndex: 0,
        totalTasks: 3,
        taskDescription: 'Task A',
        agentRole: 'Agent A',
        startedAt: '2026-03-05T14:00:01Z',
      };
      const msg1: ServerMessage = {
        type: 'task_started',
        taskIndex: 1,
        totalTasks: 3,
        taskDescription: 'Task B',
        agentRole: 'Agent B',
        startedAt: '2026-03-05T14:00:02Z',
      };
      const s1 = liveReducer(BASE_STATE, msg0);
      const s2 = liveReducer(s1, msg1);
      expect(s2.tasks).toHaveLength(2);
    });
  });

  describe('task_completed', () => {
    it('transitions task to completed and sets duration/tokens', () => {
      const state = stateWithTask(0, 'running');
      const msg: ServerMessage = {
        type: 'task_completed',
        taskIndex: 0,
        totalTasks: 3,
        taskDescription: 'Task 0',
        agentRole: 'Agent A',
        completedAt: '2026-03-05T14:00:45Z',
        durationMs: 44000,
        tokenCount: 1842,
        toolCallCount: 3,
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].status).toBe('completed');
      expect(next.tasks[0].completedAt).toBe('2026-03-05T14:00:45Z');
      expect(next.tasks[0].durationMs).toBe(44000);
      expect(next.tasks[0].tokenCount).toBe(1842);
      expect(next.tasks[0].toolCallCount).toBe(3);
    });

    it('returns same state when task not found', () => {
      const msg: ServerMessage = {
        type: 'task_completed',
        taskIndex: 99,
        totalTasks: 3,
        taskDescription: 'Unknown',
        agentRole: 'Agent X',
        completedAt: '2026-03-05T14:01:00Z',
        durationMs: 1000,
        tokenCount: 100,
        toolCallCount: 0,
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next).toBe(BASE_STATE);
    });

    it('does not mutate the existing task object', () => {
      const state = stateWithTask(0, 'running');
      const originalTask = state.tasks[0];
      const msg: ServerMessage = {
        type: 'task_completed',
        taskIndex: 0,
        totalTasks: 3,
        taskDescription: 'Task 0',
        agentRole: 'Agent A',
        completedAt: '2026-03-05T14:00:45Z',
        durationMs: 44000,
        tokenCount: 1842,
        toolCallCount: 3,
      };
      liveReducer(state, msg);
      expect(originalTask.status).toBe('running');
    });
  });

  describe('task_failed', () => {
    it('transitions task to failed and captures reason', () => {
      const state = stateWithTask(0, 'running');
      const msg: ServerMessage = {
        type: 'task_failed',
        taskIndex: 0,
        taskDescription: 'Task 0',
        agentRole: 'Agent A',
        failedAt: '2026-03-05T14:00:30Z',
        reason: 'MaxIterationsExceededException: limit reached',
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].status).toBe('failed');
      expect(next.tasks[0].failedAt).toBe('2026-03-05T14:00:30Z');
      expect(next.tasks[0].reason).toBe('MaxIterationsExceededException: limit reached');
    });

    it('returns same state when task not found', () => {
      const msg: ServerMessage = {
        type: 'task_failed',
        taskIndex: 99,
        taskDescription: 'Unknown',
        agentRole: 'Agent X',
        failedAt: '2026-03-05T14:01:00Z',
        reason: 'error',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next).toBe(BASE_STATE);
    });
  });

  describe('tool_called', () => {
    it('appends a tool call to the matching task', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A',
        taskIndex: 1,
        toolName: 'web_search',
        durationMs: 1200,
        outcome: 'SUCCESS',
        toolArguments: '{"query":"AI trends"}',
        toolResult: 'Top 10 AI trends...',
        structuredResult: null,
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].toolCalls).toHaveLength(1);
      expect(next.tasks[0].toolCalls[0].toolName).toBe('web_search');
      expect(next.tasks[0].toolCalls[0].outcome).toBe('SUCCESS');
      expect(next.tasks[0].toolCalls[0].durationMs).toBe(1200);
      expect(next.tasks[0].toolCalls[0].receivedAt).toBe(now);
    });

    it('appends multiple tool calls in order', () => {
      const state = stateWithTask(1, 'running');
      const tool1: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A',
        taskIndex: 1,
        toolName: 'web_search',
        durationMs: 1200,
        outcome: 'SUCCESS',
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      const tool2: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A',
        taskIndex: 1,
        toolName: 'calculator',
        durationMs: 50,
        outcome: 'SUCCESS',
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      const s1 = liveReducer(state, tool1);
      const s2 = liveReducer(s1, tool2);
      expect(s2.tasks[0].toolCalls).toHaveLength(2);
      expect(s2.tasks[0].toolCalls[0].toolName).toBe('web_search');
      expect(s2.tasks[0].toolCalls[1].toolName).toBe('calculator');
    });

    it('returns same state when task not found and no running tasks available', () => {
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent X',
        taskIndex: 99,
        toolName: 'web_search',
        durationMs: 100,
        outcome: 'SUCCESS',
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      // BASE_STATE has no tasks, so fallback also finds nothing
      const next = liveReducer(BASE_STATE, msg);
      expect(next).toBe(BASE_STATE);
    });

    it('falls back to most recent running task by agentRole when taskIndex is 0', () => {
      // Java WebSocketStreamingListener sends taskIndex=0 for all tool calls.
      // The reducer should fall back to the most recent running task for the same role.
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A', // matches the running task's agentRole
        taskIndex: 0,         // 0 = unknown, as sent by the Java server
        toolName: 'web_search',
        durationMs: 800,
        outcome: 'SUCCESS',
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].toolCalls).toHaveLength(1);
      expect(next.tasks[0].toolCalls[0].toolName).toBe('web_search');
    });

    it('falls back to any running task when taskIndex is 0 and no role match', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Different Agent', // no match by role
        taskIndex: 0,
        toolName: 'calculator',
        durationMs: 50,
        outcome: 'SUCCESS',
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      const next = liveReducer(state, msg);
      // Falls back to the only running task
      expect(next.tasks[0].toolCalls).toHaveLength(1);
      expect(next.tasks[0].toolCalls[0].toolName).toBe('calculator');
    });

    it('normalizes null outcome to UNKNOWN', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A',
        taskIndex: 1,
        toolName: 'web_search',
        durationMs: 500,
        outcome: null, // null = server could not determine outcome
        toolArguments: null,
        toolResult: null,
        structuredResult: null,
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].toolCalls[0].outcome).toBe('UNKNOWN');
    });
  });

  describe('review_requested', () => {
    it('adds a pending review', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'review_requested',
        reviewId: 'review-123',
        taskDescription: 'Research AI trends',
        taskOutput: 'The AI landscape...',
        timing: 'AFTER_EXECUTION',
        prompt: null,
        timeoutMs: 300000,
        onTimeout: 'CONTINUE',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.pendingReviews).toHaveLength(1);
      expect(next.pendingReviews[0].reviewId).toBe('review-123');
      expect(next.pendingReviews[0].taskOutput).toBe('The AI landscape...');
      expect(next.pendingReviews[0].receivedAt).toBe(now);
    });

    it('queues multiple concurrent reviews', () => {
      const msg1: ServerMessage = {
        type: 'review_requested',
        reviewId: 'review-1',
        taskDescription: 'Task A',
        taskOutput: 'Output A',
        timing: 'AFTER_EXECUTION',
        prompt: null,
        timeoutMs: 300000,
        onTimeout: 'CONTINUE',
      };
      const msg2: ServerMessage = {
        type: 'review_requested',
        reviewId: 'review-2',
        taskDescription: 'Task B',
        taskOutput: 'Output B',
        timing: 'AFTER_EXECUTION',
        prompt: null,
        timeoutMs: 300000,
        onTimeout: 'CONTINUE',
      };
      const s1 = liveReducer(BASE_STATE, msg1);
      const s2 = liveReducer(s1, msg2);
      expect(s2.pendingReviews).toHaveLength(2);
    });
  });

  describe('review_timed_out', () => {
    it('removes the timed-out review from pending reviews', () => {
      const stateWithReview = {
        ...BASE_STATE,
        pendingReviews: [
          {
            reviewId: 'review-123',
            taskDescription: 'Task',
            taskOutput: 'Output',
            timing: 'AFTER_EXECUTION',
            prompt: null,
            timeoutMs: 300000,
            onTimeout: 'CONTINUE',
            receivedAt: Date.now(),
          },
        ],
      };
      const msg: ServerMessage = {
        type: 'review_timed_out',
        reviewId: 'review-123',
        action: 'CONTINUE',
      };
      const next = liveReducer(stateWithReview, msg);
      expect(next.pendingReviews).toHaveLength(0);
    });

    it('leaves other reviews intact', () => {
      const stateWithReviews = {
        ...BASE_STATE,
        pendingReviews: [
          {
            reviewId: 'review-1',
            taskDescription: 'Task A',
            taskOutput: 'Output A',
            timing: 'AFTER_EXECUTION',
            prompt: null,
            timeoutMs: 300000,
            onTimeout: 'CONTINUE',
            receivedAt: Date.now(),
          },
          {
            reviewId: 'review-2',
            taskDescription: 'Task B',
            taskOutput: 'Output B',
            timing: 'AFTER_EXECUTION',
            prompt: null,
            timeoutMs: 300000,
            onTimeout: 'CONTINUE',
            receivedAt: Date.now(),
          },
        ],
      };
      const msg: ServerMessage = {
        type: 'review_timed_out',
        reviewId: 'review-1',
        action: 'CONTINUE',
      };
      const next = liveReducer(stateWithReviews, msg);
      expect(next.pendingReviews).toHaveLength(1);
      expect(next.pendingReviews[0].reviewId).toBe('review-2');
    });
  });

  describe('ensemble_completed', () => {
    it('sets completedAt and ensembleComplete flag', () => {
      const msg: ServerMessage = {
        type: 'ensemble_completed',
        ensembleId: 'ens-001',
        completedAt: '2026-03-05T14:05:00Z',
        durationMs: 300000,
        exitReason: 'COMPLETED',
        totalTokens: 12500,
        totalToolCalls: 15,
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.ensembleComplete).toBe(true);
      expect(next.completedAt).toBe('2026-03-05T14:05:00Z');
    });
  });

  describe('no-op messages', () => {
    it.each([
      [
        'heartbeat',
        {
          type: 'heartbeat' as const,
          serverTimeMs: 1741212300000,
        },
      ],
      [
        'delegation_started',
        {
          type: 'delegation_started' as const,
          delegationId: 'd1',
          delegatingAgentRole: 'Lead',
          workerRole: 'Worker',
          taskDescription: 'Sub task',
        },
      ],
      [
        'delegation_completed',
        {
          type: 'delegation_completed' as const,
          delegationId: 'd1',
          delegatingAgentRole: 'Lead',
          workerRole: 'Worker',
          durationMs: 5000,
        },
      ],
      [
        'delegation_failed',
        {
          type: 'delegation_failed' as const,
          delegationId: 'd1',
          delegatingAgentRole: 'Lead',
          workerRole: 'Worker',
          reason: 'rejected',
        },
      ],
      [
        'pong',
        {
          type: 'pong' as const,
        },
      ],
    ])('%s returns the same state reference', (_name, msg) => {
      const next = liveReducer(BASE_STATE, msg as ServerMessage);
      expect(next).toBe(BASE_STATE);
    });
  });
});

// ========================
// liveActionReducer
// ========================

describe('liveActionReducer', () => {
  it('CONNECTING sets status to connecting and stores serverUrl', () => {
    const action: LiveAction = { type: 'CONNECTING', serverUrl: 'ws://localhost:7329/ws' };
    const next = liveActionReducer(initialLiveState, action);
    expect(next.connectionStatus).toBe('connecting');
    expect(next.serverUrl).toBe('ws://localhost:7329/ws');
  });

  it('CONNECTED sets status to connected', () => {
    const state = { ...initialLiveState, connectionStatus: 'connecting' as const };
    const action: LiveAction = { type: 'CONNECTED' };
    const next = liveActionReducer(state, action);
    expect(next.connectionStatus).toBe('connected');
  });

  it('DISCONNECTED sets status to disconnected', () => {
    const state = { ...initialLiveState, connectionStatus: 'connected' as const };
    const action: LiveAction = { type: 'DISCONNECTED' };
    const next = liveActionReducer(state, action);
    expect(next.connectionStatus).toBe('disconnected');
  });

  it('ERROR sets status to error', () => {
    const state = { ...initialLiveState, connectionStatus: 'connecting' as const };
    const action: LiveAction = { type: 'ERROR', error: 'Connection refused' };
    const next = liveActionReducer(state, action);
    expect(next.connectionStatus).toBe('error');
  });

  it('RESET clears state but preserves serverUrl', () => {
    const state: LiveState = {
      ...initialLiveState,
      connectionStatus: 'connected',
      serverUrl: 'ws://localhost:7329/ws',
      ensembleId: 'ens-001',
      tasks: stateWithTask(0, 'running').tasks,
    };
    const action: LiveAction = { type: 'RESET' };
    const next = liveActionReducer(state, action);
    expect(next.serverUrl).toBe('ws://localhost:7329/ws');
    expect(next.ensembleId).toBeNull();
    expect(next.tasks).toHaveLength(0);
    expect(next.connectionStatus).toBe('disconnected');
  });

  it('MESSAGE delegates to liveReducer', () => {
    const action: LiveAction = {
      type: 'MESSAGE',
      message: {
        type: 'ensemble_started',
        ensembleId: 'ens-999',
        startedAt: '2026-03-05T14:00:00Z',
        totalTasks: 4,
        workflow: 'PARALLEL',
      },
    };
    const next = liveActionReducer(initialLiveState, action);
    expect(next.ensembleId).toBe('ens-999');
    expect(next.workflow).toBe('PARALLEL');
    expect(next.totalTasks).toBe(4);
  });
});

// Restore fake timers after each test
beforeEach(() => {
  vi.useRealTimers();
});
