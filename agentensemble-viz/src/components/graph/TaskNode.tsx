import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import type { NodeProps, Node } from '@xyflow/react';
import type { TaskNodeData } from '../../utils/graphLayout.js';
import { formatDuration, parseDurationMs, formatTokenCount } from '../../utils/parser.js';

export type TaskNodeType = Node<TaskNodeData, 'taskNode'>;

/**
 * Custom ReactFlow node component for rendering a task in the DAG.
 *
 * Displays:
 * - Task description (truncated)
 * - Agent role badge (colored)
 * - Critical path indicator
 * - Parallel group level
 * - Optional trace metrics (duration, token count) when trace data is available
 */
const TaskNode = memo(function TaskNode({ data }: NodeProps<TaskNodeType>) {
  const { task, agentColor, isSelected, traceData } = data;

  const durationMs = traceData ? parseDurationMs(traceData.duration) : null;

  return (
    <div className="relative" style={{ width: 220 }}>
      {/* Incoming edge handle (left) */}
      <Handle type="target" position={Position.Left} />

      {/* Node body */}
      <div
        style={{
          border: `2px solid ${isSelected ? agentColor.bg : task.onCriticalPath ? '#EF4444' : agentColor.border}`,
          boxShadow: isSelected
            ? `0 0 0 3px ${agentColor.bg}40`
            : task.onCriticalPath
              ? '0 0 0 2px rgba(239,68,68,0.3)'
              : undefined,
          backgroundColor: '#FFFFFF',
        }}
        className="rounded-lg bg-white transition-all dark:bg-gray-800"
      >
        {/* Header: agent role colored stripe */}
        <div
          style={{ backgroundColor: agentColor.bg }}
          className="flex items-center justify-between rounded-t-md px-2.5 py-1.5"
        >
          <span className="truncate text-xs font-semibold text-white">{task.agentRole}</span>
          <div className="flex items-center gap-1">
            {task.onCriticalPath && (
              <span title="Critical path" className="rounded bg-white/20 px-1 text-xs text-white">
                CP
              </span>
            )}
            {task.nodeType === 'map' && (
              <span title="Map phase task" className="rounded bg-white/30 px-1 text-xs font-semibold text-white">
                MAP
              </span>
            )}
            {task.nodeType === 'reduce' && task.mapReduceLevel !== undefined && (
              <span
                title={`Reduce level ${task.mapReduceLevel}`}
                className="rounded bg-white/30 px-1 text-xs font-semibold text-white"
              >
                REDUCE L{task.mapReduceLevel}
              </span>
            )}
            {task.nodeType === 'final-reduce' && (
              <span title="Final aggregation task" className="rounded bg-white/30 px-1 text-xs font-semibold text-white">
                AGGREGATE
              </span>
            )}
            {task.nodeType === 'direct' && (
              <span title="Direct (short-circuit) task" className="rounded bg-white/30 px-1 text-xs font-semibold text-white">
                DIRECT
              </span>
            )}
            <span className="rounded bg-white/20 px-1 text-xs text-white">
              L{task.parallelGroup}
            </span>
          </div>
        </div>

        {/* Body: task description */}
        <div className="px-2.5 py-2">
          <p
            className="line-clamp-2 text-xs text-gray-700 dark:text-gray-300"
            title={task.description}
          >
            {task.description}
          </p>
        </div>

        {/* Footer: metrics from trace (if available) */}
        {(durationMs !== null || traceData) && (
          <div className="flex items-center justify-between border-t border-gray-100 px-2.5 py-1 dark:border-gray-700">
            {durationMs !== null && (
              <span className="text-xs text-gray-500">{formatDuration(durationMs)}</span>
            )}
            {traceData && traceData.metrics.totalTokens !== -1 && (
              <span className="text-xs text-gray-500">
                {formatTokenCount(traceData.metrics.totalTokens)} tok
              </span>
            )}
            {traceData && (
              <span className="text-xs text-gray-500">
                {traceData.metrics.llmCallCount} LLM
                {traceData.metrics.toolCallCount > 0 &&
                  ` / ${traceData.metrics.toolCallCount} tool`}
              </span>
            )}
          </div>
        )}
      </div>

      {/* Outgoing edge handle (right) */}
      <Handle type="source" position={Position.Right} />
    </div>
  );
});

export default TaskNode;
