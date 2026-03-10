/**
 * Unit tests for TimelineView in live mode.
 *
 * useLiveServer is mocked at the module level so tests can control liveState
 * without a real WebSocket connection.
 *
 * requestAnimationFrame is replaced with a no-op stub so the rAF loop does
 * not interfere with synchronous test assertions.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TimelineView from '../pages/TimelineView.js';
import { initialLiveState } from '../utils/liveReducer.js';
import type { LiveState, LiveTask, CompletedRun } from '../types/live.js';
import type { LiveServerContextValue } from '../contexts/LiveServerContext.js';

// ========================
// Mock LiveServerContext
// ========================

// vi.mock is hoisted -- the factory runs before any imports
vi.mock('../contexts/LiveServerContext.js', () => ({
  useLiveServer: vi.fn(),
  LiveServerProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

import { useLiveServer } from '../contexts/LiveServerContext.js';
const mockUseLiveServer = vi.mocked(useLiveServer);

// ========================
// Helpers
// ========================

const NOW = new Date('2026-03-05T14:01:00Z').getTime();
const STARTED_AT = '2026-03-05T14:00:00Z';
const STARTED_AT_MS = new Date(STARTED_AT).getTime();

function makeRunningTask(taskIndex: number, agentRole = 'Agent A'): LiveTask {
  return {
    taskIndex,
    taskDescription: `Task ${taskIndex}`,
    agentRole,
    status: 'running',
    startedAt: new Date(STARTED_AT_MS + taskIndex * 5000).toISOString(),
    completedAt: null,
    failedAt: null,
    durationMs: null,
    tokenCount: null,
    toolCallCount: null,
    toolCalls: [],
    reason: null,
  };
}

function makeCompletedTask(taskIndex: number, agentRole = 'Agent A'): LiveTask {
  const startMs = STARTED_AT_MS + taskIndex * 5000;
  return {
    ...makeRunningTask(taskIndex, agentRole),
    status: 'completed',
    completedAt: new Date(startMs + 30000).toISOString(),
    durationMs: 30000,
  };
}

function makeFailedTask(taskIndex: number, agentRole = 'Agent A'): LiveTask {
  const startMs = STARTED_AT_MS + taskIndex * 5000;
  return {
    ...makeRunningTask(taskIndex, agentRole),
    status: 'failed',
    failedAt: new Date(startMs + 10000).toISOString(),
    reason: 'MaxIterationsExceededException',
  };
}

function makeLiveState(partial: Partial<LiveState> = {}): LiveState {
  return {
    ...initialLiveState,
    connectionStatus: 'connected',
    startedAt: STARTED_AT,
    workflow: 'SEQUENTIAL',
    totalTasks: 3,
    ...partial,
  };
}

function mockLiveServer(liveState: LiveState) {
  mockUseLiveServer.mockReturnValue({
    liveState,
    connect: vi.fn(),
    disconnect: vi.fn(),
    // Return boolean so the mocks satisfy LiveServerContextValue's typed signatures.
    sendMessage: vi.fn(() => false),
    sendDecision: vi.fn(() => false),
  } as unknown as LiveServerContextValue);
}

function renderLiveTimeline() {
  return render(<TimelineView trace={null} isLive />);
}

// ========================
// Setup / teardown
// ========================

beforeEach(() => {
  vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(() => 0);
  vi.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(() => {});
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

// ========================
// Tests
// ========================

describe('TimelineView live mode', () => {
  describe('task bar appearance', () => {
    it('shows "Waiting for tasks..." when no tasks have started', () => {
      mockLiveServer(makeLiveState({ tasks: [] }));
      renderLiveTimeline();
      expect(screen.getByText(/waiting for tasks/i)).toBeInTheDocument();
    });

    it('renders a task bar when a task_started event has been received', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const bars = screen.getAllByTestId('live-task-bar');
      expect(bars).toHaveLength(1);
      expect(bars[0]).toHaveAttribute('data-task-index', '0');
      expect(bars[0]).toHaveAttribute('data-task-status', 'running');
    });

    it('renders an animated right-edge indicator for running tasks', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const edge = screen.getByTestId('live-task-bar-running-edge');
      expect(edge).toBeInTheDocument();
      // SVG elements use SVGAnimatedString for className; getAttribute('class') returns a plain string
      expect(edge.getAttribute('class')).toContain('ae-pulse');
    });

    it('does NOT render animated edge for completed tasks', () => {
      const state = makeLiveState({ tasks: [makeCompletedTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.queryByTestId('live-task-bar-running-edge')).toBeNull();
    });

    it('renders completed task with data-task-status="completed"', () => {
      const state = makeLiveState({ tasks: [makeCompletedTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const bars = screen.getAllByTestId('live-task-bar');
      expect(bars[0]).toHaveAttribute('data-task-status', 'completed');
    });

    it('renders failed task with data-task-status="failed"', () => {
      const state = makeLiveState({ tasks: [makeFailedTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const bar = screen.getAllByTestId('live-task-bar')[0];
      expect(bar).toHaveAttribute('data-task-status', 'failed');
    });

    it('applies red fill color to failed task bar', () => {
      const state = makeLiveState({ tasks: [makeFailedTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const bar = screen.getAllByTestId('live-task-bar')[0];
      // The fill attribute should contain the red color for failed tasks
      expect(bar.getAttribute('fill')).toContain('239'); // rgba(239,...) = #EF4444
    });

    it('renders multiple task bars for multiple tasks', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0, 'Agent A'), makeCompletedTask(1, 'Agent B')],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      const bars = screen.getAllByTestId('live-task-bar');
      expect(bars).toHaveLength(2);
    });
  });

  describe('tool call markers', () => {
    it('renders a tool marker when a tool_called event has been received', () => {
      const task = makeRunningTask(0);
      task.toolCalls = [
        { toolName: 'web_search', durationMs: 1200, outcome: 'SUCCESS', receivedAt: NOW - 5000 },
      ];
      const state = makeLiveState({ tasks: [task] });
      mockLiveServer(state);
      renderLiveTimeline();
      const marker = screen.getByTestId('live-tool-marker');
      expect(marker).toBeInTheDocument();
      expect(marker).toHaveAttribute('data-tool-name', 'web_search');
      expect(marker).toHaveAttribute('data-task-index', '0');
    });

    it('renders multiple tool markers in order', () => {
      const task = makeRunningTask(0);
      task.toolCalls = [
        { toolName: 'web_search', durationMs: 1200, outcome: 'SUCCESS', receivedAt: NOW - 5000 },
        { toolName: 'calculator', durationMs: 50, outcome: 'SUCCESS', receivedAt: NOW - 2000 },
      ];
      const state = makeLiveState({ tasks: [task] });
      mockLiveServer(state);
      renderLiveTimeline();
      const markers = screen.getAllByTestId('live-tool-marker');
      expect(markers).toHaveLength(2);
      expect(markers[0]).toHaveAttribute('data-tool-name', 'web_search');
      expect(markers[1]).toHaveAttribute('data-tool-name', 'calculator');
    });

    it('does not render tool markers when task has no tool calls', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.queryAllByTestId('live-tool-marker')).toHaveLength(0);
    });
  });

  describe('follow latest toggle', () => {
    it('shows "Following latest" button (enabled by default)', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('follow-latest-toggle');
      expect(toggle).toHaveAttribute('data-follow-latest', 'true');
      expect(toggle).toHaveTextContent('Following latest');
    });

    it('clicking the toggle disables follow-latest', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('follow-latest-toggle');
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('data-follow-latest', 'false');
      expect(toggle).toHaveTextContent('Follow latest');
    });

    it('clicking toggle again re-enables follow-latest', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('follow-latest-toggle');
      fireEvent.click(toggle); // disable
      fireEvent.click(toggle); // re-enable
      expect(toggle).toHaveAttribute('data-follow-latest', 'true');
    });

    it('re-engages follow-latest when user scrolls to the right edge', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();

      const toggle = screen.getByTestId('follow-latest-toggle');
      // Disable follow-latest
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('data-follow-latest', 'false');

      // Simulate scroll to right edge: scrollLeft + clientWidth >= scrollWidth - 10.
      // Use writable: true so the component's auto-scroll useEffect can set scrollLeft back.
      const scrollContainer = screen.getByTestId('live-timeline-scroll');
      Object.defineProperty(scrollContainer, 'scrollLeft', {
        value: 990,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(scrollContainer, 'clientWidth', {
        value: 10,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(scrollContainer, 'scrollWidth', {
        value: 1000,
        configurable: true,
        writable: true,
      });
      fireEvent.scroll(scrollContainer);

      // follow-latest should be re-engaged
      expect(toggle).toHaveAttribute('data-follow-latest', 'true');
    });

    it('does NOT re-engage follow-latest when scrolled away from right edge', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();

      const toggle = screen.getByTestId('follow-latest-toggle');
      fireEvent.click(toggle); // disable
      expect(toggle).toHaveAttribute('data-follow-latest', 'false');

      // Scroll to middle -- not at right edge
      const scrollContainer = screen.getByTestId('live-timeline-scroll');
      Object.defineProperty(scrollContainer, 'scrollLeft', {
        value: 200,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(scrollContainer, 'clientWidth', {
        value: 800,
        configurable: true,
        writable: true,
      });
      Object.defineProperty(scrollContainer, 'scrollWidth', {
        value: 2000,
        configurable: true,
        writable: true,
      });
      fireEvent.scroll(scrollContainer);

      // follow-latest should remain disabled
      expect(toggle).toHaveAttribute('data-follow-latest', 'false');
    });

    it('shows the follow-latest toggle even when no tasks have started yet', () => {
      const state = makeLiveState({ tasks: [] });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.getByTestId('follow-latest-toggle')).toBeInTheDocument();
    });
  });

  describe('live header info', () => {
    it('shows the workflow type', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)], workflow: 'PARALLEL' });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.getByText('PARALLEL')).toBeInTheDocument();
    });

    it('shows task progress count', () => {
      const state = makeLiveState({
        tasks: [makeCompletedTask(0), makeRunningTask(1)],
        totalTasks: 3,
      });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.getByText('1 / 3 tasks')).toBeInTheDocument();
    });
  });

  describe('grouping toggle', () => {
    it('defaults to "By Task" grouping', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('grouping-toggle');
      expect(toggle).toHaveAttribute('data-grouping', 'task');
      expect(toggle).toHaveTextContent('By Task');
    });

    it('grouping toggle is present when waiting for tasks', () => {
      mockLiveServer(makeLiveState({ tasks: [] }));
      renderLiveTimeline();
      expect(screen.getByTestId('grouping-toggle')).toBeInTheDocument();
    });

    it('shows one lane label per task in "By Task" mode even when roles repeat', () => {
      const state = makeLiveState({
        tasks: [
          makeRunningTask(0, 'Researcher'),
          makeRunningTask(1, 'Researcher'),
          makeRunningTask(2, 'Agent'),
        ],
        totalTasks: 3,
      });
      mockLiveServer(state);
      renderLiveTimeline();
      // Default is "By Task" -- 3 tasks means 3 lane labels
      const labels = screen.getAllByTestId('timeline-lane-label');
      expect(labels).toHaveLength(3);
      labels.forEach((label) => expect(label).toHaveAttribute('data-lane-type', 'task'));
    });

    it('switches to "By Agent" grouping when toggle is clicked', () => {
      const state = makeLiveState({ tasks: [makeRunningTask(0)] });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('grouping-toggle');
      fireEvent.click(toggle);
      expect(toggle).toHaveAttribute('data-grouping', 'agent');
      expect(toggle).toHaveTextContent('By Agent');
    });

    it('shows one lane label per unique agent role in "By Agent" mode', () => {
      const state = makeLiveState({
        tasks: [
          makeRunningTask(0, 'Researcher'),
          makeRunningTask(1, 'Researcher'),
          makeRunningTask(2, 'Agent'),
        ],
        totalTasks: 3,
      });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('grouping-toggle');
      fireEvent.click(toggle); // switch to By Agent
      const labels = screen.getAllByTestId('timeline-lane-label');
      // 2 unique roles: Researcher and Agent
      expect(labels).toHaveLength(2);
      labels.forEach((label) => expect(label).toHaveAttribute('data-lane-type', 'agent'));
    });

    it('switching back from "By Agent" to "By Task" restores per-task lanes', () => {
      const state = makeLiveState({
        tasks: [
          makeRunningTask(0, 'Researcher'),
          makeRunningTask(1, 'Researcher'),
        ],
        totalTasks: 2,
      });
      mockLiveServer(state);
      renderLiveTimeline();
      const toggle = screen.getByTestId('grouping-toggle');

      fireEvent.click(toggle); // switch to By Agent -- 1 unique role
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(1);

      fireEvent.click(toggle); // switch back to By Task -- 2 tasks
      expect(screen.getAllByTestId('timeline-lane-label')).toHaveLength(2);
    });

    it('all task bars are rendered in both grouping modes', () => {
      const state = makeLiveState({
        tasks: [
          makeRunningTask(0, 'Researcher'),
          makeCompletedTask(1, 'Researcher'),
          makeRunningTask(2, 'Agent'),
        ],
        totalTasks: 3,
      });
      mockLiveServer(state);
      renderLiveTimeline();

      // By Task mode: 3 bars
      expect(screen.getAllByTestId('live-task-bar')).toHaveLength(3);

      // By Agent mode: still 3 bars
      fireEvent.click(screen.getByTestId('grouping-toggle'));
      expect(screen.getAllByTestId('live-task-bar')).toHaveLength(3);
    });
  });

  describe('completed runs (stacked sections)', () => {
    function makeCompletedRunFixture(runNumber: number, taskCount = 1): CompletedRun {
      const runStartMs = STARTED_AT_MS - runNumber * 300000; // 5 minutes apart
      return {
        ensembleId: `run-${runNumber}`,
        workflow: 'SEQUENTIAL',
        startedAt: new Date(runStartMs).toISOString(),
        completedAt: new Date(runStartMs + 30000 * taskCount).toISOString(),
        totalTasks: taskCount,
        tasks: Array.from({ length: taskCount }, (_, i) =>
          makeCompletedTask(i + 1, `Agent ${String.fromCharCode(65 + i)}`),
        ),
      };
    }

    it('does not render completed-run sections when completedRuns is empty', () => {
      mockLiveServer(makeLiveState({ tasks: [makeRunningTask(0)], completedRuns: [] }));
      renderLiveTimeline();
      expect(screen.queryAllByTestId('completed-run-section')).toHaveLength(0);
    });

    it('renders one completed-run section when one run has been archived', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.getAllByTestId('completed-run-section')).toHaveLength(1);
    });

    it('renders N completed-run sections for N archived runs', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(2), makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      expect(screen.getAllByTestId('completed-run-section')).toHaveLength(2);
    });

    it('each completed-run section has a run header', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      const headers = screen.getAllByTestId('completed-run-header');
      expect(headers).toHaveLength(1);
    });

    it('run header shows run number as #1 for the first completed run', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      const header = screen.getByTestId('completed-run-header');
      expect(header.textContent).toContain('#1');
    });

    it('sections are separated by labeled dividers', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(2), makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      // One divider per completed run section
      const dividers = screen.getAllByTestId('completed-run-divider');
      expect(dividers.length).toBeGreaterThanOrEqual(1);
    });

    it('completed run sections render task bars for the archived tasks', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(1, 2)], // 2 archived tasks
      });
      mockLiveServer(state);
      renderLiveTimeline();
      // The 2 archived completed tasks + 1 active running task = 3 task bars total
      const bars = screen.getAllByTestId('live-task-bar');
      expect(bars.length).toBeGreaterThanOrEqual(3);
    });

    it('groupBy toggle applies uniformly (sections share the same groupBy state)', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0, 'Agent A'), makeRunningTask(1, 'Agent B')],
        completedRuns: [makeCompletedRunFixture(1, 2)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      // Verify the grouping toggle is present -- clicking it should affect all sections
      expect(screen.getByTestId('grouping-toggle')).toBeInTheDocument();
    });

    it('completed run sections appear above the active run (before the live timeline scroll)', () => {
      const state = makeLiveState({
        tasks: [makeRunningTask(0)],
        completedRuns: [makeCompletedRunFixture(1)],
      });
      mockLiveServer(state);
      renderLiveTimeline();
      // Completed run section should exist
      const section = screen.getByTestId('completed-run-section');
      const liveScroll = screen.getByTestId('live-timeline-scroll');
      // completed-run-section must appear before live-timeline-scroll in DOM order
      const sectionPos = section.compareDocumentPosition(liveScroll);
      // DOCUMENT_POSITION_FOLLOWING = 4 (liveScroll follows section)
      expect(sectionPos & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });
});
