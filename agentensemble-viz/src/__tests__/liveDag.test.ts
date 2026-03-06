/**
 * Unit tests for liveDag.ts utilities.
 *
 * buildSyntheticDagModel and buildLiveStatusMap are pure functions that can be
 * tested without any DOM or React setup.
 */

import { describe, it, expect } from 'vitest';
import { buildSyntheticDagModel, buildLiveStatusMap, liveTaskNodeId } from '../utils/liveDag.js';
import { initialLiveState } from '../utils/liveReducer.js';
import type { LiveState, LiveTask } from '../types/live.js';

// ========================
// Fixtures
// ========================

function makeRunningTask(taskIndex: number, agentRole: string, taskDescription: string): LiveTask {
  return {
    taskIndex,
    taskDescription,
    agentRole,
    status: 'running',
    startedAt: '2026-03-05T14:00:01Z',
    completedAt: null,
    failedAt: null,
    durationMs: null,
    tokenCount: null,
    toolCallCount: null,
    toolCalls: [],
    reason: null,
  };
}

function makeCompletedTask(
  taskIndex: number,
  agentRole: string,
  taskDescription: string,
): LiveTask {
  return {
    ...makeRunningTask(taskIndex, agentRole, taskDescription),
    status: 'completed',
    completedAt: '2026-03-05T14:00:30Z',
    durationMs: 29000,
  };
}

function makeFailedTask(taskIndex: number, agentRole: string, taskDescription: string): LiveTask {
  return {
    ...makeRunningTask(taskIndex, agentRole, taskDescription),
    status: 'failed',
    failedAt: '2026-03-05T14:00:30Z',
    reason: 'MaxIterationsExceededException',
  };
}

// ========================
// liveTaskNodeId
// ========================

describe('liveTaskNodeId', () => {
  it('produces deterministic IDs', () => {
    expect(liveTaskNodeId(0)).toBe('live-task-0');
    expect(liveTaskNodeId(3)).toBe('live-task-3');
  });
});

// ========================
// buildSyntheticDagModel
// ========================

describe('buildSyntheticDagModel', () => {
  it('returns empty tasks and agents when liveState has no tasks', () => {
    const model = buildSyntheticDagModel(initialLiveState);
    expect(model.tasks).toHaveLength(0);
    expect(model.agents).toHaveLength(0);
    expect(model.type).toBe('dag');
  });

  it('creates one task node per live task', () => {
    const state: LiveState = {
      ...initialLiveState,
      workflow: 'SEQUENTIAL',
      tasks: [
        makeRunningTask(0, 'Researcher', 'Do research'),
        makeCompletedTask(1, 'Writer', 'Write report'),
      ],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.tasks).toHaveLength(2);
    expect(model.tasks[0].id).toBe('live-task-0');
    expect(model.tasks[0].description).toBe('Do research');
    expect(model.tasks[1].id).toBe('live-task-1');
    expect(model.tasks[1].description).toBe('Write report');
  });

  it('collects unique agents in arrival order', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [
        makeRunningTask(0, 'Researcher', 'Task A'),
        makeRunningTask(1, 'Writer', 'Task B'),
        makeRunningTask(2, 'Researcher', 'Task C'), // duplicate role
      ],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.agents).toHaveLength(2);
    expect(model.agents[0].role).toBe('Researcher');
    expect(model.agents[1].role).toBe('Writer');
  });

  it('creates linear chain dependencies for SEQUENTIAL workflow', () => {
    const state: LiveState = {
      ...initialLiveState,
      workflow: 'SEQUENTIAL',
      tasks: [
        makeRunningTask(0, 'Agent A', 'Task 0'),
        makeRunningTask(1, 'Agent B', 'Task 1'),
        makeRunningTask(2, 'Agent C', 'Task 2'),
      ],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.tasks[0].dependsOn).toHaveLength(0); // root
    expect(model.tasks[1].dependsOn).toEqual(['live-task-0']);
    expect(model.tasks[2].dependsOn).toEqual(['live-task-1']);
  });

  it('creates no dependencies for PARALLEL workflow', () => {
    const state: LiveState = {
      ...initialLiveState,
      workflow: 'PARALLEL',
      tasks: [
        makeRunningTask(0, 'Agent A', 'Task 0'),
        makeRunningTask(1, 'Agent B', 'Task 1'),
      ],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.tasks[0].dependsOn).toHaveLength(0);
    expect(model.tasks[1].dependsOn).toHaveLength(0);
  });

  it('uses the liveState workflow type', () => {
    const state: LiveState = {
      ...initialLiveState,
      workflow: 'HIERARCHICAL',
      tasks: [makeRunningTask(0, 'Lead', 'Manage')],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.workflow).toBe('HIERARCHICAL');
  });

  it('uses SEQUENTIAL when workflow is null', () => {
    const state: LiveState = {
      ...initialLiveState,
      workflow: null,
      tasks: [makeRunningTask(0, 'Agent A', 'Task')],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.workflow).toBe('SEQUENTIAL');
  });

  it('has no criticalPath', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [makeRunningTask(0, 'Agent A', 'Task')],
    };
    const model = buildSyntheticDagModel(state);
    expect(model.criticalPath).toHaveLength(0);
  });
});

// ========================
// buildLiveStatusMap
// ========================

describe('buildLiveStatusMap', () => {
  it('returns an empty map when there are no tasks', () => {
    const map = buildLiveStatusMap(initialLiveState);
    expect(map.size).toBe(0);
  });

  it('maps running task to "running" status', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [makeRunningTask(0, 'Agent A', 'Task 0')],
    };
    const map = buildLiveStatusMap(state);
    expect(map.get('live-task-0')).toBe('running');
  });

  it('maps failed task to "failed" status', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [makeFailedTask(0, 'Agent A', 'Task 0')],
    };
    const map = buildLiveStatusMap(state);
    expect(map.get('live-task-0')).toBe('failed');
  });

  it('maps completed task to undefined (uses agent color in rendering)', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [makeCompletedTask(0, 'Agent A', 'Task 0')],
    };
    const map = buildLiveStatusMap(state);
    // completed tasks are stored as undefined so TaskNode uses normal agent color
    expect(map.get('live-task-0')).toBeUndefined();
    // but the key IS in the map (set to undefined explicitly)
    expect(map.has('live-task-0')).toBe(true);
  });

  it('handles mixed statuses for multiple tasks', () => {
    const state: LiveState = {
      ...initialLiveState,
      tasks: [
        makeCompletedTask(0, 'Agent A', 'Task 0'),
        makeRunningTask(1, 'Agent B', 'Task 1'),
        makeFailedTask(2, 'Agent C', 'Task 2'),
      ],
    };
    const map = buildLiveStatusMap(state);
    expect(map.get('live-task-0')).toBeUndefined(); // completed -> agent color
    expect(map.get('live-task-1')).toBe('running');
    expect(map.get('live-task-2')).toBe('failed');
  });
});
