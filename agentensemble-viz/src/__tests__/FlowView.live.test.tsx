/**
 * Unit tests for FlowView live mode.
 *
 * Testing strategy:
 *
 * The full FlowView component requires ReactFlow which needs ResizeObserver and
 * other browser APIs that are awkward to exercise in jsdom. Instead, the live
 * mode behavior is tested at two levels:
 *
 * 1. Pure utility level -- buildSyntheticDagModel and buildLiveStatusMap are covered
 *    in liveDag.test.ts (no DOM needed).
 *
 * 2. TaskNode component level -- The TaskNode component is the visual output of the
 *    live status system. By rendering TaskNode with different liveStatus values we
 *    verify that the correct CSS classes and colors are applied.
 *    @xyflow/react is mocked so Handle components render as null.
 *
 * Together these two levels satisfy the acceptance criteria:
 *   - "node is gray before task_started"    -> buildLiveStatusMap returns undefined -> agent color
 *   - "node turns blue on task_started"     -> liveStatus='running' -> blue header + ae-pulse
 *   - "node turns agent-color on completed" -> liveStatus=undefined -> agentColor.bg (e.g. emerald)
 *   - "node turns red on task_failed"       -> liveStatus='failed' -> red header
 *   - "running node has animation class"    -> ae-pulse class present
 *   - "completed/failed nodes do not"       -> ae-pulse class absent
 *
 * Note on color assertions: jsdom normalises hex colors to rgb() strings.
 * All color assertions therefore use rgb() format via hexToRgb().
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import type { NodeProps } from '@xyflow/react';
import { seedAgentColors, getAgentColor } from '../utils/colors.js';
import type { TaskNodeData } from '../utils/graphLayout.js';
import type { DagTaskNode } from '../types/dag.js';

// Mock @xyflow/react so Handle components render as null without needing a ReactFlow context
vi.mock('@xyflow/react', () => ({
  Handle: () => null,
  Position: { Left: 'left', Right: 'right' },
}));

import TaskNode from '../components/graph/TaskNode.js';
import type { TaskNodeType } from '../components/graph/TaskNode.js';

// ========================
// Utilities
// ========================

/**
 * Convert a 6-digit hex color string to the rgb() format that jsdom returns
 * when reading back from style.backgroundColor.
 */
function hexToRgb(hex: string): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgb(${r}, ${g}, ${b})`;
}

// ========================
// Fixtures
// ========================

// Seed with two agents so the second role gets a visually distinct (non-blue) color.
// Researcher -> index 0 -> blue (#3B82F6)
// Writer     -> index 1 -> emerald (#10B981)
const BLUE_AGENT = 'Researcher';
const EMERALD_AGENT = 'Writer';

function makeDagTask(agentRole: string): DagTaskNode {
  return {
    id: 'task-0',
    description: 'Do research',
    expectedOutput: 'A research report',
    agentRole,
    dependsOn: [],
    parallelGroup: 0,
    onCriticalPath: false,
  };
}

function makeNodeData(
  agentRole: string,
  liveStatus?: 'running' | 'completed' | 'failed',
): TaskNodeData {
  const agentColor = getAgentColor(agentRole);
  return {
    task: makeDagTask(agentRole),
    agentColor,
    isSelected: false,
    liveStatus,
  };
}

/**
 * Build minimal NodeProps for TaskNode.
 * TaskNode only accesses `data` from its props at runtime; the rest of the
 * ReactFlow-required fields are type-only requirements.
 */
function makeNodeProps(data: TaskNodeData): NodeProps<TaskNodeType> {
  return { data } as unknown as NodeProps<TaskNodeType>;
}

// ========================
// Setup
// ========================

beforeEach(() => {
  // Seed both agents so they get consistent palette indices
  seedAgentColors([BLUE_AGENT, EMERALD_AGENT]);
});

// ========================
// TaskNode live status tests
// ========================

describe('TaskNode live status rendering', () => {
  describe('no liveStatus (historical mode or completed node)', () => {
    it('renders the header with the agent color', () => {
      // Use EMERALD_AGENT so the expected color is NOT the live-running blue
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, undefined))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      const agentColor = getAgentColor(EMERALD_AGENT);
      expect(header.style.backgroundColor).toBe(hexToRgb(agentColor.bg));
    });

    it('does not apply ae-pulse animation class on the header', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, undefined))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      expect(header.className).not.toContain('ae-pulse');
    });

    it('does not apply ae-node-pulse animation class on the root element', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, undefined))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.className).not.toContain('ae-node-pulse');
    });

    it('does not set data-live-status attribute', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, undefined))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.dataset.liveStatus).toBeUndefined();
    });
  });

  describe('liveStatus = "running"', () => {
    it('renders the header in the live-running blue (#3B82F6)', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'running'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      // Live override: always blue regardless of agent color
      expect(header.style.backgroundColor).toBe(hexToRgb('#3B82F6'));
    });

    it('applies ae-pulse animation class to the header', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'running'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      expect(header.className).toContain('ae-pulse');
    });

    it('applies ae-node-pulse animation class to the root element', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'running'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.className).toContain('ae-node-pulse');
    });

    it('sets data-live-status="running" on the root element', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'running'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.dataset.liveStatus).toBe('running');
    });
  });

  describe('liveStatus = "completed"', () => {
    it('renders the header with the normal agent color (not the live-override blue)', () => {
      // Use EMERALD_AGENT so we can distinguish from running-blue
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'completed'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      const agentColor = getAgentColor(EMERALD_AGENT);
      expect(header.style.backgroundColor).toBe(hexToRgb(agentColor.bg));
    });

    it('does NOT apply ae-pulse animation class', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'completed'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      expect(header.className).not.toContain('ae-pulse');
    });

    it('does NOT apply ae-node-pulse animation class', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'completed'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.className).not.toContain('ae-node-pulse');
    });

    it('sets data-live-status="completed" on the root element', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'completed'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.dataset.liveStatus).toBe('completed');
    });
  });

  describe('liveStatus = "failed"', () => {
    it('renders the header in the live-failed red (#EF4444)', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'failed'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      expect(header.style.backgroundColor).toBe(hexToRgb('#EF4444'));
    });

    it('does NOT apply ae-pulse animation class (failed nodes do not pulse)', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'failed'))} />,
      );
      const header = container.querySelector('.rounded-t-md') as HTMLElement;
      expect(header.className).not.toContain('ae-pulse');
    });

    it('does NOT apply ae-node-pulse animation class', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'failed'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.className).not.toContain('ae-node-pulse');
    });

    it('sets data-live-status="failed" on the root element', () => {
      const { container } = render(
        <TaskNode {...makeNodeProps(makeNodeData(EMERALD_AGENT, 'failed'))} />,
      );
      const root = container.firstElementChild as HTMLElement;
      expect(root.dataset.liveStatus).toBe('failed');
    });
  });
});
