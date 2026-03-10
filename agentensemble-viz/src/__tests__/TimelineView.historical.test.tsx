/**
 * Unit tests for TimelineView in historical (static trace) mode.
 *
 * Covers the groupBy toggle behavior and lane rendering in both "By Task"
 * (default) and "By Agent" modes for HistoricalTimelineView.
 */

import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TimelineView, { buildHistoricalByTaskLanes } from '../pages/TimelineView.js';
import type { ExecutionTrace, TaskTrace } from '../types/trace.js';

// ========================
// Helpers
// ========================

const BASE_START = '2026-03-10T10:00:00.000Z';
const BASE_START_MS = new Date(BASE_START).getTime();

function makeTaskTrace(
  description: string,
  agentRole: string,
  offsetMs = 0,
  durationMs = 5000,
): TaskTrace {
  const startedAt = new Date(BASE_START_MS + offsetMs).toISOString();
  const completedAt = new Date(BASE_START_MS + offsetMs + durationMs).toISOString();
  return {
    taskDescription: description,
    agentRole,
    startedAt,
    completedAt,
    duration: 'PT5S',
    llmInteractions: [],
    finalOutput: 'output',
    metrics: {} as TaskTrace['metrics'],
    prompts: null,
  } as unknown as TaskTrace;
}

function makeTrace(taskTraces: TaskTrace[]): ExecutionTrace {
  const roles = [...new Set(taskTraces.map((t) => t.agentRole))];
  const completedAt = new Date(BASE_START_MS + 60000).toISOString();
  return {
    workflow: 'SEQUENTIAL',
    taskTraces,
    agents: roles.map((role) => ({ role, goal: role, background: null, toolNames: [], allowDelegation: false })),
    startedAt: BASE_START,
    completedAt,
    totalDuration: 'PT60S',
    captureMode: 'STANDARD',
    metrics: {} as ExecutionTrace['metrics'],
    errors: [],
    inputs: {},
    metadata: {},
    ensembleId: 'test-ensemble',
    schemaVersion: '1.1',
  } as unknown as ExecutionTrace;
}

// ========================
// Tests
// ========================

describe('TimelineView historical mode', () => {
  describe('grouping toggle presence and defaults', () => {
    it('renders the grouping toggle', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      expect(screen.getByTestId('grouping-toggle')).toBeInTheDocument();
    });

    it('defaults to "By Task" grouping', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      const toggle = screen.getByTestId('grouping-toggle');
      expect(toggle).toHaveAttribute('data-grouping', 'task');
      expect(toggle).toHaveTextContent('By Task');
    });

    it('has aria-pressed="true" in "By Task" mode', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      expect(screen.getByTestId('grouping-toggle')).toHaveAttribute('aria-pressed', 'true');
    });

    it('has type="button" on the toggle', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      expect(screen.getByTestId('grouping-toggle')).toHaveAttribute('type', 'button');
    });
  });

  describe('"By Task" mode (default)', () => {
    it('shows one lane label per task when all tasks share the same role', () => {
      const trace = makeTrace([
        makeTaskTrace('Research AI trends', 'Researcher', 0),
        makeTaskTrace('Research blockchain', 'Researcher', 5000),
        makeTaskTrace('Summarize findings', 'Agent', 10000),
      ]);
      render(<TimelineView trace={trace} />);
      const labels = screen.getAllByTestId('timeline-lane-label');
      // 3 tasks -> 3 lanes
      expect(labels).toHaveLength(3);
      labels.forEach((label) => expect(label).toHaveAttribute('data-lane-type', 'task'));
    });

    it('shows one lane label per task when tasks have unique roles', () => {
      const trace = makeTrace([
        makeTaskTrace('Research the topic', 'Researcher', 0),
        makeTaskTrace('Write the report', 'Writer', 5000),
      ]);
      render(<TimelineView trace={trace} />);
      const labels = screen.getAllByTestId('timeline-lane-label');
      expect(labels).toHaveLength(2);
      labels.forEach((label) => expect(label).toHaveAttribute('data-lane-type', 'task'));
    });
  });

  describe('"By Agent" mode', () => {
    it('switches to "By Agent" when toggle is clicked', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      const toggle = screen.getByTestId('grouping-toggle');
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('data-grouping', 'agent');
      expect(toggle).toHaveTextContent('By Agent');
    });

    it('has aria-pressed="false" in "By Agent" mode', () => {
      const trace = makeTrace([makeTaskTrace('Research AI trends', 'Researcher')]);
      render(<TimelineView trace={trace} />);
      fireEvent.click(screen.getByTestId('grouping-toggle'));
      expect(screen.getByTestId('grouping-toggle')).toHaveAttribute('aria-pressed', 'false');
    });

    it('shows one lane label per unique agent role in "By Agent" mode', () => {
      const trace = makeTrace([
        makeTaskTrace('Research AI trends', 'Researcher', 0),
        makeTaskTrace('Research blockchain', 'Researcher', 5000),
        makeTaskTrace('Summarize findings', 'Agent', 10000),
      ]);
      render(<TimelineView trace={trace} />);
      fireEvent.click(screen.getByTestId('grouping-toggle'));
      const labels = screen.getAllByTestId('timeline-lane-label');
      // 2 unique roles: Researcher and Agent
      expect(labels).toHaveLength(2);
      labels.forEach((label) => expect(label).toHaveAttribute('data-lane-type', 'agent'));
    });

    it('collapses multiple tasks with the same role into one lane in "By Agent" mode', () => {
      const trace = makeTrace([
        makeTaskTrace('Task One', 'Agent', 0),
        makeTaskTrace('Task Two', 'Agent', 5000),
        makeTaskTrace('Task Three', 'Agent', 10000),
      ]);
      render(<TimelineView trace={trace} />);
      // By Task mode: 3 lanes
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(3);
      // Switch to By Agent: 1 lane (all share 'Agent' role)
      fireEvent.click(screen.getByTestId('grouping-toggle'));
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(1);
    });
  });

  describe('toggle round-trip', () => {
    it('switching back from "By Agent" to "By Task" restores per-task lanes', () => {
      const trace = makeTrace([
        makeTaskTrace('Research AI trends', 'Researcher', 0),
        makeTaskTrace('Research blockchain', 'Researcher', 5000),
      ]);
      render(<TimelineView trace={trace} />);
      const toggle = screen.getByTestId('grouping-toggle');

      fireEvent.click(toggle); // By Agent -- 1 lane
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(1);

      fireEvent.click(toggle); // Back to By Task -- 2 lanes
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(2);
      expect(toggle).toHaveAttribute('data-grouping', 'task');
    });
  });

  describe('HIERARCHICAL workflow renders indented worker lanes', () => {
    function makeHierarchicalTrace(): ExecutionTrace {
      const managerTask = makeTaskTrace('Coordinate team', 'Manager', 0, 30000);
      const worker1 = makeTaskTrace('Research topic', 'Researcher', 1000, 15000);
      const worker2 = makeTaskTrace('Write summary', 'Writer', 16000, 12000);
      const trace = makeTrace([managerTask, worker1, worker2]);
      return { ...trace, workflow: 'HIERARCHICAL' };
    }

    it('renders Manager lane at depth 0 and worker lanes at depth 1', () => {
      const trace = makeHierarchicalTrace();
      render(<TimelineView trace={trace} />);
      // Manager at depth 0 uses data-lane-type="task"
      const taskLabels = screen.getAllByTestId('timeline-lane-label').filter(
        (el) => el.getAttribute('data-lane-type') === 'task',
      );
      const delegationLabels = screen.getAllByTestId('timeline-lane-label').filter(
        (el) => el.getAttribute('data-lane-type') === 'delegation',
      );
      expect(taskLabels).toHaveLength(1); // Manager
      expect(delegationLabels).toHaveLength(2); // Researcher and Writer
    });

    it('total lane count is 3 (1 Manager + 2 workers) in HIERARCHICAL "By Task" mode', () => {
      const trace = makeHierarchicalTrace();
      render(<TimelineView trace={trace} />);
      const labels = screen.getAllByTestId('timeline-lane-label');
      expect(labels).toHaveLength(3);
    });
  });
});

// ========================
// buildHistoricalByTaskLanes unit tests
// ========================

describe('buildHistoricalByTaskLanes', () => {
  const base: TaskTrace = makeTaskTrace('T', 'A') as unknown as TaskTrace;

  it('returns depth-0 lanes for non-HIERARCHICAL workflows', () => {
    const traces = [
      makeTaskTrace('Task 1', 'Agent A') as unknown as TaskTrace,
      makeTaskTrace('Task 2', 'Agent B') as unknown as TaskTrace,
    ];
    const lanes = buildHistoricalByTaskLanes(traces, 'SEQUENTIAL');
    expect(lanes).toHaveLength(2);
    expect(lanes.every((l) => l.depth === 0)).toBe(true);
  });

  it('returns depth-0 lanes for PARALLEL workflow', () => {
    const traces = [makeTaskTrace('T', 'A') as unknown as TaskTrace];
    const lanes = buildHistoricalByTaskLanes(traces, 'PARALLEL');
    expect(lanes[0].depth).toBe(0);
  });

  it('places Manager at depth 0 and workers at depth 1 for HIERARCHICAL workflow', () => {
    const manager = { ...base, agentRole: 'Manager', taskDescription: 'Manage' } as unknown as TaskTrace;
    const worker1 = { ...base, agentRole: 'Researcher', taskDescription: 'Research' } as unknown as TaskTrace;
    const worker2 = { ...base, agentRole: 'Writer', taskDescription: 'Write' } as unknown as TaskTrace;
    const lanes = buildHistoricalByTaskLanes([manager, worker1, worker2], 'HIERARCHICAL');
    expect(lanes).toHaveLength(3);
    expect(lanes[0].trace.agentRole).toBe('Manager');
    expect(lanes[0].depth).toBe(0);
    expect(lanes[1].depth).toBe(1);
    expect(lanes[2].depth).toBe(1);
  });

  it('falls back to flat depth-0 rendering when no Manager task is found', () => {
    const w1 = { ...base, agentRole: 'Worker A' } as unknown as TaskTrace;
    const w2 = { ...base, agentRole: 'Worker B' } as unknown as TaskTrace;
    const lanes = buildHistoricalByTaskLanes([w1, w2], 'HIERARCHICAL');
    // No Manager -> all depth 0
    expect(lanes.every((l) => l.depth === 0)).toBe(true);
    expect(lanes).toHaveLength(2);
  });

  it('preserves input order for non-HIERARCHICAL traces', () => {
    const t1 = { ...base, taskDescription: 'First' } as unknown as TaskTrace;
    const t2 = { ...base, taskDescription: 'Second' } as unknown as TaskTrace;
    const t3 = { ...base, taskDescription: 'Third' } as unknown as TaskTrace;
    const lanes = buildHistoricalByTaskLanes([t1, t2, t3], 'SEQUENTIAL');
    expect(lanes.map((l) => l.trace.taskDescription)).toEqual(['First', 'Second', 'Third']);
  });

  it('places Manager first regardless of input order', () => {
    const worker = { ...base, agentRole: 'Worker', taskDescription: 'Work' } as unknown as TaskTrace;
    const manager = { ...base, agentRole: 'Manager', taskDescription: 'Manage' } as unknown as TaskTrace;
    // Worker comes before Manager in input
    const lanes = buildHistoricalByTaskLanes([worker, manager], 'HIERARCHICAL');
    expect(lanes[0].trace.agentRole).toBe('Manager');
    expect(lanes[0].depth).toBe(0);
    expect(lanes[1].trace.agentRole).toBe('Worker');
    expect(lanes[1].depth).toBe(1);
  });
});
