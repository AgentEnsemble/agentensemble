/**
 * Utilities for parsing and validating trace and DAG JSON files.
 */

import type { ExecutionTrace } from '../types/trace.js';
import type { DagModel } from '../types/dag.js';

export type FileType = 'trace' | 'dag';

export interface ParsedFile {
  type: FileType;
  name: string;
  trace?: ExecutionTrace;
  dag?: DagModel;
}

/**
 * Detect the file type from the parsed JSON object.
 *
 * A DagModel has `"type": "dag"`. An ExecutionTrace has `"taskTraces"` and `"ensembleId"`.
 */
export function detectFileType(json: unknown): FileType | null {
  if (!json || typeof json !== 'object') return null;
  const obj = json as Record<string, unknown>;

  if (obj['type'] === 'dag') return 'dag';
  if ('taskTraces' in obj && 'ensembleId' in obj) return 'trace';
  return null;
}

/**
 * Parse a JSON string into a typed ParsedFile, or throw a descriptive error.
 */
export function parseJsonFile(name: string, content: string): ParsedFile {
  let json: unknown;
  try {
    json = JSON.parse(content);
  } catch (e) {
    throw new Error(`Failed to parse "${name}" as JSON: ${String(e)}`);
  }

  const type = detectFileType(json);
  if (type === null) {
    throw new Error(
      `"${name}" is not a recognized AgentEnsemble file. ` +
        'Expected a .dag.json (DagModel) or .trace.json (ExecutionTrace).',
    );
  }

  if (type === 'dag') {
    return { type: 'dag', name, dag: json as DagModel };
  }
  return { type: 'trace', name, trace: json as ExecutionTrace };
}

/**
 * Read a File object and parse it as a trace or DAG file.
 */
export async function readAndParseFile(file: File): Promise<ParsedFile> {
  const content = await file.text();
  return parseJsonFile(file.name, content);
}

/**
 * Fetch and parse a file from the local CLI server.
 */
export async function fetchAndParseFile(fileName: string): Promise<ParsedFile> {
  const response = await fetch(`/api/file?name=${encodeURIComponent(fileName)}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch "${fileName}": HTTP ${response.status}`);
  }
  const content = await response.text();
  return parseJsonFile(fileName, content);
}

/**
 * Parse an ISO-8601 duration string (e.g. "PT1.234S") to milliseconds.
 * Returns NaN if the string is not a valid duration.
 */
export function parseDurationMs(iso: string | null | undefined): number {
  if (!iso) return NaN;
  // ISO 8601 duration: PT[hours]H[minutes]M[seconds]S
  const match = iso.match(
    /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/,
  );
  if (!match) return NaN;
  const hours = parseFloat(match[1] ?? '0') || 0;
  const minutes = parseFloat(match[2] ?? '0') || 0;
  const seconds = parseFloat(match[3] ?? '0') || 0;
  return (hours * 3600 + minutes * 60 + seconds) * 1000;
}

/**
 * Format a duration in milliseconds to a human-readable string.
 * Examples: "1.2s", "12ms", "3m 4.1s"
 */
export function formatDuration(ms: number): string {
  if (!isFinite(ms) || ms < 0) return '-';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(1)}s`;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs.toFixed(1)}s`;
}

/**
 * Format an ISO-8601 instant string to a short local time string.
 */
export function formatInstant(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString();
  } catch {
    return iso;
  }
}

/**
 * Format a token count. Returns "?" when the count is -1 (unknown).
 */
export function formatTokenCount(count: number): string {
  if (count === -1) return '?';
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}k`;
  return String(count);
}
