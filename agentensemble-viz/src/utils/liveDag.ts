/**
 * Utilities for building synthetic ReactFlow graph data from live execution state.
 *
 * In live mode, a DagModel is not available (no .dag.json file was loaded).
 * These utilities construct a synthetic DagModel from the LiveState so that the
 * existing FlowView layout and rendering infrastructure can be reused.
 */

import type { DagModel, DagAgentNode, DagTaskNode } from '../types/dag.js';
import type { LiveState, LiveTaskStatus } from '../types/live.js';
import type { Workflow } from '../types/trace.js';

/** Schema version used for synthetic models. Not written to disk; internal only. */
const SYNTHETIC_SCHEMA_VERSION = 'live-1.0';

/**
 * Build a synthetic DagModel from the current live state.
 *
 * Tasks appear in the model in task arrival order (the order task_started
 * messages were received). Dependencies are inferred from the workflow type:
 * - SEQUENTIAL: each task depends on the previous task (linear chain)
 * - PARALLEL / all others: all tasks are independent (no dependsOn)
 *
 * @param liveState Current live state
 * @returns A DagModel compatible with layoutDagGraph
 */
export function buildSyntheticDagModel(liveState: LiveState): DagModel {
  const workflow = (liveState.workflow ?? 'SEQUENTIAL') as Workflow;
  const isSequential = workflow === 'SEQUENTIAL';

  // Collect unique agent roles in task arrival order
  const seenRoles = new Set<string>();
  const agents: DagAgentNode[] = [];
  for (const task of liveState.tasks) {
    if (!seenRoles.has(task.agentRole)) {
      seenRoles.add(task.agentRole);
      agents.push({
        role: task.agentRole,
        // Goal not available in live mode -- use role as placeholder
        goal: task.agentRole,
        background: null,
        toolNames: [],
        allowDelegation: false,
      });
    }
  }

  // Build task nodes. IDs are deterministic: live-task-<taskIndex>
  const tasks: DagTaskNode[] = liveState.tasks.map((task, arrayIndex) => {
    const id = liveTaskNodeId(task.taskIndex);
    const prevId =
      isSequential && arrayIndex > 0
        ? liveTaskNodeId(liveState.tasks[arrayIndex - 1].taskIndex)
        : null;

    return {
      id,
      description: task.taskDescription,
      expectedOutput: '',
      agentRole: task.agentRole,
      dependsOn: prevId ? [prevId] : [],
      parallelGroup: isSequential ? arrayIndex : 0,
      onCriticalPath: false,
    };
  });

  // Build parallelGroups: one level per task for sequential, one level for all for parallel
  const parallelGroups: string[][] = isSequential
    ? tasks.map((t) => [t.id])
    : tasks.length > 0
      ? [tasks.map((t) => t.id)]
      : [];

  return {
    schemaVersion: SYNTHETIC_SCHEMA_VERSION,
    type: 'dag',
    workflow,
    generatedAt: liveState.startedAt ?? new Date().toISOString(),
    agents,
    tasks,
    parallelGroups,
    criticalPath: [],
  };
}

/**
 * Build a map from synthetic task node ID -> LiveTaskStatus.
 *
 * Used to set the `liveStatus` field on ReactFlow node data, which drives the
 * node's visual appearance (running = blue pulse, failed = red).
 *
 * Completed tasks are NOT included in the map: when `liveStatus` is undefined,
 * the TaskNode renders in its normal agent color (the same as historical mode).
 */
export function buildLiveStatusMap(liveState: LiveState): Map<string, LiveTaskStatus | undefined> {
  const map = new Map<string, LiveTaskStatus | undefined>();
  for (const task of liveState.tasks) {
    const nodeId = liveTaskNodeId(task.taskIndex);
    // Only include running and failed -- completed nodes use the default (undefined) rendering
    if (task.status === 'running' || task.status === 'failed') {
      map.set(nodeId, task.status);
    } else {
      map.set(nodeId, undefined);
    }
  }
  return map;
}

/**
 * Derive the synthetic ReactFlow node ID for a live task.
 * Used consistently across buildSyntheticDagModel and buildLiveStatusMap.
 */
export function liveTaskNodeId(taskIndex: number): string {
  return `live-task-${taskIndex}`;
}
