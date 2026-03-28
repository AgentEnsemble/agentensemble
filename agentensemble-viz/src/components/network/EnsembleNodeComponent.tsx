/**
 * Custom React Flow node component for an ensemble in the network topology.
 * Shows ensemble name, lifecycle state, task progress, and queue depth.
 */

import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { NetworkEnsemble } from '../../types/network.js';

interface EnsembleNodeData {
  ensemble: NetworkEnsemble;
  selected: boolean;
  onClick: (name: string) => void;
}

const stateColors: Record<string, string> = {
  READY: 'bg-green-500',
  STARTING: 'bg-yellow-500',
  DRAINING: 'bg-orange-500',
  STOPPED: 'bg-red-500',
};

const connectionColors: Record<string, string> = {
  connected: 'border-green-400',
  connecting: 'border-yellow-400',
  disconnected: 'border-gray-400',
  error: 'border-red-400',
};

export default function EnsembleNodeComponent({ data }: NodeProps) {
  const { ensemble, selected, onClick } = data as unknown as EnsembleNodeData;

  const stateColor = stateColors[ensemble.lifecycleState ?? ''] ?? 'bg-gray-400';
  const borderColor = connectionColors[ensemble.connectionStatus] ?? 'border-gray-400';

  return (
    <>
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div
        onClick={() => onClick(ensemble.name)}
        className={`cursor-pointer rounded-lg border-2 ${borderColor} bg-white p-3 shadow-md transition-shadow hover:shadow-lg dark:bg-gray-800 ${
          selected ? 'ring-2 ring-blue-500' : ''
        }`}
        style={{ minWidth: 160 }}
      >
        {/* Header */}
        <div className="flex items-center gap-2">
          <div className={`h-2.5 w-2.5 rounded-full ${stateColor}`} />
          <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
            {ensemble.name}
          </span>
        </div>

        {/* Status line */}
        <div className="mt-2 flex items-center gap-3 text-xs text-gray-500 dark:text-gray-400">
          <span>{ensemble.activeTasks} active</span>
          <span>{ensemble.queueDepth} queued</span>
        </div>

        {/* Progress bar */}
        {ensemble.taskCount > 0 && (
          <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
            <div
              className="h-full rounded-full bg-blue-500 transition-all"
              style={{
                width: `${Math.min(100, (ensemble.completedTasks / ensemble.taskCount) * 100)}%`,
              }}
            />
          </div>
        )}
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-gray-500" />
    </>
  );
}
