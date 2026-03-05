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

  // Convert to ReactFlow nodes
  const nodes: TaskFlowNode[] = dag.tasks.map((task) => {
    const pos = g.node(task.id);
    return {
      id: task.id,
      type: 'taskNode' as const,
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
      data: {
        task,
        agentColor: getAgentColor(task.agentRole),
        isSelected: task.id === selectedNodeId,
        traceData: traceByAgentRole?.get(task.agentRole),
      },
    };
  });

  // Convert to ReactFlow edges
  const edges: Edge[] = dag.tasks.flatMap((task) =>
    task.dependsOn.map((depId) => ({
      id: `${depId}->${task.id}`,
      source: depId,
      target: task.id,
      type: 'smoothstep',
      style: {
        stroke: task.onCriticalPath ? '#EF4444' : '#94A3B8',
        strokeWidth: task.onCriticalPath ? 2 : 1.5,
      },
      animated: false,
    })),
  );

  return { nodes, edges };
}
