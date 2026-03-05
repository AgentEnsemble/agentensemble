import React, { useCallback, useEffect, useReducer } from 'react';
import type { DagModel } from './types/dag.js';
import type { ExecutionTrace } from './types/trace.js';
import { readAndParseFile, fetchAndParseFile } from './utils/parser.js';
import { seedAgentColors } from './utils/colors.js';
import LoadTrace from './pages/LoadTrace.js';
import FlowView from './pages/FlowView.js';
import TimelineView from './pages/TimelineView.js';

// ========================
// App state
// ========================

type View = 'load' | 'flow' | 'timeline';

interface AppState {
  dag: DagModel | null;
  dagName: string | null;
  trace: ExecutionTrace | null;
  traceName: string | null;
  activeView: View;
  error: string | null;
  loading: boolean;
  darkMode: boolean;
}

type AppAction =
  | { type: 'SET_DAG'; dag: DagModel; name: string }
  | { type: 'SET_TRACE'; trace: ExecutionTrace; name: string }
  | { type: 'CLEAR_DAG' }
  | { type: 'CLEAR_TRACE' }
  | { type: 'SET_VIEW'; view: View }
  | { type: 'SET_ERROR'; error: string | null }
  | { type: 'SET_LOADING'; loading: boolean }
  | { type: 'TOGGLE_DARK_MODE' };

function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'SET_DAG':
      return {
        ...state,
        dag: action.dag,
        dagName: action.name,
        activeView: 'flow',
        error: null,
        loading: false,
      };
    case 'SET_TRACE':
      return {
        ...state,
        trace: action.trace,
        traceName: action.name,
        activeView: 'timeline',
        error: null,
        loading: false,
      };
    case 'CLEAR_DAG':
      return {
        ...state,
        dag: null,
        dagName: null,
        activeView: state.trace ? 'timeline' : 'load',
      };
    case 'CLEAR_TRACE':
      return {
        ...state,
        trace: null,
        traceName: null,
        activeView: state.dag ? 'flow' : 'load',
      };
    case 'SET_VIEW':
      return { ...state, activeView: action.view };
    case 'SET_ERROR':
      return { ...state, error: action.error, loading: false };
    case 'SET_LOADING':
      return { ...state, loading: action.loading };
    case 'TOGGLE_DARK_MODE':
      return { ...state, darkMode: !state.darkMode };
    default:
      return state;
  }
}

const initialState: AppState = {
  dag: null,
  dagName: null,
  trace: null,
  traceName: null,
  activeView: 'load',
  error: null,
  loading: false,
  darkMode: false,
};

// ========================
// App component
// ========================

export default function App() {
  const [state, dispatch] = useReducer(appReducer, initialState);
  const { dag, dagName, trace, traceName, activeView, error, loading, darkMode } = state;

  // Apply dark mode class to root
  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
  }, [darkMode]);

  // Seed agent colors when DAG or trace changes
  useEffect(() => {
    const roles =
      dag?.agents.map((a) => a.role) ?? trace?.agents.map((a) => a.role) ?? [];
    if (roles.length > 0) {
      seedAgentColors(roles);
    }
  }, [dag, trace]);

  // On mount, try to contact the CLI server and auto-load any available files
  useEffect(() => {
    void autoLoadFromServer(dispatch);
  }, []);

  const handleFiles = useCallback(
    async (files: FileList | File[]) => {
      dispatch({ type: 'SET_LOADING', loading: true });
      dispatch({ type: 'SET_ERROR', error: null });
      try {
        for (const file of Array.from(files)) {
          const parsed = await readAndParseFile(file);
          if (parsed.type === 'dag' && parsed.dag) {
            dispatch({ type: 'SET_DAG', dag: parsed.dag, name: parsed.name });
          } else if (parsed.type === 'trace' && parsed.trace) {
            dispatch({ type: 'SET_TRACE', trace: parsed.trace, name: parsed.name });
          }
        }
      } catch (e) {
        dispatch({ type: 'SET_ERROR', error: String(e) });
      }
    },
    [],
  );

  return (
    <div className="flex h-full flex-col">
      {/* Top navigation bar */}
      <header className="flex h-12 shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4 dark:border-gray-700 dark:bg-gray-900">
        <div className="flex items-center gap-3">
          <span className="text-sm font-bold tracking-tight text-gray-900 dark:text-gray-100">
            AgentEnsemble
          </span>
          <span className="text-xs text-gray-400 dark:text-gray-500">Trace Viewer</span>

          {/* View tabs */}
          {(dag || trace) && (
            <div className="ml-4 flex gap-1">
              {dag && (
                <TabButton
                  label="Flow"
                  active={activeView === 'flow'}
                  onClick={() => dispatch({ type: 'SET_VIEW', view: 'flow' })}
                  badge={dag.tasks.length}
                />
              )}
              {trace && (
                <TabButton
                  label="Timeline"
                  active={activeView === 'timeline'}
                  onClick={() => dispatch({ type: 'SET_VIEW', view: 'timeline' })}
                  badge={trace.taskTraces.length}
                />
              )}
              <TabButton
                label="Load"
                active={activeView === 'load'}
                onClick={() => dispatch({ type: 'SET_VIEW', view: 'load' })}
              />
            </div>
          )}
        </div>

        <div className="flex items-center gap-3">
          {/* Loaded file indicators */}
          {dagName && (
            <FileChip
              label={dagName}
              color="blue"
              onRemove={() => dispatch({ type: 'CLEAR_DAG' })}
            />
          )}
          {traceName && (
            <FileChip
              label={traceName}
              color="emerald"
              onRemove={() => dispatch({ type: 'CLEAR_TRACE' })}
            />
          )}

          {/* Dark mode toggle */}
          <button
            onClick={() => dispatch({ type: 'TOGGLE_DARK_MODE' })}
            className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-200"
            title={darkMode ? 'Switch to light mode' : 'Switch to dark mode'}
          >
            {darkMode ? (
              <SunIcon className="h-4 w-4" />
            ) : (
              <MoonIcon className="h-4 w-4" />
            )}
          </button>
        </div>
      </header>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 bg-red-50 px-4 py-2 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-400">
          <span className="font-medium">Error:</span> {error}
          <button
            onClick={() => dispatch({ type: 'SET_ERROR', error: null })}
            className="ml-auto text-red-500 hover:text-red-700"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Loading indicator */}
      {loading && (
        <div className="flex items-center gap-2 bg-blue-50 px-4 py-2 text-sm text-blue-700 dark:bg-blue-900/20 dark:text-blue-400">
          <span className="animate-spin">&#9696;</span> Loading...
        </div>
      )}

      {/* Main content */}
      <main className="flex min-h-0 flex-1">
        {activeView === 'load' && (
          <LoadTrace onFiles={handleFiles} dag={dag} trace={trace} />
        )}
        {activeView === 'flow' && dag && <FlowView dag={dag} trace={trace} />}
        {activeView === 'timeline' && trace && <TimelineView trace={trace} />}
      </main>
    </div>
  );
}

// ========================
// Auto-load from CLI server
// ========================

async function autoLoadFromServer(dispatch: React.Dispatch<AppAction>) {
  try {
    const res = await fetch('/api/files');
    if (!res.ok) return; // Not running via CLI server
    const data = (await res.json()) as { files: Array<{ name: string; type: string }> };
    if (!data.files || data.files.length === 0) return;

    // Auto-load files: load the most recent dag and trace
    const dagFile = [...data.files].reverse().find((f) => f.type === 'dag');
    const traceFile = [...data.files].reverse().find((f) => f.type === 'trace');

    if (dagFile) {
      const parsed = await fetchAndParseFile(dagFile.name);
      if (parsed.dag) dispatch({ type: 'SET_DAG', dag: parsed.dag, name: parsed.name });
    }
    if (traceFile) {
      const parsed = await fetchAndParseFile(traceFile.name);
      if (parsed.trace) dispatch({ type: 'SET_TRACE', trace: parsed.trace, name: parsed.name });
    }
  } catch {
    // Not running via CLI — drag-and-drop mode
  }
}

// ========================
// Sub-components
// ========================

function TabButton({
  label,
  active,
  onClick,
  badge,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
  badge?: number;
}) {
  return (
    <button
      onClick={onClick}
      className={[
        'flex items-center gap-1 rounded px-2.5 py-1 text-xs font-medium transition-colors',
        active
          ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-800',
      ].join(' ')}
    >
      {label}
      {badge !== undefined && (
        <span
          className={[
            'rounded-full px-1 text-xs',
            active
              ? 'bg-blue-200 text-blue-800 dark:bg-blue-800 dark:text-blue-200'
              : 'bg-gray-200 text-gray-600 dark:bg-gray-700 dark:text-gray-300',
          ].join(' ')}
        >
          {badge}
        </span>
      )}
    </button>
  );
}

function FileChip({
  label,
  color,
  onRemove,
}: {
  label: string;
  color: 'blue' | 'emerald';
  onRemove: () => void;
}) {
  const colorClasses =
    color === 'blue'
      ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
      : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300';

  // Truncate long filenames
  const display = label.length > 30 ? '...' + label.slice(-27) : label;

  return (
    <span className={`flex items-center gap-1 rounded-full px-2 py-0.5 text-xs ${colorClasses}`}>
      {display}
      <button onClick={onRemove} className="ml-0.5 opacity-60 hover:opacity-100" title="Remove">
        &#x2715;
      </button>
    </span>
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
