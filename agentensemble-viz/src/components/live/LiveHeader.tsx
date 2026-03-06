/**
 * LiveHeader: top navigation bar rendered in live execution mode.
 *
 * Displays:
 * - "AgentEnsemble" branding and "Live" badge
 * - Ensemble ID (short form) and workflow type when available
 * - Task progress count ("2 / 5 tasks")
 * - "Flow | Timeline" view toggle
 * - Dark mode toggle
 */

import type { LiveState } from '../../types/live.js';

export type LiveView = 'flow' | 'timeline';

interface LiveHeaderProps {
  liveState: LiveState;
  activeView: LiveView;
  onViewChange: (view: LiveView) => void;
  darkMode: boolean;
  onToggleDarkMode: () => void;
}

/**
 * Navigation bar for the live execution dashboard.
 *
 * Shows connection metadata (ensemble ID, workflow, task count) and the
 * view-switch toggle between Flow and Timeline views.
 */
export default function LiveHeader({
  liveState,
  activeView,
  onViewChange,
  darkMode,
  onToggleDarkMode,
}: LiveHeaderProps) {
  const { ensembleId, workflow, tasks, totalTasks } = liveState;

  // Count tasks that are no longer running (completed or failed)
  const finishedCount = tasks.filter(
    (t) => t.status === 'completed' || t.status === 'failed',
  ).length;

  return (
    <header
      className="flex h-12 shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4 dark:border-gray-700 dark:bg-gray-900"
      data-testid="live-header"
    >
      <div className="flex items-center gap-3">
        {/* Branding */}
        <span className="text-sm font-bold tracking-tight text-gray-900 dark:text-gray-100">
          AgentEnsemble
        </span>
        <span className="rounded bg-blue-600 px-1.5 py-0.5 text-xs font-semibold text-white">
          Live
        </span>

        {/* Ensemble metadata */}
        {ensembleId && (
          <span
            className="font-mono text-xs text-gray-500 dark:text-gray-400"
            title={ensembleId}
            data-testid="live-header-ensemble-id"
          >
            {ensembleId.length > 8 ? ensembleId.slice(0, 8) : ensembleId}
          </span>
        )}

        {workflow && (
          <span
            className="text-xs font-medium text-gray-600 dark:text-gray-400"
            data-testid="live-header-workflow"
          >
            {workflow}
          </span>
        )}

        {/* Task progress */}
        {totalTasks > 0 && (
          <span
            className="text-xs text-gray-500 dark:text-gray-400"
            data-testid="live-header-task-progress"
          >
            {finishedCount} / {totalTasks} tasks
          </span>
        )}

        {/* View toggle */}
        <div className="ml-4 flex gap-1">
          <TabButton
            label="Timeline"
            active={activeView === 'timeline'}
            onClick={() => onViewChange('timeline')}
          />
          <TabButton
            label="Flow"
            active={activeView === 'flow'}
            onClick={() => onViewChange('flow')}
          />
        </div>
      </div>

      <div className="flex items-center gap-2">
        {/* Dark mode toggle */}
        <button
          onClick={onToggleDarkMode}
          className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-200"
          title={darkMode ? 'Switch to light mode' : 'Switch to dark mode'}
          data-testid="live-header-dark-mode-toggle"
        >
          {darkMode ? (
            <SunIcon className="h-4 w-4" />
          ) : (
            <MoonIcon className="h-4 w-4" />
          )}
        </button>
      </div>
    </header>
  );
}

// ========================
// Sub-components
// ========================

function TabButton({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      data-testid={`live-view-tab-${label.toLowerCase()}`}
      className={[
        'rounded px-2.5 py-1 text-xs font-medium transition-colors',
        active
          ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-800',
      ].join(' ')}
    >
      {label}
    </button>
  );
}

function SunIcon({ className }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor">
      <circle cx="12" cy="12" r="5" strokeWidth="2" />
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2"
        d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"
      />
    </svg>
  );
}

function MoonIcon({ className }: { className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor">
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2"
        d="M21 12.79A9 9 0 1111.21 3a7 7 0 009.79 9.79z"
      />
    </svg>
  );
}
