/**
 * TypeScript types for the AgentEnsemble DagModel JSON schema (version 1.3).
 *
 * These types mirror the Java classes in net.agentensemble.devtools.dag and allow
 * the trace viewer to parse and render DAG files produced by DagExporter.
 *
 * Schema history:
 *   1.0 -- initial release
 *   1.1 -- added mapReduceMode on DagModel and nodeType/mapReduceLevel on DagTaskNode
 *   1.2 -- added "loop" nodeType for Loop super-nodes with loopMaxIterations and
 *          loopBody (nested DagTaskNode list rendered as a collapsible sub-DAG)
 *   1.3 -- added mode = "graph" for state-machine ensembles, plus "graph-state" /
 *          "graph-end" nodeType values and the graphEdges list with conditional /
 *          fired metadata
 */

import type { Workflow } from './trace.js';

export interface DagAgentNode {
  role: string;
  goal: string;
  background: string | null;
  toolNames: string[];
  allowDelegation: boolean;
}

export interface DagTaskNode {
  id: string;
  description: string;
  expectedOutput: string;
  agentRole: string;
  /** IDs of tasks this task depends on. Empty for root tasks. */
  dependsOn: string[];
  /** Topological level (0 = root, N = level N). Tasks at the same level can run in parallel. */
  parallelGroup: number;
  /** Whether this task is on the critical path (longest chain through the DAG). */
  onCriticalPath: boolean;
  /**
   * Node type discriminator. null for standard tasks; 'map'/'reduce'/'final-reduce' for
   * MapReduceEnsemble DAGs (schema 1.1+); 'loop' for Loop super-nodes (schema 1.2+);
   * 'graph-state' for Graph state nodes and 'graph-end' for the implicit terminal
   * sentinel (schema 1.3+).
   */
  nodeType?:
    | 'map'
    | 'reduce'
    | 'final-reduce'
    | 'direct'
    | 'loop'
    | 'graph-state'
    | 'graph-end';
  /**
   * Map-reduce tree level: 0 = map, 1+ = reduce levels. Populated only for
   * MapReduceEnsemble DAGs (schema 1.1+). null or undefined for standard tasks.
   */
  mapReduceLevel?: number;
  /**
   * For nodeType === 'loop' (schema 1.2+): the configured maxIterations cap.
   * null or undefined for non-loop nodes.
   */
  loopMaxIterations?: number;
  /**
   * For nodeType === 'loop' (schema 1.2+): the body tasks rendered as nested
   * DagTaskNodes. Visualization tools should render the loop as a collapsible
   * super-node containing this sub-DAG. null or undefined for non-loop nodes.
   */
  loopBody?: DagTaskNode[];
}

export interface DagModel {
  schemaVersion: string;
  type: 'dag';
  workflow: Workflow;
  /** ISO-8601 instant when this model was generated */
  generatedAt: string;
  agents: DagAgentNode[];
  tasks: DagTaskNode[];
  /** Topological levels: index = level, value = list of task IDs at that level */
  parallelGroups: string[][];
  /** Ordered task IDs forming the longest sequential chain */
  criticalPath: string[];
  /**
   * Map-reduce reduction strategy. Populated only for MapReduceEnsemble DAGs (schema 1.1+).
   * null or undefined for standard ensembles.
   */
  mapReduceMode?: 'STATIC' | 'ADAPTIVE';

  /**
   * Top-level shape discriminator. null/undefined for legacy DAG ensembles; 'graph' for
   * Graph state-machine ensembles (schema 1.3+). When 'graph', graphEdges is populated
   * and tasks carry state nodes ({@code nodeType: 'graph-state'}) plus a terminal
   * 'graph-end' node.
   */
  mode?: 'graph';

  /**
   * Edge list for graph ensembles. Populated when mode === 'graph'. null/undefined for
   * non-graph ensembles.
   */
  graphEdges?: DagGraphEdge[];

  /** Start-state id for graph ensembles. null/undefined otherwise. */
  graphStartStateId?: string;

  /**
   * Termination reason for graph ensembles, populated post-execution by
   * DagExporter.build(graph, graphTrace). 'terminal' or 'maxSteps'. null pre-execution.
   */
  graphTerminationReason?: 'terminal' | 'maxSteps';

  /** Number of steps actually run, populated post-execution. null pre-execution. */
  graphStepsRun?: number;
}

/**
 * Edge in a graph-mode DagModel (schema 1.3+).
 */
export interface DagGraphEdge {
  /** Source state id. */
  fromStateId: string;
  /** Target state id, or the implicit terminal sentinel. */
  toStateId: string;
  /**
   * Human-readable description of the condition (used as the rendered edge label).
   * null for unconditional edges and for conditional edges without a supplied label.
   */
  conditionDescription?: string | null;
  /** True for unconditional fallback edges (no predicate). */
  unconditional: boolean;
  /**
   * Whether this edge was traversed at least once during execution. Populated
   * post-execution only; false pre-execution.
   */
  fired: boolean;
}
