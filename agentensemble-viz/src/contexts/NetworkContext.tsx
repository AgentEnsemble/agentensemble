/**
 * NetworkContext: manages multiple WebSocket connections (one per ensemble)
 * and provides aggregated network state to the network dashboard.
 *
 * Each ensemble gets its own WebSocket connection for live event streaming.
 * Status polling fetches /api/status from each ensemble's HTTP endpoint every 5s.
 */

import { createContext, useContext, useReducer, useRef, useCallback, useEffect, type ReactNode } from 'react';
import type { NetworkState } from '../types/network.js';
import { initialNetworkState, networkReducer } from '../utils/networkReducer.js';

// ========================
// Context
// ========================

interface NetworkContextValue {
  state: NetworkState;
  addEnsemble: (name: string, wsUrl: string) => void;
  removeEnsemble: (name: string) => void;
  selectEnsemble: (name: string | null) => void;
}

const NetworkContext = createContext<NetworkContextValue | null>(null);

export function useNetwork(): NetworkContextValue {
  const ctx = useContext(NetworkContext);
  if (!ctx) throw new Error('useNetwork must be used within a NetworkProvider');
  return ctx;
}

// ========================
// Provider
// ========================

interface NetworkProviderProps {
  children: ReactNode;
  /** Initial ensembles to connect to (parsed from URL query params). */
  initialEnsembles?: Array<{ name: string; wsUrl: string }>;
}

export function NetworkProvider({ children, initialEnsembles }: NetworkProviderProps) {
  const [state, dispatch] = useReducer(networkReducer, initialNetworkState);
  const wsRefs = useRef<Map<string, WebSocket>>(new Map());
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // ---- WebSocket management ----

  const connectWs = useCallback((name: string, wsUrl: string) => {
    const existing = wsRefs.current.get(name);
    if (existing && existing.readyState <= WebSocket.OPEN) return;

    dispatch({ type: 'SET_CONNECTION_STATUS', name, status: 'connecting' });

    const ws = new WebSocket(wsUrl);
    wsRefs.current.set(name, ws);

    ws.onopen = () => {
      dispatch({ type: 'SET_CONNECTION_STATUS', name, status: 'connected' });
    };

    ws.onclose = () => {
      dispatch({ type: 'SET_CONNECTION_STATUS', name, status: 'disconnected' });
      wsRefs.current.delete(name);
    };

    ws.onerror = () => {
      dispatch({ type: 'SET_CONNECTION_STATUS', name, status: 'error' });
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'hello' && msg.sharedCapabilities) {
          dispatch({
            type: 'UPDATE_STATUS',
            name,
            status: { sharedCapabilities: msg.sharedCapabilities },
          });
        }
        if (msg.type === 'ensemble_started') {
          dispatch({
            type: 'UPDATE_STATUS',
            name,
            status: {
              ensembleId: msg.ensembleId,
              workflow: msg.workflow,
              taskCount: msg.totalTasks,
              activeTasks: 0,
              completedTasks: 0,
            },
          });
        }
        if (msg.type === 'task_started') {
          dispatch({ type: 'TASK_STARTED', name });
        }
        if (msg.type === 'task_completed' || msg.type === 'task_failed') {
          dispatch({ type: 'TASK_ENDED', name });
        }
      } catch {
        // Ignore malformed messages
      }
    };
  }, [state.ensembles]);

  const disconnectWs = useCallback((name: string) => {
    const ws = wsRefs.current.get(name);
    if (ws) {
      ws.close();
      wsRefs.current.delete(name);
    }
  }, []);

  // ---- Status polling ----

  const pollStatus = useCallback(async () => {
    for (const [name, ensemble] of Object.entries(state.ensembles)) {
      try {
        // Derive HTTP URL from WebSocket URL
        const httpUrl = ensemble.wsUrl
          .replace('ws://', 'http://')
          .replace('wss://', 'https://')
          .replace(/\/ws$/, '/api/status');

        const response = await fetch(httpUrl, { signal: AbortSignal.timeout(3000) });
        if (response.ok) {
          const data = await response.json();
          dispatch({
            type: 'UPDATE_STATUS',
            name,
            status: {
              lifecycleState: data.lifecycleState ?? null,
              queueDepth: data.queueDepth ?? 0,
              activeTasks: data.activeTasks ?? ensemble.activeTasks,
              sharedCapabilities: data.sharedCapabilities ?? ensemble.sharedCapabilities,
            },
          });
        }
      } catch {
        // Status poll failure is non-fatal
      }
    }
  }, [state.ensembles]);

  // Start polling every 5s
  useEffect(() => {
    pollRef.current = setInterval(pollStatus, 5000);
    pollStatus(); // Initial poll
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [pollStatus]);

  // ---- Public actions ----

  const addEnsemble = useCallback((name: string, wsUrl: string) => {
    dispatch({ type: 'ADD_ENSEMBLE', name, wsUrl });
    connectWs(name, wsUrl);
  }, [connectWs]);

  const removeEnsemble = useCallback((name: string) => {
    disconnectWs(name);
    dispatch({ type: 'REMOVE_ENSEMBLE', name });
  }, [disconnectWs]);

  const selectEnsemble = useCallback((name: string | null) => {
    dispatch({ type: 'SELECT_ENSEMBLE', name });
  }, []);

  // Connect to initial ensembles on mount
  useEffect(() => {
    if (initialEnsembles) {
      for (const { name, wsUrl } of initialEnsembles) {
        addEnsemble(name, wsUrl);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      wsRefs.current.forEach((ws) => ws.close());
      wsRefs.current.clear();
    };
  }, []);

  return (
    <NetworkContext.Provider value={{ state, addEnsemble, removeEnsemble, selectEnsemble }}>
      {children}
    </NetworkContext.Provider>
  );
}
