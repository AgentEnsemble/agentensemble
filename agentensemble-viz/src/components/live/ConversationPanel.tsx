/**
 * ConversationPanel: chat-bubble UI showing the live LLM conversation for a selected agent.
 *
 * Renders system messages (collapsible), user messages, assistant messages
 * (with tool call badges), and tool result messages.
 * Auto-scrolls to bottom as new messages arrive.
 */

import { useEffect, useRef, useState, memo } from 'react';
import type { LiveConversation, LiveConversationMessage } from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

interface ConversationPanelProps {
  conversation: LiveConversation | null;
  agentRole: string;
}

export default function ConversationPanel({ conversation, agentRole }: ConversationPanelProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  // Auto-scroll to bottom when new messages arrive
  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [conversation?.messages.length, autoScroll]);

  // Detect manual scroll to disable auto-scroll
  const handleScroll = () => {
    if (!scrollRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 40;
    setAutoScroll(isAtBottom);
  };

  if (!conversation || conversation.messages.length === 0) {
    return (
      <div className="flex h-full items-center justify-center p-4">
        <span className="text-xs text-gray-400 dark:text-gray-500">
          Waiting for conversation...
        </span>
      </div>
    );
  }

  const color = getAgentColor(agentRole);

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex shrink-0 items-center gap-2 border-b border-gray-200 px-3 py-1.5 dark:border-gray-700">
        <div className="h-2 w-2 rounded-full" style={{ backgroundColor: color.bg }} />
        <span className="text-[10px] font-medium text-gray-600 dark:text-gray-400">
          Iteration {conversation.iterationIndex + 1}
        </span>
        <span className="text-[10px] text-gray-400 dark:text-gray-500">
          {conversation.messages.length} messages
        </span>
        {conversation.isThinking && (
          <span className="animate-[ae-pulse_1.5s_ease-in-out_infinite] text-[10px] font-medium text-blue-500">
            Thinking...
          </span>
        )}
      </div>

      {/* Messages */}
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        className="flex-1 space-y-2 overflow-y-auto p-3"
      >
        {conversation.messages.map((msg, i) => (
          <ConversationMessage key={i} message={msg} />
        ))}

        {/* Thinking indicator */}
        {conversation.isThinking && (
          <div className="flex items-center gap-1.5 px-2 py-1">
            <div className="flex gap-0.5">
              <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:0ms]" />
              <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:150ms]" />
              <span className="inline-block h-1.5 w-1.5 animate-bounce rounded-full bg-blue-400 [animation-delay:300ms]" />
            </div>
          </div>
        )}
      </div>

      {/* Auto-scroll indicator */}
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
// Message components
// ========================

const ConversationMessage = memo(function ConversationMessage({
  message,
}: {
  message: LiveConversationMessage;
}) {
  switch (message.role) {
    case 'system':
      return <SystemMessage message={message} />;
    case 'user':
      return <UserMessage message={message} />;
    case 'assistant':
      return <AssistantMessage message={message} />;
    case 'tool':
      return <ToolMessage message={message} />;
    default:
      return null;
  }
});

function SystemMessage({ message }: { message: LiveConversationMessage }) {
  const [expanded, setExpanded] = useState(false);
  const content = message.content ?? '';
  const isLong = content.length > 200;

  return (
    <div className="rounded border border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-2 py-1 text-left"
      >
        <span className="text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          System
        </span>
        {isLong && (
          <span className="text-[10px] text-gray-400 dark:text-gray-500">
            {expanded ? 'collapse' : `${content.length} chars`}
          </span>
        )}
        <svg
          className={`ml-auto h-3 w-3 text-gray-400 transition-transform ${expanded ? 'rotate-180' : ''}`}
          fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {(expanded || !isLong) && (
        <div className="border-t border-gray-200 px-2 py-1.5 dark:border-gray-700">
          <pre className="whitespace-pre-wrap text-[10px] leading-relaxed text-gray-600 dark:text-gray-300">
            {content}
          </pre>
        </div>
      )}
    </div>
  );
}

function UserMessage({ message }: { message: LiveConversationMessage }) {
  return (
    <div className="rounded-lg bg-blue-50 px-3 py-2 dark:bg-blue-900/20">
      <div className="mb-0.5 text-[10px] font-semibold text-blue-600 dark:text-blue-400">
        User
      </div>
      <MessageContent content={message.content} />
    </div>
  );
}

function AssistantMessage({ message }: { message: LiveConversationMessage }) {
  const hasToolCalls = message.toolCalls && message.toolCalls.length > 0;

  return (
    <div className="rounded-lg bg-purple-50 px-3 py-2 dark:bg-purple-900/20">
      <div className="mb-0.5 text-[10px] font-semibold text-purple-600 dark:text-purple-400">
        Assistant
      </div>
      {message.content && <MessageContent content={message.content} />}
      {hasToolCalls && (
        <div className="mt-1.5 flex flex-wrap gap-1">
          {message.toolCalls!.map((tc, i) => (
            <ToolCallBadge key={i} name={tc.name} arguments={tc.arguments} />
          ))}
        </div>
      )}
    </div>
  );
}

function ToolMessage({ message }: { message: LiveConversationMessage }) {
  const [expanded, setExpanded] = useState(false);
  const content = message.content ?? '';
  const isLong = content.length > 300;

  return (
    <div className="ml-4 rounded border border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-900/20">
      <button
        onClick={() => isLong && setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-2 py-1 text-left"
      >
        <span className="text-[10px] font-semibold text-green-700 dark:text-green-400">
          {message.toolName ?? 'Tool'}
        </span>
        {isLong && (
          <span className="text-[10px] text-green-500 dark:text-green-500">
            {expanded ? 'collapse' : `${content.length} chars`}
          </span>
        )}
      </button>
      <div className="border-t border-green-200 px-2 py-1 dark:border-green-800">
        <pre className="whitespace-pre-wrap text-[10px] leading-relaxed text-green-800 dark:text-green-200">
          {isLong && !expanded ? content.slice(0, 300) + '...' : content}
        </pre>
      </div>
    </div>
  );
}

// ========================
// Shared sub-components
// ========================

function MessageContent({ content }: { content: string | null }) {
  if (!content) return null;

  const [expanded, setExpanded] = useState(false);
  const isLong = content.length > 500;
  const displayContent = isLong && !expanded ? content.slice(0, 500) + '...' : content;

  return (
    <div>
      <pre className="whitespace-pre-wrap text-[11px] leading-relaxed text-gray-700 dark:text-gray-200">
        {displayContent}
      </pre>
      {isLong && (
        <button
          onClick={() => setExpanded(!expanded)}
          className="mt-1 text-[10px] text-blue-500 hover:text-blue-700 dark:text-blue-400"
        >
          {expanded ? 'Show less' : 'Show more'}
        </button>
      )}
    </div>
  );
}

function ToolCallBadge({ name, arguments: args }: { name: string; arguments: string }) {
  const [showArgs, setShowArgs] = useState(false);

  return (
    <div>
      <button
        onClick={() => setShowArgs(!showArgs)}
        className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700 hover:bg-purple-200 dark:bg-purple-800/40 dark:text-purple-300 dark:hover:bg-purple-800/60"
      >
        {name}
      </button>
      {showArgs && args && (
        <pre className="mt-1 rounded bg-gray-100 p-1.5 text-[10px] text-gray-600 dark:bg-gray-800 dark:text-gray-300">
          {args.length > 500 ? args.slice(0, 500) + '...' : args}
        </pre>
      )}
    </div>
  );
}
