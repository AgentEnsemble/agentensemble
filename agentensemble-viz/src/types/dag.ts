/**
 * TypeScript types for the AgentEnsemble DagModel JSON schema (version 1.1).
 *
 * These types mirror the Java classes in net.agentensemble.devtools.dag and allow
 * the trace viewer to parse and render DAG files produced by DagExporter.
 *
 * Schema history:
 *   1.0 -- initial release
 *   1.1 -- added mapReduceMode on DagModel and nodeType/mapReduceLevel on DagTaskNode
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
   * Map-reduce node type. Populated only for MapReduceEnsemble DAGs (schema 1.1+).
   * null or undefined for standard tasks.
   */
  nodeType?: 'map' | 'reduce' | 'final-reduce' | 'direct';
  /**
   * Map-reduce tree level: 0 = map, 1+ = reduce levels. Populated only for
   * MapReduceEnsemble DAGs (schema 1.1+). null or undefined for standard tasks.
   */
  mapReduceLevel?: number;
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
}
