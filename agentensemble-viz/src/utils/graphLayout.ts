/**
 * Graph layout utilities using dagre for automatic DAG positioning.
 *
 * Computes x,y positions for ReactFlow nodes based on the dependency structure
 * in a DagModel.
 */

import dagre from '@dagrejs/dagre';
import type { Node, Edge } from '@xyflow/react';
import type { DagTaskNode, DagModel } from '../types/dag.js';
import type { TaskTrace } from '../types/trace.js';
import type { LiveTaskStatus } from '../types/live.js';
import { getAgentColor } from './colors.js';

export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 90;

export interface TaskNodeData extends Record<string, unknown> {
  task: DagTaskNode;
  agentColor: ReturnType<typeof getAgentColor>;
  isSelected: boolean;
  traceData?: TaskTrace;
  /**
   * Live execution status for nodes rendered in live mode.
   * When present, overrides the node's visual appearance:
   *   - 'running'   -> blue pulsing header
   *   - 'failed'    -> red header
   * When absent (undefined), the node renders in its normal agent color.
   */
  liveStatus?: LiveTaskStatus;
}

/** Full ReactFlow node type for task nodes. */
export type TaskFlowNode = Node<TaskNodeData, 'taskNode'>;

/**
 * Compute ReactFlow nodes and edges from a DagModel using dagre layout.
 *
 * @param dag The DAG model to lay out
 * @param selectedNodeId The currently selected node ID (for highlight state)
 * @param traceByAgentRole Optional map of agent role -> TaskTrace for enriching nodes
 */
export function layoutDagGraph(
  dag: DagModel,
  selectedNodeId: string | null,
  traceByAgentRole?: Map<string, TaskTrace>,
): { nodes: TaskFlowNode[]; edges: Edge[] } {
  const isGraphMode = dag.mode === 'graph';

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    // Graph state machines look better top-to-bottom; legacy DAGs stay left-to-right
    rankdir: isGraphMode ? 'TB' : 'LR',
    nodesep: 50,
    ranksep: 80,
    edgesep: 20,
  });

  // Add all nodes
  for (const task of dag.tasks) {
    g.setNode(task.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }

  // Add edges. For graph-mode DAGs, edges live on the top-level `graphEdges` field
  // rather than per-task `dependsOn` lists.
  if (isGraphMode && dag.graphEdges) {
    for (const edge of dag.graphEdges) {
      g.setEdge(edge.fromStateId, edge.toStateId);
    }
  } else {
    for (const task of dag.tasks) {
      for (const depId of task.dependsOn) {
        g.setEdge(depId, task.id);
      }
    }
  }

  // Run dagre layout
  dagre.layout(g);

  // Convert to ReactFlow nodes.
  // Lookup trace data by composite key "agentRole:taskDescription" to handle the case
  // where the same agent runs multiple tasks (a plain agentRole key would lose all but
  // the last task's trace for that agent).
  const nodes: TaskFlowNode[] = dag.tasks.map((task) => {
    const pos = g.node(task.id);
    const traceKey = `${task.agentRole}:${task.description}`;
    return {
      id: task.id,
      type: 'taskNode' as const,
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
      data: {
        task,
        agentColor: getAgentColor(task.agentRole),
        isSelected: task.id === selectedNodeId,
        traceData: traceByAgentRole?.get(traceKey),
      },
    };
  });

  // Build set of critical-path edge IDs: only edges where source and target are
  // adjacent nodes in the critical path are considered critical.
  // Marking all incoming edges to a critical-path node as red is incorrect --
  // only the one edge that is actually on the critical path should be highlighted.
  const criticalEdgeSet = new Set<string>();
  for (let i = 0; i < dag.criticalPath.length - 1; i++) {
    criticalEdgeSet.add(`${dag.criticalPath[i]}->${dag.criticalPath[i + 1]}`);
  }

  // Convert to ReactFlow edges. Graph-mode DAGs render conditional edge labels and
  // grey out edges that did not fire post-execution (when fired metadata is present).
  let edges: Edge[];
  if (isGraphMode && dag.graphEdges) {
    // Edges with explicit `fired = false` from a post-execution export render greyed.
    // Pre-execution exports have fired = false everywhere; we treat that as "no styling"
    // by checking whether ANY edge has fired = true (signal that this is post-exec).
    // Compute once outside the map to keep edge styling O(E) instead of O(E^2).
    const anyFired = dag.graphEdges.some((e) => e.fired);
    edges = dag.graphEdges.map((edge) => {
      const edgeId = `${edge.fromStateId}->${edge.toStateId}`;
      const label = edge.unconditional
        ? '' // unconditional edges render bare
        : edge.conditionDescription ?? '(condition)';
      const isFiredOrPreExec = !anyFired || edge.fired;
      return {
        id: edgeId,
        source: edge.fromStateId,
        target: edge.toStateId,
        type: 'smoothstep',
        label,
        labelBgPadding: [6, 4] as [number, number],
        labelBgBorderRadius: 4,
        labelBgStyle: { fill: '#FFFFFF', fillOpacity: 0.85 },
        labelStyle: { fontSize: 11, fill: '#475569' },
        style: {
          stroke: isFiredOrPreExec ? '#3B82F6' : '#CBD5E1',
          strokeWidth: isFiredOrPreExec ? 1.5 : 1,
          strokeDasharray: edge.unconditional ? '4 4' : undefined,
        },
        animated: false,
      };
    });
  } else {
    edges = dag.tasks.flatMap((task) =>
      task.dependsOn.map((depId) => {
        const edgeId = `${depId}->${task.id}`;
        const isCritical = criticalEdgeSet.has(edgeId);
        return {
          id: edgeId,
          source: depId,
          target: task.id,
          type: 'smoothstep',
          style: {
            stroke: isCritical ? '#EF4444' : '#94A3B8',
            strokeWidth: isCritical ? 2 : 1.5,
          },
          animated: false,
        };
      }),
    );
  }

  return { nodes, edges };
}
