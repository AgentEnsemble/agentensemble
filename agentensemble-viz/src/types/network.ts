/**
 * TypeScript types for the AgentEnsemble multi-ensemble network dashboard.
 *
 * The network dashboard connects to multiple ensembles simultaneously and shows
 * their status, capabilities, and connections in a topology graph.
 */

import type { ConnectionStatus } from './live.js';

// ========================
// Network ensemble state
// ========================

/** Shared capability advertised by an ensemble. */
export interface SharedCapability {
  name: string;
  description: string;
  type: 'TASK' | 'TOOL';
}

/** State of a single ensemble in the network. */
export interface NetworkEnsemble {
  /** Human-readable name of this ensemble. */
  name: string;
  /** WebSocket URL for this ensemble. */
  wsUrl: string;
  /** Current WebSocket connection status. */
  connectionStatus: ConnectionStatus;
  /** Lifecycle state from /api/status (STARTING, READY, DRAINING, STOPPED). */
  lifecycleState: string | null;
  /** Current ensemble run ID. */
  ensembleId: string | null;
  /** Workflow strategy (SEQUENTIAL, PARALLEL, HIERARCHICAL). */
  workflow: string | null;
  /** Total task count for the current run. */
  taskCount: number;
  /** Number of currently active tasks. */
  activeTasks: number;
  /** Number of completed tasks in the current run. */
  completedTasks: number;
  /** Number of queued work requests. */
  queueDepth: number;
  /** Shared capabilities advertised by this ensemble. */
  sharedCapabilities: SharedCapability[];
}

/** A connection (edge) between two ensembles in the network topology. */
export interface NetworkConnection {
  /** Source ensemble name. */
  from: string;
  /** Target ensemble name. */
  to: string;
  /** Label for the edge (e.g., shared task/tool name). */
  label: string;
}

// ========================
// Network state
// ========================

/** Full state of the multi-ensemble network dashboard. */
export interface NetworkState {
  /** Ensembles keyed by name. */
  ensembles: Record<string, NetworkEnsemble>;
  /** Known connections between ensembles. */
  connections: NetworkConnection[];
  /** Currently selected ensemble name (for sidebar detail panel). */
  selectedEnsemble: string | null;
}

// ========================
// Network actions
// ========================

export type NetworkAction =
  | { type: 'ADD_ENSEMBLE'; name: string; wsUrl: string }
  | { type: 'REMOVE_ENSEMBLE'; name: string }
  | { type: 'SET_CONNECTION_STATUS'; name: string; status: ConnectionStatus }
  | { type: 'UPDATE_STATUS'; name: string; status: Partial<NetworkEnsemble> }
  | { type: 'SELECT_ENSEMBLE'; name: string | null }
  | { type: 'ADD_CONNECTION'; connection: NetworkConnection };
