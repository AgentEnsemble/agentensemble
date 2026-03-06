import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { DagModel } from '../types/dag.js';
import type { ExecutionTrace } from '../types/trace.js';

interface LoadTraceProps {
  onFiles: (files: FileList | File[]) => Promise<void>;
  dag: DagModel | null;
  trace: ExecutionTrace | null;
}

interface ServerFile {
  name: string;
  type: 'dag' | 'trace';
  sizeBytes: number;
}

/**
 * Landing page shown when no files are loaded, or when the user navigates to the load view.
 *
 * Supports two modes:
 * 1. CLI server mode: lists available files from /api/files and lets user select them
 * 2. Drag-and-drop mode: accepts dragged or selected .dag.json and .trace.json files
 */
export default function LoadTrace({ onFiles, dag, trace }: LoadTraceProps) {
  const [dragOver, setDragOver] = useState(false);
  const [serverFiles, setServerFiles] = useState<ServerFile[] | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Probe the CLI server for available files
  useEffect(() => {
    void fetchServerFiles();

    async function fetchServerFiles() {
      try {
        const res = await fetch('/api/files');
        if (!res.ok) return;
        const data = (await res.json()) as { files: ServerFile[] };
        setServerFiles(data.files ?? []);
      } catch {
        // Not running via CLI server -- drag-and-drop only
        setServerFiles(null);
      }
    }
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragOver(false);
      const files = Array.from(e.dataTransfer.files).filter(
        (f) => f.name.endsWith('.dag.json') || f.name.endsWith('.trace.json'),
      );
      if (files.length > 0) void onFiles(files);
    },
    [onFiles],
  );

  const handleFileInput = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (e.target.files && e.target.files.length > 0) void onFiles(e.target.files);
    },
    [onFiles],
  );

  const handleServerFile = useCallback(
    async (fileName: string) => {
      const res = await fetch(`/api/file?name=${encodeURIComponent(fileName)}`);
      if (!res.ok) return;
      const content = await res.text();
      const file = new File([content], fileName, { type: 'application/json' });
      void onFiles([file]);
    },
    [onFiles],
  );

  const hasLoaded = dag !== null || trace !== null;

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-8 p-8">
      {/* Header */}
      <div className="text-center">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">
          AgentEnsemble Trace Viewer
        </h1>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          Visualize execution graphs and debug agent runs
        </p>
      </div>

      {/* Connect to live server */}
      <LiveConnectForm />

      {/* Currently loaded summary */}
      {hasLoaded && (
        <div className="flex gap-4">
          {dag && (
            <div className="flex items-center gap-2 rounded-lg bg-blue-50 px-4 py-2 text-sm text-blue-700 dark:bg-blue-900/20 dark:text-blue-300">
              <span className="font-semibold">DAG loaded:</span>
              {dag.tasks.length} tasks, {dag.agents.length} agents, {dag.workflow}
            </div>
          )}
          {trace && (
            <div className="flex items-center gap-2 rounded-lg bg-emerald-50 px-4 py-2 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
              <span className="font-semibold">Trace loaded:</span>
              {trace.taskTraces.length} tasks, {trace.workflow}
            </div>
          )}
        </div>
      )}

      {/* Server file list (CLI mode) */}
      {serverFiles !== null && serverFiles.length > 0 && (
        <div className="w-full max-w-lg">
          <h2 className="mb-3 text-sm font-semibold text-gray-700 dark:text-gray-300">
            Available files from traces directory
          </h2>
          <div className="space-y-2">
            {serverFiles.map((file) => (
              <button
                key={file.name}
                onClick={() => void handleServerFile(file.name)}
                className="flex w-full items-center justify-between rounded-lg border border-gray-200 bg-white px-4 py-2.5 text-left text-sm transition-colors hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:bg-gray-700"
              >
                <div className="flex items-center gap-2">
                  <span
                    className={[
                      'rounded px-1.5 py-0.5 text-xs font-medium',
                      file.type === 'dag'
                        ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300'
                        : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
                    ].join(' ')}
                  >
                    {file.type.toUpperCase()}
                  </span>
                  <span className="font-mono text-gray-700 dark:text-gray-300">{file.name}</span>
                </div>
                <span className="text-xs text-gray-400">
                  {(file.sizeBytes / 1024).toFixed(1)} KB
                </span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Drag-and-drop zone */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        className={[
          'flex w-full max-w-lg cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-10 transition-colors',
          dragOver
            ? 'border-blue-400 bg-blue-50 dark:bg-blue-900/20'
            : 'border-gray-300 hover:border-gray-400 hover:bg-gray-50 dark:border-gray-600 dark:hover:border-gray-500 dark:hover:bg-gray-800/50',
        ].join(' ')}
      >
        <UploadIcon className="h-10 w-10 text-gray-400" />
        <div className="text-center">
          <p className="text-sm font-medium text-gray-700 dark:text-gray-300">
            {dragOver ? 'Drop files here' : 'Drop files or click to open'}
          </p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            Accepts <code className="font-mono">.dag.json</code> and{' '}
            <code className="font-mono">.trace.json</code> files
          </p>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept=".json"
          onChange={handleFileInput}
          className="hidden"
        />
      </div>

      {/* Usage instructions */}
      <div className="w-full max-w-lg rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800/50">
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
          How to export files
        </h3>
        <pre className="text-xs text-gray-600 dark:text-gray-400">
          {`// In your Java application:
import net.agentensemble.devtools.EnsembleDevTools;

// Export DAG before running (shows planned execution)
EnsembleDevTools.exportDag(ensemble, Path.of("./traces/"));

// Export trace after running (shows what happened)
EnsembleDevTools.exportTrace(output, Path.of("./traces/"));

// Or use captureMode to enable rich trace data:
Ensemble.builder()
    .captureMode(CaptureMode.STANDARD)
    .traceExporter(new JsonTraceExporter(Path.of("./traces/")))
    .build();`}
        </pre>
      </div>

      {/* Server file count when empty */}
      {serverFiles !== null && serverFiles.length === 0 && (
        <p className="text-xs text-gray-400 dark:text-gray-500">
          No trace files found in the connected traces directory.
          <br />
          Export some files from your Java application, then refresh.
        </p>
      )}
    </div>
  );
}

/**
 * Form that lets the user connect to a running agentensemble-web WebSocket server.
 * Navigates to /live?server=<encodedUrl> on submit.
 */
function LiveConnectForm() {
  const navigate = useNavigate();
  const [serverUrl, setServerUrl] = useState('ws://localhost:7329/ws');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = serverUrl.trim();
    if (trimmed) {
      navigate(`/live?server=${encodeURIComponent(trimmed)}`);
    }
  };

  return (
    <div
      className="w-full max-w-lg rounded-xl border border-blue-200 bg-blue-50 p-5 dark:border-blue-800 dark:bg-blue-950/30"
      data-testid="live-connect-form"
    >
      <h2 className="mb-3 text-sm font-semibold text-blue-800 dark:text-blue-300">
        Connect to live server
      </h2>
      <p className="mb-4 text-xs text-blue-700 dark:text-blue-400">
        Watch a running ensemble in real-time. Start your Java application with{' '}
        <code className="font-mono">.webDashboard(WebDashboard.onPort(7329))</code>, then connect
        below.
      </p>
      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          type="text"
          value={serverUrl}
          onChange={(e) => setServerUrl(e.target.value)}
          placeholder="ws://localhost:7329/ws"
          data-testid="live-connect-url-input"
          className="flex-1 rounded-md border border-blue-300 bg-white px-3 py-1.5 text-sm text-gray-900 placeholder-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:border-blue-700 dark:bg-gray-800 dark:text-gray-100 dark:placeholder-gray-500"
        />
        <button
          type="submit"
          data-testid="live-connect-button"
          className="rounded-md bg-blue-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1"
        >
          Connect
        </button>
      </form>
    </div>
  );
}

function UploadIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={1.5}
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5"
      />
    </svg>
  );
}
