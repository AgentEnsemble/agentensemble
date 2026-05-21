/**
 * TypeScript types for the distributed live-event hub dashboard.
 *
 * The hub aggregates AgentEnsemble live events from many publisher processes and serves a
 * unified WebSocket to the browser. Each event arriving at the browser is a
 * {@link LiveEventEnvelope} wrapping the existing single-process {@link ServerMessage}
 * payload with producer attribution.
 */

import type { ServerMessage, IterationSnapshot, LiveState, ConnectionStatus } from './live.js';

/** Identity + metadata for one producer. */
export interface ProducerInfo {
  producerId: string;
  serviceName?: string | null;
  instanceId?: string | null;
  host?: string | null;
  version?: string | null;
  tags?: Record<string, string> | null;
}

/** Wire envelope: every per-producer event arrives wrapped like this. */
export interface LiveEventEnvelope {
  type: 'event';
  producer: ProducerInfo;
  sequence: number;
  receivedAt: string;
  message: ServerMessage;
}

/** Browser late-join message sent by the hub on connect. */
export interface HubHelloMessage {
  type: 'hub_hello';
  producers?: ProducerInfo[] | null;
  /** JSON array of {@link LiveEventEnvelope}s across all retained producers. */
  snapshotTrace?: LiveEventEnvelope[] | null;
  iterationsByProducer?: Record<string, IterationSnapshot[]> | null;
}

/** Broadcast when a new producer attaches to the hub ingress. */
export interface ProducerJoinedMessage {
  type: 'producer_joined';
  producer: ProducerInfo;
  joinedAt: string;
}

/** Broadcast when a producer disconnects or is evicted. */
export interface ProducerLeftMessage {
  type: 'producer_left';
  producerId: string;
  leftAt: string;
  reason?: string | null;
}

/** Union of hub-specific server messages. */
export type HubServerMessage =
  | HubHelloMessage
  | LiveEventEnvelope
  | ProducerJoinedMessage
  | ProducerLeftMessage;

/** Hub-side aggregate state: per-producer live state plus producer roster. */
export interface HubState {
  connectionStatus: ConnectionStatus;
  serverUrl: string | null;
  /** Roster of currently known producers, keyed by producerId. */
  producers: Record<string, ProducerInfo>;
  /** Per-producer single-producer live state, keyed by producerId. Retained across
   * {@code producer_left} so a reconnecting producer resumes against existing history;
   * pair with {@link inactiveProducers} to render disconnected state in the UI. */
  perProducer: Record<string, LiveState>;
  /** producerIds the hub has marked as disconnected/evicted. Producer metadata in
   * {@link producers} is removed on {@code producer_left}; the per-producer LiveState in
   * {@link perProducer} is retained for the session so reconnects keep their history. */
  inactiveProducers: Set<string>;
}
