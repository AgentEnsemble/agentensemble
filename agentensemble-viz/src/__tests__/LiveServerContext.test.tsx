/**
 * Unit tests for LiveServerContext.
 *
 * WebSocket is not available in jsdom, so we stub globalThis.WebSocket with a
 * controllable fake that captures the most recent instance and allows tests to
 * trigger onopen / onmessage / onerror / onclose programmatically.
 *
 * Fake timer control (vi.useFakeTimers) is used to test exponential-backoff
 * reconnect scheduling without real delays.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { LiveServerProvider, useLiveServer } from '../contexts/LiveServerContext.js';

// ========================
// Mock WebSocket
// ========================

interface MockWsInstance {
  url: string;
  readyState: number;
  sentMessages: string[];
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  close: () => void;
  send: (data: string) => void;
  // Test helpers
  triggerOpen: () => void;
  triggerMessage: (data: unknown) => void;
  triggerError: () => void;
  triggerClose: () => void;
}

let lastWsInstance: MockWsInstance | null = null;

class MockWebSocket {
  url: string;
  readyState: number;
  sentMessages: string[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;

  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  // Non-static constants needed for the instance property check `ws.readyState === WebSocket.OPEN`
  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSING = 2;
  readonly CLOSED = 3;

  constructor(url: string) {
    this.url = url;
    this.readyState = MockWebSocket.CONNECTING;
    lastWsInstance = this as unknown as MockWsInstance;
  }

  close() {
    this.readyState = MockWebSocket.CLOSED;
  }

  send(data: string) {
    this.sentMessages.push(data);
  }

  triggerOpen() {
    this.readyState = MockWebSocket.OPEN;
    this.onopen?.({} as Event);
  }

  triggerMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) } as MessageEvent);
  }

  triggerError() {
    this.onerror?.({} as Event);
  }

  triggerClose() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({ code: 1006, reason: '' } as CloseEvent);
  }
}

// ========================
// Test helpers
// ========================

function wrapper({ children }: { children: React.ReactNode }) {
  return <LiveServerProvider>{children}</LiveServerProvider>;
}

// ========================
// Setup / teardown
// ========================

beforeEach(() => {
  lastWsInstance = null;
  vi.stubGlobal('WebSocket', MockWebSocket);
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

// ========================
// Tests
// ========================

describe('LiveServerContext', () => {
  describe('connect()', () => {
    it('transitions to connecting then connected on successful open', async () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });

      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });

      expect(result.current.liveState.connectionStatus).toBe('connecting');
      expect(result.current.liveState.serverUrl).toBe('ws://localhost:7329/ws');

      act(() => {
        lastWsInstance!.triggerOpen();
      });

      expect(result.current.liveState.connectionStatus).toBe('connected');
    });

    it('opens a WebSocket to the given URL', async () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      expect(lastWsInstance?.url).toBe('ws://localhost:7329/ws');
    });

    it('resets live state when connect() is called', async () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });

      // Put some state in place first
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
        lastWsInstance!.triggerMessage({
          type: 'ensemble_started',
          ensembleId: 'ens-001',
          startedAt: '2026-03-05T14:00:00Z',
          totalTasks: 3,
          workflow: 'SEQUENTIAL',
        });
      });
      expect(result.current.liveState.ensembleId).toBe('ens-001');

      // Re-connect resets state
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      expect(result.current.liveState.ensembleId).toBeNull();
    });
  });

  describe('message handling', () => {
    it('dispatches incoming JSON messages into live state', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
        lastWsInstance!.triggerMessage({
          type: 'ensemble_started',
          ensembleId: 'ens-XYZ',
          startedAt: '2026-03-05T14:00:00Z',
          totalTasks: 4,
          workflow: 'PARALLEL',
        });
      });
      expect(result.current.liveState.ensembleId).toBe('ens-XYZ');
      expect(result.current.liveState.workflow).toBe('PARALLEL');
    });

    it('dispatches task_started messages', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
        lastWsInstance!.triggerMessage({
          type: 'task_started',
          taskIndex: 0,
          totalTasks: 2,
          taskDescription: 'Do research',
          agentRole: 'Researcher',
          startedAt: '2026-03-05T14:00:01Z',
        });
      });
      expect(result.current.liveState.tasks).toHaveLength(1);
      expect(result.current.liveState.tasks[0].status).toBe('running');
    });

    it('silently ignores malformed (non-JSON) messages', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
        // Inject a malformed message directly via onmessage
        lastWsInstance!.onmessage?.({ data: 'not-json!!!' } as MessageEvent);
      });
      // State should be unchanged -- no error thrown
      expect(result.current.liveState.connectionStatus).toBe('connected');
    });
  });

  describe('error handling', () => {
    it('transitions to error status on WebSocket error', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerError();
      });
      expect(result.current.liveState.connectionStatus).toBe('error');
    });
  });

  describe('disconnect()', () => {
    it('transitions to disconnected and stops reconnect', async () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
      });

      act(() => {
        result.current.disconnect();
      });

      expect(result.current.liveState.connectionStatus).toBe('disconnected');

      // Advance timers -- no reconnect should happen
      const instanceBeforeTimer = lastWsInstance;
      act(() => {
        vi.advanceTimersByTime(60000);
      });
      // The same (closed) instance -- no new connection was opened
      expect(lastWsInstance).toBe(instanceBeforeTimer);
    });
  });

  describe('auto-reconnect with exponential backoff', () => {
    it('schedules a reconnect with 1s delay after first unexpected close', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
      });

      // Unexpected close
      act(() => {
        lastWsInstance!.triggerClose();
      });

      expect(result.current.liveState.connectionStatus).toBe('disconnected');

      // Before the 1s delay fires, no new WebSocket should exist
      const instanceAfterClose = lastWsInstance;
      act(() => {
        vi.advanceTimersByTime(999);
      });
      expect(lastWsInstance).toBe(instanceAfterClose);

      // After 1s, a new WebSocket is created
      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(lastWsInstance).not.toBe(instanceAfterClose);
      expect(lastWsInstance?.url).toBe('ws://localhost:7329/ws');
    });

    it('doubles the delay for successive reconnect attempts', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });

      // First close -> 1s delay
      act(() => {
        lastWsInstance!.triggerClose();
      });
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      // Second close -> 2s delay
      act(() => {
        lastWsInstance!.triggerClose();
      });
      const instanceAfterSecondClose = lastWsInstance;
      act(() => {
        vi.advanceTimersByTime(1999);
      });
      expect(lastWsInstance).toBe(instanceAfterSecondClose); // not reconnected yet
      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(lastWsInstance).not.toBe(instanceAfterSecondClose); // reconnected
    });

    it('caps backoff delay at 30 seconds', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });

      // Simulate 6 consecutive failures (beyond the backoff table)
      for (let i = 0; i < 6; i++) {
        act(() => {
          lastWsInstance!.triggerClose();
        });
        act(() => {
          vi.advanceTimersByTime(30000); // advance by max delay
        });
      }

      // Seventh close -- should still reconnect within 30s, not longer
      act(() => {
        lastWsInstance!.triggerClose();
      });
      const instanceBeforeTimer = lastWsInstance;
      act(() => {
        vi.advanceTimersByTime(29999);
      });
      expect(lastWsInstance).toBe(instanceBeforeTimer); // not yet

      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(lastWsInstance).not.toBe(instanceBeforeTimer); // reconnected within 30s
    });

    it('resets retry count after a successful connection', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });

      // Close and reconnect once (1s delay)
      act(() => {
        lastWsInstance!.triggerClose();
      });
      act(() => {
        vi.advanceTimersByTime(1000);
      });

      // Successful open on reconnect
      act(() => {
        lastWsInstance!.triggerOpen();
      });

      // Another close -- should use 1s again (reset) not 2s
      act(() => {
        lastWsInstance!.triggerClose();
      });
      const instanceAfterThirdClose = lastWsInstance;
      act(() => {
        vi.advanceTimersByTime(999);
      });
      expect(lastWsInstance).toBe(instanceAfterThirdClose); // not yet

      act(() => {
        vi.advanceTimersByTime(1);
      });
      expect(lastWsInstance).not.toBe(instanceAfterThirdClose); // reconnected at 1s
    });
  });

  describe('sendMessage()', () => {
    it('sends JSON to the open WebSocket', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
      });
      act(() => {
        result.current.sendMessage({ type: 'ping' });
      });
      expect(lastWsInstance!.sentMessages).toHaveLength(1);
      expect(JSON.parse(lastWsInstance!.sentMessages[0])).toEqual({ type: 'ping' });
    });

    it('no-ops when WebSocket is not open', () => {
      const { result } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      // Do not trigger open -- still in CONNECTING state
      act(() => {
        result.current.sendMessage({ type: 'ping' });
      });
      expect(lastWsInstance!.sentMessages).toHaveLength(0);
    });
  });

  describe('useLiveServer outside provider', () => {
    it('throws a descriptive error', () => {
      // Suppress the expected React error boundary noise in test output
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      expect(() => {
        renderHook(() => useLiveServer());
      }).toThrow('useLiveServer must be called within a LiveServerProvider');
      consoleSpy.mockRestore();
    });
  });

  describe('cleanup on unmount', () => {
    it('closes WebSocket when provider unmounts', () => {
      const { result, unmount } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
      });

      const ws = lastWsInstance!;
      unmount();

      // WebSocket should be closed after unmount
      expect(ws.readyState).toBe(MockWebSocket.CLOSED);
    });

    it('does not reconnect after unmount', () => {
      const { result, unmount } = renderHook(() => useLiveServer(), { wrapper });
      act(() => {
        result.current.connect('ws://localhost:7329/ws');
      });
      act(() => {
        lastWsInstance!.triggerOpen();
      });

      unmount();

      const instanceAtUnmount = lastWsInstance;
      // Advance timers -- no new connections should appear
      act(() => {
        vi.advanceTimersByTime(60000);
      });
      expect(lastWsInstance).toBe(instanceAtUnmount);
    });
  });
});
