/**
 * Hub reducer: top-level state machine for the distributed live dashboard.
 *
 * Routes every message to the appropriate per-producer {@code liveReducer}, keyed by
 * {@code producerId}. The per-producer reducer is reused unchanged; the hub reducer only
 * adds producer attribution.
 */

import type { ConnectionStatus, LiveState, ServerMessage } from '../types/live.js';
import type {
  HubServerMessage,
  HubState,
  LiveEventEnvelope,
  ProducerInfo,
} from '../types/hub.js';
import { initialLiveState, liveReducer } from './liveReducer.js';

/** Empty initial hub state. */
export const initialHubState: HubState = {
  connectionStatus: 'disconnected',
  serverUrl: null,
  producers: {},
  perProducer: {},
};

/** Action surface for the hub UI's reducer; mirrors LiveAction for symmetry. */
export type HubAction =
  | { type: 'CONNECTING'; serverUrl: string }
  | { type: 'CONNECTED' }
  | { type: 'DISCONNECTED' }
  | { type: 'ERROR'; error: string }
  | { type: 'RESET' }
  | { type: 'MESSAGE'; message: HubServerMessage };

function ensureProducer(state: HubState, info: ProducerInfo): HubState {
  if (state.producers[info.producerId] && state.perProducer[info.producerId]) {
    // Refresh the info in case metadata changed (e.g. new tags after reconnect).
    return {
      ...state,
      producers: { ...state.producers, [info.producerId]: info },
    };
  }
  return {
    ...state,
    producers: { ...state.producers, [info.producerId]: info },
    perProducer: {
      ...state.perProducer,
      [info.producerId]:
        state.perProducer[info.producerId] ?? { ...initialLiveState, connectionStatus: 'connected' },
    },
  };
}

function dispatchToProducer(state: HubState, producerId: string, inner: ServerMessage): HubState {
  const current = state.perProducer[producerId] ?? { ...initialLiveState, connectionStatus: 'connected' };
  const next: LiveState = liveReducer(current, inner);
  return {
    ...state,
    perProducer: { ...state.perProducer, [producerId]: next },
  };
}

export function hubReducer(state: HubState, message: HubServerMessage): HubState {
  switch (message.type) {
    case 'hub_hello': {
      let next: HubState = {
        ...state,
        connectionStatus: 'connected',
        producers: {},
        perProducer: {},
      };
      if (Array.isArray(message.producers)) {
        for (const p of message.producers) {
          next = ensureProducer(next, p);
        }
      }
      if (Array.isArray(message.snapshotTrace)) {
        for (const envelope of message.snapshotTrace as LiveEventEnvelope[]) {
          next = ensureProducer(next, envelope.producer);
          next = dispatchToProducer(next, envelope.producer.producerId, envelope.message);
        }
      }
      return next;
    }
    case 'event': {
      const envelope = message;
      let next = ensureProducer(state, envelope.producer);
      next = dispatchToProducer(next, envelope.producer.producerId, envelope.message);
      return next;
    }
    case 'producer_joined': {
      return ensureProducer(state, message.producer);
    }
    case 'producer_left': {
      const { [message.producerId]: _droppedProducer, ...remainingProducers } = state.producers;
      const { [message.producerId]: _droppedLive, ...remainingLive } = state.perProducer;
      return { ...state, producers: remainingProducers, perProducer: remainingLive };
    }
    default:
      return state;
  }
}

/** Action-style reducer wrapping hubReducer with connection-lifecycle actions. */
export function hubActionReducer(state: HubState, action: HubAction): HubState {
  switch (action.type) {
    case 'CONNECTING':
      return { ...state, connectionStatus: 'connecting' as ConnectionStatus, serverUrl: action.serverUrl };
    case 'CONNECTED':
      return { ...state, connectionStatus: 'connected' };
    case 'DISCONNECTED':
      return { ...state, connectionStatus: 'disconnected' };
    case 'ERROR':
      return { ...state, connectionStatus: 'error' };
    case 'RESET':
      return { ...initialHubState };
    case 'MESSAGE':
      return hubReducer(state, action.message);
    default:
      return state;
  }
}
