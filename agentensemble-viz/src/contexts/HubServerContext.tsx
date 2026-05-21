/**
 * HubServerContext: React context managing the WebSocket connection to a LiveEventHub.
 *
 * Mirrors {@link LiveServerContext} but uses {@link hubActionReducer} so events are routed
 * per-producer and the browser keeps a multi-producer aggregate view.
 */

import React, { createContext, useCallback, useContext, useEffect, useReducer, useRef } from 'react';
import { hubActionReducer, initialHubState } from '../utils/hubReducer.js';
import type { HubState, HubServerMessage } from '../types/hub.js';
import type { ReviewDecisionMessage } from '../types/live.js';

const BACKOFF_DELAYS_MS = [1000, 2000, 4000, 8000, 16000, 30000];

export interface HubServerContextValue {
  hubState: HubState;
  connect: (url: string) => void;
  disconnect: () => void;
  /** Sends a {@code review_decision} back through the hub so it can route to the owning producer. */
  sendDecision: (
    reviewId: string,
    decision: ReviewDecisionMessage['decision'],
    revisedOutput?: string,
  ) => boolean;
}

const HubServerContext = createContext<HubServerContextValue | null>(null);

export function HubServerProvider({ children }: { children: React.ReactNode }) {
  const [hubState, dispatch] = useReducer(hubActionReducer, initialHubState);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryCountRef = useRef(0);
  const intentionalDisconnectRef = useRef(false);
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
      ws.onopen = null;
      ws.onmessage = null;
      ws.onerror = null;
      ws.onclose = null;
      ws.close();
      wsRef.current = null;
    }
  }, []);

  const openWsRef = useRef<(url: string) => void>(() => {});
  openWsRef.current = (url: string) => {
    closeWs();
    dispatch({ type: 'CONNECTING', serverUrl: url });

    const ws = new WebSocket(url);
    wsRef.current = ws;

    ws.onopen = () => {
      retryCountRef.current = 0;
      dispatch({ type: 'CONNECTED' });
    };
    ws.onmessage = (event: MessageEvent) => {
      try {
        const msg = JSON.parse(event.data as string) as HubServerMessage;
        dispatch({ type: 'MESSAGE', message: msg });
      } catch {
        // Drop malformed messages
      }
    };
    ws.onerror = () => dispatch({ type: 'ERROR', error: 'WebSocket error' });
    ws.onclose = () => {
      wsRef.current = null;
      if (intentionalDisconnectRef.current) {
        intentionalDisconnectRef.current = false;
        dispatch({ type: 'DISCONNECTED' });
        return;
      }
      dispatch({ type: 'DISCONNECTED' });
      const delay = BACKOFF_DELAYS_MS[Math.min(retryCountRef.current, BACKOFF_DELAYS_MS.length - 1)];
      retryCountRef.current++;
      reconnectTimerRef.current = setTimeout(() => {
        if (serverUrlRef.current) openWsRef.current(serverUrlRef.current);
      }, delay);
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

  const sendDecision = useCallback(
    (reviewId: string, decision: ReviewDecisionMessage['decision'], revisedOutput?: string): boolean => {
      const ws = wsRef.current;
      if (!ws || ws.readyState !== WebSocket.OPEN) return false;
      const msg: ReviewDecisionMessage = {
        type: 'review_decision',
        reviewId,
        decision,
        ...(revisedOutput !== undefined ? { revisedOutput } : {}),
      };
      ws.send(JSON.stringify(msg));
      return true;
    },
    [],
  );

  useEffect(
    () => () => {
      clearReconnectTimer();
      intentionalDisconnectRef.current = true;
      closeWs();
    },
    [clearReconnectTimer, closeWs],
  );

  return (
    <HubServerContext.Provider value={{ hubState, connect, disconnect, sendDecision }}>
      {children}
    </HubServerContext.Provider>
  );
}

export function useHubServer(): HubServerContextValue {
  const ctx = useContext(HubServerContext);
  if (!ctx) throw new Error('useHubServer must be called within a HubServerProvider');
  return ctx;
}
