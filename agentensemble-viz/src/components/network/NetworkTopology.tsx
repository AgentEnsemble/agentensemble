/**
 * Network topology graph using @xyflow/react.
 * Renders ensembles as nodes and connections as edges.
 */

import { useMemo, useCallback } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  type Node,
  type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useNetwork } from '../../contexts/NetworkContext.js';
import EnsembleNodeComponent from './EnsembleNodeComponent.js';

const nodeTypes = { ensemble: EnsembleNodeComponent };

export default function NetworkTopology() {
  const { state, selectEnsemble } = useNetwork();

  const handleNodeClick = useCallback(
    (name: string) => {
      selectEnsemble(state.selectedEnsemble === name ? null : name);
    },
    [state.selectedEnsemble, selectEnsemble],
  );

  // Convert ensembles to React Flow nodes with auto-layout
  const nodes: Node[] = useMemo(() => {
    const entries = Object.values(state.ensembles);
    const cols = Math.max(1, Math.ceil(Math.sqrt(entries.length)));
    return entries.map((ensemble, idx) => ({
      id: ensemble.name,
      type: 'ensemble',
      position: {
        x: (idx % cols) * 250,
        y: Math.floor(idx / cols) * 180,
      },
      data: {
        ensemble,
        selected: state.selectedEnsemble === ensemble.name,
        onClick: handleNodeClick,
      },
    }));
  }, [state.ensembles, state.selectedEnsemble, handleNodeClick]);

  // Convert connections to React Flow edges
  const edges: Edge[] = useMemo(
    () =>
      state.connections.map((conn, idx) => ({
        id: `edge-${idx}`,
        source: conn.from,
        target: conn.to,
        label: conn.label,
        animated: true,
        style: { stroke: '#6b7280' },
      })),
    [state.connections],
  );

  return (
    <div className="h-full w-full" data-testid="network-topology">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        fitView
        proOptions={{ hideAttribution: true }}
      >
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
