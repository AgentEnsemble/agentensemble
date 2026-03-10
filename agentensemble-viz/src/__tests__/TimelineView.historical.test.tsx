/**
 * Unit tests for TimelineView in historical (static trace) mode.
 *
 * Covers the groupBy toggle behavior and lane rendering in both "By Task"
 * (default) and "By Agent" modes for HistoricalTimelineView.
 */

import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TimelineView from '../pages/TimelineView.js';
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
});
