/**
 * Unit tests for ToolCallDetailPanel (IO-004).
 */


import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ToolCallDetailPanel from '../components/live/ToolCallDetailPanel.js';
import type { LiveToolCall } from '../types/live.js';

// ========================
// Fixtures
// ========================

function makeToolCall(overrides: Partial<LiveToolCall> = {}): LiveToolCall {
  return {
    toolName: 'web_search',
    durationMs: 1200,
    outcome: 'SUCCESS',
    receivedAt: Date.now(),
    taskIndex: 1,
    toolArguments: '{"query":"AI trends"}',
    toolResult: 'Top 10 AI trends for 2026...',
    structuredResult: null,
    ...overrides,
  };
}

// ========================
// Tests
// ========================

describe('ToolCallDetailPanel', () => {
  describe('empty state', () => {
    it('shows "No tool calls yet" when toolCalls is empty', () => {
      render(<ToolCallDetailPanel toolCalls={[]} />);
      expect(screen.getByTestId('tool-detail-empty')).toBeInTheDocument();
      expect(screen.getByText('No tool calls yet')).toBeInTheDocument();
    });
  });

  describe('card rendering', () => {
    it('renders one card per tool call', () => {
      const calls = [makeToolCall({ toolName: 'web_search' }), makeToolCall({ toolName: 'calculator' })];
      render(<ToolCallDetailPanel toolCalls={calls} />);
      const cards = screen.getAllByTestId('tool-call-card');
      expect(cards).toHaveLength(2);
    });

    it('displays tool name in each card header', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ toolName: 'web_search' })]} />);
      expect(screen.getByText('web_search')).toBeInTheDocument();
    });

    it('displays SUCCESS outcome badge with correct data attribute', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ outcome: 'SUCCESS' })]} />);
      const badge = screen.getByTestId('outcome-badge');
      expect(badge).toHaveAttribute('data-outcome', 'SUCCESS');
      expect(badge).toHaveTextContent('SUCCESS');
    });

    it('displays FAILURE outcome badge with correct data attribute', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ outcome: 'FAILURE' })]} />);
      const badge = screen.getByTestId('outcome-badge');
      expect(badge).toHaveAttribute('data-outcome', 'FAILURE');
      expect(badge).toHaveTextContent('FAILURE');
    });

    it('displays UNKNOWN outcome badge for unknown outcomes', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ outcome: 'UNKNOWN' })]} />);
      const badge = screen.getByTestId('outcome-badge');
      expect(badge).toHaveAttribute('data-outcome', 'UNKNOWN');
    });

    it('displays duration in ms for short durations', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ durationMs: 42 })]} />);
      expect(screen.getByText('42ms')).toBeInTheDocument();
    });

    it('displays duration in seconds for long durations', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ durationMs: 2500 })]} />);
      expect(screen.getByText('2.5s')).toBeInTheDocument();
    });

    it('displays task index badge when taskIndex > 0', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ taskIndex: 3 })]} />);
      expect(screen.getByText('Task 3')).toBeInTheDocument();
    });

    it('does not display task index badge when taskIndex is 0', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ taskIndex: 0 })]} />);
      expect(screen.queryByText(/Task 0/)).toBeNull();
    });

    it('shows call count in header', () => {
      const calls = [makeToolCall(), makeToolCall()];
      render(<ToolCallDetailPanel toolCalls={calls} />);
      expect(screen.getByText('2 calls')).toBeInTheDocument();
    });

    it('shows singular "call" for single tool call', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall()]} />);
      expect(screen.getByText('1 call')).toBeInTheDocument();
    });

    it('shows agent role when provided', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall()]} agentRole="Researcher" />);
      expect(screen.getByText('Researcher')).toBeInTheDocument();
    });
  });

  describe('expand/collapse', () => {
    it('cards are collapsed by default (no body visible)', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall()]} />);
      expect(screen.queryByTestId('tool-call-card-body')).toBeNull();
    });

    it('clicking card header expands it to show body', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall()]} />);
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.getByTestId('tool-call-card-body')).toBeInTheDocument();
    });

    it('clicking an expanded card header collapses it', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall()]} />);
      const header = screen.getByTestId('tool-call-card-header');
      fireEvent.click(header); // expand
      expect(screen.getByTestId('tool-call-card-body')).toBeInTheDocument();
      fireEvent.click(header); // collapse
      expect(screen.queryByTestId('tool-call-card-body')).toBeNull();
    });

    it('Expand All button expands all cards', () => {
      const calls = [makeToolCall({ toolName: 'a' }), makeToolCall({ toolName: 'b' })];
      render(<ToolCallDetailPanel toolCalls={calls} />);
      fireEvent.click(screen.getByTestId('tool-detail-expand-all'));
      expect(screen.getAllByTestId('tool-call-card-body')).toHaveLength(2);
    });

    it('Collapse All button collapses all cards', () => {
      const calls = [makeToolCall({ toolName: 'a' }), makeToolCall({ toolName: 'b' })];
      render(<ToolCallDetailPanel toolCalls={calls} />);
      fireEvent.click(screen.getByTestId('tool-detail-expand-all'));
      expect(screen.getAllByTestId('tool-call-card-body')).toHaveLength(2);
      fireEvent.click(screen.getByTestId('tool-detail-collapse-all'));
      expect(screen.queryAllByTestId('tool-call-card-body')).toHaveLength(0);
    });
  });

  describe('expanded body content', () => {
    it('renders arguments section with formatted JSON', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ toolArguments: '{"x":1}' })]} />);
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      const argsSection = screen.getByTestId('tool-call-arguments');
      expect(argsSection).toBeInTheDocument();
      // Should pretty-print the JSON
      expect(argsSection.textContent).toContain('"x": 1');
    });

    it('renders result section', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ toolResult: 'some result text' })]} />);
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.getByTestId('tool-call-result')).toBeInTheDocument();
      expect(screen.getByText('some result text')).toBeInTheDocument();
    });

    it('renders structured result as JSON', () => {
      render(
        <ToolCallDetailPanel
          toolCalls={[makeToolCall({ structuredResult: { key: 'value' } })]}
        />,
      );
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.getByTestId('tool-call-structured-result')).toBeInTheDocument();
    });

    it('handles malformed JSON gracefully (shows raw text)', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ toolArguments: 'not-json{' })]} />);
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.getByText('not-json{')).toBeInTheDocument();
    });

    it('shows "No I/O data available" when all fields are null', () => {
      render(
        <ToolCallDetailPanel
          toolCalls={[makeToolCall({ toolArguments: null, toolResult: null, structuredResult: null })]}
        />,
      );
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.getByText('No I/O data available')).toBeInTheDocument();
    });

    it('does not render arguments section when toolArguments is null', () => {
      render(<ToolCallDetailPanel toolCalls={[makeToolCall({ toolArguments: null, toolResult: 'result' })]} />);
      fireEvent.click(screen.getByTestId('tool-call-card-header'));
      expect(screen.queryByTestId('tool-call-arguments')).toBeNull();
      expect(screen.getByTestId('tool-call-result')).toBeInTheDocument();
    });
  });
});