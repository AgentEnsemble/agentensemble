/**
 * NetworkPage: multi-ensemble network dashboard.
 *
 * Route: /network
 * Query params: ?ensembles=name1:ws://host1:port/ws,name2:ws://host2:port/ws
 *
 * Connects to each ensemble, shows topology graph, sidebar detail panel,
 * and real-time status updates.
 */

import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { NetworkProvider } from '../contexts/NetworkContext.js';
import NetworkHeader from '../components/network/NetworkHeader.js';
import NetworkTopology from '../components/network/NetworkTopology.js';
import NetworkSidebar from '../components/network/NetworkSidebar.js';

/** Parse ?ensembles=name1:ws://host1:port/ws,name2:ws://host2:port/ws */
function parseEnsembleParams(
  raw: string | null,
): Array<{ name: string; wsUrl: string }> {
  if (!raw) return [];
  return raw
    .split(',')
    .map((entry) => {
      const colonIdx = entry.indexOf(':');
      if (colonIdx < 1) return null;
      const name = entry.substring(0, colonIdx);
      const wsUrl = entry.substring(colonIdx + 1);
      return { name, wsUrl };
    })
    .filter((e): e is { name: string; wsUrl: string } => e !== null);
}

function NetworkPageInner() {
  return (
    <div className="flex h-screen flex-col bg-gray-50 dark:bg-gray-900">
      <NetworkHeader />
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1">
          <NetworkTopology />
        </div>
        <NetworkSidebar />
      </div>
    </div>
  );
}

export default function NetworkPage() {
  const [searchParams] = useSearchParams();
  const initialEnsembles = useMemo(
    () => parseEnsembleParams(searchParams.get('ensembles')),
    [searchParams],
  );

  return (
    <NetworkProvider initialEnsembles={initialEnsembles}>
      <NetworkPageInner />
    </NetworkProvider>
  );
}
