/**
 * AnimatedEdge: custom ReactFlow edge with status-based styling.
 *
 * - Active delegations: animated dashed line with moving dots
 * - Completed: solid green line
 * - Failed: solid red line
 * - Default/pending: gray dashed line
 */

import { memo } from 'react';
import { BaseEdge, getStraightPath, type EdgeProps } from '@xyflow/react';

export interface AnimatedEdgeData extends Record<string, unknown> {
  delegationStatus?: 'active' | 'completed' | 'failed';
}

const AnimatedEdge = memo(function AnimatedEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
}: EdgeProps) {
  const status = (data as AnimatedEdgeData | undefined)?.delegationStatus;

  const [edgePath] = getStraightPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
  });

  const edgeStyle: React.CSSProperties = (() => {
    switch (status) {
      case 'active':
        return {
          stroke: '#3B82F6',
          strokeWidth: 2,
          strokeDasharray: '8 4',
          animation: 'ae-edge-flow 1s linear infinite',
        };
      case 'completed':
        return {
          stroke: '#22C55E',
          strokeWidth: 2,
        };
      case 'failed':
        return {
          stroke: '#EF4444',
          strokeWidth: 2,
        };
      default:
        return {
          stroke: '#9CA3AF',
          strokeWidth: 1.5,
          strokeDasharray: '4 4',
        };
    }
  })();

  return (
    <>
      <BaseEdge id={id} path={edgePath} style={edgeStyle} />
      {status === 'active' && (
        <circle r="3" fill="#3B82F6">
          <animateMotion dur="1.5s" repeatCount="indefinite" path={edgePath} />
        </circle>
      )}
    </>
  );
});

export default AnimatedEdge;
