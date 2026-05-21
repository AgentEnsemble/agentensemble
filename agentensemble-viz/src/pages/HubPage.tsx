/**
 * HubPage: distributed live observability dashboard.
 *
 * Route: /hub
 * Query params: ?server=ws://localhost:7400/ws
 *
 * Connects to one LiveEventHub WebSocket and shows a coherent merged view of all
 * publisher processes. Each producer's events flow through {@code liveReducer} so the
 * per-producer panels behave exactly like the embedded /live dashboard.
 */

import { useEffect, useMemo, useState } from 'react';
import { HubServerProvider, useHubServer } from '../contexts/HubServerContext.js';
import ConnectionStatusBar from '../components/shared/ConnectionStatusBar.js';
import type { ProducerInfo } from '../types/hub.js';
import type { LiveState } from '../types/live.js';

function getServerUrlFromQuery(): string | null {
  if (typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get('server');
}

export default function HubPage() {
  const [darkMode, setDarkMode] = useState(false);
  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
  }, [darkMode]);

  return (
    <HubServerProvider>
      <HubPageInner darkMode={darkMode} onToggleDarkMode={() => setDarkMode((v) => !v)} />
    </HubServerProvider>
  );
}

function HubPageInner({
  darkMode,
  onToggleDarkMode,
}: {
  darkMode: boolean;
  onToggleDarkMode: () => void;
}) {
  const { hubState, connect } = useHubServer();
  const [selectedProducerId, setSelectedProducerId] = useState<string | null>(null);
  const [serviceFilter, setServiceFilter] = useState<string | null>(null);

  useEffect(() => {
    const url = getServerUrlFromQuery();
    if (url) connect(url);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const producers = useMemo<ProducerInfo[]>(() => {
    const list = Object.values(hubState.producers);
    return serviceFilter ? list.filter((p) => p.serviceName === serviceFilter) : list;
  }, [hubState.producers, serviceFilter]);

  const services = useMemo<string[]>(() => {
    const set = new Set<string>();
    Object.values(hubState.producers).forEach((p) => {
      if (p.serviceName) set.add(p.serviceName);
    });
    return Array.from(set).sort();
  }, [hubState.producers]);

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-2 dark:border-gray-700 dark:bg-gray-900">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
          AgentEnsemble Hub
        </h1>
        <div className="flex items-center gap-2">
          {services.length > 0 && (
            <select
              className="rounded border border-gray-300 bg-white px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
              value={serviceFilter ?? ''}
              onChange={(e) => setServiceFilter(e.target.value || null)}
            >
              <option value="">All services</option>
              {services.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          )}
          <button
            type="button"
            className="rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:text-gray-100"
            onClick={onToggleDarkMode}
          >
            {darkMode ? '☀' : '☾'}
          </button>
        </div>
      </header>
      <ConnectionStatusBar
        status={hubState.connectionStatus}
        serverUrl={hubState.serverUrl}
      />
      <main className="flex min-h-0 flex-1">
        <aside className="w-72 overflow-y-auto border-r border-gray-200 bg-white p-2 dark:border-gray-700 dark:bg-gray-900">
          <h2 className="px-2 pb-2 text-xs font-medium uppercase tracking-wide text-gray-500">
            Producers ({producers.length})
          </h2>
          <ul className="space-y-1">
            {producers.map((p) => (
              <ProducerChip
                key={p.producerId}
                producer={p}
                liveState={hubState.perProducer[p.producerId]}
                selected={p.producerId === selectedProducerId}
                onSelect={() => setSelectedProducerId(p.producerId)}
              />
            ))}
            {producers.length === 0 && (
              <li className="px-2 py-1 text-sm text-gray-400">No producers connected.</li>
            )}
          </ul>
        </aside>
        <section className="flex-1 overflow-y-auto p-4">
          {selectedProducerId ? (
            <ProducerDetail
              producer={hubState.producers[selectedProducerId]}
              liveState={hubState.perProducer[selectedProducerId]}
            />
          ) : (
            <div className="text-sm text-gray-500 dark:text-gray-400">
              Select a producer in the sidebar to inspect its tasks.
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function ProducerChip({
  producer,
  liveState,
  selected,
  onSelect,
}: {
  producer: ProducerInfo;
  liveState: LiveState | undefined;
  selected: boolean;
  onSelect: () => void;
}) {
  const tasks = liveState?.tasks ?? [];
  const running = tasks.filter((t) => t.status === 'running').length;
  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        className={`w-full rounded px-2 py-1 text-left text-sm ${
          selected
            ? 'bg-blue-100 text-blue-900 dark:bg-blue-900 dark:text-blue-100'
            : 'hover:bg-gray-100 dark:hover:bg-gray-800'
        }`}
      >
        <div className="font-medium text-gray-900 dark:text-gray-100">
          {producer.serviceName ?? producer.producerId}
        </div>
        <div className="text-xs text-gray-500 dark:text-gray-400">
          {producer.producerId}
          {producer.instanceId ? ` · ${producer.instanceId}` : ''}
        </div>
        <div className="text-xs text-gray-500 dark:text-gray-400">
          {tasks.length} task{tasks.length === 1 ? '' : 's'}{running > 0 ? ` · ${running} running` : ''}
        </div>
      </button>
    </li>
  );
}

function ProducerDetail({
  producer,
  liveState,
}: {
  producer: ProducerInfo | undefined;
  liveState: LiveState | undefined;
}) {
  if (!producer || !liveState) {
    return (
      <div className="text-sm text-gray-500 dark:text-gray-400">
        Producer not yet reporting.
      </div>
    );
  }
  return (
    <div>
      <h3 className="mb-2 text-base font-semibold text-gray-900 dark:text-gray-100">
        {producer.serviceName ?? producer.producerId}
      </h3>
      <dl className="mb-4 grid grid-cols-2 gap-x-6 gap-y-1 text-sm text-gray-700 dark:text-gray-300">
        <dt>Producer ID</dt>
        <dd className="font-mono text-xs">{producer.producerId}</dd>
        {producer.instanceId && (
          <>
            <dt>Instance</dt>
            <dd>{producer.instanceId}</dd>
          </>
        )}
        {producer.host && (
          <>
            <dt>Host</dt>
            <dd>{producer.host}</dd>
          </>
        )}
        <dt>Ensemble</dt>
        <dd>{liveState.ensembleId ?? '—'}</dd>
        <dt>Tasks</dt>
        <dd>{liveState.tasks.length}</dd>
      </dl>
      <h4 className="mb-1 text-sm font-medium text-gray-900 dark:text-gray-100">Tasks</h4>
      <ul className="space-y-1">
        {liveState.tasks.map((task) => (
          <li
            key={`${task.taskIndex}-${task.taskDescription}`}
            className="rounded border border-gray-200 bg-white p-2 text-sm dark:border-gray-700 dark:bg-gray-900"
          >
            <div className="flex justify-between">
              <span className="font-medium text-gray-900 dark:text-gray-100">{task.taskDescription}</span>
              <span className="text-xs uppercase text-gray-500">{task.status}</span>
            </div>
            <div className="text-xs text-gray-500">{task.agentRole}</div>
          </li>
        ))}
        {liveState.tasks.length === 0 && (
          <li className="text-sm text-gray-500 dark:text-gray-400">No tasks yet for this producer.</li>
        )}
      </ul>
    </div>
  );
}
