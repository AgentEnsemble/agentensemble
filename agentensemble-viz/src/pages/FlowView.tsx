import { useCallback, useEffect, useMemo, useState } from 'react';
import { initialLiveState } from '../utils/liveReducer.js';
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
import { useLiveServer } from '../contexts/LiveServerContext.js';
import { buildSyntheticDagModel, buildLiveStatusMap } from '../utils/liveDag.js';

// nodeTypes must be declared outside the component to prevent React re-renders
const nodeTypes: NodeTypes = {
  taskNode: TaskNode as NodeTypes[string],
};

interface FlowViewProps {
  dag: DagModel | null;
  trace: ExecutionTrace | null;
  /** When true, renders in live mode using LiveServerContext instead of a static dag. */
  isLive?: boolean;
  /** Optional callback when a node is clicked; receives the agent role of the clicked node. */
  onNodeClick?: (agentRole: string) => void;
}

/**
 * Flow View page: renders the task dependency graph using ReactFlow.
 *
 * When `isLive` is true, builds a synthetic DAG model from the live execution state
 * and overlays node status colors (pending -> gray; running -> blue pulsing;
 * completed -> agent color; failed -> red).
 *
 * When `isLive` is false (default), renders from the supplied static DagModel with
 * optional trace enrichment.
 */
export default function FlowView({ dag, trace, isLive, onNodeClick: onExternalNodeClick }: FlowViewProps) {
  if (isLive) {
    return (
      <ReactFlowProvider>
        <LiveFlowViewInner onExternalNodeClick={onExternalNodeClick} />
      </ReactFlowProvider>
    );
  }
  if (!dag) return null;
  return (
    <ReactFlowProvider>
      <HistoricalFlowViewInner dag={dag} trace={trace} />
    </ReactFlowProvider>
  );
}

// ========================
// Historical (static) flow view
// ========================

function HistoricalFlowViewInner({ dag, trace }: { dag: DagModel; trace: ExecutionTrace | null }) {
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [showMinimap, setShowMinimap] = useState(true);

  const traceByAgentRole = useMemo(() => {
    if (!trace) return undefined;
    const map = new Map<string, TaskTrace>();
    for (const taskTrace of trace.taskTraces) {
      map.set(`${taskTrace.agentRole}:${taskTrace.taskDescription}`, taskTrace);
    }
    return map;
  }, [trace]);

  useEffect(() => {
    seedAgentColors(dag.agents.map((a) => a.role));
  }, [dag]);

  const { nodes: initialNodes, edges: initialEdges } = useMemo(
    () => layoutDagGraph(dag, selectedTaskId, traceByAgentRole),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dag, traceByAgentRole],
  );

  const [nodes, setNodes, onNodesChange] = useNodesState<TaskFlowNode>(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = layoutDagGraph(
      dag,
      selectedTaskId,
      traceByAgentRole,
    );
    setNodes(newNodes);
    setEdges(newEdges);
  }, [dag, selectedTaskId, traceByAgentRole, setNodes, setEdges]);

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
          <div className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900">
            <span className="font-semibold text-gray-700 dark:text-gray-300">{dag.workflow}</span>
            <span className="ml-2 text-gray-500">
              {dag.tasks.length} tasks &middot; {dag.agents.length} agents &middot;{' '}
              {dag.parallelGroups.length} levels
            </span>
          </div>

          <div className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-3 py-2 shadow-sm dark:border-gray-700 dark:bg-gray-900">
            {dag.agents.map((agent) => {
              const color = getAgentColor(agent.role);
              return (
                <span key={agent.role} title={agent.goal} className="flex items-center gap-1 text-xs">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: color.bg }} />
                  <span className="text-gray-700 dark:text-gray-300">{agent.role}</span>
                </span>
              );
            })}
          </div>
        </div>

        <div className="absolute right-3 top-3 z-10 flex flex-col gap-1.5">
          <button
            onClick={() => setShowMinimap((v) => !v)}
            className="rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-600 shadow-sm hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-gray-700"
          >
            {showMinimap ? 'Hide' : 'Show'} minimap
          </button>
        </div>

        {dag.criticalPath.length > 0 && (
          <div className="absolute bottom-14 left-3 z-10 flex items-center gap-2 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900">
            <span className="h-2 w-6 rounded" style={{ backgroundColor: '#EF4444' }} />
            <span className="text-gray-500">Critical path ({dag.criticalPath.length} tasks)</span>
          </div>
        )}
      </div>

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

// ========================
// Live flow view
// ========================

/**
 * Live mode flow graph. Builds a synthetic DAG from the current live execution state,
 * applying live status overrides to node colors:
 * - No tasks yet: shows a "waiting" empty state
 * - running   -> blue header with ae-pulse animation
 * - failed    -> red header
 * - completed -> normal agent color (same as historical mode)
 */
function LiveFlowViewInner({ onExternalNodeClick }: { onExternalNodeClick?: (agentRole: string) => void }) {
  const { liveState } = useLiveServer();
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
  const [showMinimap, setShowMinimap] = useState(true);

  /**
   * Run selector: null = active (current) run; 0..N-1 = index into completedRuns.
   * Allows inspecting the DAG of any completed run without leaving live mode.
   */
  const [viewRunIndex, setViewRunIndex] = useState<number | null>(null);

  // Stable snapshot of the selected completed run, or null when viewing the active run.
  // Computing this in a separate memo prevents the DAG from being re-laid out on every
  // live update (tokens, delegations, etc.) while the user is inspecting a past run.
  const completedRunState = useMemo(
    () =>
      viewRunIndex !== null && viewRunIndex < liveState.completedRuns.length
        ? {
            ...initialLiveState,
            ensembleId: liveState.completedRuns[viewRunIndex].ensembleId,
            workflow: liveState.completedRuns[viewRunIndex].workflow,
            startedAt: liveState.completedRuns[viewRunIndex].startedAt,
            completedAt: liveState.completedRuns[viewRunIndex].completedAt,
            totalTasks: liveState.completedRuns[viewRunIndex].totalTasks,
            tasks: liveState.completedRuns[viewRunIndex].tasks,
            ensembleComplete: true,
          }
        : null,
    [viewRunIndex, liveState.completedRuns],
  );

  // When a completed run is selected, use its frozen state so live updates to the active
  // run do not cause unnecessary DAG re-layouts. When no completed run is selected, fall
  // back to the full live state to keep the active-run DAG current.
  const displayedState = completedRunState ?? liveState;

  // Build synthetic DagModel from the displayed state
  const dag = useMemo(() => buildSyntheticDagModel(displayedState), [displayedState]);

  // Build live status map only for the active run (completed runs have no live status)
  const liveStatusMap = useMemo(
    () => (viewRunIndex === null ? buildLiveStatusMap(liveState) : new Map()),
    [viewRunIndex, liveState],
  );

  // Seed agent colors from the live task agent roles in arrival order
  useEffect(() => {
    const roles = liveState.tasks.map((t) => t.agentRole);
    if (roles.length > 0) seedAgentColors(roles);
  }, [liveState.tasks]);

  // Compute layout and apply live status overrides to node data
  const { nodes: initialNodes, edges: initialEdges } = useMemo(() => {
    const { nodes, edges } = layoutDagGraph(dag, selectedTaskId, undefined);
    const nodesWithStatus = nodes.map((node) => ({
      ...node,
      data: {
        ...node.data,
        liveStatus: liveStatusMap.get(node.id),
      },
    }));
    return { nodes: nodesWithStatus, edges };
  }, [dag, selectedTaskId, liveStatusMap]);

  const [nodes, setNodes, onNodesChange] = useNodesState<TaskFlowNode>(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // Re-layout whenever live state changes
  useEffect(() => {
    const { nodes: newNodes, edges: newEdges } = layoutDagGraph(dag, selectedTaskId, undefined);
    const nodesWithStatus = newNodes.map((node) => ({
      ...node,
      data: {
        ...node.data,
        liveStatus: liveStatusMap.get(node.id),
      },
    }));
    setNodes(nodesWithStatus);
    setEdges(newEdges);
  }, [dag, selectedTaskId, liveStatusMap, setNodes, setEdges]);

  const onNodeClick: NodeMouseHandler = useCallback((_event, node: Node) => {
    setSelectedTaskId((prev) => (prev === node.id ? null : node.id));
    const data = node.data as TaskNodeData;
    if (data.task?.agentRole && onExternalNodeClick) {
      onExternalNodeClick(data.task.agentRole);
    }
  }, [onExternalNodeClick]);

  if (liveState.tasks.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center text-sm text-gray-400 dark:text-gray-500">
        Waiting for tasks to start...
      </div>
    );
  }

  const runningCount = liveState.tasks.filter((t) => t.status === 'running').length;
  const completedCount = liveState.tasks.filter(
    (t) => t.status === 'completed' || t.status === 'failed',
  ).length;

  return (
    <div className="flex flex-1 overflow-hidden" data-testid="live-flow-view">
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
                const ls = data.liveStatus;
                if (ls === 'running') return '#3B82F6';
                if (ls === 'failed') return '#EF4444';
                return data.agentColor.bg;
              }}
              maskColor="rgba(0,0,0,0.1)"
            />
          )}
        </ReactFlow>

        {/* Live status overlay */}
        <div className="absolute left-3 top-3 z-10 flex gap-2">
          <div
            className="rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900"
            data-testid="live-flow-status-bar"
          >
            <span className="font-semibold text-gray-700 dark:text-gray-300">
              {liveState.workflow ?? 'Live'}
            </span>
            <span className="ml-2 text-gray-500">
              {completedCount} / {liveState.totalTasks > 0 ? liveState.totalTasks : liveState.tasks.length} tasks
            </span>
            {runningCount > 0 && (
              <span className="ml-2 flex items-center gap-1 text-blue-600 dark:text-blue-400">
                <span className="h-1.5 w-1.5 rounded-full bg-blue-500 ae-pulse" />
                {runningCount} running
              </span>
            )}
          </div>

          {/* Agent legend */}
          <div className="flex items-center gap-1.5 rounded-lg border border-gray-200 bg-white px-3 py-2 shadow-sm dark:border-gray-700 dark:bg-gray-900">
            {dag.agents.map((agent) => {
              const color = getAgentColor(agent.role);
              return (
                <span key={agent.role} className="flex items-center gap-1 text-xs">
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

        {/* Live status legend */}
        <div className="absolute bottom-14 left-3 z-10 flex items-center gap-3 rounded-lg border border-gray-200 bg-white px-3 py-2 text-xs shadow-sm dark:border-gray-700 dark:bg-gray-900">
          <span className="flex items-center gap-1">
            <span className="h-2 w-4 rounded bg-blue-500" />
            <span className="text-gray-500">Running</span>
          </span>
          <span className="flex items-center gap-1">
            <span className="h-2 w-4 rounded bg-red-500" />
            <span className="text-gray-500">Failed</span>
          </span>
          <span className="flex items-center gap-1">
            <span className="h-2 w-4 rounded bg-gray-400" />
            <span className="text-gray-500">Pending</span>
          </span>
        </div>

        <div className="absolute right-3 top-3 z-10 flex flex-col gap-1.5">
          {/* Run selector: allows inspecting any completed run's DAG or the active run */}
          {liveState.completedRuns.length > 0 && (
            <select
              value={viewRunIndex === null ? 'active' : String(viewRunIndex)}
              onChange={(e) => {
                const v = e.target.value;
                setViewRunIndex(v === 'active' ? null : Number(v));
                setSelectedTaskId(null);
              }}
              data-testid="live-flow-run-selector"
              className="rounded-md border border-gray-200 bg-white px-2 py-1.5 text-xs text-gray-600 shadow-sm dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
              aria-label="Select run to view"
            >
              {liveState.completedRuns.map((_, i) => (
                <option key={i} value={String(i)}>
                  Run {i + 1}
                </option>
              ))}
              <option value="active">
                Run {liveState.completedRuns.length + 1} (active)
              </option>
            </select>
          )}
          <button
            onClick={() => setShowMinimap((v) => !v)}
            className="rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-600 shadow-sm hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400 dark:hover:bg-gray-700"
          >
            {showMinimap ? 'Hide' : 'Show'} minimap
          </button>
        </div>
      </div>
    </div>
  );
}
