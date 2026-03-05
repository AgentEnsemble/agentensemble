/**
 * TypeScript types for the AgentEnsemble ExecutionTrace JSON schema (version 1.1).
 *
 * These types mirror the Java classes in net.agentensemble.trace and allow the
 * trace viewer to parse and render execution trace files produced by JsonTraceExporter.
 */

export interface MemoryOperationCounts {
  stmWrites: number;
  ltmStores: number;
  ltmRetrievals: number;
  entityLookups: number;
}

export interface CostEstimate {
  inputCost: string;
  outputCost: string;
  totalCost: string;
  currency: string;
}

export interface TaskMetrics {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  /** ISO-8601 duration string, e.g. "PT1.234S" */
  llmLatency: string;
  toolExecutionTime: string;
  memoryRetrievalTime: string;
  promptBuildTime: string;
  llmCallCount: number;
  toolCallCount: number;
  delegationCount: number;
  memoryOperations: MemoryOperationCounts;
  costEstimate: CostEstimate | null;
}

export interface ExecutionMetrics {
  totalInputTokens: number;
  totalOutputTokens: number;
  totalTokens: number;
  totalLlmLatency: string;
  totalToolExecutionTime: string;
  totalMemoryRetrievalTime: string;
  totalPromptBuildTime: string;
  totalLlmCallCount: number;
  totalToolCalls: number;
  totalDelegations: number;
  memoryOperations: MemoryOperationCounts;
  taskMetrics: Record<string, TaskMetrics>;
  totalCostEstimate: CostEstimate | null;
}

export interface CapturedMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  toolCalls: Array<{ name: string; arguments: string }>;
  toolName: string | null;
}

export type ToolCallOutcome = 'SUCCESS' | 'ERROR' | 'SKIPPED';

export interface ToolCallTrace {
  toolName: string;
  arguments: string;
  result: string | null;
  structuredOutput: unknown;
  startedAt: string;
  completedAt: string;
  duration: string;
  outcome: ToolCallOutcome;
  parsedInput: Record<string, unknown> | null;
  metadata: Record<string, unknown>;
}

export type LlmResponseType = 'TOOL_CALLS' | 'FINAL_ANSWER';

export interface LlmInteraction {
  iterationIndex: number;
  startedAt: string;
  completedAt: string;
  latency: string;
  inputTokens: number;
  outputTokens: number;
  responseType: LlmResponseType;
  responseText: string | null;
  toolCalls: ToolCallTrace[];
  /** Populated when captureMode >= STANDARD */
  messages: CapturedMessage[];
}

export interface TaskPrompts {
  systemPrompt: string;
  userPrompt: string;
}

export interface DelegationTrace {
  delegatorRole: string;
  workerRole: string;
  taskDescription: string;
  startedAt: string;
  completedAt: string;
  duration: string;
  depth: number;
  result: string | null;
  succeeded: boolean;
  workerTrace: TaskTrace | null;
}

export interface TaskTrace {
  taskDescription: string;
  expectedOutput: string;
  agentRole: string;
  startedAt: string;
  completedAt: string;
  duration: string;
  prompts: TaskPrompts | null;
  llmInteractions: LlmInteraction[];
  delegations: DelegationTrace[];
  finalOutput: string;
  parsedOutput: unknown;
  metrics: TaskMetrics;
  /**
   * Map-reduce node type for adaptive runs.
   * One of "map", "reduce", or "final-reduce".
   * Absent for tasks executed outside of an adaptive map-reduce run.
   */
  nodeType?: 'map' | 'reduce' | 'final-reduce';
  /**
   * Map-reduce tree level for adaptive runs.
   * 0 = map phase, 1+ = reduce levels.
   * Absent for tasks executed outside of an adaptive map-reduce run.
   */
  mapReduceLevel?: number;
  metadata: Record<string, unknown>;
}

export interface AgentSummary {
  role: string;
  goal: string;
  background: string | null;
  toolNames: string[];
  allowDelegation: boolean;
}

export interface ErrorTrace {
  agentRole: string;
  taskDescription: string;
  errorType: string;
  message: string;
  stackTrace: string;
  occurredAt: string;
}

export type CaptureMode = 'OFF' | 'STANDARD' | 'FULL';
export type Workflow = 'SEQUENTIAL' | 'PARALLEL' | 'HIERARCHICAL' | 'MAP_REDUCE_ADAPTIVE';

/**
 * Per-level summary for an adaptive map-reduce run.
 *
 * Populated in {@link ExecutionTrace.mapReduceLevels} when the trace was produced
 * by an adaptive {@code MapReduceEnsemble} run.
 */
export interface MapReduceLevelSummary {
  /** Zero-based level index. 0 = map phase, 1+ = reduce levels. */
  level: number;
  /** Number of tasks that ran at this level. */
  taskCount: number;
  /** Wall-clock duration of this level's Ensemble.run() call (ISO-8601). */
  duration: string;
  /** Workflow type used at this level. Always "PARALLEL" for adaptive runs. */
  workflow: string;
}

export interface ExecutionTrace {
  schemaVersion: string;
  captureMode: CaptureMode;
  ensembleId: string;
  workflow: Workflow | string;
  startedAt: string;
  completedAt: string;
  totalDuration: string;
  inputs: Record<string, string>;
  agents: AgentSummary[];
  taskTraces: TaskTrace[];
  metrics: ExecutionMetrics;
  totalCostEstimate: CostEstimate | null;
  errors: ErrorTrace[];
  /**
   * Per-level summaries for adaptive map-reduce runs.
   * Empty for standard (non-adaptive) runs.
   */
  mapReduceLevels: MapReduceLevelSummary[];
  metadata: Record<string, unknown>;
}
