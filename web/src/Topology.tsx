import { useEffect, useRef, useState, type CSSProperties } from 'react';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, Handle, Position, ConnectionMode,
  BaseEdge, getBezierPath, MarkerType, useNodesState, useEdgesState, useReactFlow, useUpdateNodeInternals,
  type Node, type NodeProps, type Edge, type EdgeProps, type Connection, type IsValidConnection,
} from '@xyflow/react';
import { api, isMock } from './lib/api';
import { connectionError, createTopologyNode, draftError, nodePresets, validNodeShape } from './lib/topology-model';
import type { TopologyDraft, TopologyDraftWrite, TopologyLink, TopologyNode, TopologyNodeKind } from './lib/types';

type DeviceNode = Node<{ model: TopologyNode }, 'device'>;
const virtualKinds: TopologyNodeKind[] = ['SWITCH', 'MERGE', 'SPLIT', 'FORWARDER'];
const asNode = (model: TopologyNode): DeviceNode => ({ id: model.id, type: 'device', position: model.position, data: { model } });
const asEdge = (link: TopologyLink): Edge => ({ id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort, target: link.targetNodeId, targetHandle: link.targetPort, type: 'directed', markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' }, data: { status: 'UNVERIFIED' }, ariaLabel: '期望连接，未验证' });
const asLink = (edge: Edge): TopologyLink => ({ id: edge.id, sourceNodeId: edge.source, sourcePort: edge.sourceHandle ?? '', targetNodeId: edge.target, targetPort: edge.targetHandle ?? '', status: 'UNVERIFIED' });

function Device({ data, selected }: NodeProps<DeviceNode>) {
  const node = data.model;
  const updateNodeInternals = useUpdateNodeInternals();
  useEffect(() => { updateNodeInternals(node.id); }, [node.id, node.inputCount, node.outputCount, updateNodeInternals]);
  const height = Math.max(node.inputCount, node.outputCount, 1) * 26 + 16;
  return <div className={`topology-device ${selected ? 'is-selected' : ''}`}>
    <div className="device-title"><span className="device-symbol">{node.kind === 'SWITCH' ? '▦' : virtualKinds.includes(node.kind) ? '⇄' : '▤'}</span><div><strong>{node.name}</strong><small>{nodePresets[node.kind].description}</small></div></div>
    <div className="device-ports" style={{ height }}>
      {Array.from({ length: node.inputCount }, (_, i) => <div className="device-port port-in" key={`in-${i + 1}`} style={{ top: 12 + i * 26 }}><Handle id={`in-${i + 1}`} type="target" position={Position.Left} aria-label={`${node.name} 入口 ${i + 1}`} /><span>入口 {i + 1}</span></div>)}
      {Array.from({ length: node.outputCount }, (_, i) => <div className="device-port port-out" key={`out-${i + 1}`} style={{ top: 12 + i * 26 }}><span>出口 {i + 1}</span><Handle id={`out-${i + 1}`} type="source" position={Position.Right} aria-label={`${node.name} 出口 ${i + 1}`} /></div>)}
    </div><div className="device-status">期望节点 · 未部署</div>
  </div>;
}
function DirectedEdge(props: EdgeProps) {
  const [path] = getBezierPath(props); const gradient = `link-${props.id}`;
  return <g><defs><linearGradient id={gradient} gradientUnits="userSpaceOnUse" x1={props.sourceX} y1={props.sourceY} x2={props.targetX} y2={props.targetY}><stop offset="0%" stopColor="#2eaf83" /><stop offset="100%" stopColor="#4a86ed" /></linearGradient></defs><BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} style={{ stroke: `url(#${gradient})`, strokeWidth: props.selected ? 3 : 2, strokeDasharray: '6 4' }} /></g>;
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
  const [menu, setMenu] = useState<Menu>(); const menuRef = useRef<HTMLDivElement>(null); const canvasRef = useRef<HTMLElement>(null);
  const { screenToFlowPosition, fitView } = useReactFlow<DeviceNode>();
  const draft = (): TopologyDraftWrite => ({ nodes: nodes.map(n => ({ ...n.data.model, position: n.position })), links: edges.map(asLink) });
  async function load() {
    setBusy(true); setError(''); setMenu(undefined);
    try { const r = await api<TopologyDraft>('/topology/draft'); const payload = { nodes: r.data.nodes, links: r.data.links }; const invalid = draftError(payload); if (invalid) throw new Error(invalid); setNodes(payload.nodes.map(asNode)); setEdges(payload.links.map(asEdge)); setEtag(r.etag ?? ''); setDirty(false); setLoaded(true); setMessage('已读取期望草稿，所有连接尚未验证'); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  useEffect(() => { void load(); }, []);
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
    setNodes(current => [...current, asNode(model)]); setDirty(true); setMenu(undefined); setError(''); setMessage(`已添加${model.name}：${model.inputCount} 入 / ${model.outputCount} 出`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  const candidate = (connection: Connection | Edge): TopologyLink => ({ id: crypto.randomUUID(), sourceNodeId: connection.source, sourcePort: connection.sourceHandle ?? '', targetNodeId: connection.target, targetPort: connection.targetHandle ?? '', status: 'UNVERIFIED' });
  const validConnection: IsValidConnection = connection => loaded && !busy && edges.length < 800 && !connectionError(draft().nodes, edges.map(asLink), candidate(connection));
  function connect(connection: Connection) {
    if (edges.length >= 800) { setError('最多添加 800 条连接'); return; }
    const link = candidate(connection); const invalid = connectionError(draft().nodes, edges.map(asLink), link);
    if (invalid) { setError(invalid); return; }
    setEdges(current => [...current, asEdge(link)]); setDirty(true); setError(''); setMessage('已添加绿色出口 → 蓝色入口的期望连接');
  }
  function removeNode(id: string) { setNodes(current => current.filter(n => n.id !== id)); setEdges(current => current.filter(e => e.source !== id && e.target !== id)); setDirty(true); setMenu(undefined); setMessage('节点及关联连接已删除'); }
  function changePorts(node: TopologyNode, direction: 'inputCount' | 'outputCount', delta: number) {
    const model = { ...node, [direction]: node[direction] + delta }; if (!validNodeShape(model)) return;
    if (delta < 0 && edges.some(e => direction === 'inputCount' ? e.target === node.id && e.targetHandle === `in-${node.inputCount}` : e.source === node.id && e.sourceHandle === `out-${node.outputCount}`)) { setError('最后一个端口仍有连接，请先删除关联连线'); setMenu(undefined); return; }
    setNodes(current => current.map(n => n.id === node.id ? { ...n, data: { model } } : n)); setDirty(true); setMenu(undefined); setMessage('端口数量已更新');
  }
  const contextNode = nodes.find(n => n.id === menu?.nodeId)?.data.model;
  return <>
    <div className="page-heading"><div><h1>网络拓扑实验室</h1><p>绿色出口 → 蓝色入口，用多端口节点组织期望转发路径。</p></div><div className="topology-actions"><button className="secondary" disabled={busy} onClick={load}>{dirty ? '重新读取（丢弃未保存）' : '↻ 重新读取'}</button><button className="primary" disabled={!loaded || busy || !dirty || !etag} onClick={save}>{busy ? '处理中…' : '保存草稿'}</button></div></div>
    <div className="alert">右键画布添加交换机与转发器，右键节点调整端口，右键连线删除。每个端口仅连接一条线；同色连接、自连接与环路均不允许。草稿不执行真实转发。</div>
    {error && <div className="alert error" role="alert">{error}</div>}
    <div className="topology-tools"><div className="port-legend"><span><i className="input-dot" />蓝色：入口</span><span><i className="output-dot" />绿色：出口</span><span>⇢ 虚线：未验证</span></div><div className="topology-actions"><button className="secondary" disabled={!loaded || busy} onClick={e => { const rect = e.currentTarget.getBoundingClientRect(); const canvas = canvasRef.current?.getBoundingClientRect(); openMenu({ preventDefault() {}, clientX: rect.left, clientY: rect.bottom + 8 }, undefined, undefined, canvas ? { x: canvas.left + canvas.width / 2, y: canvas.top + canvas.height / 2 } : undefined); }}>＋ 添加节点</button><button className="secondary" disabled={busy || !edges.length} onClick={() => { setEdges([]); setDirty(true); }}>清空连接</button></div></div>
    <section className="topology card" aria-label="拓扑编辑器" ref={canvasRef}>
      <ReactFlow<DeviceNode> nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
        onNodesChange={changes => { onNodesChange(changes); if (changes.some(c => c.type === 'position' && c.position || c.type === 'remove')) setDirty(true); }}
        onEdgesChange={changes => { onEdgesChange(changes); if (changes.some(c => c.type === 'remove')) setDirty(true); }}
        onConnect={connect} isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => { if (state.toHandle && !state.isValid) setError('连接无效：仅允许未占用的绿色出口 → 蓝色入口，且不能形成自连接或环路'); }}
        onPaneContextMenu={e => openMenu(e)} onNodeContextMenu={(e, node) => openMenu(e, node.id)} onEdgeContextMenu={(e, edge) => openMenu(e, undefined, edge.id)}
        onPaneClick={() => setMenu(undefined)} onMoveStart={() => setMenu(undefined)}
        onNodesDelete={deleted => setEdges(current => current.filter(e => !deleted.some(n => n.id === e.source || n.id === e.target)))}
        nodesDraggable={!busy} nodesConnectable={loaded && !busy} elementsSelectable={!busy} deleteKeyCode={busy ? null : ['Backspace', 'Delete']}
        fitView minZoom={.2} maxZoom={1.7} translateExtent={[[-11000, -11000], [11000, 11000]]} nodeExtent={[[-10000, -10000], [10000, 10000]]}>
        <Background color="#dbe3ee" gap={24} /><Controls />
      </ReactFlow>
    </section>
    <div className="topology-caption"><span>{nodes.length} 个节点 · {edges.length} 条未验证连接 · {dirty ? '有未保存的修改' : '草稿未修改'}</span><span role="status">{message || (busy ? '正在读取草稿…' : '')}</span></div>
    {menu && <div className="topology-menu" role="menu" aria-label="拓扑右键菜单" ref={menuRef} style={{ left: menu.x, top: menu.y } as CSSProperties}>
      {contextNode ? <><strong>{contextNode.name} · {contextNode.inputCount} 入 / {contextNode.outputCount} 出</strong>{(['inputCount', 'outputCount'] as const).map(direction => <div key={direction} className="menu-port-actions">{[1, -1].map(delta => <button role="menuitem" key={delta} disabled={!validNodeShape({ ...contextNode, [direction]: contextNode[direction] + delta })} onClick={() => changePorts(contextNode, direction, delta)}>{delta > 0 ? '增加' : '减少'}{direction === 'inputCount' ? '入口' : '出口'}</button>)}</div>)}<button role="menuitem" className="danger" onClick={() => removeNode(contextNode.id)}>删除节点及关联连接</button></> : menu.edgeId ? <><strong>期望连接 · 未验证</strong><button role="menuitem" className="danger" onClick={() => { setEdges(current => current.filter(e => e.id !== menu.edgeId)); setDirty(true); setMenu(undefined); }}>删除连接</button></> : <><strong>添加虚拟节点</strong>{(Object.keys(nodePresets) as TopologyNodeKind[]).map(kind => <button role="menuitem" key={kind} onClick={() => addNode(kind)}><span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small></button>)}</>}
      <button role="menuitem" className="menu-cancel" onClick={() => setMenu(undefined)}>关闭菜单 · Esc</button>
    </div>}
  </>;
}
export default function Topology() { return <ReactFlowProvider><Editor /></ReactFlowProvider>; }
