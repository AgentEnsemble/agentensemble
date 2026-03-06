/**
 * LiveServerContext: React context managing the WebSocket connection to the
 * agentensemble-web server.
 *
 * Responsibilities:
 * - Open/close the WebSocket
 * - Auto-reconnect with exponential backoff (1s, 2s, 4s, 8s, 16s, 30s max)
 * - Parse incoming JSON messages and dispatch them to liveActionReducer
 * - Expose liveState, connect(), disconnect(), and sendMessage()
 *
 * Usage:
 *   Wrap a subtree in <LiveServerProvider>. Components inside can call
 *   useLiveServer() to access the context value.
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useReducer,
  useRef,
} from 'react';
import { liveActionReducer, initialLiveState } from '../utils/liveReducer.js';
import type { LiveState, ClientMessage, ServerMessage, ReviewDecisionMessage } from '../types/live.js';

// Exponential backoff delays in ms: 1s, 2s, 4s, 8s, 16s, then cap at 30s
const BACKOFF_DELAYS_MS = [1000, 2000, 4000, 8000, 16000, 30000];

// ========================
// Context value type
// ========================

export interface LiveServerContextValue {
  /** Current live state: connection status, ensemble data, tasks, etc. */
  liveState: LiveState;
  /**
   * Connect to a WebSocket server at the given URL.
   * Resets live state, cancels any pending reconnect, and opens a new connection.
   */
  connect: (url: string) => void;
  /** Intentionally close the connection and stop auto-reconnect. */
  disconnect: () => void;
  /**
   * Send a message to the server.
   * No-ops if the WebSocket is not currently open.
   */
  sendMessage: (msg: ClientMessage) => void;
  /**
   * Send a review_decision message and optimistically remove the review from
   * pendingReviews in the local state.
   *
   * @param reviewId  - The id of the review being decided.
   * @param decision  - The decision: CONTINUE, EDIT, or EXIT_EARLY.
   * @param revisedOutput - Required when decision is EDIT; the user's edited output.
   */
  sendDecision: (
    reviewId: string,
    decision: ReviewDecisionMessage['decision'],
    revisedOutput?: string,
  ) => void;
}

const LiveServerContext = createContext<LiveServerContextValue | null>(null);

// ========================
// Provider
// ========================

/** Provides WebSocket-backed live state to its subtree. */
export function LiveServerProvider({ children }: { children: React.ReactNode }) {
  const [liveState, dispatch] = useReducer(liveActionReducer, initialLiveState);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryCountRef = useRef(0);
  const intentionalDisconnectRef = useRef(false);
  // Stored so the reconnect loop knows where to connect back to
  const serverUrlRef = useRef<string | null>(null);

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const closeWs = useCallback(() => {
    const ws = wsRef.current;
    if (ws) {
      // Null out all handlers before closing to prevent spurious callbacks
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      ws.close();
      wsRef.current = null;
    }
  }, []);

  // openWs is declared via useRef so the reconnect closure captures the latest version
  const openWsRef = useRef<(url: string) => void>(() => {});

  openWsRef.current = (url: string) => {
    closeWs();
    dispatch({ type: 'CONNECTING', serverUrl: url });

    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      retryCountRef.current = 0;
      dispatch({ type: 'CONNECTED' });
      // Server automatically sends hello on connect; no explicit hello request needed
    };

    ws.onmessage = (event: MessageEvent) => {
      try {
        const message = JSON.parse(event.data as string) as ServerMessage;
        dispatch({ type: 'MESSAGE', message });
      } catch {
        // Discard malformed messages -- they are not part of the known protocol
      }
    };

    ws.onerror = () => {
      dispatch({ type: 'ERROR', error: 'WebSocket error' });
    };

    ws.onclose = () => {
      wsRef.current = null;

      if (intentionalDisconnectRef.current) {
        intentionalDisconnectRef.current = false;
        dispatch({ type: 'DISCONNECTED' });
        return;
      }

      // Unexpected close -- schedule reconnect with exponential backoff
      dispatch({ type: 'DISCONNECTED' });
      const delayMs =
        BACKOFF_DELAYS_MS[Math.min(retryCountRef.current, BACKOFF_DELAYS_MS.length - 1)];
      retryCountRef.current++;

      reconnectTimerRef.current = setTimeout(() => {
        if (serverUrlRef.current) {
          openWsRef.current(serverUrlRef.current);
        }
      }, delayMs);
    };
  };

  const connect = useCallback(
    (url: string) => {
      clearReconnectTimer();
      intentionalDisconnectRef.current = false;
      retryCountRef.current = 0;
      serverUrlRef.current = url;
      dispatch({ type: 'RESET' });
      openWsRef.current(url);
    },
    [clearReconnectTimer],
  );

  const disconnect = useCallback(() => {
    clearReconnectTimer();
    intentionalDisconnectRef.current = true;
    serverUrlRef.current = null;
    closeWs();
    dispatch({ type: 'DISCONNECTED' });
  }, [clearReconnectTimer, closeWs]);

  const sendMessage = useCallback((msg: ClientMessage) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }, []);

  const sendDecision = useCallback(
    (
      reviewId: string,
      decision: ReviewDecisionMessage['decision'],
      revisedOutput?: string,
    ) => {
      const msg: ReviewDecisionMessage = {
        type: 'review_decision',
        reviewId,
        decision,
        ...(revisedOutput !== undefined ? { revisedOutput } : {}),
      };
      sendMessage(msg);
      // Optimistically remove the review from local state so the UI updates
      // immediately without waiting for the server's acknowledgement.
      dispatch({ type: 'RESOLVE_REVIEW', reviewId });
    },
    [sendMessage],
  );

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearReconnectTimer();
      intentionalDisconnectRef.current = true;
      closeWs();
    };
  }, [clearReconnectTimer, closeWs]);

  return (
    <LiveServerContext.Provider value={{ liveState, connect, disconnect, sendMessage, sendDecision }}>
      {children}
    </LiveServerContext.Provider>
  );
}

// ========================
// Hook
// ========================

/**
 * Access the live server context value.
 *
 * Must be called from a component that is a descendant of LiveServerProvider.
 * Throws if called outside a provider.
 */
export function useLiveServer(): LiveServerContextValue {
  const ctx = useContext(LiveServerContext);
  if (!ctx) {
    throw new Error('useLiveServer must be called within a LiveServerProvider');
  }
  return ctx;
}
