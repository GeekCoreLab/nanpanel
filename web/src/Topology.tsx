import { useEffect, useRef, useState, type CSSProperties } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, Handle, Position, ConnectionMode,
  BaseEdge, getBezierPath, MarkerType, useNodesState, useEdgesState, useReactFlow, useUpdateNodeInternals,
  type Node, type NodeProps, type Edge, type EdgeProps, type Connection, type IsValidConnection,
} from '@xyflow/react';
import { api, isMock, isP0Lab, mockBusiness } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import NetbirdProviderPanel from './NetbirdProviderPanel';
import { connectionError, createTopologyNode, draftError, nodePresets, validNodeShape } from './lib/topology-model';
import './topology-status.css';
import type { KubernetesServiceInventorySnapshot, KubernetesServiceProbeSnapshot, TopologyDraft, TopologyDraftWrite, TopologyLink, TopologyNode, TopologyNodeKind } from './lib/types';

type DeviceNode = Node<{ model: TopologyNode }, 'device'>;
type P0RouteEvidence = { status: 'VERIFIED'; route: string; httpStatus: number; responseBody: string; configSha256: string; diff: string; verifiedAt: string };
const virtualKinds: TopologyNodeKind[] = ['SWITCH', 'MERGE', 'SPLIT', 'FORWARDER'];
const asNode = (model: TopologyNode): DeviceNode => ({ id: model.id, type: 'device', position: model.position, data: { model } });
const asEdge = (link: TopologyLink): Edge => ({ id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort, target: link.targetNodeId, targetHandle: link.targetPort, type: 'directed', markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' }, data: { status: 'UNVERIFIED', state: link.state ?? 'PENDING', layer: link.layer, protocol: link.protocol }, ariaLabel: `期望连接，${link.state ?? 'PENDING'} · ${link.layer}/${link.protocol}` });
const asLink = (edge: Edge): TopologyLink => ({ id: edge.id, sourceNodeId: edge.source, sourcePort: edge.sourceHandle ?? '', targetNodeId: edge.target, targetPort: edge.targetHandle ?? '', layer: 'L4', protocol: 'TCP', status: 'UNVERIFIED' });
const linkStateLabels = { PENDING: '待部署', VERIFIED: '已验证', ERROR: '异常', UNSUPPORTED: '不可支持' } as const;

function p0RouteError(graph: TopologyDraftWrite): string | null {
  if (graph.nodes.length !== 3 || graph.links.length !== 2) return 'P0 实验要求一条 Gateway → NAT Host → Service 路径';
  const byKind = new Map(graph.nodes.map(node => [node.kind, node]));
  if (!byKind.has('GATEWAY') || !byKind.has('HOST') || !byKind.has('SERVICE')) return 'P0 实验要求一个 Gateway、一个 NAT Host 和一个 Service';
  const [gateway, host, service] = (['GATEWAY', 'HOST', 'SERVICE'] as const).map(kind => byKind.get(kind)!);
  const connects = (source: TopologyNode, target: TopologyNode) => graph.links.some(link => link.sourceNodeId === source.id && link.targetNodeId === target.id && link.sourcePort === 'out-1' && link.targetPort === 'in-1');
  return connects(gateway, host) && connects(host, service) ? null : '请连接 Gateway 出口 → NAT Host 入口，再连接 NAT Host 出口 → Service 入口';
}

function Device({ data, selected }: NodeProps<DeviceNode>) {
  const node = data.model;
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(node.id); }, [node.id, node.inputCount, node.outputCount, updateNodeInternals]);
  const height = Math.max(node.inputCount, node.outputCount, 1) * 26 + 16;
  return <div className={`topology-device ${selected ? 'is-selected' : ''}`}>
    <div className="device-title"><span className="device-symbol">{node.kind === 'SWITCH' ? '▦' : virtualKinds.includes(node.kind) ? '⇄' : '▤'}</span><div><strong>{node.name}</strong><small>{nodePresets[node.kind].description}</small></div></div>
    <div className="device-ports" style={{ height }}>
      {Array.from({ length: node.inputCount }, (_, i) => <div className="device-port port-in" key={`in-${i + 1}`} style={{ top: 12 + i * 26 }}><Handle id={`in-${i + 1}`} type="target" position={Position.Left} aria-label={`${node.name} 入口 ${i + 1}`} /><span>入口 {i + 1} · L4</span></div>)}
      {Array.from({ length: node.outputCount }, (_, i) => <div className="device-port port-out" key={`out-${i + 1}`} style={{ top: 12 + i * 26 }}><span>出口 {i + 1} · L4</span><Handle id={`out-${i + 1}`} type="source" position={Position.Right} aria-label={`${node.name} 出口 ${i + 1}`} /></div>)}
    </div><div className="device-status">期望节点 · 未部署</div>
  </div>;
}
function DirectedEdge(props: EdgeProps) {
  const [path] = getBezierPath(props); const gradient = `link-${props.id}`;
  const state = (props.data?.state ?? 'PENDING') as keyof typeof linkStateLabels;
  const colors = { PENDING: '#8897ab', VERIFIED: '#2eaf83', ERROR: '#d46c68', UNSUPPORTED: '#aa8ec2' };
  return <g className={`link-state-${state.toLowerCase()}`} aria-label={linkStateLabels[state]}><BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} style={{ stroke: colors[state], strokeWidth: props.selected ? 3 : 2, strokeDasharray: state === 'VERIFIED' || state === 'ERROR' ? undefined : '6 4' }} /></g>;
}
const nodeTypes = { device: Device };
const edgeTypes = { directed: DirectedEdge };
type Menu = { x: number; y: number; point: { x: number; y: number }; nodeId?: string; edgeId?: string };

function Editor() {
  const [nodes, setNodes, onNodesChange] = useNodesState<DeviceNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [etag, setEtag] = useState(''); const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false); const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState(''); const [error, setError] = useState('');
  const [serviceInventory, setServiceInventory] = useState<KubernetesServiceInventorySnapshot>();
  const [serviceProbes, setServiceProbes] = useState<KubernetesServiceProbeSnapshot>();
  const [serviceInventoryError, setServiceInventoryError] = useState(''); const [inventoryBusy, setInventoryBusy] = useState(false);
  const [routeEvidence, setRouteEvidence] = useState<P0RouteEvidence>();
  const [menu, setMenu] = useState<Menu>(); const menuRef = useRef<HTMLDivElement>(null); const canvasRef = useRef<HTMLElement>(null);
  const { screenToFlowPosition, fitView } = useReactFlow<DeviceNode>();
  const draft = (): TopologyDraftWrite => ({ nodes: nodes.map(n => ({ ...n.data.model, position: n.position })), links: edges.map(asLink) });
  async function load() {
    setBusy(true); setError(''); setMenu(undefined);
    try { const r = await api<TopologyDraft>('/topology/draft'); const payload = { nodes: r.data.nodes, links: r.data.links.map(({ state: _state, ...link }) => link) }; const invalid = draftError(payload); if (invalid) throw new Error(invalid); setNodes(payload.nodes.map(asNode)); setEdges(r.data.links.map(asEdge)); setEtag(r.etag ?? ''); setDirty(false); setLoaded(true); setMessage(`已读取 Revision ${r.data.generation}；链路状态来自运行观测`); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function loadServiceInventory(refresh = false) {
    setInventoryBusy(true); setServiceInventoryError('');
    try {
      const result = await api<KubernetesServiceInventorySnapshot>(refresh ? '/kubernetes/service-inventory/refresh' : '/kubernetes/service-inventory', refresh ? 'POST' : 'GET');
      setServiceInventory(result.data);
      const probes = await api<KubernetesServiceProbeSnapshot>('/kubernetes/service-probes'); setServiceProbes(probes.data);
    }
    catch (e) { setServiceInventoryError((e as Error).message); }
    finally { setInventoryBusy(false); }
  }
  useEffect(() => { void load(); }, []);
  useEffect(() => { void loadServiceInventory(); }, []);
  useEffect(() => {
    if (!menu) return;
    const dismiss = (e: globalThis.MouseEvent) => { if (!menuRef.current?.contains(e.target as globalThis.Node)) setMenu(undefined); };
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(undefined); };
    document.addEventListener('pointerdown', dismiss); document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', escape); };
  }, [menu]);
  useEffect(() => { if (menu) menuRef.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus(); }, [menu]);
  async function save() {
    const payload = draft(); const invalid = draftError(payload); if (invalid) { setError(invalid); return; }
    setBusy(true); setError('');
    try { const r = await api<TopologyDraft>('/topology/draft', 'PUT', payload, { 'If-Match': etag }); setEtag(r.etag ?? ''); setDirty(false); setMessage(`${isMock ? 'Mock ' : ''}草稿已保存；未部署、未验证`); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function applyP0Route() {
    const payload = draft(); const invalid = draftError(payload) ?? p0RouteError(payload);
    if (invalid) { setError(invalid); return; }
    setBusy(true); setError(''); setRouteEvidence(undefined);
    try {
      const result = await api<P0RouteEvidence>('/topology/apply', 'POST', payload);
      setRouteEvidence(result.data); setMessage(`实际 FRP 路由已验证：HTTP ${result.data.httpStatus} · ${result.data.route}`);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  function openMenu(event: { preventDefault(): void; clientX: number; clientY: number }, nodeId?: string, edgeId?: string, insertionPoint?: { x: number; y: number }) {
    event.preventDefault(); if (!loaded || busy) return;
    setMenu({ x: Math.max(8, Math.min(event.clientX, window.innerWidth - 278)), y: Math.max(8, Math.min(event.clientY, window.innerHeight - 450)), point: screenToFlowPosition(insertionPoint ?? { x: event.clientX, y: event.clientY }), nodeId, edgeId });
  }
  function addNode(kind: TopologyNodeKind) {
    if (nodes.length >= 100) { setError('最多添加 100 个节点'); return; }
    const point = menu?.point ?? screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
    const height = 110 + Math.max(nodePresets[kind].inputs, nodePresets[kind].outputs) * 26;
    // Keep the clicked position when free; otherwise find a nearby free grid cell.
    let position: { x: number; y: number } | undefined;
    for (let ring = 0; ring <= 20 && !position; ring++) {
      for (let dx = -ring; dx <= ring && !position; dx++) {
        for (let dy = -ring; dy <= ring; dy++) {
          if (ring && Math.abs(dx) !== ring && Math.abs(dy) !== ring) continue;
          const candidate = { x: Math.max(-10000, Math.min(10000, point.x + dx * 300)), y: Math.max(-10000, Math.min(10000, point.y + dy * (height + 40))) };
          if (!nodes.some(n => candidate.x < n.position.x + 270 && candidate.x + 270 > n.position.x && candidate.y < n.position.y + 150 + Math.max(n.data.model.inputCount, n.data.model.outputCount) * 26 && candidate.y + height + 40 > n.position.y)) { position = candidate; break; }
        }
      }
    }
    if (!position) { setError('附近没有可用位置，请平移画布后重试'); return; }
    const model = createTopologyNode(kind, position);
    setNodes(current => [...current, asNode(model)]); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setError(''); setMessage(`已添加${model.name}：${model.inputCount} 入 / ${model.outputCount} 出`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  const candidate = (connection: Connection | Edge): TopologyLink => ({ id: createUuid(), sourceNodeId: connection.source, sourcePort: connection.sourceHandle ?? '', targetNodeId: connection.target, targetPort: connection.targetHandle ?? '', layer: 'L4', protocol: 'TCP', status: 'UNVERIFIED' });
  const validConnection: IsValidConnection = connection => loaded && !busy && edges.length < 800 && !connectionError(draft().nodes, edges.map(asLink), candidate(connection));
  function connect(connection: Connection) {
    if (edges.length >= 800) { setError('最多添加 800 条连接'); return; }
    const link = candidate(connection); const invalid = connectionError(draft().nodes, edges.map(asLink), link);
    if (invalid) { setError(invalid); return; }
    setEdges(current => [...current, asEdge(link)]); setDirty(true); setRouteEvidence(undefined); setError(''); setMessage('已添加绿色出口 → 蓝色入口的期望连接');
  }
  function removeNode(id: string) { setNodes(current => current.filter(n => n.id !== id)); setEdges(current => current.filter(e => e.source !== id && e.target !== id)); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setMessage('节点及关联连接已删除'); }
  function portChangeCandidate(node: TopologyNode, direction: 'inputCount' | 'outputCount', delta: number): TopologyNode | null {
    const input = direction === 'inputCount';
    const portId = `${input ? 'in' : 'out'}-${input ? node.inputCount : node.outputCount}`;
    if (delta < 0 && edges.some(e => input ? e.target === node.id && e.targetHandle === portId : e.source === node.id && e.sourceHandle === portId)) return null;
    const nextCount = node[direction] + delta;
    if (nextCount < 0 || nextCount > 16) return null;
    const addedPort = { id: `${input ? 'in' : 'out'}-${nextCount}`, direction: input ? 'INPUT' as const : 'OUTPUT' as const, layer: 'L4' as const, protocols: ['TCP', 'UDP'] as ('TCP' | 'UDP')[] };
    const ports = delta > 0
      ? input
        ? [...node.ports.filter(port => port.direction === 'INPUT'), addedPort, ...node.ports.filter(port => port.direction === 'OUTPUT')]
        : [...node.ports, addedPort]
      : node.ports.filter(port => port.id !== portId);
    const model = { ...node, [direction]: nextCount, ports };
    return validNodeShape(model) ? model : null;
  }
  function changePorts(node: TopologyNode, direction: 'inputCount' | 'outputCount', delta: number) {
    const input = direction === 'inputCount';
    const portId = `${input ? 'in' : 'out'}-${input ? node.inputCount : node.outputCount}`;
    const model = portChangeCandidate(node, direction, delta);
    if (!model) {
      if (delta < 0 && edges.some(e => input ? e.target === node.id && e.targetHandle === portId : e.source === node.id && e.sourceHandle === portId)) {
        setError('最后一个端口仍有连接，请先删除关联连线'); setMenu(undefined);
      }
      return;
    }
    setNodes(current => current.map(n => n.id === node.id ? { ...n, data: { model } } : n)); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setMessage('端口数量已更新');
  }
  const contextNode = nodes.find(n => n.id === menu?.nodeId)?.data.model;
  const contextEdge = edges.find(edge => edge.id === menu?.edgeId);
  return <>
    <div className="page-heading"><div><h1>{isP0Lab ? '拓扑到实际路由 P0 实验' : '网络拓扑实验室'}</h1><p>绿色出口 → 蓝色入口，用多端口节点组织期望转发路径。</p></div><div className="topology-actions"><button className="secondary" disabled={busy} onClick={load}>{dirty ? '重新读取（丢弃未保存）' : '↻ 重新读取'}</button><button className="primary" disabled={!loaded || busy || !dirty || !etag} onClick={save}>{busy ? '处理中…' : '保存草稿'}</button>{isP0Lab && <button className="primary" disabled={!loaded || busy || !!draftError(draft()) || !!p0RouteError(draft())} onClick={applyP0Route}>{busy ? '处理中…' : '应用到 FRP 实际路由'}</button>}</div></div>
    <div className="alert">右键画布添加交换机与转发器，右键节点调整端口，右键连线删除。每个端口仅连接一条线；同色连接、自连接与环路均不允许。{isP0Lab ? 'P0 实验仅接受 Gateway → NAT Host → Service，并把配置部署到隔离 FRP 容器。' : '草稿不执行真实转发。'}</div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {routeEvidence && <div className="alert" role="status"><strong>实际路由已验证</strong> · {routeEvidence.route} · HTTP {routeEvidence.httpStatus} · 响应 {routeEvidence.responseBody} · SHA-256 {routeEvidence.configSha256}<pre className="p0-route-diff">{routeEvidence.diff}</pre></div>}
    <div className="topology-tools"><div className="port-legend"><span><i className="input-dot" />蓝色：入口</span><span><i className="output-dot" />绿色：出口</span>{Object.entries(linkStateLabels).map(([state, label]) => <span key={state}><i className={`state-dot ${state.toLowerCase()}`} />{label}</span>)}</div><div className="topology-actions"><button className="secondary" disabled={!loaded || busy} onClick={e => { const rect = e.currentTarget.getBoundingClientRect(); const canvas = canvasRef.current?.getBoundingClientRect(); openMenu({ preventDefault() {}, clientX: rect.left, clientY: rect.bottom + 8 }, undefined, undefined, canvas ? { x: canvas.left + canvas.width / 2, y: canvas.top + canvas.height / 2 } : undefined); }}>＋ 添加节点</button><button className="secondary" disabled={busy || !edges.length} onClick={() => { setEdges([]); setDirty(true); setRouteEvidence(undefined); }}>清空连接</button></div></div>
    <section className="topology card" aria-label="拓扑编辑器" ref={canvasRef}>
      <ReactFlow<DeviceNode> nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
        onNodesChange={changes => { onNodesChange(changes); if (changes.some(c => c.type === 'position' && c.position || c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onEdgesChange={changes => { onEdgesChange(changes); if (changes.some(c => c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onConnect={connect} isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => { if (state.toHandle && !state.isValid) setError('连接无效：仅允许未占用的绿色出口 → 蓝色入口，且不能形成自连接或环路'); }}
        onPaneContextMenu={e => openMenu(e)} onNodeContextMenu={(e, node) => openMenu(e, node.id)} onEdgeContextMenu={(e, edge) => openMenu(e, undefined, edge.id)}
        onPaneClick={() => setMenu(undefined)} onMoveStart={() => setMenu(undefined)}
        onNodesDelete={deleted => { setEdges(current => current.filter(e => !deleted.some(n => n.id === e.source || n.id === e.target))); setRouteEvidence(undefined); }}
        nodesDraggable={!busy} nodesConnectable={loaded && !busy} elementsSelectable={!busy} deleteKeyCode={busy ? null : ['Backspace', 'Delete']}
        fitView minZoom={.2} maxZoom={1.7} translateExtent={[[-11000, -11000], [11000, 11000]]} nodeExtent={[[-10000, -10000], [10000, 10000]]}>
        <Background color="#dbe3ee" gap={24} /><Controls />
      </ReactFlow>
    </section>
    <section className="service-network card" aria-labelledby="service-network-title">
      <div className="card-title"><div><h2 id="service-network-title">Kubernetes 服务网络</h2><p>Service 与 EndpointSlice 持久化快照；TCP 探测需单独显式启用</p></div><button className="secondary" disabled={inventoryBusy} onClick={() => void loadServiceInventory(true)}>{inventoryBusy ? '读取中…' : '↻ 刷新清单'}</button></div>
        {serviceInventoryError && <div className="alert error" role="alert">{serviceInventoryError}{!isMock && ' · 请在 Core 配置集群身份、namespace 并显式启用 Kubernetes 服务发现。'}</div>}
      {serviceInventory && <>
        <div className="service-network-meta"><span><b>{serviceInventory.sourceId}</b> · namespace <b>{serviceInventory.namespace}</b></span><span>{serviceInventory.observedAt ? `观测于 ${new Date(serviceInventory.observedAt).toLocaleString()}` : '尚无持久化快照'}</span>{serviceProbes && <span className={`badge ${serviceProbes.enabled ? 'available' : 'pending'}`}>TCP 探测{serviceProbes.enabled ? '已启用' : '已关闭'}</span>}{mockBusiness && <span className="badge pending">Mock 示例</span>}</div>
        {serviceInventory.services.length ? <div className="service-network-list">{serviceInventory.services.map(service => <article className="service-network-item" key={service.serviceName}>
          <div className="service-network-title"><div><strong>{service.serviceName}</strong><small>{service.type} · {service.clusterIps.length ? service.clusterIps.join(', ') : service.externalName ?? '无 ClusterIP'}</small></div><span className="badge">{service.endpoints.filter(endpoint => endpoint.ready === true).length}/{service.endpoints.length} ready</span></div>
          <div className="service-network-ports">{service.ports.map((port, index) => <span className="chip" key={`${port.name ?? 'port'}-${index}`}>{port.name ? `${port.name} · ` : ''}{port.port}/{port.protocol} → {port.targetPort}</span>)}</div>
          {service.endpoints.length ? <div className="service-network-endpoints">{service.endpoints.map((endpoint, index) => {
            const endpointProbes = serviceProbes?.probes.filter(probe => probe.serviceName === service.serviceName && probe.address === endpoint.address) ?? [];
            return <div key={`${endpoint.address}-${index}`}><span className={`badge ${endpoint.ready === true ? 'online' : endpoint.ready === false ? 'offline' : 'unknown'}`}>{endpoint.ready === true ? 'Ready' : endpoint.ready === false ? 'Not ready' : 'Unknown'}</span><code>{endpoint.address}</code><span>{endpoint.targetKind && endpoint.targetName ? `${endpoint.targetKind}/${endpoint.targetName}` : '无 Pod 身份'}</span><small>{endpoint.nodeName ?? endpoint.addressType}{endpoint.terminating ? ' · 正在终止' : ''}</small>{serviceProbes?.enabled && <span className="service-network-probe">{endpointProbes.length ? endpointProbes.map(probe => <span className={`badge ${probe.outcome === 'SUCCEEDED' ? 'succeeded' : probe.outcome === 'FAILED' ? 'failed' : 'unknown'}`} key={`${probe.port}/${probe.protocol}`} title={`清单观测 ${probe.inventoryObservedAt} · 探测 ${probe.observedAt}`}>{probe.protocol}/{probe.port} {probe.outcome}</span>) : <span className="badge pending">尚无 TCP 结果</span>}</span>}</div>;
          })}</div> : <p className="service-network-empty">当前没有 EndpointSlice 地址</p>}
        </article>)}</div> : <div className="empty">{serviceInventory.observedAt ? '该 namespace 当前没有 Service。' : '等待首次采集；可手动刷新清单。'}</div>}
      </>}
    </section>
    <NetbirdProviderPanel />
    <div className="topology-caption"><span>{nodes.length} 个节点 · {edges.length} 条未验证连接 · {dirty ? '有未保存的修改' : '草稿未修改'}</span><span role="status">{message || (busy ? '正在读取草稿…' : '')}</span></div>
    {menu && <div className="topology-menu" role="menu" aria-label="拓扑右键菜单" ref={menuRef} style={{ left: menu.x, top: menu.y } as CSSProperties}>
      {contextNode ? <><strong>{contextNode.name} · {contextNode.inputCount} 入 / {contextNode.outputCount} 出</strong>{(['inputCount', 'outputCount'] as const).map(direction => <div key={direction} className="menu-port-actions">{[1, -1].map(delta => <button role="menuitem" key={delta} disabled={!portChangeCandidate(contextNode, direction, delta)} onClick={() => changePorts(contextNode, direction, delta)}>{delta > 0 ? '增加' : '减少'}{direction === 'inputCount' ? '入口' : '出口'}</button>)}</div>)}<button role="menuitem" className="danger" onClick={() => removeNode(contextNode.id)}>删除节点及关联连接</button></> : menu.edgeId ? <><strong>期望连接 · {linkStateLabels[(contextEdge?.data?.state ?? 'PENDING') as keyof typeof linkStateLabels]} · L4/{(contextEdge?.data?.protocol ?? 'TCP') as string}</strong><button role="menuitem" className="danger" onClick={() => { setEdges(current => current.filter(e => e.id !== menu.edgeId)); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); }}>删除连接</button></> : <><strong>添加虚拟节点</strong>{(Object.keys(nodePresets) as TopologyNodeKind[]).map(kind => <button role="menuitem" key={kind} onClick={() => addNode(kind)}><span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small></button>)}</>}
      <button role="menuitem" className="menu-cancel" onClick={() => setMenu(undefined)}>关闭菜单 · Esc</button>
    </div>}
  </>;
}
export default function Topology() { return <ReactFlowProvider><Editor /></ReactFlowProvider>; }
