/**
 * Unit tests for AgentConversationPanel (IO-005).
 */

import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AgentConversationPanel from '../components/live/AgentConversationPanel.js';
import type { LiveConversation, LiveIteration, LiveTaskInput } from '../types/live.js';

// ========================
// Fixtures
// ========================

function makeTaskInput(overrides: Partial<LiveTaskInput> = {}): LiveTaskInput {
  return {
    taskIndex: 1,
    taskDescription: 'Research AI trends',
    expectedOutput: 'A comprehensive report',
    agentRole: 'Researcher',
    agentGoal: 'Find the latest developments in AI',
    agentBackground: 'Senior research analyst',
    toolNames: ['web_search', 'calculator'],
    assembledContext: 'Previous findings: AI is evolving rapidly...',
    ...overrides,
  };
}

function makeIteration(overrides: Partial<LiveIteration> = {}): LiveIteration {
  return {
    iterationIndex: 0,
    inputMessages: [
      { role: 'system', content: 'You are a researcher.', timestamp: Date.now() },
      { role: 'user', content: 'Research AI trends', timestamp: Date.now() },
    ],
    pending: false,
    responseType: 'FINAL_ANSWER',
    responseText: 'AI is evolving rapidly.',
    inputTokens: 500,
    outputTokens: 200,
    latencyMs: 1500,
    ...overrides,
  };
}

function makeConversation(overrides: Partial<LiveConversation> = {}): LiveConversation {
  return {
    agentRole: 'Researcher',
    taskDescription: 'Research AI trends',
    iterationIndex: 0,
    messages: [
      { role: 'system', content: 'You are a researcher.', timestamp: Date.now() },
      { role: 'user', content: 'Research AI trends', timestamp: Date.now() },
      { role: 'assistant', content: 'AI is evolving rapidly.', timestamp: Date.now() },
    ],
    isThinking: false,
    iterations: [makeIteration()],
    ...overrides,
  };
}

// ========================
// Tests
// ========================

describe('AgentConversationPanel', () => {
  describe('empty state', () => {
    it('shows empty message when conversation is null', () => {
      render(<AgentConversationPanel conversation={null} taskInput={null} agentRole="Agent" />);
      expect(screen.getByTestId('agent-conversation-empty')).toBeInTheDocument();
      expect(screen.getByText('Waiting for conversation...')).toBeInTheDocument();
    });

    it('shows empty message when conversation has no iterations and no taskInput', () => {
      const conv = makeConversation({ iterations: [], messages: [] });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Agent" />);
      expect(screen.getByTestId('agent-conversation-empty')).toBeInTheDocument();
    });
  });

  describe('task input header', () => {
    it('renders task input header when taskInput is provided', () => {
      const conv = makeConversation();
      render(<AgentConversationPanel conversation={conv} taskInput={makeTaskInput()} agentRole="Researcher" />);
      expect(screen.getByTestId('task-input-header')).toBeInTheDocument();
    });

    it('displays agent role in header', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={makeTaskInput()} agentRole="Researcher" />);
      expect(screen.getByText('Researcher')).toBeInTheDocument();
    });

    it('displays agent goal', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={makeTaskInput()} agentRole="Researcher" />);
      expect(screen.getByText('Find the latest developments in AI')).toBeInTheDocument();
    });

    it('displays tool name pills', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={makeTaskInput()} agentRole="Researcher" />);
      const pills = screen.getAllByTestId('tool-name-pill');
      expect(pills).toHaveLength(2);
      expect(pills[0]).toHaveTextContent('web_search');
      expect(pills[1]).toHaveTextContent('calculator');
    });

    it('shows collapsible assembled context', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={makeTaskInput()} agentRole="Researcher" />);
      const toggle = screen.getByTestId('context-toggle');
      expect(toggle).toBeInTheDocument();
      // Context should be collapsed by default
      expect(screen.queryByTestId('assembled-context')).toBeNull();
      // Click to expand
      fireEvent.click(toggle);
      expect(screen.getByTestId('assembled-context')).toBeInTheDocument();
      expect(screen.getByTestId('assembled-context')).toHaveTextContent('Previous findings');
    });

    it('does not render header when taskInput is null', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.queryByTestId('task-input-header')).toBeNull();
    });
  });

  describe('iteration cards', () => {
    it('renders one iteration card per iteration', () => {
      const conv = makeConversation({
        iterations: [makeIteration({ iterationIndex: 0 }), makeIteration({ iterationIndex: 1 })],
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      const cards = screen.getAllByTestId('iteration-card');
      expect(cards).toHaveLength(2);
    });

    it('displays iteration number (1-based) in card header', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByText('Iteration 1')).toBeInTheDocument();
    });

    it('displays token badges when available', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByText('500 in')).toBeInTheDocument();
      expect(screen.getByText('200 out')).toBeInTheDocument();
    });

    it('displays latency badge when available', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByText('1500ms')).toBeInTheDocument();
    });

    it('shows "pending" indicator for pending iterations', () => {
      const conv = makeConversation({
        iterations: [makeIteration({ pending: true, responseType: undefined, responseText: undefined })],
        isThinking: true,
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      const card = screen.getByTestId('iteration-card');
      expect(card).toHaveAttribute('data-pending', 'true');
      expect(screen.getByText('pending')).toBeInTheDocument();
    });

    it('renders final answer response text', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByText('Final Answer')).toBeInTheDocument();
      expect(screen.getByText('AI is evolving rapidly.')).toBeInTheDocument();
    });

    it('renders tool call requests for TOOL_CALLS response type', () => {
      const conv = makeConversation({
        iterations: [
          makeIteration({
            responseType: 'TOOL_CALLS',
            responseText: null,
            toolRequests: [
              { name: 'web_search', arguments: '{"query":"AI"}' },
              { name: 'calculator', arguments: '{"expr":"2+2"}' },
            ],
          }),
        ],
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      const requests = screen.getAllByTestId('tool-request-card');
      expect(requests).toHaveLength(2);
      expect(screen.getByText('web_search')).toBeInTheDocument();
      expect(screen.getByText('calculator')).toBeInTheDocument();
    });

    it('renders input messages within iteration card', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByText('System')).toBeInTheDocument();
      expect(screen.getByText('User')).toBeInTheDocument();
    });
  });

  describe('thinking indicator', () => {
    it('shows thinking indicator when isThinking is true', () => {
      const conv = makeConversation({ isThinking: true });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByTestId('thinking-indicator')).toBeInTheDocument();
      expect(screen.getByText('Thinking...')).toBeInTheDocument();
    });

    it('does not show thinking indicator when isThinking is false', () => {
      const conv = makeConversation({ isThinking: false });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      expect(screen.queryByTestId('thinking-indicator')).toBeNull();
    });
  });

  describe('iteration navigation', () => {
    it('shows iteration nav sidebar when 2+ iterations exist', () => {
      const conv = makeConversation({
        iterations: [makeIteration({ iterationIndex: 0 }), makeIteration({ iterationIndex: 1 })],
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      expect(screen.getByTestId('iteration-nav')).toBeInTheDocument();
      const buttons = screen.getAllByTestId('iteration-nav-button');
      expect(buttons).toHaveLength(2);
    });

    it('does not show iteration nav for single iteration', () => {
      render(<AgentConversationPanel conversation={makeConversation()} taskInput={null} agentRole="Researcher" />);
      expect(screen.queryByTestId('iteration-nav')).toBeNull();
    });

    it('nav buttons display 1-based iteration numbers', () => {
      const conv = makeConversation({
        iterations: [makeIteration({ iterationIndex: 0 }), makeIteration({ iterationIndex: 1 })],
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      const buttons = screen.getAllByTestId('iteration-nav-button');
      expect(buttons[0]).toHaveTextContent('1');
      expect(buttons[1]).toHaveTextContent('2');
    });
  });

  describe('late-join hydration', () => {
    it('renders iterations populated from snapshot hydration', () => {
      // Simulate what happens when liveReducer hydrates from recentIterations
      const conv = makeConversation({
        iterations: [
          makeIteration({ iterationIndex: 0, responseText: 'First answer' }),
          makeIteration({ iterationIndex: 1, responseText: 'Second answer' }),
        ],
      });
      render(<AgentConversationPanel conversation={conv} taskInput={null} agentRole="Researcher" />);
      const cards = screen.getAllByTestId('iteration-card');
      expect(cards).toHaveLength(2);
      expect(screen.getByText('First answer')).toBeInTheDocument();
      expect(screen.getByText('Second answer')).toBeInTheDocument();
    });
  });
});