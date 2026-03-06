/**
 * LivePage: the live execution dashboard page.
 *
 * Accessed via the /live route (e.g. /live?server=ws://localhost:7329/ws).
 * Wraps its content in LiveServerProvider and auto-connects to the server URL
 * read from the ?server query parameter.
 */

import { useEffect, useState } from 'react';
import { LiveServerProvider, useLiveServer } from '../contexts/LiveServerContext.js';
import LiveHeader, { type LiveView } from '../components/live/LiveHeader.js';
import ConnectionStatusBar from '../components/shared/ConnectionStatusBar.js';
import TimelineView from './TimelineView.js';
import FlowView from './FlowView.js';

/**
 * Read the WebSocket server URL from the ?server query parameter.
 * Returns null if the parameter is absent.
 */
function getServerUrlFromQuery(): string | null {
  if (typeof window === 'undefined') return null;
  return new URLSearchParams(window.location.search).get('server');
}

/**
 * Root component for the /live route.
 *
 * Owns dark mode state (so it persists across view switches) and wraps
 * its subtree in LiveServerProvider.
 */
export default function LivePage() {
  const [darkMode, setDarkMode] = useState(false);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', darkMode);
  }, [darkMode]);

  return (
    <LiveServerProvider>
      <LivePageInner
        darkMode={darkMode}
        onToggleDarkMode={() => setDarkMode((v) => !v)}
      />
    </LiveServerProvider>
  );
}

// ========================
// Inner component (consumes LiveServerProvider)
// ========================

function LivePageInner({
  darkMode,
  onToggleDarkMode,
}: {
  darkMode: boolean;
  onToggleDarkMode: () => void;
}) {
  const { liveState, connect } = useLiveServer();
  const [activeView, setActiveView] = useState<LiveView>('timeline');

  // Auto-connect to the server URL supplied via the ?server query param
  useEffect(() => {
    const url = getServerUrlFromQuery();
    if (url) {
      connect(url);
    }
    // connect is stable (wrapped in useCallback), but we only want to run this once
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="flex h-full flex-col">
      <LiveHeader
        liveState={liveState}
        activeView={activeView}
        onViewChange={setActiveView}
        darkMode={darkMode}
        onToggleDarkMode={onToggleDarkMode}
      />
      <ConnectionStatusBar
        status={liveState.connectionStatus}
        serverUrl={liveState.serverUrl}
      />
      <main className="flex min-h-0 flex-1">
        {activeView === 'timeline' && <TimelineView trace={null} isLive />}
        {activeView === 'flow' && <FlowView dag={null} trace={null} isLive />}
      </main>
    </div>
  );
}
