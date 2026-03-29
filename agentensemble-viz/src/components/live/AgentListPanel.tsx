/**
 * AgentListPanel: sidebar showing all agents in the current ensemble run.
 *
 * Derives agent summaries from LiveState.tasks — each unique agentRole becomes
 * an entry with status, tool call count, and task count.
 *
 * Click an agent to select it; the right panel shows its conversation.
 */

import { useMemo } from 'react';
import type { LiveState, LiveTask } from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

interface AgentSummary {
  role: string;
  status: 'running' | 'completed' | 'failed' | 'idle';
  taskCount: number;
  toolCallCount: number;
  currentTask: string | null;
}

interface AgentListPanelProps {
  liveState: LiveState;
  selectedAgent: string | null;
  onSelectAgent: (role: string | null) => void;
}

function deriveAgentSummaries(tasks: LiveTask[]): AgentSummary[] {
  const byRole = new Map<string, LiveTask[]>();
  for (const task of tasks) {
    const existing = byRole.get(task.agentRole) ?? [];
    existing.push(task);
    byRole.set(task.agentRole, existing);
  }

  const summaries: AgentSummary[] = [];
  for (const [role, roleTasks] of byRole) {
    const hasRunning = roleTasks.some((t) => t.status === 'running');
    const hasFailed = roleTasks.some((t) => t.status === 'failed');
    const allCompleted = roleTasks.every((t) => t.status === 'completed');

    let status: AgentSummary['status'];
    if (hasRunning) status = 'running';
    else if (hasFailed) status = 'failed';
    else if (allCompleted) status = 'completed';
    else status = 'idle';

    const toolCallCount = roleTasks.reduce((sum, t) => sum + t.toolCalls.length, 0);
    const runningTask = roleTasks.find((t) => t.status === 'running');

    summaries.push({
      role,
      status,
      taskCount: roleTasks.length,
      toolCallCount,
      currentTask: runningTask?.taskDescription ?? null,
    });
  }

  return summaries;
}

export default function AgentListPanel({
  liveState,
  selectedAgent,
  onSelectAgent,
}: AgentListPanelProps) {
  const summaries = useMemo(() => deriveAgentSummaries(liveState.tasks), [liveState.tasks]);

  if (summaries.length === 0) {
    return (
      <div className="flex h-full items-center justify-center p-4">
        <span className="text-xs text-gray-400 dark:text-gray-500">Waiting for agents...</span>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col overflow-y-auto" data-testid="agent-list-panel">
      <div className="border-b border-gray-200 px-3 py-2 dark:border-gray-700">
        <span className="text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          Agents
        </span>
      </div>
      <div className="flex-1 overflow-y-auto">
        {summaries.map((agent) => (
          <AgentListEntry
            key={agent.role}
            agent={agent}
            selected={selectedAgent === agent.role}
            onClick={() => onSelectAgent(selectedAgent === agent.role ? null : agent.role)}
          />
        ))}
      </div>
    </div>
  );
}

function AgentListEntry({
  agent,
  selected,
  onClick,
}: {
  agent: AgentSummary;
  selected: boolean;
  onClick: () => void;
}) {
  const color = getAgentColor(agent.role);

  return (
    <button
      onClick={onClick}
      data-testid={`agent-list-entry-${agent.role}`}
      className={[
        'flex w-full items-start gap-2 border-b border-gray-100 px-3 py-2.5 text-left transition-colors',
        'dark:border-gray-800',
        selected
          ? 'bg-blue-50 dark:bg-blue-900/20'
          : 'hover:bg-gray-50 dark:hover:bg-gray-800/50',
      ].join(' ')}
    >
      {/* Agent color dot with status animation */}
      <div className="mt-0.5 flex-shrink-0">
        <div
          className={[
            'h-2.5 w-2.5 rounded-full',
            agent.status === 'running' ? 'animate-[ae-pulse_2s_ease-in-out_infinite]' : '',
          ].join(' ')}
          style={{ backgroundColor: color.bg }}
        />
      </div>

      <div className="min-w-0 flex-1">
        {/* Role name */}
        <div className="truncate text-xs font-medium text-gray-900 dark:text-gray-100">
          {agent.role}
        </div>

        {/* Current task (if running) */}
        {agent.currentTask && (
          <div className="mt-0.5 truncate text-[10px] text-gray-500 dark:text-gray-400">
            {agent.currentTask}
          </div>
        )}

        {/* Stats row */}
        <div className="mt-1 flex items-center gap-2">
          <StatusBadge status={agent.status} />
          {agent.toolCallCount > 0 && (
            <span className="text-[10px] text-gray-400 dark:text-gray-500">
              {agent.toolCallCount} tools
            </span>
          )}
          {agent.taskCount > 1 && (
            <span className="text-[10px] text-gray-400 dark:text-gray-500">
              {agent.taskCount} tasks
            </span>
          )}
        </div>
      </div>
    </button>
  );
}

function StatusBadge({ status }: { status: AgentSummary['status'] }) {
  const config = {
    running: { label: 'Running', classes: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300' },
    completed: { label: 'Done', classes: 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300' },
    failed: { label: 'Failed', classes: 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300' },
    idle: { label: 'Idle', classes: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400' },
  }[status];

  return (
    <span className={`rounded px-1 py-0.5 text-[10px] font-medium ${config.classes}`}>
      {config.label}
    </span>
  );
}
