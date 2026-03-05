import React from 'react';
import type { DagTaskNode, DagAgentNode } from '../../types/dag.js';
import type { TaskTrace } from '../../types/trace.js';
import { formatDuration, parseDurationMs, formatTokenCount } from '../../utils/parser.js';
import { getAgentColor } from '../../utils/colors.js';
import { TaskMetricsBadges } from './MetricsBadge.js';

interface DetailPanelProps {
  task: DagTaskNode;
  taskTrace: TaskTrace | null;
  agentInfo: DagAgentNode | null;
  onClose: () => void;
}

/**
 * Detail panel for the Flow View, shown when a DAG task node is selected.
 *
 * Displays:
 * - Task description and expected output
 * - Agent configuration (goal, tools, delegation)
 * - Dependencies (task IDs this task depends on)
 * - Trace metrics when a trace file is loaded
 * - Final output when trace data is available
 */
export default function DetailPanel({ task, taskTrace, agentInfo, onClose }: DetailPanelProps) {
  const color = getAgentColor(task.agentRole);
  const durationMs = taskTrace ? parseDurationMs(taskTrace.duration) : null;

  return (
    <div className="flex w-80 flex-col overflow-hidden border-l border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-900">
      {/* Header */}
      <div
        className="flex items-center justify-between px-3 py-2.5"
        style={{ borderBottom: `2px solid ${color.bg}` }}
      >
        <div className="flex items-center gap-2">
          <span
            className="rounded-md px-2 py-0.5 text-xs font-semibold text-white"
            style={{ backgroundColor: color.bg }}
          >
            {task.agentRole}
          </span>
          {durationMs !== null && (
            <span className="text-xs text-gray-500">{formatDuration(durationMs)}</span>
          )}
        </div>
        <button
          onClick={onClose}
          className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200"
          title="Close"
        >
          &#x2715;
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto scrollbar-thin">
        {/* Task details */}
        <Section title="Task">
          <p className="text-xs text-gray-700 dark:text-gray-300">{task.description}</p>
        </Section>
        <Section title="Expected Output">
          <p className="text-xs text-gray-600 dark:text-gray-400">{task.expectedOutput}</p>
        </Section>

        {/* Graph properties */}
        <Section title="Graph">
          <div className="space-y-1 text-xs">
            <Row label="Parallel group" value={`Level ${task.parallelGroup}`} />
            <Row label="Critical path" value={task.onCriticalPath ? 'Yes' : 'No'} />
            {task.dependsOn.length > 0 && (
              <Row label="Depends on" value={task.dependsOn.map((id) => `Task ${id}`).join(', ')} />
            )}
          </div>
        </Section>

        {/* Agent info */}
        {agentInfo && (
          <Section title="Agent">
            <div className="space-y-1 text-xs">
              <Row label="Goal" value={agentInfo.goal} />
              {agentInfo.background && (
                <Row label="Background" value={agentInfo.background} />
              )}
              {agentInfo.toolNames.length > 0 && (
                <Row label="Tools" value={agentInfo.toolNames.join(', ')} />
              )}
              <Row label="Can delegate" value={agentInfo.allowDelegation ? 'Yes' : 'No'} />
            </div>
          </Section>
        )}

        {/* Trace metrics */}
        {taskTrace && (
          <Section title="Execution Metrics">
            <TaskMetricsBadges metrics={taskTrace.metrics} />
          </Section>
        )}

        {/* Final output */}
        {taskTrace && taskTrace.finalOutput && (
          <Section title="Final Output">
            <p className="whitespace-pre-wrap text-xs text-gray-700 dark:text-gray-300">
              {taskTrace.finalOutput}
            </p>
          </Section>
        )}

        {/* LLM iterations summary */}
        {taskTrace && taskTrace.llmInteractions.length > 0 && (
          <Section title={`LLM Iterations (${taskTrace.llmInteractions.length})`}>
            <div className="space-y-1">
              {taskTrace.llmInteractions.map((llm) => (
                <div
                  key={llm.iterationIndex}
                  className="rounded bg-gray-50 px-2 py-1 text-xs dark:bg-gray-800"
                >
                  <span className="font-medium text-indigo-600 dark:text-indigo-400">
                    #{llm.iterationIndex + 1}
                  </span>
                  <span className="ml-2 text-gray-500">
                    {llm.responseType.replace('_', ' ').toLowerCase()}
                  </span>
                  <span className="ml-2 text-gray-400">
                    {formatDuration(parseDurationMs(llm.latency))}
                  </span>
                  {llm.toolCalls.length > 0 && (
                    <span className="ml-2 text-emerald-600 dark:text-emerald-400">
                      {llm.toolCalls.length} tool{llm.toolCalls.length > 1 ? 's' : ''}
                    </span>
                  )}
                  {(llm.inputTokens !== -1 || llm.outputTokens !== -1) && (
                    <span className="ml-2 text-gray-400">
                      {formatTokenCount(llm.inputTokens)}+{formatTokenCount(llm.outputTokens)} tok
                    </span>
                  )}
                </div>
              ))}
            </div>
          </Section>
        )}
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border-b border-gray-100 px-3 py-2.5 dark:border-gray-800">
      <p className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        {title}
      </p>
      {children}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <span className="w-24 shrink-0 text-gray-400 dark:text-gray-500">{label}</span>
      <span className="text-gray-700 dark:text-gray-300">{value}</span>
    </div>
  );
}
