/**
 * AgentConversationPanel (IO-005): full ReAct reasoning chain view.
 *
 * Shows the agent conversation structured by iteration, with:
 * - Header: task input summary (agent role, goal, tools, collapsible context)
 * - Iteration cards: input messages, LLM response, tool requests, token/latency badges
 * - Navigation: clickable iteration index sidebar
 * - "Thinking..." indicator for pending iterations
 * - Late-join hydration support (iterations populated from snapshots)
 */

import { useEffect, useRef, useState, useCallback, memo } from 'react';
import type {
  LiveConversation,
  LiveConversationMessage,
  LiveIteration,
  LiveTaskInput,
} from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

// ========================
// Props
// ========================

interface AgentConversationPanelProps {
  conversation: LiveConversation | null;
  taskInput: LiveTaskInput | null | undefined;
  agentRole: string;
}

// ========================
// Main component
// ========================

export default function AgentConversationPanel({
  conversation,
  taskInput,
  agentRole,
}: AgentConversationPanelProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [selectedIteration, setSelectedIteration] = useState<number | null>(null);

  const iterations = conversation?.iterations ?? [];
  const color = getAgentColor(agentRole);

  // Auto-scroll to bottom when new iterations arrive
  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [iterations.length, autoScroll]);

  const handleScroll = useCallback(() => {
    if (!scrollRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 40;
    setAutoScroll(isAtBottom);
  }, []);

  const jumpToIteration = useCallback((idx: number) => {
    setSelectedIteration(idx);
    const element = document.getElementById(`iteration-card-${idx}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, []);

  // Empty state
  if (!conversation || (iterations.length === 0 && !taskInput)) {
    return (
      <div
        className="flex h-full items-center justify-center p-4"
        data-testid="agent-conversation-empty"
      >
        <span className="text-xs text-gray-400 dark:text-gray-500">
          Waiting for conversation...
        </span>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col" data-testid="agent-conversation-panel">
      {/* Task input header */}
      {taskInput && <TaskInputHeader taskInput={taskInput} color={color} />}

      {/* Main content area with iteration nav */}
      <div className="flex flex-1 overflow-hidden">
        {/* Iteration navigation sidebar (only when 2+ iterations) */}
        {iterations.length >= 2 && (
          <div
            className="flex w-8 shrink-0 flex-col items-center gap-0.5 overflow-y-auto border-r border-gray-200 py-2 dark:border-gray-700"
            data-testid="iteration-nav"
          >
            {iterations.map((iter) => (
              <button
                key={iter.iterationIndex}
                onClick={() => jumpToIteration(iter.iterationIndex)}
                className={[
                  'flex h-5 w-5 items-center justify-center rounded text-[9px] font-medium transition-colors',
                  selectedIteration === iter.iterationIndex
                    ? 'bg-blue-500 text-white'
                    : iter.pending
                      ? 'animate-pulse bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-gray-700',
                ].join(' ')}
                data-testid="iteration-nav-button"
                data-iteration-index={iter.iterationIndex}
              >
                {iter.iterationIndex + 1}
              </button>
            ))}
          </div>
        )}

        {/* Iteration cards */}
        <div
          ref={scrollRef}
          onScroll={handleScroll}
          className="flex-1 space-y-2 overflow-y-auto p-2"
        >
          {iterations.map((iter) => (
            <IterationCard
              key={iter.iterationIndex}
              iteration={iter}
              isSelected={selectedIteration === iter.iterationIndex}
            />
          ))}

          {/* Fallback: show flat messages if no iterations exist yet but conversation has messages */}
          {iterations.length === 0 && conversation.messages.length > 0 && (
            <div className="space-y-1">
              {conversation.messages.map((msg, i) => (
                <FlatMessage key={i} message={msg} />
              ))}
            </div>
          )}

          {/* Thinking indicator */}
          {conversation.isThinking && (
            <div
              className="flex items-center gap-1.5 px-2 py-1"
              data-testid="thinking-indicator"
            >
              <div className="flex gap-0.5">
                <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:0ms]" />
                <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:150ms]" />
                <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:300ms]" />
              </div>
              <span className="text-[10px] text-blue-500">Thinking...</span>
            </div>
          )}
        </div>
      </div>

      {/* Scroll to bottom button */}
      {!autoScroll && (
        <button
          onClick={() => {
            setAutoScroll(true);
            if (scrollRef.current) {
              scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
            }
          }}
          className="shrink-0 border-t border-gray-200 bg-blue-50 px-3 py-1 text-center text-[10px] text-blue-600 hover:bg-blue-100 dark:border-gray-700 dark:bg-blue-900/20 dark:text-blue-400 dark:hover:bg-blue-900/30"
        >
          Scroll to bottom
        </button>
      )}
    </div>
  );
}

// ========================
// TaskInputHeader
// ========================

interface TaskInputHeaderProps {
  taskInput: LiveTaskInput;
  color: { bg: string; text: string; border: string };
}

const TaskInputHeader = memo(function TaskInputHeader({
  taskInput,
  color,
}: TaskInputHeaderProps) {
  const [contextExpanded, setContextExpanded] = useState(false);

  return (
    <div
      className="shrink-0 border-b border-gray-200 p-2 dark:border-gray-700"
      data-testid="task-input-header"
    >
      {/* Agent role + goal */}
      <div className="mb-1 flex items-center gap-1.5">
        <div
          className="h-2 w-2 rounded-full"
          style={{ backgroundColor: color.bg }}
        />
        <span className="text-[11px] font-semibold text-gray-700 dark:text-gray-300">
          {taskInput.agentRole}
        </span>
      </div>

      {taskInput.agentGoal && (
        <div className="mb-1 text-[10px] text-gray-600 dark:text-gray-400">
          {taskInput.agentGoal}
        </div>
      )}

      {/* Task description */}
      <div className="mb-1 text-[10px] text-gray-500 dark:text-gray-500">
        Task: {taskInput.taskDescription}
      </div>

      {/* Expected output */}
      {taskInput.expectedOutput && (
        <div className="mb-1 text-[10px] text-gray-500 dark:text-gray-500">
          Expected: {taskInput.expectedOutput}
        </div>
      )}

      {/* Tool pills */}
      {taskInput.toolNames.length > 0 && (
        <div className="mb-1 flex flex-wrap gap-1">
          {taskInput.toolNames.map((name) => (
            <span
              key={name}
              className="rounded bg-purple-100 px-1.5 py-0.5 text-[9px] font-medium text-purple-700 dark:bg-purple-900/30 dark:text-purple-400"
              data-testid="tool-name-pill"
            >
              {name}
            </span>
          ))}
        </div>
      )}

      {/* Assembled context (collapsible) */}
      {taskInput.assembledContext && (
        <div>
          <button
            onClick={() => setContextExpanded(!contextExpanded)}
            className="flex items-center gap-1 text-[10px] text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"
            data-testid="context-toggle"
          >
            <svg
              className={`h-3 w-3 transition-transform ${contextExpanded ? 'rotate-90' : ''}`}
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
              strokeWidth={2}
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
            </svg>
            Context ({taskInput.assembledContext.length} chars)
          </button>
          {contextExpanded && (
            <pre
              className="mt-1 max-h-32 overflow-auto rounded bg-gray-50 p-1.5 text-[9px] leading-relaxed text-gray-600 dark:bg-gray-800/50 dark:text-gray-300"
              data-testid="assembled-context"
            >
              {taskInput.assembledContext}
            </pre>
          )}
        </div>
      )}
    </div>
  );
});

// ========================
// IterationCard
// ========================

interface IterationCardProps {
  iteration: LiveIteration;
  isSelected: boolean;
}

const IterationCard = memo(function IterationCard({
  iteration,
  isSelected,
}: IterationCardProps) {
  return (
    <div
      id={`iteration-card-${iteration.iterationIndex}`}
      className={[
        'rounded border',
        isSelected
          ? 'border-blue-400 dark:border-blue-500'
          : 'border-gray-200 dark:border-gray-700',
        iteration.pending
          ? 'border-dashed'
          : '',
      ].join(' ')}
      data-testid="iteration-card"
      data-iteration-index={iteration.iterationIndex}
      data-pending={iteration.pending}
    >
      {/* Card header */}
      <div className="flex items-center gap-1.5 border-b border-gray-200 px-2 py-1 dark:border-gray-700">
        <span className="text-[10px] font-semibold text-gray-600 dark:text-gray-400">
          Iteration {iteration.iterationIndex + 1}
        </span>

        {/* Token badges */}
        {iteration.inputTokens != null && (
          <span className="rounded bg-blue-50 px-1 py-0.5 text-[9px] text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
            {iteration.inputTokens} in
          </span>
        )}
        {iteration.outputTokens != null && (
          <span className="rounded bg-green-50 px-1 py-0.5 text-[9px] text-green-600 dark:bg-green-900/30 dark:text-green-400">
            {iteration.outputTokens} out
          </span>
        )}

        {/* Latency badge */}
        {iteration.latencyMs != null && (
          <span className="rounded bg-gray-100 px-1 py-0.5 text-[9px] text-gray-500 dark:bg-gray-800 dark:text-gray-400">
            {iteration.latencyMs}ms
          </span>
        )}

        {/* Pending indicator */}
        {iteration.pending && (
          <span className="animate-pulse text-[9px] font-medium text-yellow-600 dark:text-yellow-400">
            pending
          </span>
        )}
      </div>

      {/* Input messages */}
      <div className="space-y-1 p-2">
        {iteration.inputMessages.map((msg, i) => (
          <FlatMessage key={`in-${i}`} message={msg} />
        ))}
      </div>

      {/* Response (if completed) */}
      {!iteration.pending && iteration.responseType && (
        <div className="border-t border-gray-200 p-2 dark:border-gray-700">
          {iteration.responseType === 'FINAL_ANSWER' && iteration.responseText && (
            <div className="rounded-lg bg-purple-50 px-3 py-2 dark:bg-purple-900/20">
              <div className="mb-0.5 text-[10px] font-semibold text-purple-600 dark:text-purple-400">
                Final Answer
              </div>
              <pre className="whitespace-pre-wrap text-[10px] leading-relaxed text-gray-700 dark:text-gray-200">
                {iteration.responseText}
              </pre>
            </div>
          )}

          {iteration.responseType === 'TOOL_CALLS' && iteration.toolRequests && (
            <div>
              <div className="mb-1 text-[10px] font-semibold text-purple-600 dark:text-purple-400">
                Tool Calls
              </div>
              <div className="space-y-1">
                {iteration.toolRequests.map((tr, i) => (
                  <div
                    key={i}
                    className="rounded border border-purple-200 bg-purple-50 px-2 py-1 dark:border-purple-800 dark:bg-purple-900/20"
                    data-testid="tool-request-card"
                  >
                    <span className="text-[10px] font-medium text-purple-700 dark:text-purple-300">
                      {tr.name}
                    </span>
                    {tr.arguments && (
                      <pre className="mt-0.5 text-[9px] text-gray-600 dark:text-gray-400">
                        {tr.arguments.length > 200
                          ? tr.arguments.slice(0, 200) + '...'
                          : tr.arguments}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
});

// ========================
// FlatMessage (shared sub-component for individual chat messages)
// ========================

const FlatMessage = memo(function FlatMessage({
  message,
}: {
  message: LiveConversationMessage;
}) {
  const content = message.content ?? '';
  const isLong = content.length > 200 && message.role === 'system';
  // System messages: collapsed by default only when long; short system messages always show.
  const [expanded, setExpanded] = useState(message.role !== 'system' || !isLong);

  const roleConfig: Record<string, { bg: string; label: string }> = {
    system: { bg: 'bg-gray-50 dark:bg-gray-800/50', label: 'System' },
    user: { bg: 'bg-blue-50 dark:bg-blue-900/20', label: 'User' },
    assistant: { bg: 'bg-purple-50 dark:bg-purple-900/20', label: 'Assistant' },
    tool: { bg: 'bg-green-50 dark:bg-green-900/20', label: 'Tool' },
  };

  const config = roleConfig[message.role] ?? roleConfig.user;

  return (
    <div className={`rounded px-2 py-1.5 ${config.bg}`}>
      <div className="mb-0.5 flex items-center gap-1">
        <span className="text-[9px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          {config.label}
        </span>
        {message.role === 'system' && isLong && (
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-[9px] text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            {expanded ? 'collapse' : `${content.length} chars`}
          </button>
        )}
        {message.role === 'tool' && message.toolName && (
          <span className="text-[9px] text-green-600 dark:text-green-400">
            ({message.toolName})
          </span>
        )}
      </div>
      {(message.role !== 'system' || expanded) && content && (
        <pre className="whitespace-pre-wrap text-[10px] leading-relaxed text-gray-700 dark:text-gray-200">
          {isLong && !expanded ? content.slice(0, 200) + '...' : content}
        </pre>
      )}
      {message.toolCalls && message.toolCalls.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">
          {message.toolCalls.map((tc, i) => (
            <span
              key={i}
              className="rounded bg-purple-100 px-1.5 py-0.5 text-[9px] font-medium text-purple-700 dark:bg-purple-800/40 dark:text-purple-300"
            >
              {tc.name}
            </span>
          ))}
        </div>
      )}
    </div>
  );
});