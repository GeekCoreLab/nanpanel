import { useState, type MouseEventHandler, type ReactNode } from 'react';
import {
  Background, Controls, BaseEdge, ReactFlow,
  getBezierPath,
  type Connection, type Edge, type EdgeProps, type EdgeTypes, type Node,
  type ReactFlowProps,
} from '@xyflow/react';
import './topology-canvas.css';

export function TopologyBezierEdge(props: EdgeProps) {
  const [path] = getBezierPath(props);
  const status = String(props.data?.state ?? 'PENDING').toLowerCase();
  const colors: Record<string, string> = {
    pending: '#8897ab', verified: '#2eaf83', error: '#d46c68',
    unsupported: '#aa8ec2', observed: '#527eaf',
  };
  const stroke = typeof props.style?.stroke === 'string'
    ? props.style.stroke : colors[status] ?? '#4a86ed';
  const observed = props.data?.observedRoute === true;
  const dashed = !['verified', 'error'].includes(status);
  const label = observed
    ? `Agent 路由观测 · L3/IP · ${props.data?.routeCount ?? 0} 条路由`
    : status.toUpperCase();
  return <g className={`link-state-${status}`} aria-label={label}>
    <BaseEdge
      id={props.id}
      path={path}
      markerEnd={props.markerEnd}
      style={{ ...props.style, stroke, strokeWidth: props.selected ? 3 : props.style?.strokeWidth ?? 2, strokeDasharray: props.style?.strokeDasharray ?? (dashed ? '6 4' : undefined) }}
    />
  </g>;
}

export type TopologyCanvasProps<N extends Node, E extends Edge> = Omit<
  ReactFlowProps<N, E>, 'edgeTypes' | 'onConnect' | 'children'
> & {
  edgeTypes?: EdgeTypes;
  onConnect?: (connection: Connection) => void;
  reviewConnection?: (connection: Connection) => string | null;
  onCanvasContextMenu?: MouseEventHandler<HTMLDivElement>;
  children?: ReactNode;
};

/** Shared React Flow surface for every editable topology in the console. */
export function TopologyCanvas<N extends Node, E extends Edge>({
  edgeTypes,
  defaultEdgeOptions,
  onConnect,
  reviewConnection,
  onCanvasContextMenu,
  children,
  ...flowProps
}: TopologyCanvasProps<N, E>) {
  const [pendingReview, setPendingReview] = useState<{ connection: Connection; message: string }>();
  const handleConnect = (connection: Connection) => {
    const message = reviewConnection?.(connection);
    if (message) {
      setPendingReview({ connection, message });
      return;
    }
    onConnect?.(connection);
  };
  const confirmConnection = () => {
    if (!pendingReview) return;
    onConnect?.(pendingReview.connection);
    setPendingReview(undefined);
  };

  return <div className="topology-canvas-shell" onContextMenu={event => {
    const target = event.target;
    if (!onCanvasContextMenu || target instanceof Element
        && target.closest('.react-flow__node, .react-flow__edge, .react-flow__controls, .react-flow__minimap')) return;
    event.preventDefault();
    event.stopPropagation();
    onCanvasContextMenu(event);
  }}>
    <ReactFlow<N, E>
      {...flowProps}
      edgeTypes={{ ...edgeTypes, nanpanelBezier: TopologyBezierEdge }}
      defaultEdgeOptions={{ type: 'nanpanelBezier', ...defaultEdgeOptions }}
      onConnect={handleConnect}
    >
      {children ?? <><Background color="#dbe3ee" gap={24} /><Controls /></>}
    </ReactFlow>
    {pendingReview && <div className="topology-review-backdrop" role="presentation">
      <section className="topology-review-dialog" role="alertdialog" aria-modal="true" aria-labelledby="topology-review-title">
        <span className="eyebrow">CONNECTION REVIEW</span>
        <h2 id="topology-review-title">需要人工确认接口兼容性</h2>
        <p>{pendingReview.message}</p>
        <p>请核对两端接口类型、设备能力及流量方向。确认后只保存拓扑连接，不代表 Provider 已部署或验证此能力。</p>
        <div className="form-actions">
          <button type="button" className="secondary" onClick={() => setPendingReview(undefined)}>取消连接</button>
          <button type="button" className="primary" onClick={confirmConnection}>我已分析，继续连接</button>
        </div>
      </section>
    </div>}
  </div>;
}
