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

function stateWithTask(taskIndex: number, status: 'running' | 'completed' | 'failed'): LiveState {
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

    it('archives current run into completedRuns when tasks exist before new run starts', () => {
      const stateWithCompletedRun: LiveState = {
        ...BASE_STATE,
        tasks: stateWithTask(1, 'completed').tasks,
        ensembleComplete: true,
        completedAt: '2026-03-05T14:05:00Z',
      };
      const msg: ServerMessage = {
        type: 'ensemble_started',
        ensembleId: 'ens-002',
        startedAt: '2026-03-05T15:00:00Z',
        totalTasks: 2,
        workflow: 'SEQUENTIAL',
      };
      const next = liveReducer(stateWithCompletedRun, msg);
      // The old run should be archived
      expect(next.completedRuns).toHaveLength(1);
      expect(next.completedRuns[0].ensembleId).toBe('ens-001');
      expect(next.completedRuns[0].tasks).toHaveLength(1);
      expect(next.completedRuns[0].completedAt).toBe('2026-03-05T14:05:00Z');
      // Active state should be reset for the new run
      expect(next.tasks).toHaveLength(0);
      expect(next.ensembleId).toBe('ens-002');
      expect(next.ensembleComplete).toBe(false);
      expect(next.completedAt).toBeNull();
    });

    it('does NOT archive an empty-task run (avoids race condition entries)', () => {
      // If no tasks have been dispatched yet, do not create an empty CompletedRun
      const emptyState: LiveState = {
        ...BASE_STATE,
        tasks: [],
      };
      const msg: ServerMessage = {
        type: 'ensemble_started',
        ensembleId: 'ens-002',
        startedAt: '2026-03-05T15:00:00Z',
        totalTasks: 2,
        workflow: 'SEQUENTIAL',
      };
      const next = liveReducer(emptyState, msg);
      expect(next.completedRuns).toHaveLength(0);
    });

    it('accumulates completedRuns across multiple consecutive runs', () => {
      let state = initialLiveState;
      // Run 1: task_started, ensemble_started (run 2 begins)
      state = liveReducer(state, {
        type: 'ensemble_started',
        ensembleId: 'run-1',
        startedAt: '2026-03-05T14:00:00Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      });
      state = liveReducer(state, {
        type: 'task_started',
        taskIndex: 1,
        totalTasks: 1,
        taskDescription: 'Task A',
        agentRole: 'Agent A',
        startedAt: '2026-03-05T14:00:01Z',
      });
      state = liveReducer(state, {
        type: 'task_completed',
        taskIndex: 1,
        totalTasks: 1,
        taskDescription: 'Task A',
        agentRole: 'Agent A',
        completedAt: '2026-03-05T14:00:30Z',
        durationMs: 29000,
        tokenCount: 500,
        toolCallCount: 0,
      });

      // Run 2 starts: run 1 should be archived
      state = liveReducer(state, {
        type: 'ensemble_started',
        ensembleId: 'run-2',
        startedAt: '2026-03-05T14:01:00Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      });
      expect(state.completedRuns).toHaveLength(1);
      expect(state.completedRuns[0].ensembleId).toBe('run-1');

      state = liveReducer(state, {
        type: 'task_started',
        taskIndex: 1,
        totalTasks: 1,
        taskDescription: 'Task B',
        agentRole: 'Agent B',
        startedAt: '2026-03-05T14:01:01Z',
      });

      // Run 3 starts: both run 1 and run 2 should be in completedRuns
      state = liveReducer(state, {
        type: 'ensemble_started',
        ensembleId: 'run-3',
        startedAt: '2026-03-05T14:02:00Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      });
      expect(state.completedRuns).toHaveLength(2);
      expect(state.completedRuns[0].ensembleId).toBe('run-1');
      expect(state.completedRuns[1].ensembleId).toBe('run-2');
      expect(state.ensembleId).toBe('run-3');
      expect(state.tasks).toHaveLength(0);
    });

    it('late-join snapshot replay builds completedRuns correctly', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'run-2',
        startedAt: '2026-03-05T14:01:00Z',
        snapshotTrace: [
          { type: 'ensemble_started', ensembleId: 'run-1', startedAt: '2026-03-05T14:00:00Z', totalTasks: 1, workflow: 'SEQUENTIAL' },
          { type: 'task_started', taskIndex: 1, totalTasks: 1, taskDescription: 'Task A', agentRole: 'Agent A', startedAt: '2026-03-05T14:00:01Z' },
          { type: 'task_completed', taskIndex: 1, totalTasks: 1, taskDescription: 'Task A', agentRole: 'Agent A', completedAt: '2026-03-05T14:00:30Z', durationMs: 29000, tokenCount: 500, toolCallCount: 0 },
          { type: 'ensemble_completed', ensembleId: 'run-1', completedAt: '2026-03-05T14:00:31Z', durationMs: 31000, exitReason: 'COMPLETED', totalTokens: 500, totalToolCalls: 0 },
          { type: 'ensemble_started', ensembleId: 'run-2', startedAt: '2026-03-05T14:01:00Z', totalTasks: 1, workflow: 'SEQUENTIAL' },
          { type: 'task_started', taskIndex: 1, totalTasks: 1, taskDescription: 'Task B', agentRole: 'Agent B', startedAt: '2026-03-05T14:01:01Z' },
        ],
      };
      const next = liveReducer(initialLiveState, msg);
      // run-1 should be in completedRuns; run-2 is the active run
      expect(next.completedRuns).toHaveLength(1);
      expect(next.completedRuns[0].ensembleId).toBe('run-1');
      expect(next.completedRuns[0].tasks).toHaveLength(1);
      expect(next.ensembleId).toBe('run-2');
      expect(next.tasks).toHaveLength(1);
      expect(next.tasks[0].taskDescription).toBe('Task B');
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
        requiredRole: null,
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
        requiredRole: null,
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
        requiredRole: null,
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
            requiredRole: null,
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
            requiredRole: null,
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
            requiredRole: null,
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

  describe('token', () => {
    it('appends token to streamingOutput of matching running task', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'token',
        token: 'Hello ',
        agentRole: 'Agent A',
        taskDescription: 'Task 1',
        sentAt: '2026-03-05T14:00:10Z',
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].streamingOutput).toEqual(['Hello ']);
    });

    it('accumulates multiple tokens in order as chunks array', () => {
      const state = stateWithTask(1, 'running');
      const tok1: ServerMessage = { type: 'token', token: 'Hel', agentRole: 'Agent A', taskDescription: 'Task 1', sentAt: '2026-03-05T14:00:10Z' };
      const tok2: ServerMessage = { type: 'token', token: 'lo ', agentRole: 'Agent A', taskDescription: 'Task 1', sentAt: '2026-03-05T14:00:10Z' };
      const tok3: ServerMessage = { type: 'token', token: 'world', agentRole: 'Agent A', taskDescription: 'Task 1', sentAt: '2026-03-05T14:00:10Z' };
      const s1 = liveReducer(state, tok1);
      const s2 = liveReducer(s1, tok2);
      const s3 = liveReducer(s2, tok3);
      expect(s3.tasks[0].streamingOutput).toEqual(['Hel', 'lo ', 'world']);
      expect(s3.tasks[0].streamingOutput?.join('')).toBe('Hello world');
    });

    it('matches by agentRole + taskDescription for precise attribution in parallel workflows', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'token',
        token: 'tok',
        agentRole: 'Agent A',
        taskDescription: 'Task 1',  // matches the running task exactly
        sentAt: '2026-03-05T14:00:10Z',
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].streamingOutput).toEqual(['tok']);
    });

    it('falls back to most recent running task when no role match', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'token',
        token: 'tok',
        agentRole: 'Unknown Agent',  // no match by role
        taskDescription: 'Unknown task',
        sentAt: '2026-03-05T14:00:10Z',
      };
      const next = liveReducer(state, msg);
      // Falls back to the only running task
      expect(next.tasks[0].streamingOutput).toEqual(['tok']);
    });

    it('returns same state when no running tasks exist', () => {
      const state = stateWithTask(1, 'completed');
      const msg: ServerMessage = {
        type: 'token',
        token: 'tok',
        agentRole: 'Agent A',
        taskDescription: 'Task 1',
        sentAt: '2026-03-05T14:00:10Z',
      };
      const next = liveReducer(state, msg);
      expect(next).toBe(state);
    });

    it('does not mutate the existing task object', () => {
      const state = stateWithTask(1, 'running');
      const originalTask = state.tasks[0];
      const msg: ServerMessage = {
        type: 'token',
        token: 'tok',
        agentRole: 'Agent A',
        taskDescription: 'Task 1',
        sentAt: '2026-03-05T14:00:10Z',
      };
      liveReducer(state, msg);
      expect(originalTask.streamingOutput).toBeUndefined();
    });

    it('task_completed clears streamingOutput', () => {
      // Accumulate some streaming output
      const state = stateWithTask(1, 'running');
      const tok: ServerMessage = { type: 'token', token: 'Hello world', agentRole: 'Agent A', taskDescription: 'Task 1', sentAt: '2026-03-05T14:00:10Z' };
      const withStreaming = liveReducer(state, tok);
      expect(withStreaming.tasks[0].streamingOutput).toEqual(['Hello world']);

      // Now complete the task -- streamingOutput must be cleared
      const completed: ServerMessage = {
        type: 'task_completed',
        taskIndex: 1,
        totalTasks: 3,
        taskDescription: 'Task 1',
        agentRole: 'Agent A',
        completedAt: '2026-03-05T14:00:45Z',
        durationMs: 44000,
        tokenCount: 1842,
        toolCallCount: 0,
      };
      const next = liveReducer(withStreaming, completed);
      expect(next.tasks[0].status).toBe('completed');
      expect(next.tasks[0].streamingOutput).toBeUndefined();
    });

    it('token in BASE_STATE (no tasks) returns same state', () => {
      const msg: ServerMessage = {
        type: 'token',
        token: 'tok',
        agentRole: 'Agent A',
        taskDescription: 'Some task',
        sentAt: '2026-03-05T14:00:10Z',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next).toBe(BASE_STATE);
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
      // delegation_completed and delegation_failed are no-ops when no matching delegation exists
      [
        'delegation_completed (no match)',
        {
          type: 'delegation_completed' as const,
          delegationId: 'unknown-id',
          delegatingAgentRole: 'Lead',
          workerRole: 'Worker',
          durationMs: 5000,
        },
      ],
      [
        'delegation_failed (no match)',
        {
          type: 'delegation_failed' as const,
          delegationId: 'unknown-id',
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

  // ========================
  // delegation_started
  // ========================

  describe('delegation_started', () => {
    it('appends a new active delegation to the delegations list', () => {
      const msg = {
        type: 'delegation_started' as const,
        delegationId: 'del-abc',
        delegatingAgentRole: 'Manager',
        workerRole: 'Researcher',
        taskDescription: 'Research topic X',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.delegations).toHaveLength(1);
      expect(next.delegations[0]).toMatchObject({
        delegationId: 'del-abc',
        delegatingAgentRole: 'Manager',
        workerRole: 'Researcher',
        taskDescription: 'Research topic X',
        status: 'active',
        endedAt: null,
        durationMs: null,
        reason: null,
      });
      expect(typeof next.delegations[0].startedAt).toBe('number');
    });

    it('appends multiple delegations in arrival order', () => {
      const state1 = liveReducer(BASE_STATE, {
        type: 'delegation_started' as const,
        delegationId: 'del-1',
        delegatingAgentRole: 'Manager',
        workerRole: 'Worker A',
        taskDescription: 'Task A',
      });
      const state2 = liveReducer(state1, {
        type: 'delegation_started' as const,
        delegationId: 'del-2',
        delegatingAgentRole: 'Manager',
        workerRole: 'Worker B',
        taskDescription: 'Task B',
      });
      expect(state2.delegations).toHaveLength(2);
      expect(state2.delegations[0].delegationId).toBe('del-1');
      expect(state2.delegations[1].delegationId).toBe('del-2');
    });
  });

  // ========================
  // delegation_completed
  // ========================

  describe('delegation_completed', () => {
    it('marks the matching active delegation as completed with durationMs', () => {
      const startedAt = Date.now() - 5000;
      const stateWithDel: LiveState = {
        ...BASE_STATE,
        delegations: [
          {
            delegationId: 'del-1',
            delegatingAgentRole: 'Manager',
            workerRole: 'Researcher',
            taskDescription: 'Research X',
            status: 'active',
            startedAt,
            endedAt: null,
            durationMs: null,
            reason: null,
          },
        ],
      };
      const next = liveReducer(stateWithDel, {
        type: 'delegation_completed' as const,
        delegationId: 'del-1',
        delegatingAgentRole: 'Manager',
        workerRole: 'Researcher',
        durationMs: 4800,
      });
      expect(next.delegations[0].status).toBe('completed');
      expect(next.delegations[0].durationMs).toBe(4800);
      // endedAt is derived from startedAt + durationMs for accurate bar positioning;
      // it must not be Date.now() which would include network/processing latency.
      expect(next.delegations[0].endedAt).toBe(startedAt + 4800);
      expect(next.delegations[0].reason).toBeNull();
    });

    it('is a no-op when delegationId does not match any known delegation', () => {
      const next = liveReducer(BASE_STATE, {
        type: 'delegation_completed' as const,
        delegationId: 'no-such-id',
        delegatingAgentRole: 'Manager',
        workerRole: 'Worker',
        durationMs: 1000,
      });
      expect(next).toBe(BASE_STATE);
    });
  });

  // ========================
  // delegation_failed
  // ========================

  describe('delegation_failed', () => {
    it('marks the matching delegation as failed with a reason', () => {
      const stateWithDel: LiveState = {
        ...BASE_STATE,
        delegations: [
          {
            delegationId: 'del-err',
            delegatingAgentRole: 'Manager',
            workerRole: 'Worker',
            taskDescription: 'Doomed task',
            status: 'active',
            startedAt: Date.now() - 2000,
            endedAt: null,
            durationMs: null,
            reason: null,
          },
        ],
      };
      const next = liveReducer(stateWithDel, {
        type: 'delegation_failed' as const,
        delegationId: 'del-err',
        delegatingAgentRole: 'Manager',
        workerRole: 'Worker',
        reason: 'Worker agent threw an exception',
      });
      expect(next.delegations[0].status).toBe('failed');
      expect(next.delegations[0].reason).toBe('Worker agent threw an exception');
      expect(next.delegations[0].endedAt).not.toBeNull();
      expect(next.delegations[0].durationMs).toBeNull();
    });

    it('is a no-op when delegationId does not match any known delegation', () => {
      const next = liveReducer(BASE_STATE, {
        type: 'delegation_failed' as const,
        delegationId: 'ghost-id',
        delegatingAgentRole: 'Manager',
        workerRole: 'Worker',
        reason: 'Not found',
      });
      expect(next).toBe(BASE_STATE);
    });
  });

  // ========================
  // llm_iteration_started
  // ========================

  describe('llm_iteration_started', () => {
    it('creates a new conversation entry when none exists for the agent', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        messages: [
          { role: 'system', content: 'You are a researcher.', toolCalls: null, toolName: null },
          { role: 'user', content: 'Research AI trends', toolCalls: null, toolName: null },
        ],
      };
      const next = liveReducer(BASE_STATE, msg);
      const key = 'Researcher:Research AI trends';
      expect(next.conversations[key]).toBeDefined();
      expect(next.conversations[key].agentRole).toBe('Researcher');
      expect(next.conversations[key].taskDescription).toBe('Research AI trends');
      expect(next.conversations[key].iterationIndex).toBe(0);
      expect(next.conversations[key].isThinking).toBe(true);
      expect(next.conversations[key].messages).toHaveLength(2);
      expect(next.conversations[key].messages[0].role).toBe('system');
      expect(next.conversations[key].messages[0].content).toBe('You are a researcher.');
      expect(next.conversations[key].messages[0].timestamp).toBe(now);
      expect(next.conversations[key].messages[1].role).toBe('user');
      expect(next.conversations[key].messages[1].content).toBe('Research AI trends');
    });

    it('appends new messages to an existing conversation using dedup by existingCount', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const key = 'Researcher:Research AI trends';
      // Pre-populate the conversation with 2 messages (simulating a prior iteration)
      const stateWithConversation: LiveState = {
        ...BASE_STATE,
        conversations: {
          [key]: {
            agentRole: 'Researcher',
            taskDescription: 'Research AI trends',
            iterationIndex: 0,
            messages: [
              { role: 'system', content: 'You are a researcher.', timestamp: now - 5000 },
              { role: 'user', content: 'Research AI trends', timestamp: now - 5000 },
            ],
            isThinking: false,
            iterations: [],
          },
        },
      };

      // Server sends 3 messages: the 2 existing + 1 new
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 1,
        messages: [
          { role: 'system', content: 'You are a researcher.', toolCalls: null, toolName: null },
          { role: 'user', content: 'Research AI trends', toolCalls: null, toolName: null },
          { role: 'assistant', content: 'Let me research that.', toolCalls: null, toolName: null },
        ],
      };
      const next = liveReducer(stateWithConversation, msg);
      // Should have original 2 + 1 new = 3 total
      expect(next.conversations[key].messages).toHaveLength(3);
      // The first 2 messages should be the originals (unchanged timestamps)
      expect(next.conversations[key].messages[0].timestamp).toBe(now - 5000);
      expect(next.conversations[key].messages[1].timestamp).toBe(now - 5000);
      // The new message should have the current timestamp
      expect(next.conversations[key].messages[2].role).toBe('assistant');
      expect(next.conversations[key].messages[2].content).toBe('Let me research that.');
      expect(next.conversations[key].messages[2].timestamp).toBe(now);
      expect(next.conversations[key].iterationIndex).toBe(1);
      expect(next.conversations[key].isThinking).toBe(true);
    });

    it('handles capped buffer (existingCount > wireMessages.length) by keeping stored messages', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const key = 'Coder:Write code';
      // Existing conversation has 5 messages
      const existingMessages = Array.from({ length: 5 }, (_, i) => ({
        role: 'user' as const,
        content: `Message ${i}`,
        timestamp: now - 10000,
      }));
      const stateWithConversation: LiveState = {
        ...BASE_STATE,
        conversations: {
          [key]: {
            agentRole: 'Coder',
            taskDescription: 'Write code',
            iterationIndex: 2,
            messages: existingMessages,
            isThinking: false,
            iterations: [],
          },
        },
      };

      // Server sends only 3 messages (capped buffer, fewer than we have)
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Coder',
        taskDescription: 'Write code',
        iterationIndex: 3,
        messages: [
          { role: 'system', content: 'You are a coder.', toolCalls: null, toolName: null },
          { role: 'user', content: 'Write code', toolCalls: null, toolName: null },
          { role: 'assistant', content: 'Sure thing.', toolCalls: null, toolName: null },
        ],
      };
      const next = liveReducer(stateWithConversation, msg);
      // existingCount (5) > wireMessages.length (3), so slice(5) returns [],
      // no new messages are added, existing messages are preserved
      expect(next.conversations[key].messages).toHaveLength(5);
      expect(next.conversations[key].messages[0].content).toBe('Message 0');
      expect(next.conversations[key].isThinking).toBe(true);
      expect(next.conversations[key].iterationIndex).toBe(3);
    });

    it('maps tool_calls in messages to proper format', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Coder',
        taskDescription: 'Write code',
        iterationIndex: 0,
        messages: [
          {
            role: 'assistant',
            content: null,
            toolCalls: [{ name: 'web_search', arguments: '{"query":"React hooks"}' }],
            toolName: null,
          },
          {
            role: 'tool',
            content: 'Search results...',
            toolCalls: null,
            toolName: 'web_search',
          },
        ],
      };
      const next = liveReducer(BASE_STATE, msg);
      const key = 'Coder:Write code';
      expect(next.conversations[key].messages[0].toolCalls).toEqual([
        { name: 'web_search', arguments: '{"query":"React hooks"}' },
      ]);
      expect(next.conversations[key].messages[0].content).toBeNull();
      expect(next.conversations[key].messages[1].toolName).toBe('web_search');
      expect(next.conversations[key].messages[1].content).toBe('Search results...');
    });

    it('sets isThinking=true and updates iterationIndex', () => {
      const key = 'Agent:Task';
      const stateWithConversation: LiveState = {
        ...BASE_STATE,
        conversations: {
          [key]: {
            agentRole: 'Agent',
            taskDescription: 'Task',
            iterationIndex: 0,
            messages: [],
            isThinking: false,
            iterations: [],
          },
        },
      };
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Agent',
        taskDescription: 'Task',
        iterationIndex: 5,
        messages: [],
      };
      const next = liveReducer(stateWithConversation, msg);
      expect(next.conversations[key].isThinking).toBe(true);
      expect(next.conversations[key].iterationIndex).toBe(5);
    });

    it('does not mutate the input state conversations object', () => {
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Agent',
        taskDescription: 'Task',
        iterationIndex: 0,
        messages: [{ role: 'user', content: 'Hello', toolCalls: null, toolName: null }],
      };
      const before = { ...BASE_STATE.conversations };
      liveReducer(BASE_STATE, msg);
      expect(BASE_STATE.conversations).toEqual(before);
    });

    it('handles null toolCalls and null toolName by converting to undefined', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Agent',
        taskDescription: 'Task',
        iterationIndex: 0,
        messages: [
          { role: 'user', content: 'Hello', toolCalls: null, toolName: null },
        ],
      };
      const next = liveReducer(BASE_STATE, msg);
      const key = 'Agent:Task';
      // null values should be converted to undefined (via ?? undefined)
      expect(next.conversations[key].messages[0].toolCalls).toBeUndefined();
      expect(next.conversations[key].messages[0].toolName).toBeUndefined();
    });
  });

  // ========================
  // llm_iteration_completed
  // ========================

  describe('llm_iteration_completed', () => {
    const key = 'Researcher:Research AI trends';

    function stateWithConversation(isThinking: boolean): LiveState {
      return {
        ...BASE_STATE,
        conversations: {
          [key]: {
            agentRole: 'Researcher',
            taskDescription: 'Research AI trends',
            iterationIndex: 0,
            messages: [
              { role: 'system', content: 'You are a researcher.', timestamp: Date.now() - 5000 },
              { role: 'user', content: 'Research AI trends', timestamp: Date.now() - 4000 },
            ],
            isThinking,
            iterations: [],
          },
        },
      };
    }

    it('sets isThinking=false on the conversation', () => {
      const state = stateWithConversation(true);
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'Here are the trends...',
        toolRequests: null,
        inputTokens: 500,
        outputTokens: 200,
        latencyMs: 1500,
      };
      const next = liveReducer(state, msg);
      expect(next.conversations[key].isThinking).toBe(false);
    });

    it('stores the response text as an assistant message', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const state = stateWithConversation(true);
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'Here are the AI trends for 2026.',
        toolRequests: null,
        inputTokens: 500,
        outputTokens: 200,
        latencyMs: 1500,
      };
      const next = liveReducer(state, msg);
      // Should have 2 existing + 1 new assistant message = 3
      expect(next.conversations[key].messages).toHaveLength(3);
      const assistantMsg = next.conversations[key].messages[2];
      expect(assistantMsg.role).toBe('assistant');
      expect(assistantMsg.content).toBe('Here are the AI trends for 2026.');
      expect(assistantMsg.timestamp).toBe(now);
    });

    it('handles tool_requests properly by storing them as toolCalls', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const state = stateWithConversation(true);
      const toolRequests = [
        { name: 'web_search', arguments: '{"query":"latest AI papers"}' },
        { name: 'calculator', arguments: '{"expression":"2+2"}' },
      ];
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        responseType: 'TOOL_CALLS',
        responseText: null,
        toolRequests,
        inputTokens: 500,
        outputTokens: 100,
        latencyMs: 800,
      };
      const next = liveReducer(state, msg);
      const assistantMsg = next.conversations[key].messages[2];
      expect(assistantMsg.role).toBe('assistant');
      expect(assistantMsg.toolCalls).toEqual(toolRequests);
      expect(assistantMsg.content).toBeNull();
    });

    it('handles missing conversation gracefully (no crash)', () => {
      // No conversations exist in state
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Unknown',
        taskDescription: 'Unknown task',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'some text',
        toolRequests: null,
        inputTokens: 100,
        outputTokens: 50,
        latencyMs: 500,
      };
      const next = liveReducer(BASE_STATE, msg);
      // Should return the same state without crashing
      expect(next).toBe(BASE_STATE);
    });

    it('updates iterationIndex on the conversation', () => {
      const state = stateWithConversation(true);
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 7,
        responseType: 'FINAL_ANSWER',
        responseText: 'Done.',
        toolRequests: null,
        inputTokens: 100,
        outputTokens: 50,
        latencyMs: 300,
      };
      const next = liveReducer(state, msg);
      expect(next.conversations[key].iterationIndex).toBe(7);
    });

    it('does not mutate the existing conversation messages array', () => {
      const state = stateWithConversation(true);
      const originalMessages = [...state.conversations[key].messages];
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'Result',
        toolRequests: null,
        inputTokens: 100,
        outputTokens: 50,
        latencyMs: 200,
      };
      liveReducer(state, msg);
      expect(state.conversations[key].messages).toHaveLength(originalMessages.length);
    });

    it('stores null toolRequests as undefined on the assistant message', () => {
      const state = stateWithConversation(true);
      const msg: ServerMessage = {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI trends',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'Final answer.',
        toolRequests: null,
        inputTokens: 100,
        outputTokens: 50,
        latencyMs: 200,
      };
      const next = liveReducer(state, msg);
      const assistantMsg = next.conversations[key].messages[2];
      // null toolRequests becomes undefined via ?? undefined
      expect(assistantMsg.toolCalls).toBeUndefined();
    });
  });

  // ========================
  // file_changed
  // ========================

  describe('file_changed', () => {
    it('adds file change entries with correct fields', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/main.ts',
        changeType: 'MODIFIED',
        linesAdded: 10,
        linesRemoved: 3,
        timestamp: '2026-03-05T14:00:05Z',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.fileChanges).toHaveLength(1);
      expect(next.fileChanges[0].filePath).toBe('src/main.ts');
      expect(next.fileChanges[0].changeType).toBe('MODIFIED');
      expect(next.fileChanges[0].linesAdded).toBe(10);
      expect(next.fileChanges[0].linesRemoved).toBe(3);
      expect(next.fileChanges[0].agentRole).toBe('Coder');
    });

    it('uses server timestamp when it is a number', () => {
      const serverTs = 1741186805000;
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/app.ts',
        changeType: 'CREATED',
        linesAdded: 50,
        linesRemoved: 0,
        // Cast to bypass strict typing since the reducer handles number timestamps
        timestamp: serverTs as unknown as string,
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.fileChanges[0].timestamp).toBe(serverTs);
    });

    it('uses server timestamp when it is an ISO string', () => {
      const isoTimestamp = '2026-03-05T14:00:05Z';
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/index.ts',
        changeType: 'CREATED',
        linesAdded: 20,
        linesRemoved: 0,
        timestamp: isoTimestamp,
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.fileChanges[0].timestamp).toBe(Date.parse(isoTimestamp));
    });

    it('falls back to Date.now() when timestamp is missing', () => {
      const now = 1700000000000;
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/deleted.ts',
        changeType: 'DELETED',
        linesAdded: 0,
        linesRemoved: 15,
        // null timestamp (missing from server)
        timestamp: null as unknown as string,
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.fileChanges[0].timestamp).toBe(now);
    });

    it('falls back to Date.now() when timestamp is invalid', () => {
      const now = 1700000000000;
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/broken.ts',
        changeType: 'MODIFIED',
        linesAdded: 1,
        linesRemoved: 1,
        timestamp: 'not-a-valid-date',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.fileChanges[0].timestamp).toBe(now);
    });

    it('appends multiple file changes in order', () => {
      const msg1: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/a.ts',
        changeType: 'CREATED',
        linesAdded: 10,
        linesRemoved: 0,
        timestamp: '2026-03-05T14:00:01Z',
      };
      const msg2: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/b.ts',
        changeType: 'MODIFIED',
        linesAdded: 5,
        linesRemoved: 2,
        timestamp: '2026-03-05T14:00:02Z',
      };
      const s1 = liveReducer(BASE_STATE, msg1);
      const s2 = liveReducer(s1, msg2);
      expect(s2.fileChanges).toHaveLength(2);
      expect(s2.fileChanges[0].filePath).toBe('src/a.ts');
      expect(s2.fileChanges[1].filePath).toBe('src/b.ts');
    });

    it('does not mutate the existing fileChanges array', () => {
      const msg: ServerMessage = {
        type: 'file_changed',
        agentRole: 'Coder',
        filePath: 'src/new.ts',
        changeType: 'CREATED',
        linesAdded: 5,
        linesRemoved: 0,
        timestamp: '2026-03-05T14:00:01Z',
      };
      const originalLength = BASE_STATE.fileChanges.length;
      liveReducer(BASE_STATE, msg);
      expect(BASE_STATE.fileChanges).toHaveLength(originalLength);
    });
  });

  // ========================
  // metrics_snapshot
  // ========================

  describe('metrics_snapshot', () => {
    it('appends metrics snapshots to metricsHistory', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'Researcher',
        taskIndex: 1,
        inputTokens: 1500,
        outputTokens: 800,
        llmLatencyMs: 2000,
        toolExecutionTimeMs: 500,
        iterationCount: 3,
        costEstimate: '$0.05',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next.metricsHistory).toHaveLength(1);
      expect(next.metricsHistory[0].agentRole).toBe('Researcher');
      expect(next.metricsHistory[0].inputTokens).toBe(1500);
      expect(next.metricsHistory[0].outputTokens).toBe(800);
      expect(next.metricsHistory[0].llmLatencyMs).toBe(2000);
      expect(next.metricsHistory[0].iterationCount).toBe(3);
      expect(next.metricsHistory[0].timestamp).toBe(now);
    });

    it('stores all per-agent metrics correctly across multiple agents', () => {
      const now = Date.now();
      vi.setSystemTime(now);
      const msg1: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'Researcher',
        taskIndex: 1,
        inputTokens: 1000,
        outputTokens: 500,
        llmLatencyMs: 1500,
        toolExecutionTimeMs: 300,
        iterationCount: 2,
        costEstimate: null,
      };
      const msg2: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'Writer',
        taskIndex: 2,
        inputTokens: 2000,
        outputTokens: 1500,
        llmLatencyMs: 3000,
        toolExecutionTimeMs: 100,
        iterationCount: 5,
        costEstimate: '$0.10',
      };
      const s1 = liveReducer(BASE_STATE, msg1);
      const s2 = liveReducer(s1, msg2);
      expect(s2.metricsHistory).toHaveLength(2);
      expect(s2.metricsHistory[0].agentRole).toBe('Researcher');
      expect(s2.metricsHistory[0].inputTokens).toBe(1000);
      expect(s2.metricsHistory[0].outputTokens).toBe(500);
      expect(s2.metricsHistory[1].agentRole).toBe('Writer');
      expect(s2.metricsHistory[1].inputTokens).toBe(2000);
      expect(s2.metricsHistory[1].outputTokens).toBe(1500);
      expect(s2.metricsHistory[1].llmLatencyMs).toBe(3000);
      expect(s2.metricsHistory[1].iterationCount).toBe(5);
    });

    it('caps metricsHistory at 1000 entries', () => {
      // Pre-fill with 999 entries
      const existingSnapshots = Array.from({ length: 999 }, (_, i) => ({
        agentRole: `Agent-${i}`,
        inputTokens: i * 10,
        outputTokens: i * 5,
        llmLatencyMs: i * 100,
        iterationCount: i,
        timestamp: Date.now() - (999 - i) * 1000,
      }));
      const stateWith999: LiveState = {
        ...BASE_STATE,
        metricsHistory: existingSnapshots,
      };

      // Add 2 more to exceed 1000
      const msg1: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'New-Agent-1',
        taskIndex: 1,
        inputTokens: 9990,
        outputTokens: 4995,
        llmLatencyMs: 99900,
        toolExecutionTimeMs: 0,
        iterationCount: 999,
        costEstimate: null,
      };
      const msg2: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'New-Agent-2',
        taskIndex: 2,
        inputTokens: 10000,
        outputTokens: 5000,
        llmLatencyMs: 100000,
        toolExecutionTimeMs: 0,
        iterationCount: 1000,
        costEstimate: null,
      };
      const s1 = liveReducer(stateWith999, msg1);
      expect(s1.metricsHistory).toHaveLength(1000);

      const s2 = liveReducer(s1, msg2);
      // Should still be capped at 1000; oldest entry is dropped
      expect(s2.metricsHistory).toHaveLength(1000);
      // The very first entry should now be Agent-1 (Agent-0 was dropped)
      expect(s2.metricsHistory[0].agentRole).toBe('Agent-1');
      // The last entry is the newly added one
      expect(s2.metricsHistory[999].agentRole).toBe('New-Agent-2');
    });

    it('does not mutate the existing metricsHistory array', () => {
      const msg: ServerMessage = {
        type: 'metrics_snapshot',
        agentRole: 'Agent',
        taskIndex: 1,
        inputTokens: 100,
        outputTokens: 50,
        llmLatencyMs: 500,
        toolExecutionTimeMs: 100,
        iterationCount: 1,
        costEstimate: null,
      };
      const originalLength = BASE_STATE.metricsHistory.length;
      liveReducer(BASE_STATE, msg);
      expect(BASE_STATE.metricsHistory).toHaveLength(originalLength);
    });
  });

  // ========================
  // ensemble_started resets delegations
  // ========================

  describe('ensemble_started resets delegations', () => {
    it('clears active delegations when a new run starts', () => {
      const stateWithDel: LiveState = {
        ...BASE_STATE,
        tasks: [
          {
            taskIndex: 1,
            taskDescription: 'Prior task',
            agentRole: 'Manager',
            status: 'running',
            startedAt: '2026-03-05T14:00:01Z',
            completedAt: null,
            failedAt: null,
            durationMs: null,
            tokenCount: null,
            toolCallCount: null,
            toolCalls: [],
            reason: null,
          },
        ],
        delegations: [
          {
            delegationId: 'del-prior',
            delegatingAgentRole: 'Manager',
            workerRole: 'Worker',
            taskDescription: 'Prior delegation',
            status: 'active',
            startedAt: Date.now() - 5000,
            endedAt: null,
            durationMs: null,
            reason: null,
          },
        ],
      };
      const next = liveReducer(stateWithDel, {
        type: 'ensemble_started' as const,
        ensembleId: 'new-run',
        startedAt: '2026-03-05T15:00:00Z',
        totalTasks: 2,
        workflow: 'HIERARCHICAL',
      });
      expect(next.delegations).toHaveLength(0);
      // Prior delegations are archived into completedRuns
      expect(next.completedRuns).toHaveLength(1);
      expect(next.completedRuns[0].delegations).toHaveLength(1);
      expect(next.completedRuns[0].delegations[0].delegationId).toBe('del-prior');
    });
  });

  // ========================
  // hello with recentIterations (IO-003: late-join iteration snapshots)
  // ========================

  describe('hello with recentIterations', () => {
    it('hydrates conversations from recentIterations on hello', () => {
      const now = Date.now();
      vi.setSystemTime(now);

      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: [
          {
            type: 'ensemble_started',
            ensembleId: 'ens-abc',
            startedAt: '2026-03-05T14:00:00Z',
            totalTasks: 1,
            workflow: 'SEQUENTIAL',
          },
          {
            type: 'task_started',
            taskIndex: 1,
            totalTasks: 1,
            taskDescription: 'Research AI',
            agentRole: 'Researcher',
            startedAt: '2026-03-05T14:00:01Z',
          },
        ],
        recentIterations: [
          {
            started: {
              type: 'llm_iteration_started',
              agentRole: 'Researcher',
              taskDescription: 'Research AI',
              iterationIndex: 0,
              messages: [
                { role: 'system', content: 'You are a researcher.', toolCalls: null, toolName: null },
                { role: 'user', content: 'Research AI trends', toolCalls: null, toolName: null },
              ],
            },
            completed: {
              type: 'llm_iteration_completed',
              agentRole: 'Researcher',
              taskDescription: 'Research AI',
              iterationIndex: 0,
              responseType: 'FINAL_ANSWER',
              responseText: 'AI is evolving rapidly.',
              toolRequests: null,
              inputTokens: 500,
              outputTokens: 200,
              latencyMs: 1500,
            },
          },
        ],
      };

      const next = liveReducer(initialLiveState, msg);
      const key = 'Researcher:Research AI';
      expect(next.conversations[key]).toBeDefined();
      expect(next.conversations[key].isThinking).toBe(false);
      // 2 messages from started + 1 assistant from completed = 3
      expect(next.conversations[key].messages).toHaveLength(3);
      expect(next.conversations[key].messages[0].role).toBe('system');
      expect(next.conversations[key].messages[1].role).toBe('user');
      expect(next.conversations[key].messages[2].role).toBe('assistant');
      expect(next.conversations[key].messages[2].content).toBe('AI is evolving rapidly.');
    });

    it('hydrates incomplete iteration (pending, no completed)', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
        recentIterations: [
          {
            started: {
              type: 'llm_iteration_started',
              agentRole: 'Agent',
              taskDescription: 'Task',
              iterationIndex: 0,
              messages: [
                { role: 'user', content: 'Hello', toolCalls: null, toolName: null },
              ],
            },
            completed: null,
          },
        ],
      };

      const next = liveReducer(initialLiveState, msg);
      const key = 'Agent:Task';
      expect(next.conversations[key]).toBeDefined();
      expect(next.conversations[key].isThinking).toBe(true);
      expect(next.conversations[key].messages).toHaveLength(1);
      expect(next.conversations[key].messages[0].role).toBe('user');
    });

    it('handles hello without recentIterations (backward compat)', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
      };

      const next = liveReducer(initialLiveState, msg);
      expect(next.conversations).toEqual({});
    });

    it('handles hello with empty recentIterations array', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
        recentIterations: [],
      };

      const next = liveReducer(initialLiveState, msg);
      expect(next.conversations).toEqual({});
    });

    it('hydrates multiple tasks from recentIterations', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
        recentIterations: [
          {
            started: {
              type: 'llm_iteration_started',
              agentRole: 'Agent1',
              taskDescription: 'Task1',
              iterationIndex: 0,
              messages: [
                { role: 'user', content: 'Q1', toolCalls: null, toolName: null },
              ],
            },
            completed: {
              type: 'llm_iteration_completed',
              agentRole: 'Agent1',
              taskDescription: 'Task1',
              iterationIndex: 0,
              responseType: 'FINAL_ANSWER',
              responseText: 'A1',
              toolRequests: null,
              inputTokens: 100,
              outputTokens: 50,
              latencyMs: 500,
            },
          },
          {
            started: {
              type: 'llm_iteration_started',
              agentRole: 'Agent2',
              taskDescription: 'Task2',
              iterationIndex: 0,
              messages: [
                { role: 'user', content: 'Q2', toolCalls: null, toolName: null },
              ],
            },
            completed: {
              type: 'llm_iteration_completed',
              agentRole: 'Agent2',
              taskDescription: 'Task2',
              iterationIndex: 0,
              responseType: 'FINAL_ANSWER',
              responseText: 'A2',
              toolRequests: null,
              inputTokens: 200,
              outputTokens: 100,
              latencyMs: 800,
            },
          },
        ],
      };

      const next = liveReducer(initialLiveState, msg);
      expect(next.conversations['Agent1:Task1']).toBeDefined();
      expect(next.conversations['Agent2:Task2']).toBeDefined();
      expect(next.conversations['Agent1:Task1'].messages[1].content).toBe('A1');
      expect(next.conversations['Agent2:Task2'].messages[1].content).toBe('A2');
    });
  });

  // ========================
  // tool_called stores I/O fields (IO-004)
  // ========================

  describe('tool_called stores I/O fields (IO-004)', () => {
    it('stores toolArguments, toolResult, and structuredResult', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'tool_called',
        agentRole: 'Agent A',
        taskIndex: 1,
        toolName: 'web_search',
        durationMs: 800,
        outcome: 'SUCCESS',
        toolArguments: '{"query":"AI trends"}',
        toolResult: 'Top 10 AI trends...',
        structuredResult: { items: ['trend1', 'trend2'] },
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].toolCalls[0].toolArguments).toBe('{"query":"AI trends"}');
      expect(next.tasks[0].toolCalls[0].toolResult).toBe('Top 10 AI trends...');
      expect(next.tasks[0].toolCalls[0].structuredResult).toEqual({ items: ['trend1', 'trend2'] });
      expect(next.tasks[0].toolCalls[0].taskIndex).toBe(1);
    });

    it('normalizes null I/O fields to null', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
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
      const next = liveReducer(state, msg);
      expect(next.tasks[0].toolCalls[0].toolArguments).toBeNull();
      expect(next.tasks[0].toolCalls[0].toolResult).toBeNull();
      expect(next.tasks[0].toolCalls[0].structuredResult).toBeNull();
    });
  });

  // ========================
  // task_input (IO-004/IO-005)
  // ========================

  describe('task_input (IO-004/IO-005)', () => {
    it('stores TaskInput on the matching task', () => {
      const state = stateWithTask(1, 'running');
      const msg: ServerMessage = {
        type: 'task_input',
        taskIndex: 1,
        taskDescription: 'Research AI',
        expectedOutput: 'A report',
        agentRole: 'Researcher',
        agentGoal: 'Find AI trends',
        agentBackground: 'Senior analyst',
        toolNames: ['web_search', 'calculator'],
        assembledContext: 'Prior context: ...',
      };
      const next = liveReducer(state, msg);
      expect(next.tasks[0].taskInput).toBeDefined();
      expect(next.tasks[0].taskInput!.agentRole).toBe('Researcher');
      expect(next.tasks[0].taskInput!.agentGoal).toBe('Find AI trends');
      expect(next.tasks[0].taskInput!.toolNames).toEqual(['web_search', 'calculator']);
      expect(next.tasks[0].taskInput!.assembledContext).toBe('Prior context: ...');
    });

    it('returns same state when task not found', () => {
      const msg: ServerMessage = {
        type: 'task_input',
        taskIndex: 99,
        taskDescription: 'Unknown',
        expectedOutput: '',
        agentRole: 'Agent',
        agentGoal: '',
        agentBackground: '',
        toolNames: [],
        assembledContext: '',
      };
      const next = liveReducer(BASE_STATE, msg);
      expect(next).toBe(BASE_STATE);
    });

    it('does not mutate the existing task', () => {
      const state = stateWithTask(1, 'running');
      const originalTask = state.tasks[0];
      const msg: ServerMessage = {
        type: 'task_input',
        taskIndex: 1,
        taskDescription: 'Research AI',
        expectedOutput: '',
        agentRole: 'Agent',
        agentGoal: 'Goal',
        agentBackground: '',
        toolNames: [],
        assembledContext: '',
      };
      liveReducer(state, msg);
      expect(originalTask.taskInput).toBeUndefined();
    });
  });

  // ========================
  // iterations tracking (IO-005)
  // ========================

  describe('iterations tracking (IO-005)', () => {
    it('llm_iteration_started creates a pending iteration', () => {
      const msg: ServerMessage = {
        type: 'llm_iteration_started',
        agentRole: 'Researcher',
        taskDescription: 'Research AI',
        iterationIndex: 0,
        messages: [
          { role: 'user', content: 'Research AI trends', toolCalls: null, toolName: null },
        ],
      };
      const next = liveReducer(BASE_STATE, msg);
      const key = 'Researcher:Research AI';
      expect(next.conversations[key].iterations).toHaveLength(1);
      expect(next.conversations[key].iterations[0].iterationIndex).toBe(0);
      expect(next.conversations[key].iterations[0].pending).toBe(true);
      expect(next.conversations[key].iterations[0].inputMessages).toHaveLength(1);
    });

    it('llm_iteration_completed marks the iteration as completed with metadata', () => {
      let state = liveReducer(BASE_STATE, {
        type: 'llm_iteration_started',
        agentRole: 'Researcher',
        taskDescription: 'Research AI',
        iterationIndex: 0,
        messages: [
          { role: 'user', content: 'Question', toolCalls: null, toolName: null },
        ],
      } as ServerMessage);

      state = liveReducer(state, {
        type: 'llm_iteration_completed',
        agentRole: 'Researcher',
        taskDescription: 'Research AI',
        iterationIndex: 0,
        responseType: 'FINAL_ANSWER',
        responseText: 'Here are the trends.',
        toolRequests: null,
        inputTokens: 500,
        outputTokens: 200,
        latencyMs: 1200,
      } as ServerMessage);

      const key = 'Researcher:Research AI';
      expect(state.conversations[key].iterations).toHaveLength(1);
      expect(state.conversations[key].iterations[0].pending).toBe(false);
      expect(state.conversations[key].iterations[0].responseType).toBe('FINAL_ANSWER');
      expect(state.conversations[key].iterations[0].responseText).toBe('Here are the trends.');
      expect(state.conversations[key].iterations[0].inputTokens).toBe(500);
      expect(state.conversations[key].iterations[0].outputTokens).toBe(200);
      expect(state.conversations[key].iterations[0].latencyMs).toBe(1200);
    });

    it('multiple iterations accumulate in order', () => {
      let state: LiveState = BASE_STATE;
      state = liveReducer(state, {
        type: 'llm_iteration_started',
        agentRole: 'A',
        taskDescription: 'T',
        iterationIndex: 0,
        messages: [{ role: 'user', content: 'Q1', toolCalls: null, toolName: null }],
      } as ServerMessage);
      state = liveReducer(state, {
        type: 'llm_iteration_completed',
        agentRole: 'A',
        taskDescription: 'T',
        iterationIndex: 0,
        responseType: 'TOOL_CALLS',
        responseText: null,
        toolRequests: [{ name: 'web_search', arguments: '{}' }],
        inputTokens: 100,
        outputTokens: 50,
        latencyMs: 600,
      } as ServerMessage);
      state = liveReducer(state, {
        type: 'llm_iteration_started',
        agentRole: 'A',
        taskDescription: 'T',
        iterationIndex: 1,
        messages: [
          { role: 'user', content: 'Q1', toolCalls: null, toolName: null },
          { role: 'assistant', content: null, toolCalls: [{ name: 'web_search', arguments: '{}' }], toolName: null },
          { role: 'tool', content: 'Results...', toolCalls: null, toolName: 'web_search' },
        ],
      } as ServerMessage);
      state = liveReducer(state, {
        type: 'llm_iteration_completed',
        agentRole: 'A',
        taskDescription: 'T',
        iterationIndex: 1,
        responseType: 'FINAL_ANSWER',
        responseText: 'Done.',
        toolRequests: null,
        inputTokens: 200,
        outputTokens: 100,
        latencyMs: 800,
      } as ServerMessage);

      const key = 'A:T';
      expect(state.conversations[key].iterations).toHaveLength(2);
      expect(state.conversations[key].iterations[0].iterationIndex).toBe(0);
      expect(state.conversations[key].iterations[0].responseType).toBe('TOOL_CALLS');
      expect(state.conversations[key].iterations[1].iterationIndex).toBe(1);
      expect(state.conversations[key].iterations[1].responseType).toBe('FINAL_ANSWER');
    });

    it('hello with recentIterations hydrates iterations', () => {
      const msg: ServerMessage = {
        type: 'hello',
        ensembleId: 'ens-abc',
        startedAt: '2026-03-05T14:00:00Z',
        snapshotTrace: null,
        recentIterations: [
          {
            started: {
              type: 'llm_iteration_started',
              agentRole: 'Agent',
              taskDescription: 'Task',
              iterationIndex: 0,
              messages: [
                { role: 'user', content: 'Hello', toolCalls: null, toolName: null },
              ],
            },
            completed: {
              type: 'llm_iteration_completed',
              agentRole: 'Agent',
              taskDescription: 'Task',
              iterationIndex: 0,
              responseType: 'FINAL_ANSWER',
              responseText: 'World',
              toolRequests: null,
              inputTokens: 100,
              outputTokens: 50,
              latencyMs: 500,
            },
          },
        ],
      };
      const next = liveReducer(initialLiveState, msg);
      const key = 'Agent:Task';
      expect(next.conversations[key].iterations).toHaveLength(1);
      expect(next.conversations[key].iterations[0].pending).toBe(false);
      expect(next.conversations[key].iterations[0].responseText).toBe('World');
      expect(next.conversations[key].iterations[0].inputTokens).toBe(100);
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

  describe('RESOLVE_REVIEW', () => {
    const stateWithReviews: LiveState = {
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
          requiredRole: null,
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
          requiredRole: null,
          receivedAt: Date.now(),
        },
        {
          reviewId: 'review-3',
          taskDescription: 'Task C',
          taskOutput: 'Output C',
          timing: 'AFTER_EXECUTION',
          prompt: null,
          timeoutMs: 300000,
          onTimeout: 'EXIT_EARLY',
          requiredRole: null,
          receivedAt: Date.now(),
        },
      ],
    };

    it('removes the resolved review from pendingReviews', () => {
      const action: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-1' };
      const next = liveActionReducer(stateWithReviews, action);
      expect(next.pendingReviews).toHaveLength(2);
      expect(next.pendingReviews.find((r) => r.reviewId === 'review-1')).toBeUndefined();
    });

    it('leaves other reviews intact when one is resolved', () => {
      const action: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-2' };
      const next = liveActionReducer(stateWithReviews, action);
      expect(next.pendingReviews).toHaveLength(2);
      expect(next.pendingReviews[0].reviewId).toBe('review-1');
      expect(next.pendingReviews[1].reviewId).toBe('review-3');
    });

    it('resolving all reviews one by one empties the queue (FIFO order)', () => {
      const a1: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-1' };
      const a2: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-2' };
      const a3: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-3' };

      const s1 = liveActionReducer(stateWithReviews, a1);
      expect(s1.pendingReviews).toHaveLength(2);
      expect(s1.pendingReviews[0].reviewId).toBe('review-2');

      const s2 = liveActionReducer(s1, a2);
      expect(s2.pendingReviews).toHaveLength(1);
      expect(s2.pendingReviews[0].reviewId).toBe('review-3');

      const s3 = liveActionReducer(s2, a3);
      expect(s3.pendingReviews).toHaveLength(0);
    });

    it('is a no-op when the reviewId is not found', () => {
      const action: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-unknown' };
      const next = liveActionReducer(stateWithReviews, action);
      expect(next.pendingReviews).toHaveLength(3);
    });

    it('does not mutate the input state', () => {
      const original = [...stateWithReviews.pendingReviews];
      const action: LiveAction = { type: 'RESOLVE_REVIEW', reviewId: 'review-1' };
      liveActionReducer(stateWithReviews, action);
      expect(stateWithReviews.pendingReviews).toHaveLength(3);
      expect(stateWithReviews.pendingReviews).toEqual(original);
    });
  });
});

// Restore fake timers after each test
beforeEach(() => {
  vi.useRealTimers();
});
