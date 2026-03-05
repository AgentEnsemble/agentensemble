import { formatTokenCount, formatDuration, parseDurationMs } from '../../utils/parser.js';
import type { TaskMetrics, ExecutionMetrics } from '../../types/trace.js';

interface MetricsBadgeProps {
  label: string;
  value: string;
  title?: string;
  color?: string;
}

/**
 * A small inline badge displaying a metric label and value.
 */
export function MetricsBadge({ label, value, title, color }: MetricsBadgeProps) {
  return (
    <span
      title={title}
      className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-800"
    >
      <span className="text-gray-500 dark:text-gray-400">{label}</span>
      <span style={{ color }} className="font-medium text-gray-800 dark:text-gray-200">
        {value}
      </span>
    </span>
  );
}

interface TaskMetricsBadgesProps {
  metrics: TaskMetrics;
}

/**
 * A row of metric badges for a single TaskMetrics object.
 */
export function TaskMetricsBadges({ metrics }: TaskMetricsBadgesProps) {
  const llmMs = parseDurationMs(metrics.llmLatency);
  const toolMs = parseDurationMs(metrics.toolExecutionTime);

  return (
    <div className="flex flex-wrap gap-1.5">
      {metrics.totalTokens !== -1 && (
        <MetricsBadge
          label="tokens"
          value={formatTokenCount(metrics.totalTokens)}
          title={`Input: ${metrics.inputTokens}, Output: ${metrics.outputTokens}`}
        />
      )}
      {metrics.totalTokens === -1 && (
        <MetricsBadge label="tokens" value="?" title="Token count unavailable from provider" />
      )}
      {isFinite(llmMs) && llmMs > 0 && (
        <MetricsBadge
          label="LLM"
          value={formatDuration(llmMs)}
          title="Cumulative LLM call latency"
          color="#6366F1"
        />
      )}
      {isFinite(toolMs) && toolMs > 0 && (
        <MetricsBadge
          label="tools"
          value={formatDuration(toolMs)}
          title="Cumulative tool execution time"
          color="#22C55E"
        />
      )}
      {metrics.llmCallCount > 0 && (
        <MetricsBadge
          label="LLM calls"
          value={String(metrics.llmCallCount)}
          title="Number of LLM chat() round-trips"
        />
      )}
      {metrics.toolCallCount > 0 && (
        <MetricsBadge
          label="tool calls"
          value={String(metrics.toolCallCount)}
          title="Number of tool invocations"
        />
      )}
      {metrics.delegationCount > 0 && (
        <MetricsBadge
          label="delegations"
          value={String(metrics.delegationCount)}
          title="Number of agent-to-agent delegations"
          color="#F59E0B"
        />
      )}
      {metrics.costEstimate && (
        <MetricsBadge
          label="cost"
          value={`${metrics.costEstimate.currency} ${metrics.costEstimate.totalCost}`}
          title="Estimated LLM cost"
          color="#10B981"
        />
      )}
    </div>
  );
}

interface RunSummaryBadgesProps {
  metrics: ExecutionMetrics;
  totalDuration: string;
}

/**
 * Summary badges for an entire ensemble run.
 */
export function RunSummaryBadges({ metrics, totalDuration }: RunSummaryBadgesProps) {
  const durationMs = parseDurationMs(totalDuration);

  return (
    <div className="flex flex-wrap gap-1.5">
      {isFinite(durationMs) && (
        <MetricsBadge
          label="total duration"
          value={formatDuration(durationMs)}
          title="Total wall-clock run duration"
        />
      )}
      {metrics.totalTokens !== -1 && (
        <MetricsBadge
          label="total tokens"
          value={formatTokenCount(metrics.totalTokens)}
          title={`Input: ${metrics.totalInputTokens}, Output: ${metrics.totalOutputTokens}`}
        />
      )}
      {metrics.totalLlmCallCount > 0 && (
        <MetricsBadge
          label="LLM calls"
          value={String(metrics.totalLlmCallCount)}
          title="Total LLM calls across all tasks"
          color="#6366F1"
        />
      )}
      {metrics.totalToolCalls > 0 && (
        <MetricsBadge
          label="tool calls"
          value={String(metrics.totalToolCalls)}
          title="Total tool calls across all tasks"
          color="#22C55E"
        />
      )}
      {metrics.totalDelegations > 0 && (
        <MetricsBadge
          label="delegations"
          value={String(metrics.totalDelegations)}
          title="Total agent-to-agent delegations"
          color="#F59E0B"
        />
      )}
      {metrics.totalCostEstimate && (
        <MetricsBadge
          label="total cost"
          value={`${metrics.totalCostEstimate.currency} ${metrics.totalCostEstimate.totalCost}`}
          title="Total estimated LLM cost"
          color="#10B981"
        />
      )}
    </div>
  );
}
