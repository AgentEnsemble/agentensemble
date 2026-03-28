/**
 * Sidebar panel showing details of the selected ensemble in the network dashboard.
 * Includes lifecycle state, shared capabilities, and a drill-down link.
 */

import { useNetwork } from '../../contexts/NetworkContext.js';

const stateLabels: Record<string, { label: string; color: string }> = {
  READY: { label: 'Ready', color: 'text-green-600' },
  STARTING: { label: 'Starting', color: 'text-yellow-600' },
  DRAINING: { label: 'Draining', color: 'text-orange-600' },
  STOPPED: { label: 'Stopped', color: 'text-red-600' },
};

export default function NetworkSidebar() {
  const { state, selectEnsemble } = useNetwork();
  const selected = state.selectedEnsemble
    ? state.ensembles[state.selectedEnsemble]
    : null;

  if (!selected) {
    return (
      <div className="flex h-full w-80 flex-col border-l border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-900">
        <p className="text-sm text-gray-500 dark:text-gray-400">
          Click an ensemble to view details
        </p>
      </div>
    );
  }

  const stateInfo = stateLabels[selected.lifecycleState ?? ''] ?? {
    label: selected.lifecycleState ?? 'Unknown',
    color: 'text-gray-500',
  };

  return (
    <div
      className="flex h-full w-80 flex-col border-l border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900"
      data-testid="network-sidebar"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          {selected.name}
        </h3>
        <button
          type="button"
          onClick={() => selectEnsemble(null)}
          className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
        >
          &times;
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {/* Status */}
        <div className="mb-4">
          <p className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Status
          </p>
          <p className={`text-sm font-medium ${stateInfo.color}`}>{stateInfo.label}</p>
        </div>

        {/* Metrics */}
        <div className="mb-4 grid grid-cols-2 gap-2">
          <div className="rounded bg-white p-2 dark:bg-gray-800">
            <p className="text-xs text-gray-500 dark:text-gray-400">Active Tasks</p>
            <p className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              {selected.activeTasks}
            </p>
          </div>
          <div className="rounded bg-white p-2 dark:bg-gray-800">
            <p className="text-xs text-gray-500 dark:text-gray-400">Queue Depth</p>
            <p className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              {selected.queueDepth}
            </p>
          </div>
          <div className="rounded bg-white p-2 dark:bg-gray-800">
            <p className="text-xs text-gray-500 dark:text-gray-400">Completed</p>
            <p className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              {selected.completedTasks}
            </p>
          </div>
          <div className="rounded bg-white p-2 dark:bg-gray-800">
            <p className="text-xs text-gray-500 dark:text-gray-400">Total Tasks</p>
            <p className="text-lg font-semibold text-gray-900 dark:text-gray-100">
              {selected.taskCount}
            </p>
          </div>
        </div>

        {/* Shared Capabilities */}
        {selected.sharedCapabilities.length > 0 && (
          <div className="mb-4">
            <p className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
              Shared Capabilities
            </p>
            <ul className="space-y-1">
              {selected.sharedCapabilities.map((cap) => (
                <li key={cap.name} className="text-sm text-gray-700 dark:text-gray-300">
                  <span className="inline-block rounded bg-gray-200 px-1.5 py-0.5 text-xs font-mono dark:bg-gray-700">
                    {cap.type}
                  </span>{' '}
                  {cap.name}
                </li>
              ))}
            </ul>
          </div>
        )}

        {/* Connection Info */}
        <div className="mb-4">
          <p className="mb-1 text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">
            Connection
          </p>
          <p className="text-xs text-gray-600 dark:text-gray-400">{selected.wsUrl}</p>
        </div>
      </div>

      {/* Drill-down button */}
      <div className="border-t border-gray-200 p-4 dark:border-gray-700">
        <a
          href={`/live?server=${encodeURIComponent(selected.wsUrl)}`}
          className="block w-full rounded bg-blue-600 px-4 py-2 text-center text-sm font-medium text-white hover:bg-blue-700"
          data-testid="drill-down-link"
        >
          Drill Down
        </a>
      </div>
    </div>
  );
}
