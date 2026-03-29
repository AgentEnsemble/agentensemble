/**
 * MetricsDashboard: real-time metrics visualization in the bottom panel.
 *
 * Shows:
 * - Token usage per agent (stacked horizontal bars)
 * - Cumulative token sparkline over time
 * - Iteration count per agent
 */

import { useMemo } from 'react';
import type { LiveMetricsSnapshot } from '../../types/live.js';
import type { LiveTask } from '../../types/live.js';
import { getAgentColor } from '../../utils/colors.js';

interface MetricsDashboardProps {
  metricsHistory: LiveMetricsSnapshot[];
  tasks: LiveTask[];
}

interface AgentMetrics {
  role: string;
  inputTokens: number;
  outputTokens: number;
  iterationCount: number;
}

export default function MetricsDashboard({ metricsHistory, tasks }: MetricsDashboardProps) {
  // Aggregate latest metrics per agent
  const agentMetrics = useMemo(() => {
    const byRole = new Map<string, AgentMetrics>();

    // Use metrics history if available
    for (const snapshot of metricsHistory) {
      byRole.set(snapshot.agentRole, {
        role: snapshot.agentRole,
        inputTokens: snapshot.inputTokens,
        outputTokens: snapshot.outputTokens,
        iterationCount: snapshot.iterationCount,
      });
    }

    // Fallback: derive from tasks if no metrics history
    if (byRole.size === 0) {
      for (const task of tasks) {
        if (task.tokenCount && task.tokenCount > 0) {
          const existing = byRole.get(task.agentRole);
          byRole.set(task.agentRole, {
            role: task.agentRole,
            inputTokens: (existing?.inputTokens ?? 0) + Math.floor(task.tokenCount * 0.7),
            outputTokens: (existing?.outputTokens ?? 0) + Math.floor(task.tokenCount * 0.3),
            iterationCount: (existing?.iterationCount ?? 0) + task.toolCalls.length + 1,
          });
        }
      }
    }

    return Array.from(byRole.values());
  }, [metricsHistory, tasks]);

  // Sparkline data: cumulative tokens over time
  const sparklineData = useMemo(() => {
    if (metricsHistory.length === 0) return [];
    let cumulative = 0;
    const points: Array<{ x: number; y: number }> = [];
    const startTime = metricsHistory[0]?.timestamp ?? 0;

    for (const snapshot of metricsHistory) {
      cumulative += snapshot.inputTokens + snapshot.outputTokens;
      points.push({
        x: snapshot.timestamp - startTime,
        y: cumulative,
      });
    }
    return points;
  }, [metricsHistory]);

  if (agentMetrics.length === 0) {
    return (
      <div className="flex h-full items-center justify-center">
        <span className="text-xs text-gray-400 dark:text-gray-500">
          No metrics data yet
        </span>
      </div>
    );
  }

  const maxTokens = Math.max(...agentMetrics.map((a) => a.inputTokens + a.outputTokens), 1);
  const totalTokens = agentMetrics.reduce((s, a) => s + a.inputTokens + a.outputTokens, 0);

  return (
    <div className="flex h-full gap-6 overflow-auto">
      {/* Token usage bars */}
      <div className="min-w-[300px] flex-1">
        <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          Token Usage by Agent
        </div>
        <div className="space-y-1.5">
          {agentMetrics.map((agent) => {
            const color = getAgentColor(agent.role);
            const total = agent.inputTokens + agent.outputTokens;
            const widthPercent = (total / maxTokens) * 100;
            const inputPercent = total > 0 ? (agent.inputTokens / total) * 100 : 0;

            return (
              <div key={agent.role} className="flex items-center gap-2">
                <div className="w-24 truncate text-[10px] font-medium text-gray-700 dark:text-gray-300">
                  {agent.role}
                </div>
                <div className="flex-1">
                  <div
                    className="flex h-4 overflow-hidden rounded"
                    style={{ width: `${widthPercent}%`, minWidth: '4px' }}
                  >
                    {/* Input tokens */}
                    <div
                      className="h-full"
                      style={{ width: `${inputPercent}%`, backgroundColor: color.bg, opacity: 0.7 }}
                      title={`Input: ${agent.inputTokens.toLocaleString()}`}
                    />
                    {/* Output tokens */}
                    <div
                      className="h-full"
                      style={{ width: `${100 - inputPercent}%`, backgroundColor: color.bg }}
                      title={`Output: ${agent.outputTokens.toLocaleString()}`}
                    />
                  </div>
                </div>
                <span className="w-16 text-right text-[10px] text-gray-500 dark:text-gray-400">
                  {total.toLocaleString()}
                </span>
              </div>
            );
          })}
        </div>
        <div className="mt-2 text-[10px] text-gray-500 dark:text-gray-400">
          Total: {totalTokens.toLocaleString()} tokens
        </div>
      </div>

      {/* Sparkline */}
      {sparklineData.length > 1 && (
        <div className="min-w-[200px]">
          <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
            Cumulative Tokens
          </div>
          <Sparkline data={sparklineData} width={200} height={60} />
        </div>
      )}

      {/* Iteration counts */}
      <div className="min-w-[120px]">
        <div className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          Iterations
        </div>
        <div className="space-y-1">
          {agentMetrics.map((agent) => {
            const color = getAgentColor(agent.role);
            return (
              <div key={agent.role} className="flex items-center gap-2">
                <div className="h-2 w-2 rounded-full" style={{ backgroundColor: color.bg }} />
                <span className="text-[10px] text-gray-700 dark:text-gray-300">{agent.role}</span>
                <span className="ml-auto text-[10px] font-mono text-gray-500 dark:text-gray-400">
                  {agent.iterationCount}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/** Simple SVG sparkline component. */
function Sparkline({
  data,
  width,
  height,
}: {
  data: Array<{ x: number; y: number }>;
  width: number;
  height: number;
}) {
  if (data.length < 2) return null;

  const maxX = Math.max(...data.map((p) => p.x), 1);
  const maxY = Math.max(...data.map((p) => p.y), 1);
  const padding = 2;

  const points = data
    .map((p) => {
      const x = padding + (p.x / maxX) * (width - padding * 2);
      const y = height - padding - (p.y / maxY) * (height - padding * 2);
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg width={width} height={height} className="rounded bg-gray-50 dark:bg-gray-800/50">
      <polyline
        points={points}
        fill="none"
        stroke="#3B82F6"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
