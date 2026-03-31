/**
 * ToolCallDetailPanel (IO-004): expandable cards showing tool call I/O.
 *
 * Each tool call is rendered as a collapsible card with:
 * - Header: tool name, outcome badge (green/red), duration badge, task index
 * - Expanded body: JSON-highlighted arguments + result, copy button
 *
 * Includes "Expand All" / "Collapse All" controls and an empty-state message.
 */

import { useState, useCallback, memo } from 'react';
import type { LiveToolCall } from '../../types/live.js';

// ========================
// Props
// ========================

interface ToolCallDetailPanelProps {
  toolCalls: LiveToolCall[];
  agentRole?: string;
}

// ========================
// Main component
// ========================

export default function ToolCallDetailPanel({ toolCalls, agentRole }: ToolCallDetailPanelProps) {
  const [expandedSet, setExpandedSet] = useState<Set<number>>(new Set());

  const toggleCard = useCallback((index: number) => {
    setExpandedSet((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }, []);

  const expandAll = useCallback(() => {
    setExpandedSet(new Set(toolCalls.map((_, i) => i)));
  }, [toolCalls]);

  const collapseAll = useCallback(() => {
    setExpandedSet(new Set());
  }, []);

  if (toolCalls.length === 0) {
    return (
      <div
        className="flex h-full items-center justify-center p-4"
        data-testid="tool-detail-empty"
      >
        <span className="text-xs text-gray-400 dark:text-gray-500">
          No tool calls yet
        </span>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col" data-testid="tool-detail-panel">
      {/* Controls */}
      <div className="flex shrink-0 items-center gap-2 border-b border-gray-200 px-3 py-1.5 dark:border-gray-700">
        {agentRole && (
          <span className="text-[10px] font-medium text-gray-600 dark:text-gray-400">
            {agentRole}
          </span>
        )}
        <span className="text-[10px] text-gray-400 dark:text-gray-500">
          {toolCalls.length} call{toolCalls.length !== 1 ? 's' : ''}
        </span>
        <div className="ml-auto flex gap-1">
          <button
            onClick={expandAll}
            className="rounded px-1.5 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
            data-testid="tool-detail-expand-all"
          >
            Expand All
          </button>
          <button
            onClick={collapseAll}
            className="rounded px-1.5 py-0.5 text-[10px] text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800"
            data-testid="tool-detail-collapse-all"
          >
            Collapse All
          </button>
        </div>
      </div>

      {/* Cards */}
      <div className="flex-1 space-y-1 overflow-y-auto p-2">
        {toolCalls.map((tc, i) => (
          <ToolCallCard
            key={i}
            toolCall={tc}
            index={i}
            expanded={expandedSet.has(i)}
            onToggle={toggleCard}
          />
        ))}
      </div>
    </div>
  );
}

// ========================
// ToolCallCard
// ========================

interface ToolCallCardProps {
  toolCall: LiveToolCall;
  index: number;
  expanded: boolean;
  onToggle: (index: number) => void;
}

const ToolCallCard = memo(function ToolCallCard({
  toolCall,
  index,
  expanded,
  onToggle,
}: ToolCallCardProps) {
  return (
    <div
      className="rounded border border-gray-200 dark:border-gray-700"
      data-testid="tool-call-card"
      data-tool-name={toolCall.toolName}
    >
      {/* Header row (always visible) */}
      <button
        onClick={() => onToggle(index)}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-left"
        data-testid="tool-call-card-header"
      >
        {/* Expand/collapse chevron */}
        <svg
          className={`h-3 w-3 shrink-0 text-gray-400 transition-transform ${expanded ? 'rotate-90' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
        </svg>

        {/* Tool name */}
        <span className="text-[11px] font-medium text-gray-700 dark:text-gray-300">
          {toolCall.toolName}
        </span>

        {/* Outcome badge */}
        <OutcomeBadge outcome={toolCall.outcome} />

        {/* Duration badge */}
        <span className="rounded bg-gray-100 px-1 py-0.5 text-[9px] text-gray-500 dark:bg-gray-800 dark:text-gray-400">
          {formatDuration(toolCall.durationMs)}
        </span>

        {/* Task index badge (only if non-zero) */}
        {toolCall.taskIndex > 0 && (
          <span className="rounded bg-blue-50 px-1 py-0.5 text-[9px] text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
            Task {toolCall.taskIndex}
          </span>
        )}
      </button>

      {/* Expanded body */}
      {expanded && (
        <div
          className="border-t border-gray-200 px-2 py-2 dark:border-gray-700"
          data-testid="tool-call-card-body"
        >
          {/* Arguments */}
          {toolCall.toolArguments != null && (
            <FormattedSection
              label="Arguments"
              content={toolCall.toolArguments}
              testId="tool-call-arguments"
            />
          )}

          {/* Result */}
          {toolCall.toolResult != null && (
            <FormattedSection
              label="Result"
              content={toolCall.toolResult}
              testId="tool-call-result"
            />
          )}

          {/* Structured result */}
          {toolCall.structuredResult != null && (
            <FormattedSection
              label="Structured Result"
              content={JSON.stringify(toolCall.structuredResult, null, 2)}
              testId="tool-call-structured-result"
            />
          )}

          {/* No data message */}
          {toolCall.toolArguments == null && toolCall.toolResult == null && toolCall.structuredResult == null && (
            <span className="text-[10px] italic text-gray-400 dark:text-gray-500">
              No I/O data available
            </span>
          )}
        </div>
      )}
    </div>
  );
});

// ========================
// Sub-components
// ========================

function OutcomeBadge({ outcome }: { outcome: string }) {
  if (outcome === 'SUCCESS') {
    return (
      <span
        className="rounded bg-green-100 px-1 py-0.5 text-[9px] font-medium text-green-700 dark:bg-green-900/30 dark:text-green-400"
        data-testid="outcome-badge"
        data-outcome="SUCCESS"
      >
        SUCCESS
      </span>
    );
  }
  if (outcome === 'FAILURE') {
    return (
      <span
        className="rounded bg-red-100 px-1 py-0.5 text-[9px] font-medium text-red-700 dark:bg-red-900/30 dark:text-red-400"
        data-testid="outcome-badge"
        data-outcome="FAILURE"
      >
        FAILURE
      </span>
    );
  }
  return (
    <span
      className="rounded bg-gray-100 px-1 py-0.5 text-[9px] font-medium text-gray-500 dark:bg-gray-800 dark:text-gray-400"
      data-testid="outcome-badge"
      data-outcome={outcome}
    >
      {outcome}
    </span>
  );
}

function FormattedSection({
  label,
  content,
  testId,
}: {
  label: string;
  content: string;
  testId: string;
}) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }, [content]);

  const formatted = tryFormatJson(content);

  return (
    <div className="mb-2" data-testid={testId}>
      <div className="mb-0.5 flex items-center gap-1">
        <span className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          {label}
        </span>
        <button
          onClick={handleCopy}
          className="rounded px-1 py-0.5 text-[9px] text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-800"
          data-testid={`${testId}-copy`}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="max-h-40 overflow-auto rounded bg-gray-50 p-1.5 text-[10px] leading-relaxed text-gray-700 dark:bg-gray-800/50 dark:text-gray-300">
        {formatted}
      </pre>
    </div>
  );
}

// ========================
// Helpers
// ========================

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Attempt to parse and pretty-print a JSON string.
 * Returns the original string if parsing fails.
 */
function tryFormatJson(text: string): string {
  try {
    const parsed = JSON.parse(text);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return text;
  }
}