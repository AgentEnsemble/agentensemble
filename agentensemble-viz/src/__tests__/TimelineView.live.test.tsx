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
import type { LiveState, LiveTask } from '../types/live.js';
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
    sendMessage: vi.fn(),
    sendDecision: vi.fn(),
  } as LiveServerContextValue);
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
});
