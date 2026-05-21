import { describe, expect, it } from 'vitest';
import { hubReducer, initialHubState } from '../utils/hubReducer.js';
import type {
  HubHelloMessage,
  LiveEventEnvelope,
  ProducerJoinedMessage,
  ProducerLeftMessage,
} from '../types/hub.js';

describe('hubReducer', () => {
  it('seeds producers and snapshot from hub_hello', () => {
    const hello: HubHelloMessage = {
      type: 'hub_hello',
      producers: [
        { producerId: 'p1', serviceName: 'svc' },
        { producerId: 'p2', serviceName: 'svc' },
      ],
      snapshotTrace: [
        {
          type: 'event',
          producer: { producerId: 'p1', serviceName: 'svc' },
          sequence: 1,
          receivedAt: '2026-05-21T10:00:00Z',
          message: {
            type: 'ensemble_started',
            ensembleId: 'run-1',
            startedAt: '2026-05-21T10:00:00Z',
            totalTasks: 1,
            workflow: 'SEQUENTIAL',
          },
        } as LiveEventEnvelope,
        {
          type: 'event',
          producer: { producerId: 'p1', serviceName: 'svc' },
          sequence: 2,
          receivedAt: '2026-05-21T10:00:01Z',
          message: {
            type: 'task_started',
            taskIndex: 1,
            totalTasks: 1,
            taskDescription: 'Task A',
            agentRole: 'Agent A',
            startedAt: '2026-05-21T10:00:01Z',
          },
        } as LiveEventEnvelope,
      ],
    };

    const next = hubReducer(initialHubState, hello);

    expect(Object.keys(next.producers).sort()).toEqual(['p1', 'p2']);
    expect(next.perProducer.p1.ensembleId).toBe('run-1');
    expect(next.perProducer.p1.tasks).toHaveLength(1);
    expect(next.perProducer.p1.tasks[0].taskDescription).toBe('Task A');
    expect(next.perProducer.p2).toBeDefined();
    expect(next.perProducer.p2.tasks).toHaveLength(0);
  });

  it('routes live event envelopes to the matching producer', () => {
    const seeded = hubReducer(initialHubState, {
      type: 'producer_joined',
      producer: { producerId: 'p1', serviceName: 'svc' },
      joinedAt: '2026-05-21T10:00:00Z',
    } as ProducerJoinedMessage);

    const envelope: LiveEventEnvelope = {
      type: 'event',
      producer: { producerId: 'p1', serviceName: 'svc' },
      sequence: 1,
      receivedAt: '2026-05-21T10:00:01Z',
      message: {
        type: 'ensemble_started',
        ensembleId: 'run-1',
        startedAt: '2026-05-21T10:00:01Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      },
    };
    const next = hubReducer(seeded, envelope);
    expect(next.perProducer.p1.ensembleId).toBe('run-1');
  });

  it('producer_left removes identity but retains LiveState for reconnect continuity', () => {
    let state = hubReducer(initialHubState, {
      type: 'producer_joined',
      producer: { producerId: 'p1', serviceName: 'svc' },
      joinedAt: '2026-05-21T10:00:00Z',
    } as ProducerJoinedMessage);
    state = hubReducer(state, {
      type: 'event',
      producer: { producerId: 'p1', serviceName: 'svc' },
      sequence: 1,
      receivedAt: '2026-05-21T10:00:01Z',
      message: {
        type: 'ensemble_started',
        ensembleId: 'run-1',
        startedAt: '2026-05-21T10:00:01Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      },
    } as LiveEventEnvelope);

    const left: ProducerLeftMessage = {
      type: 'producer_left',
      producerId: 'p1',
      leftAt: '2026-05-21T10:01:00Z',
      reason: 'disconnected',
    };
    const afterLeft = hubReducer(state, left);
    expect(afterLeft.producers.p1).toBeUndefined();
    // LiveState is kept so a re-join resumes against existing history.
    expect(afterLeft.perProducer.p1).toBeDefined();
    expect(afterLeft.perProducer.p1.ensembleId).toBe('run-1');
    expect(afterLeft.inactiveProducers.has('p1')).toBe(true);

    // Rejoining clears the inactive flag and refreshes producer metadata; LiveState
    // continues from where it left off.
    const rejoined = hubReducer(afterLeft, {
      type: 'producer_joined',
      producer: { producerId: 'p1', serviceName: 'svc', version: 'v2' },
      joinedAt: '2026-05-21T10:02:00Z',
    } as ProducerJoinedMessage);
    expect(rejoined.producers.p1.version).toBe('v2');
    expect(rejoined.inactiveProducers.has('p1')).toBe(false);
    expect(rejoined.perProducer.p1.ensembleId).toBe('run-1');
  });

  it('producer metadata refreshes on reconnect without losing live state', () => {
    let state = hubReducer(initialHubState, {
      type: 'producer_joined',
      producer: { producerId: 'p1', serviceName: 'svc', version: '1.0' },
      joinedAt: '2026-05-21T10:00:00Z',
    } as ProducerJoinedMessage);
    state = hubReducer(state, {
      type: 'event',
      producer: { producerId: 'p1', serviceName: 'svc' },
      sequence: 1,
      receivedAt: '2026-05-21T10:00:01Z',
      message: {
        type: 'ensemble_started',
        ensembleId: 'run-1',
        startedAt: '2026-05-21T10:00:01Z',
        totalTasks: 1,
        workflow: 'SEQUENTIAL',
      },
    } as LiveEventEnvelope);

    expect(state.perProducer.p1.ensembleId).toBe('run-1');

    state = hubReducer(state, {
      type: 'producer_joined',
      producer: { producerId: 'p1', serviceName: 'svc', version: '1.1' },
      joinedAt: '2026-05-21T10:02:00Z',
    } as ProducerJoinedMessage);
    expect(state.producers.p1.version).toBe('1.1');
    expect(state.perProducer.p1.ensembleId).toBe('run-1');
  });
});
