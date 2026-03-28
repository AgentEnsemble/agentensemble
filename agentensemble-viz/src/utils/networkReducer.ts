/**
 * State machine for the multi-ensemble network dashboard.
 *
 * networkReducer manages per-ensemble connection status, lifecycle state,
 * and topology connections. Each ensemble is independently tracked.
 */

import type { NetworkState, NetworkAction, NetworkEnsemble } from '../types/network.js';

/** Initial network state with no ensembles. */
export const initialNetworkState: NetworkState = {
  ensembles: {},
  connections: [],
  selectedEnsemble: null,
};

/** Create a default NetworkEnsemble entry for a newly added ensemble. */
function defaultEnsemble(name: string, wsUrl: string): NetworkEnsemble {
  return {
    name,
    wsUrl,
    connectionStatus: 'disconnected',
    lifecycleState: null,
    ensembleId: null,
    workflow: null,
    taskCount: 0,
    activeTasks: 0,
    completedTasks: 0,
    queueDepth: 0,
    sharedCapabilities: [],
  };
}

/** Pure reducer for network state transitions. */
export function networkReducer(state: NetworkState, action: NetworkAction): NetworkState {
  switch (action.type) {
    case 'ADD_ENSEMBLE':
      return {
        ...state,
        ensembles: {
          ...state.ensembles,
          [action.name]: defaultEnsemble(action.name, action.wsUrl),
        },
      };

    case 'REMOVE_ENSEMBLE': {
      const { [action.name]: _removed, ...remaining } = state.ensembles;
      return {
        ...state,
        ensembles: remaining,
        connections: state.connections.filter(
          (c) => c.from !== action.name && c.to !== action.name,
        ),
        selectedEnsemble:
          state.selectedEnsemble === action.name ? null : state.selectedEnsemble,
      };
    }

    case 'SET_CONNECTION_STATUS': {
      const existing = state.ensembles[action.name];
      if (!existing) return state;
      return {
        ...state,
        ensembles: {
          ...state.ensembles,
          [action.name]: { ...existing, connectionStatus: action.status },
        },
      };
    }

    case 'UPDATE_STATUS': {
      const existing = state.ensembles[action.name];
      if (!existing) return state;
      return {
        ...state,
        ensembles: {
          ...state.ensembles,
          [action.name]: { ...existing, ...action.status },
        },
      };
    }

    case 'SELECT_ENSEMBLE':
      return { ...state, selectedEnsemble: action.name };

    case 'ADD_CONNECTION':
      return {
        ...state,
        connections: [...state.connections, action.connection],
      };

    case 'TASK_STARTED': {
      const existing = state.ensembles[action.name];
      if (!existing) return state;
      return {
        ...state,
        ensembles: {
          ...state.ensembles,
          [action.name]: { ...existing, activeTasks: existing.activeTasks + 1 },
        },
      };
    }

    case 'TASK_ENDED': {
      const existing = state.ensembles[action.name];
      if (!existing) return state;
      return {
        ...state,
        ensembles: {
          ...state.ensembles,
          [action.name]: {
            ...existing,
            activeTasks: Math.max(0, existing.activeTasks - 1),
            completedTasks: existing.completedTasks + 1,
          },
        },
      };
    }

    default:
      return state;
  }
}
