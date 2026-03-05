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
import { getAgentColor } from './colors.js';

export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 90;

export interface TaskNodeData extends Record<string, unknown> {
  task: DagTaskNode;
  agentColor: ReturnType<typeof getAgentColor>;
  isSelected: boolean;
  traceData?: TaskTrace;
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
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: 'LR', // Left-to-right layout
    nodesep: 50,
    ranksep: 80,
    edgesep: 20,
  });

  // Add all nodes
  for (const task of dag.tasks) {
    g.setNode(task.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }

  // Add all edges
  for (const task of dag.tasks) {
    for (const depId of task.dependsOn) {
      g.setEdge(depId, task.id);
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

  // Convert to ReactFlow edges
  const edges: Edge[] = dag.tasks.flatMap((task) =>
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

  return { nodes, edges };
}
