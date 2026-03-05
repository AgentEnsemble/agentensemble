import { describe, it, expect } from 'vitest';
import {
  detectFileType,
  parseJsonFile,
  parseDurationMs,
  formatDuration,
  formatTokenCount,
} from '../utils/parser.js';

describe('detectFileType', () => {
  it('returns null for null input', () => {
    expect(detectFileType(null)).toBeNull();
  });

  it('returns null for non-object input', () => {
    expect(detectFileType('string')).toBeNull();
    expect(detectFileType(42)).toBeNull();
    expect(detectFileType([])).toBeNull();
  });

  it('returns "dag" when type field is "dag"', () => {
    expect(detectFileType({ type: 'dag', workflow: 'PARALLEL' })).toBe('dag');
  });

  it('returns "trace" when taskTraces and ensembleId fields are present', () => {
    expect(detectFileType({ taskTraces: [], ensembleId: 'abc-123', workflow: 'SEQUENTIAL' })).toBe(
      'trace',
    );
  });

  it('returns null for unrecognized object shape', () => {
    expect(detectFileType({ foo: 'bar' })).toBeNull();
  });

  it('type=dag takes precedence over trace fields', () => {
    expect(detectFileType({ type: 'dag', taskTraces: [], ensembleId: 'x' })).toBe('dag');
  });
});

describe('parseJsonFile', () => {
  it('throws on invalid JSON', () => {
    expect(() => parseJsonFile('test.json', '{ not valid json }')).toThrow(
      'Failed to parse "test.json" as JSON',
    );
  });

  it('throws on unrecognized file shape', () => {
    expect(() => parseJsonFile('test.json', '{"foo":"bar"}')).toThrow(
      'not a recognized AgentEnsemble file',
    );
  });

  it('parses a DAG file correctly', () => {
    const dag = {
      type: 'dag',
      schemaVersion: '1.0',
      workflow: 'PARALLEL',
      generatedAt: '2026-03-05T09:00:00Z',
      agents: [],
      tasks: [],
      parallelGroups: [],
      criticalPath: [],
    };
    const result = parseJsonFile('my-run.dag.json', JSON.stringify(dag));
    expect(result.type).toBe('dag');
    expect(result.name).toBe('my-run.dag.json');
    expect(result.dag).toBeDefined();
    expect(result.dag?.workflow).toBe('PARALLEL');
  });

  it('parses a trace file correctly', () => {
    const trace = {
      schemaVersion: '1.1',
      ensembleId: 'run-123',
      workflow: 'SEQUENTIAL',
      captureMode: 'OFF',
      startedAt: '2026-03-05T09:00:00Z',
      completedAt: '2026-03-05T09:00:05Z',
      totalDuration: 'PT5S',
      inputs: {},
      agents: [],
      taskTraces: [],
      metrics: {},
      errors: [],
      metadata: {},
    };
    const result = parseJsonFile('my-run.trace.json', JSON.stringify(trace));
    expect(result.type).toBe('trace');
    expect(result.name).toBe('my-run.trace.json');
    expect(result.trace).toBeDefined();
    expect(result.trace?.ensembleId).toBe('run-123');
  });
});

describe('parseDurationMs', () => {
  it('returns NaN for null', () => {
    expect(parseDurationMs(null)).toBeNaN();
  });

  it('returns NaN for undefined', () => {
    expect(parseDurationMs(undefined)).toBeNaN();
  });

  it('returns NaN for empty string', () => {
    expect(parseDurationMs('')).toBeNaN();
  });

  it('returns NaN for invalid format', () => {
    expect(parseDurationMs('not-a-duration')).toBeNaN();
    expect(parseDurationMs('P1D')).toBeNaN(); // days not supported
  });

  it('parses seconds correctly', () => {
    expect(parseDurationMs('PT5S')).toBe(5000);
    expect(parseDurationMs('PT1.5S')).toBe(1500);
    expect(parseDurationMs('PT0.1S')).toBeCloseTo(100, 0);
  });

  it('parses minutes correctly', () => {
    expect(parseDurationMs('PT2M')).toBe(120000);
    expect(parseDurationMs('PT1M30S')).toBe(90000);
  });

  it('parses hours correctly', () => {
    expect(parseDurationMs('PT1H')).toBe(3600000);
    expect(parseDurationMs('PT1H30M')).toBe(5400000);
  });

  it('parses combined hours, minutes, seconds', () => {
    expect(parseDurationMs('PT1H2M3.5S')).toBe((3600 + 120 + 3.5) * 1000);
  });
});

describe('formatDuration', () => {
  it('returns "-" for NaN', () => {
    expect(formatDuration(NaN)).toBe('-');
  });

  it('returns "-" for negative', () => {
    expect(formatDuration(-1)).toBe('-');
  });

  it('returns "-" for Infinity', () => {
    expect(formatDuration(Infinity)).toBe('-');
  });

  it('formats milliseconds (< 1000)', () => {
    expect(formatDuration(0)).toBe('0ms');
    expect(formatDuration(500)).toBe('500ms');
    expect(formatDuration(999)).toBe('999ms');
  });

  it('formats seconds (1s - 59s)', () => {
    expect(formatDuration(1000)).toBe('1.0s');
    expect(formatDuration(1500)).toBe('1.5s');
    expect(formatDuration(59999)).toBe('60.0s');
  });

  it('formats minutes and seconds (>= 60s)', () => {
    expect(formatDuration(60000)).toBe('1m 0.0s');
    expect(formatDuration(90000)).toBe('1m 30.0s');
    expect(formatDuration(3661000)).toBe('61m 1.0s');
  });
});

describe('formatTokenCount', () => {
  it('returns "?" for -1 (unknown)', () => {
    expect(formatTokenCount(-1)).toBe('?');
  });

  it('formats small counts directly', () => {
    expect(formatTokenCount(0)).toBe('0');
    expect(formatTokenCount(100)).toBe('100');
    expect(formatTokenCount(999)).toBe('999');
  });

  it('formats thousands with k suffix', () => {
    expect(formatTokenCount(1000)).toBe('1.0k');
    expect(formatTokenCount(1500)).toBe('1.5k');
    expect(formatTokenCount(999999)).toBe('1000.0k');
  });

  it('formats millions with M suffix', () => {
    expect(formatTokenCount(1_000_000)).toBe('1.0M');
    expect(formatTokenCount(2_500_000)).toBe('2.5M');
  });
});
