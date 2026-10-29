import { useMemo, useState } from 'react';
import { Background, Controls, ReactFlow, ReactFlowProvider, type Edge, type Node } from '@xyflow/react';
import { layoutNamespaceGraph, type NamespaceResourceLike } from '../lib/namespace-graph.ts';

const KIND_COLORS: Record<string, string> = {
  Service: '#6d4fb5', Deployment: '#2f6fb0', StatefulSet: '#2f8f6b',
  DaemonSet: '#a1692f', ReplicaSet: '#8a6bbf', Pod: '#5b7288',
};

export type NamespaceGraphResource = NamespaceResourceLike & {
  status?: string;
  summary?: string;
  labels?: Record<string, string>;
};

/**
 * Read-only relationship view of an existing namespace: Services on the left,
 * workloads on the right, joined by Service selector -> pod-template-label edges.
 * Clicking a node inspects it; only NaNPanel-managed applications (mapped via
 * `managedAppIds`) can jump to the editable canvas. Nothing here mutates or
 * adopts external resources.
 */
export function NamespaceGraph({ items, managedAppIds, onOpenApplication }: {
  items: readonly NamespaceGraphResource[];
  managedAppIds?: Record<string, string>;
  onOpenApplication?: (applicationId: string) => void;
}) {
  const [selectedId, setSelectedId] = useState<string>();
  const layout = useMemo(() => layoutNamespaceGraph(items), [items]);
  const itemById = useMemo(() => {
    const map = new Map<string, NamespaceGraphResource>();
    for (const item of items) {
      map.set(item.kind === 'Service' ? `svc:${item.name}` : `wl:${item.kind}/${item.name}`, item);
    }
    return map;
  }, [items]);
  const nodes = useMemo<Node[]>(() => layout.nodes.map(node => ({
    id: node.id,
    position: { x: node.x, y: node.y },
    data: { label: node.label },
    draggable: false,
    style: {
      width: 190, padding: '6px 10px', borderRadius: 9, fontSize: 12,
      border: `1px solid ${KIND_COLORS[node.kind] ?? '#8a97a8'}`,
      background: '#ffffff', color: '#24374d',
    },
  })), [layout]);
  const edges = useMemo<Edge[]>(() => layout.edges.map(edge => ({
    id: edge.id, source: edge.source, target: edge.target, label: edge.label,
    animated: false, style: { stroke: '#8aa8cf' }, labelStyle: { fontSize: 10, fill: '#607089' },
  })), [layout]);

  if (!layout.nodes.length) {
    return <div className="application-empty-history">该 Namespace 暂无可绘制关系的工作负载或 Service。</div>;
  }

  const selected = selectedId ? itemById.get(selectedId) : undefined;
  const managedAppId = selected ? managedAppIds?.[`${selected.kind}/${selected.name}`] : undefined;

  return <div className="application-namespace-graph">
    <ReactFlowProvider>
      <ReactFlow nodes={nodes} edges={edges} fitView proOptions={{ hideAttribution: true }}
        nodesDraggable={false} nodesConnectable={false} zoomOnDoubleClick={false}
        onNodeClick={(_event, node) => setSelectedId(node.id)} onPaneClick={() => setSelectedId(undefined)}>
        <Background color="#dbe3ee" gap={24} />
        <Controls showInteractive={false} />
      </ReactFlow>
    </ReactFlowProvider>
    {selected && <aside className="namespace-graph-detail" aria-live="polite">
      <header><strong>{selected.kind} · {selected.name}</strong>
        <button type="button" className="link" onClick={() => setSelectedId(undefined)} aria-label="关闭详情">✕</button></header>
      {selected.status && <span className="namespace-graph-status">{selected.status}</span>}
      {selected.summary && <p>{selected.summary}</p>}
      {Object.keys(selected.labels ?? {}).length > 0 && <div className="application-resource-labels">
        {Object.entries(selected.labels ?? {}).map(([key, value]) => <span key={key}>{key}={value}</span>)}
      </div>}
      {managedAppId && onOpenApplication
        ? <button type="button" className="primary" onClick={() => onOpenApplication(managedAppId)}>在画布中编排</button>
        : <small className="application-muted">外部资源只读，不接管、不修改。</small>}
    </aside>}
  </div>;
}