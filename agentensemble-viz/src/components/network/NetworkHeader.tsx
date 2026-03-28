/**
 * Header bar for the network dashboard page.
 */

import { useState } from 'react';
import { useNetwork } from '../../contexts/NetworkContext.js';

export default function NetworkHeader() {
  const { addEnsemble } = useNetwork();
  const [showAdd, setShowAdd] = useState(false);
  const [name, setName] = useState('');
  const [wsUrl, setWsUrl] = useState('');

  function handleAdd() {
    if (name.trim() && wsUrl.trim()) {
      addEnsemble(name.trim(), wsUrl.trim());
      setName('');
      setWsUrl('');
      setShowAdd(false);
    }
  }

  return (
    <header className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
          AgentEnsemble
        </h1>
        <span className="rounded bg-purple-100 px-2 py-0.5 text-xs font-medium text-purple-700 dark:bg-purple-900/40 dark:text-purple-300">
          Network
        </span>
      </div>

      {!showAdd ? (
        <button
          type="button"
          onClick={() => setShowAdd(true)}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
          data-testid="add-ensemble-button"
        >
          Add Ensemble
        </button>
      ) : (
        <div className="flex items-center gap-2" data-testid="add-ensemble-form">
          <input
            type="text"
            placeholder="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-32 rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
          />
          <input
            type="text"
            placeholder="ws://host:port/ws"
            value={wsUrl}
            onChange={(e) => setWsUrl(e.target.value)}
            className="w-56 rounded border border-gray-300 px-2 py-1 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
          />
          <button
            type="button"
            onClick={handleAdd}
            className="rounded bg-green-600 px-3 py-1 text-sm font-medium text-white hover:bg-green-700"
          >
            Connect
          </button>
          <button
            type="button"
            onClick={() => setShowAdd(false)}
            className="rounded bg-gray-200 px-3 py-1 text-sm font-medium text-gray-700 hover:bg-gray-300 dark:bg-gray-700 dark:text-gray-200"
          >
            Cancel
          </button>
        </div>
      )}
    </header>
  );
}
