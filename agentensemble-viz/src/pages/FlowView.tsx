import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  ReactFlowProvider,
} from '@xyflow/react';
import type { NodeTypes, Node, NodeMouseHandler } from '@xyflow/react';
import type { DagModel } from '../types/dag.js';
import type { ExecutionTrace, TaskTrace } from '../types/trace.js';
import TaskNode from '../components/graph/TaskNode.js';
import DetailPanel from '../components/shared/DetailPanel.js';
import { layoutDagGraph, type TaskNodeData, type TaskFlowNode } from '../utils/graphLayout.js';
import { seedAgentColors, getAgentColor } from '../utils/colors.js';

// nodeTypes must be declared outside the component to prevent React re-renders
const nodeTypes: NodeTypes = {
  taskNode: TaskNode as NodeTypes[string],
};

interface FlowViewProps {
  dag: DagModel;
  trace: ExecutionTrace | null;
}

/**
 * Flow View page: renders the task dependency graph using ReactFlow.
 *
 * Shows tasks as colored nodes organized in a left-to-right dagre layout.
 * Clicking a node opens the detail panel. Critical path edges are highlighted red.
 * When a trace is also loaded, nodes show execution duration and token counts.
 */
export default function FlowView({ dag, trace }: FlowViewProps) {
  return (
    <ReactFlowProvider>
      <FlowViewInner dag={dag} trace={trace} />
    </ReactFlowProvider>
  );
}

function FlowViewInner({ dag, trace }: FlowViewProps) {
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [showMinimap, setShowMinimap] = useState(true);

  // Build a map of "agentRole:taskDescription" -> TaskTrace for trace enrichment.
  // Using a composite key prevents key collisions when the same agent runs multiple
  // tasks (a plain agentRole key would overwrite earlier entries with later ones).
  const traceByAgentRole = useMemo(() => {
    if (!trace) return undefined;
    const map = new Map<string, TaskTrace>();
    for (const taskTrace of trace.taskTraces) {
      map.set(`${taskTrace.agentRole}:${taskTrace.taskDescription}`, taskTrace);
    }
    return map;
  }, [trace]);

  // Seed agent colors from the DAG agents list
  useEffect(() => {
    seedAgentColors(dag.agents.map((a) => a.role));
  }, [dag]);

  // Compute initial layout
  const { nodes: initialNodes, edges: initialEdges } = useMemo(
    () => layoutDagGraph(dag, selectedTaskId, traceByAgentRole),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dag, traceByAgentRole],
  );

  const [nodes, setNodes, onNodesChange] = useNodesState<TaskFlowNode>(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // Re-layout when DAG or selection changes
  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = layoutDagGraph(
      dag,
      selectedTaskId,
      traceByAgentRole,
    );
    setNodes(newNodes);
    setEdges(newEdges);
  }, [dag, selectedTaskId, traceByAgentRole, setNodes, setEdges]);

  // Handle node click: toggle selection and open detail panel
  const onNodeClick: NodeMouseHandler = useCallback((_event, node: Node) => {
    setSelectedTaskId((prev) => (prev === node.id ? null : node.id));
  }, []);

  const selectedTask = selectedTaskId
    ? dag.tasks.find((t) => t.id === selectedTaskId) ?? null
    : null;

  const selectedTaskTrace =
    selectedTask && traceByAgentRole
      ? traceByAgentRole.get(`${selectedTask.agentRole}:${selectedTask.description}`) ?? null
      : null;

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* ReactFlow canvas */}
      <div className="relative flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeClick={onNodeClick}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.3}
          maxZoom={2}
          proOptions={{ hideAttribution: true }}
          className="bg-gray-50 dark:bg-gray-950"
        >
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#D1D5DB" />
          <Controls />
          {showMinimap && (
            <MiniMap
              nodeColor={(node) => {
                const data = node.data as TaskNodeData;
                return data.agentColor.bg;
              }}
              maskColor="rgba(0,0,0,0.1)"
            />
          )}
        </ReactFlow>

        {/* Toolbar overlay */}
        <div className="absolute left-3 top-3 z-10 flex gap-2">
          {/* Run summary */}
          <div className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900">
            <span className="font-semibold text-gray-700 dark:text-gray-300">{dag.workflow}</span>
            <span className="ml-2 text-gray-500">
              {dag.tasks.length} tasks &middot; {dag.agents.length} agents &middot;{' '}
              {dag.parallelGroups.length} levels
            </span>
          </div>

          {/* Agent legend */}
          <div className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-3 py-2 shadow-sm dark:border-gray-700 dark:bg-gray-900">
            {dag.agents.map((agent) => {
              const color = getAgentColor(agent.role);
              return (
                <span key={agent.role} title={agent.goal} className="flex items-center gap-1 text-xs">
                  <span
                    className="h-2.5 w-2.5 rounded-full"
                    style={{ backgroundColor: color.bg }}
                  />
                  <span className="text-gray-700 dark:text-gray-300">{agent.role}</span>
                </span>
              );
            })}
          </div>
        </div>

        {/* Controls overlay */}
        <div className="absolute right-3 top-3 z-10 flex flex-col gap-1.5">
          <button
            onClick={() => setShowMinimap((v) => !v)}
            className="rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-600 shadow-sm hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-gray-700"
          >
            {showMinimap ? 'Hide' : 'Show'} minimap
          </button>
        </div>

        {/* Critical path legend */}
        {dag.criticalPath.length > 0 && (
          <div className="absolute bottom-14 left-3 z-10 flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900">
            <span className="h-2 w-6 rounded" style={{ backgroundColor: '#EF4444' }} />
            <span className="text-gray-500">Critical path ({dag.criticalPath.length} tasks)</span>
          </div>
        )}
      </div>

      {/* Detail panel */}
      {selectedTask && (
        <DetailPanel
          task={selectedTask}
          taskTrace={selectedTaskTrace}
          agentInfo={dag.agents.find((a) => a.role === selectedTask.agentRole) ?? null}
          onClose={() => setSelectedTaskId(null)}
        />
      )}
    </div>
  );
}
