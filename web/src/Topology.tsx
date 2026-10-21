import { useEffect, useRef, useState, type CSSProperties } from 'react';
import type { WheelEvent as ReactWheelEvent } from 'react';
import { createPortal } from 'react-dom';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, Handle, Position, ConnectionMode,
  BaseEdge, getBezierPath, MarkerType, useNodesState, useEdgesState, useReactFlow, useUpdateNodeInternals,
  type Node, type NodeProps, type Edge, type EdgeProps, type Connection, type IsValidConnection,
} from '@xyflow/react';
import { api, isMock, isP0Lab, mockBusiness } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import NetbirdProviderPanel from './NetbirdProviderPanel';
import { connectionError, createTopologyNode, draftError, nodePresets, resolveRouteNextHop, validNodeShape } from './lib/topology-model';
import './topology-status.css';
import './topology-hierarchy.css';
import './gateway-asset-picker.css';
import type { Host, HostNetworkInventory, HostPage, KubernetesClusterList, KubernetesClusterSummary, KubernetesServiceInventorySnapshot, KubernetesServiceProbeSnapshot, KubernetesTopologyNamespaceDetail, KubernetesTopologyNamespaceSnapshot, TopologyDraft, TopologyDraftWrite, TopologyLink, TopologyNode, TopologyNodeKind } from './lib/types';

type DeviceNode = Node<{
  model: TopologyNode;
  asset?: Host;
  gatewayAssetChoices?: Host[];
  gatewayAssetSearch?: string;
  gatewayAssetOpen?: boolean;
  gatewayAssetLoading?: boolean;
  gatewayAssetMock?: boolean;
  gatewayAssetNextCursor?: string | null;
  gatewayAssetError?: string;
  gatewayBoundAssetIds?: string[];
  clusterChoices?: KubernetesClusterSummary[];
  onRouterAddressChange?: (nodeId: string, address: string) => void;
  onGatewayAssetFocus?: (nodeId: string) => void;
  onGatewayAssetSearch?: (nodeId: string, value: string) => void;
  onGatewayAssetClose?: (nodeId: string) => void;
  onGatewayAssetSelect?: (nodeId: string, host: Host | null) => void;
  onGatewayAssetLoadMore?: (nodeId: string) => void;
  onFolderClusterSelect?: (nodeId: string, clusterId: string | null) => void;
}, 'device'>;
type P0RouteEvidence = { status: 'VERIFIED'; route: string; httpStatus: number; responseBody: string; configSha256: string; diff: string; verifiedAt: string };
const virtualKinds: TopologyNodeKind[] = ['SWITCH', 'MERGE', 'SPLIT', 'FORWARDER'];
const asNode = (model: TopologyNode, asset?: Host): DeviceNode => ({ id: model.id, type: 'device', position: model.position, data: { model: { ...model, parentId: model.parentId ?? null, assetId: model.assetId ?? null, clusterId: model.clusterId ?? null, ipAddress: model.ipAddress ?? null }, asset } });
const asEdge = (link: TopologyLink): Edge => ({ id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort, target: link.targetNodeId, targetHandle: link.targetPort, type: 'directed', markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' }, data: { status: 'UNVERIFIED', state: link.state ?? 'PENDING', layer: link.layer, protocol: link.protocol }, ariaLabel: `期望连接，${link.state ?? 'PENDING'} · ${link.layer}/${link.protocol}` });
const asLink = (edge: Edge): TopologyLink => ({ id: edge.id, sourceNodeId: edge.source, sourcePort: edge.sourceHandle ?? '', targetNodeId: edge.target, targetPort: edge.targetHandle ?? '', layer: (edge.data?.layer as TopologyLink['layer']) ?? 'L4', protocol: (edge.data?.protocol as TopologyLink['protocol']) ?? 'TCP', status: 'UNVERIFIED' });
const linkStateLabels = { PENDING: '待部署', VERIFIED: '已验证', ERROR: '异常', UNSUPPORTED: '不可支持', OBSERVED: 'Agent 路由观测' } as const;

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
  const gatewaySearchInput = useRef<HTMLInputElement>(null);
  const [gatewayDropdownPosition, setGatewayDropdownPosition] = useState<{ left: number; top: number; width: number; maxHeight: number }>();
  useEffect(() => { updateNodeInternals(node.id); }, [node.id, node.inputCount, node.outputCount, updateNodeInternals]);
  useEffect(() => {
    if (node.kind !== 'GATEWAY' || !data.gatewayAssetOpen) { setGatewayDropdownPosition(undefined); return; }
    const updatePosition = () => {
      const rect = gatewaySearchInput.current?.getBoundingClientRect();
      if (!rect) return;
      const margin = 8;
      const below = window.innerHeight - rect.bottom - margin;
      const above = rect.top - margin;
      const openBelow = below >= above;
      const maxHeight = Math.max(0, Math.min(160, openBelow ? below : above));
      const width = Math.min(rect.width, window.innerWidth - margin * 2);
      const left = Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin));
      setGatewayDropdownPosition({ left, top: openBelow ? rect.bottom : Math.max(margin, rect.top - maxHeight), width, maxHeight });
    };
    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [node.kind, data.gatewayAssetOpen, data.gatewayAssetChoices?.length, data.gatewayAssetLoading]);
  const scrollGatewayAssetList = (event: ReactWheelEvent<HTMLDivElement>) => {
    const list = event.currentTarget;
    const deltaScale = event.deltaMode === WheelEvent.DOM_DELTA_LINE
      ? 16
      : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? list.clientHeight : 1;
    list.scrollTop += event.deltaY * deltaScale;
    event.preventDefault();
    event.stopPropagation();
    event.nativeEvent.stopImmediatePropagation();
  };
  const height = Math.max(node.inputCount, node.outputCount, 1) * 26 + 16;
  const gatewayChoices = [...(data.gatewayAssetChoices ?? [])];
  const search = data.gatewayAssetSearch?.trim().toLocaleLowerCase() ?? '';
  const filteredGatewayChoices = gatewayChoices.filter(host => !search || host.hostname.toLocaleLowerCase().includes(search));
  const gatewayListId = `gateway-asset-options-${node.id}`;
  return <div className={`topology-device ${node.kind === 'FOLDER' ? 'is-folder' : ''} ${selected ? 'is-selected' : ''}`}>
    <div className="device-title"><span className="device-symbol">{node.kind === 'FOLDER' ? '▰' : node.kind === 'SWITCH' ? '▦' : virtualKinds.includes(node.kind) ? '⇄' : '▤'}</span><div><strong>{node.name}</strong><small>{node.assetId ? `主机资产 · ${data.asset?.status ?? '读取中'}` : nodePresets[node.kind].description}</small></div></div>
    {node.kind === 'FOLDER' && <div className="folder-cluster-control nodrag"><label htmlFor={`folder-cluster-${node.id}`}>关联 Kubernetes 集群</label><select id={`folder-cluster-${node.id}`} className="nodrag nowheel" aria-label={`${node.name}关联 Kubernetes 集群`} value={node.clusterId ?? ''} onChange={event => data.onFolderClusterSelect?.(node.id, event.target.value || null)}><option value="">使用默认集群</option>{(data.clusterChoices ?? []).map(cluster => <option value={cluster.id} key={cluster.id}>{cluster.name} · {cluster.healthStatus === 'HEALTHY' ? '健康' : '不可用'}</option>)}</select></div>}
    {node.kind === 'ROUTER' && <div className="router-address-control nodrag"><label htmlFor={`router-address-${node.id}`}>下一跳 IP 地址</label><input id={`router-address-${node.id}`} className="nodrag" inputMode="text" maxLength={45} placeholder="例如 10.0.0.1" value={node.ipAddress ?? ''} onChange={event => data.onRouterAddressChange?.(node.id, event.target.value)} /></div>}
    {node.kind === 'GATEWAY' && <div className="gateway-asset-control nodrag">
      <label htmlFor={`gateway-asset-search-${node.id}`}>绑定主机资产 <span className={`gateway-asset-source ${data.gatewayAssetMock ? 'is-mock' : ''}`}>{data.gatewayAssetMock ? 'Mock 演示数据' : '实时资产'}</span></label>
      <input ref={gatewaySearchInput} id={`gateway-asset-search-${node.id}`} className="nodrag" role="combobox" aria-label="搜索 Gateway 主机资产" aria-autocomplete="list" aria-expanded={!!data.gatewayAssetOpen} aria-controls={gatewayListId} placeholder={data.asset?.hostname ?? '输入主机名搜索…'} value={data.gatewayAssetSearch ?? ''} onFocus={() => data.onGatewayAssetFocus?.(node.id)} onBlur={() => data.onGatewayAssetClose?.(node.id)} onChange={event => data.onGatewayAssetSearch?.(node.id, event.target.value)} onKeyDown={event => { if (event.key === 'Escape') data.onGatewayAssetClose?.(node.id); }} />
      {data.gatewayAssetOpen && gatewayDropdownPosition && createPortal(<div className="gateway-asset-dropdown nowheel" id={gatewayListId} role="listbox" aria-label="可绑定的主机资产" onWheelCapture={scrollGatewayAssetList} style={{ position: 'fixed', left: gatewayDropdownPosition.left, top: gatewayDropdownPosition.top, width: gatewayDropdownPosition.width, maxHeight: gatewayDropdownPosition.maxHeight }}>
        {filteredGatewayChoices.map(host => {
          const boundElsewhere = (data.gatewayBoundAssetIds ?? []).includes(host.id) && host.id !== node.assetId;
          return <button type="button" className="gateway-asset-option nodrag" role="option" aria-selected={node.assetId === host.id} disabled={boundElsewhere} key={host.id} onMouseDown={event => event.preventDefault()} onClick={() => data.onGatewayAssetSelect?.(node.id, host)}><span><strong>{host.hostname}</strong><small>{host.os ?? 'OS 未知'} · {host.arch ?? '架构未知'}</small></span><span className={`badge ${boundElsewhere ? '' : host.status === 'ONLINE' ? 'online' : host.status === 'OFFLINE' ? 'offline' : 'unknown'}`}>{boundElsewhere ? '已绑定' : host.status}</span></button>;
        })}
        {!filteredGatewayChoices.length && <div className="gateway-asset-empty">{data.gatewayAssetLoading ? '读取主机资产…' : data.gatewayAssetError ?? '没有匹配的主机资产'}</div>}
        {data.gatewayAssetNextCursor && <button type="button" className="gateway-asset-more nodrag" disabled={data.gatewayAssetLoading} onMouseDown={event => event.preventDefault()} onClick={() => data.onGatewayAssetLoadMore?.(node.id)}>{data.gatewayAssetLoading ? '读取中…' : '加载更多主机'}</button>}
      </div>, document.body)}
      {node.assetId && <button type="button" className="gateway-asset-clear nodrag" onClick={() => data.onGatewayAssetSelect?.(node.id, null)}>取消主机绑定</button>}
    </div>}
    <div className="device-ports" style={{ height }}>
      {Array.from({ length: node.inputCount }, (_, i) => <div className="device-port port-in" key={`in-${i + 1}`} style={{ top: 12 + i * 26 }}><Handle id={`in-${i + 1}`} type="target" position={Position.Left} aria-label={`${node.name} 入口 ${i + 1}`} /><span>{node.kind === 'FOLDER' && i === 0 ? 'Ingress 接入' : `入口 ${i + 1}`} · L4</span></div>)}
      {Array.from({ length: node.outputCount }, (_, i) => <div className="device-port port-out" key={`out-${i + 1}`} style={{ top: 12 + i * 26 }}><span>{node.kind === 'FOLDER' && i === 0 ? '集群服务' : `出口 ${i + 1}`} · L4</span><Handle id={`out-${i + 1}`} type="source" position={Position.Right} aria-label={`${node.name} 出口 ${i + 1}`} /></div>)}
    </div><div className="device-status">{node.kind === 'FOLDER' ? '双击打开层级视图' : node.assetId ? `资产 ${data.asset?.hostname ?? node.assetId}` : '期望节点 · 未部署'}</div>
  </div>;
}
function DirectedEdge(props: EdgeProps) {
  const [path] = getBezierPath(props); const gradient = `link-${props.id}`;
  const state = (props.data?.state ?? 'PENDING') as keyof typeof linkStateLabels;
  const colors = { PENDING: '#8897ab', VERIFIED: '#2eaf83', ERROR: '#d46c68', UNSUPPORTED: '#aa8ec2', OBSERVED: '#527eaf' };
  return <g className={`link-state-${state.toLowerCase()}`} aria-label={props.data?.observedRoute ? `Agent 路由观测 · L3/IP · ${props.data.routeCount} 条路由` : linkStateLabels[state]}><BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} style={{ stroke: colors[state], strokeWidth: props.selected ? 3 : 2, strokeDasharray: state === 'VERIFIED' || state === 'ERROR' ? undefined : '6 4' }} /></g>;
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
  const [folderId, setFolderId] = useState<string | null>(null);
  const [hostAssets, setHostAssets] = useState<Record<string, { host: Host; inventory: HostNetworkInventory }>>({});
  const [assetChoices, setAssetChoices] = useState<Host[]>([]); const [assetNextCursor, setAssetNextCursor] = useState<string | null>(null); const [assetPickerOpen, setAssetPickerOpen] = useState(false); const [assetPickerBusy, setAssetPickerBusy] = useState(false);
  const [gatewayAssetPages, setGatewayAssetPages] = useState<Record<string, { choices: Host[]; nextCursor: string | null; loading: boolean; error?: string }>>({}); const [gatewayAssetSearch, setGatewayAssetSearch] = useState<Record<string, string>>({}); const [gatewayAssetOpen, setGatewayAssetOpen] = useState<Record<string, boolean>>({});
  const gatewayAssetRequest = useRef<Record<string, number>>({});
  const [kubernetesClusters, setKubernetesClusters] = useState<KubernetesClusterSummary[]>([]);
  const [kubernetesNamespaces, setKubernetesNamespaces] = useState<KubernetesTopologyNamespaceSnapshot>();
  const [namespaceDetails, setNamespaceDetails] = useState<Record<string, KubernetesTopologyNamespaceDetail>>({});
  const [expandedNamespace, setExpandedNamespace] = useState<string>(); const [expandedPod, setExpandedPod] = useState<string>(); const [kubernetesError, setKubernetesError] = useState(''); const [kubernetesBusy, setKubernetesBusy] = useState(false);
  const [routeEvidence, setRouteEvidence] = useState<P0RouteEvidence>();
  const [menu, setMenu] = useState<Menu>(); const menuRef = useRef<HTMLDivElement>(null); const canvasRef = useRef<HTMLElement>(null);
  const { screenToFlowPosition, fitView } = useReactFlow<DeviceNode>();
  const activeFolderClusterId = nodes.find(node => node.id === folderId)?.data.model.clusterId ?? null;
  const topologyNamespacesPath = (clusterId = activeFolderClusterId) => clusterId
    ? `/kubernetes/clusters/${encodeURIComponent(clusterId)}/topology/namespaces`
    : '/kubernetes/topology/namespaces';
  const draft = (): TopologyDraftWrite => ({ nodes: nodes.map(n => ({ ...n.data.model, parentId: n.data.model.parentId ?? null, assetId: n.data.model.assetId ?? null, clusterId: n.data.model.clusterId ?? null, position: n.position })), links: edges.map(asLink) });
  async function load() {
    setBusy(true); setError(''); setMenu(undefined);
    try { const r = await api<TopologyDraft>('/topology/draft', 'GET', undefined, {}, true); const payload = { nodes: r.data.nodes.map(node => ({ ...node, parentId: node.parentId ?? null, assetId: node.assetId ?? null, clusterId: node.clusterId ?? null })), links: r.data.links.map(({ state: _state, ...link }) => link) }; const invalid = draftError(payload); if (invalid) throw new Error(invalid); setNodes(payload.nodes.map(node => asNode(node))); setEdges(r.data.links.map(asEdge)); setFolderId(null); setEtag(r.etag ?? ''); setDirty(false); setLoaded(true); setMessage(`已读取 Revision ${r.data.generation}；链路状态来自运行观测`); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }
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
  async function loadHostAssets() {
    setAssetPickerBusy(true); setError('');
    try {
      const result = await api<HostPage>('/hosts?limit=100');
      setAssetChoices(result.data.items); setAssetNextCursor(result.data.nextCursor); setAssetPickerOpen(true);
    } catch (e) { setError((e as Error).message); }
    finally { setAssetPickerBusy(false); }
  }
  async function loadMoreHostAssets() {
    if (!assetNextCursor) return;
    setAssetPickerBusy(true);
    try {
      const result = await api<HostPage>(`/hosts?limit=100&cursor=${encodeURIComponent(assetNextCursor)}`);
      setAssetChoices(current => [...current, ...result.data.items]); setAssetNextCursor(result.data.nextCursor);
    } catch (e) { setError((e as Error).message); }
    finally { setAssetPickerBusy(false); }
  }
  async function loadGatewayHostAssets(nodeId: string, cursor: string | undefined, search: string) {
    const requestId = (gatewayAssetRequest.current[nodeId] ?? 0) + 1;
    gatewayAssetRequest.current[nodeId] = requestId;
    setGatewayAssetPages(current => ({ ...current, [nodeId]: { choices: cursor ? current[nodeId]?.choices ?? [] : [], nextCursor: cursor ? current[nodeId]?.nextCursor ?? null : null, loading: true } }));
    try {
      const params = new URLSearchParams({ limit: '20' });
      if (cursor) params.set('cursor', cursor);
      if (search.trim()) params.set('search', search.trim());
      const query = `?${params}`;
      const result = await api<HostPage>(`/hosts${query}`);
      if (gatewayAssetRequest.current[nodeId] === requestId) {
        setGatewayAssetPages(current => ({ ...current, [nodeId]: { choices: cursor ? [...(current[nodeId]?.choices ?? []), ...result.data.items] : result.data.items, nextCursor: result.data.nextCursor, loading: false } }));
      }
    } catch (e) {
      if (gatewayAssetRequest.current[nodeId] === requestId) setGatewayAssetPages(current => ({ ...current, [nodeId]: { choices: [], nextCursor: null, loading: false, error: (e as Error).message } }));
    }
  }
  function openGatewayAssetPicker(nodeId: string) {
    setGatewayAssetOpen(current => ({ ...current, [nodeId]: true }));
    void loadGatewayHostAssets(nodeId, undefined, gatewayAssetSearch[nodeId] ?? '');
  }
  function changeGatewayAssetSearch(nodeId: string, value: string) {
    setGatewayAssetSearch(current => ({ ...current, [nodeId]: value }));
    setGatewayAssetOpen(current => ({ ...current, [nodeId]: true }));
    void loadGatewayHostAssets(nodeId, undefined, value);
  }
  function selectGatewayAsset(nodeId: string, host: Host | null) {
    if (host && nodes.some(node => node.id !== nodeId && node.data.model.assetId === host.id)) { setError('该主机资产已绑定到其他拓扑节点'); return; }
    setNodes(current => current.map(node => node.id === nodeId ? { ...node, data: { ...node.data, model: { ...node.data.model, assetId: host?.id ?? null }, asset: host ?? undefined } } : node));
    setGatewayAssetSearch(current => ({ ...current, [nodeId]: '' }));
    setGatewayAssetOpen(current => ({ ...current, [nodeId]: false }));
    setDirty(true); setRouteEvidence(undefined); setError('');
    setMessage(host ? `公网 Gateway 已绑定主机资产 ${host.hostname}，将读取其网络接口和路由表` : '已取消公网 Gateway 的主机资产绑定');
  }
  function selectFolderCluster(nodeId: string, clusterId: string | null) {
    setNodes(current => current.map(node => node.id === nodeId ? { ...node, data: { ...node.data, model: { ...node.data.model, clusterId } } } : node));
    if (folderId === nodeId) { setKubernetesNamespaces(undefined); setExpandedNamespace(undefined); setExpandedPod(undefined); }
    setDirty(true); setKubernetesError(''); setMessage(clusterId ? '文件夹已关联独立 Kubernetes 集群' : '文件夹将使用默认 Kubernetes 集群');
  }
  function selectRouterAddress(nodeId: string, address: string) {
    setNodes(current => current.map(node => node.id === nodeId
      ? { ...node, data: { ...node.data, model: { ...node.data.model, ipAddress: address.trim() || null } } }
      : node));
    setDirty(true);
    setRouteEvidence(undefined);
  }
  function loadMoreGatewayHostAssets(nodeId: string) {
    const page = gatewayAssetPages[nodeId];
    if (page?.nextCursor && !page.loading) {
      void loadGatewayHostAssets(nodeId, page.nextCursor, gatewayAssetSearch[nodeId] ?? '');
    }
  }
  async function loadNamespace(namespace: string) {
    setExpandedNamespace(namespace); setKubernetesBusy(true); setKubernetesError('');
    try {
      const result = await api<KubernetesTopologyNamespaceDetail>(`${topologyNamespacesPath()}/${encodeURIComponent(namespace)}`, 'GET', undefined, {}, !!activeFolderClusterId);
      setNamespaceDetails(current => ({ ...current, [`${folderId}:${activeFolderClusterId ?? 'default'}:${namespace}`]: result.data }));
    } catch (e) { setKubernetesError((e as Error).message); }
    finally { setKubernetesBusy(false); }
  }
  useEffect(() => { void load(); }, []);
  useEffect(() => { void loadServiceInventory(); }, []);
  useEffect(() => {
    if (isMock) { setKubernetesClusters([]); return; }
    let cancelled = false;
    void api<KubernetesClusterList>('/kubernetes/clusters', 'GET', undefined, {}, true)
      .then(result => { if (!cancelled) setKubernetesClusters(result.data.items); })
      .catch(e => { if (!cancelled) setError((e as Error).message); });
    return () => { cancelled = true; };
  }, []);
  useEffect(() => {
    const known = new Set(Object.keys(hostAssets));
    const missing = [...new Set(nodes.map(node => node.data.model.assetId).filter((value): value is string => !!value))].filter(id => !known.has(id));
    if (!missing.length) return;
    let cancelled = false;
    void Promise.all(missing.map(async id => {
      try {
        const [host, inventory] = await Promise.all([api<Host>(`/hosts/${id}`, 'GET', undefined, {}, true), api<HostNetworkInventory>(`/hosts/${id}/network-inventory`, 'GET', undefined, {}, true)]);
        return [id, { host: host.data, inventory: inventory.data }] as const;
      } catch { return null; }
    })).then(results => {
      if (cancelled) return;
      const ready = Object.fromEntries(results.filter((entry): entry is NonNullable<typeof entry> => !!entry));
      if (Object.keys(ready).length) {
        setHostAssets(current => ({ ...current, ...ready }));
        setNodes(current => current.map(node => ready[node.data.model.assetId ?? ''] ? { ...node, data: { ...node.data, asset: ready[node.data.model.assetId!].host } } : node));
      }
    });
    return () => { cancelled = true; };
  }, [nodes, hostAssets]);
  useEffect(() => {
    if (!folderId) { setKubernetesNamespaces(undefined); setExpandedNamespace(undefined); setExpandedPod(undefined); return; }
    let cancelled = false; setKubernetesNamespaces(undefined); setExpandedNamespace(undefined); setExpandedPod(undefined); setKubernetesBusy(true); setKubernetesError('');
    void api<KubernetesTopologyNamespaceSnapshot>(topologyNamespacesPath(), 'GET', undefined, {}, !!activeFolderClusterId)
      .then(result => { if (!cancelled) setKubernetesNamespaces(result.data); })
      .catch(e => { if (!cancelled) setKubernetesError((e as Error).message); })
      .finally(() => { if (!cancelled) setKubernetesBusy(false); });
    return () => { cancelled = true; };
  }, [folderId, activeFolderClusterId]);
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
    try { const r = await api<TopologyDraft>('/topology/draft', 'PUT', payload, { 'If-Match': etag }, true); setEtag(r.etag ?? ''); setDirty(false); setMessage(`${isMock ? 'Mock ' : ''}草稿已保存；未部署、未验证`); }
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
          if (!nodes.filter(n => (n.data.model.parentId ?? null) === folderId).some(n => candidate.x < n.position.x + 270 && candidate.x + 270 > n.position.x && candidate.y < n.position.y + 150 + Math.max(n.data.model.inputCount, n.data.model.outputCount) * 26 && candidate.y + height + 40 > n.position.y)) { position = candidate; break; }
        }
      }
    }
    if (!position) { setError('附近没有可用位置，请平移画布后重试'); return; }
    const model = { ...createTopologyNode(kind, position), parentId: folderId, assetId: null };
    setNodes(current => [...current, asNode(model)]); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setError(''); setMessage(`已添加${model.name}：${model.inputCount} 入 / ${model.outputCount} 出`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  function addHostAsset(host: Host) {
    if (nodes.some(node => node.data.model.assetId === host.id)) { setError('该主机资产已添加到主拓扑'); return; }
    if (nodes.length >= 100) { setError('最多添加 100 个节点'); return; }
    const canvas = canvasRef.current?.getBoundingClientRect();
    const point = screenToFlowPosition({ x: canvas ? canvas.left + canvas.width / 2 : window.innerWidth / 2, y: canvas ? canvas.top + canvas.height / 2 : window.innerHeight / 2 });
    const base = createTopologyNode('HOST', point);
    const model = { ...base, name: host.hostname, assetId: host.id, parentId: null };
    setNodes(current => [...current, asNode(model, host)]); setDirty(true); setRouteEvidence(undefined); setAssetPickerOpen(false); setError(''); setMessage(`已将主机资产 ${host.hostname} 添加到最外层拓扑`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  function enterFolder(id: string) {
    const folder = nodes.find(node => node.id === id)?.data.model;
    if (!folder || folder.kind !== 'FOLDER') return;
    setFolderId(id); setMenu(undefined); setMessage(`正在编辑 ${folder.name} 的层级拓扑`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  function folderPath(): TopologyNode[] {
    const path: TopologyNode[] = [];
    let current = nodes.find(node => node.id === folderId)?.data.model;
    while (current) {
      path.unshift(current);
      const parentId = current.parentId;
      current = parentId ? nodes.find(node => node.id === parentId)?.data.model : undefined;
    }
    return path;
  }
  const activeNodes = nodes.filter(node => (node.data.model.parentId ?? null) === folderId);
  const activeDisplayNodes = activeNodes.map(node => {
    if (node.data.model.kind === 'GATEWAY') {
      return {
        ...node,
        data: {
          ...node.data,
          gatewayAssetChoices: gatewayAssetPages[node.id]?.choices ?? [],
          gatewayAssetSearch: gatewayAssetSearch[node.id] ?? '',
          gatewayAssetOpen: gatewayAssetOpen[node.id] ?? false,
          gatewayAssetLoading: gatewayAssetPages[node.id]?.loading ?? false,
          gatewayAssetError: gatewayAssetPages[node.id]?.error,
          gatewayAssetMock: mockBusiness,
          gatewayAssetNextCursor: gatewayAssetPages[node.id]?.nextCursor,
          gatewayBoundAssetIds: nodes.map(item => item.data.model.assetId).filter((id): id is string => !!id),
          onGatewayAssetFocus: openGatewayAssetPicker,
          onGatewayAssetSearch: changeGatewayAssetSearch,
          onGatewayAssetClose: (id: string) => setGatewayAssetOpen(current => ({ ...current, [id]: false })),
          onGatewayAssetSelect: selectGatewayAsset,
          onGatewayAssetLoadMore: loadMoreGatewayHostAssets,
        },
      };
    }
    if (node.data.model.kind === 'FOLDER') {
      return { ...node, data: { ...node.data, clusterChoices: kubernetesClusters, onFolderClusterSelect: selectFolderCluster } };
    }
    if (node.data.model.kind === 'ROUTER') {
      return { ...node, data: { ...node.data, onRouterAddressChange: selectRouterAddress } };
    }
    return node;
  });
  const activeEdges = edges.filter(edge => {
    const source = nodes.find(node => node.id === edge.source)?.data.model;
    return source && (source.parentId ?? null) === folderId;
  });
  const folder = nodes.find(node => node.id === folderId)?.data.model;
  const rootAssetNodes = nodes.filter(node => !node.data.model.parentId && node.data.model.assetId);
  const hostByAddress = new Map<string, { id: string; hostname: string; kind: 'HOST' | 'ROUTER' }[]>();
  for (const node of rootAssetNodes) {
    const asset = hostAssets[node.data.model.assetId!];
    for (const networkInterface of asset?.inventory.interfaces ?? []) {
      for (const address of networkInterface.addresses) {
        const owners = hostByAddress.get(address.address.toLowerCase()) ?? [];
        if (!owners.some(owner => owner.id === asset.host.id)) owners.push({ id: asset.host.id, hostname: asset.host.hostname, kind: 'HOST' });
        hostByAddress.set(address.address.toLowerCase(), owners);
      }
    }
  }
  const rootNodeByNextHopId = new Map(rootAssetNodes.map(node => [node.data.model.assetId!, node]));
  for (const node of nodes.filter(item => !item.data.model.parentId && item.data.model.kind === 'ROUTER')) {
    const address = node.data.model.ipAddress?.trim().toLowerCase();
    if (!address) continue;
    const owners = hostByAddress.get(address) ?? [];
    if (!owners.some(owner => owner.id === node.id)) owners.push({ id: node.id, hostname: node.data.model.name, kind: 'ROUTER' });
    hostByAddress.set(address, owners);
    rootNodeByNextHopId.set(node.id, node);
  }
  const observedRouteCounts = new Map<string, { source: DeviceNode; target: DeviceNode; count: number }>();
  for (const source of rootAssetNodes) {
    const sourceAsset = hostAssets[source.data.model.assetId!];
    if (!sourceAsset) continue;
    for (const route of sourceAsset.inventory.routes) {
      const resolution = resolveRouteNextHop(route.gateway, sourceAsset.host.id, hostByAddress);
      if (resolution.kind !== 'MATCHED') continue;
      const target = rootNodeByNextHopId.get(resolution.host.id);
      if (!target || target.data.model.inputCount < 1 || source.data.model.outputCount < 1) continue;
      const key = `${source.id}:${target.id}`;
      const observed = observedRouteCounts.get(key);
      if (observed) observed.count++;
      else observedRouteCounts.set(key, { source, target, count: 1 });
    }
  }
  const observedRouteEdges: Edge[] = [...observedRouteCounts.entries()].map(([key, observed]) => ({
    id: `observed-${key}`, source: observed.source.id, sourceHandle: 'out-1',
    target: observed.target.id, targetHandle: 'in-1', type: 'directed', selectable: false,
    deletable: false, focusable: false, markerEnd: { type: MarkerType.ArrowClosed, color: '#527eaf' },
    data: { state: 'OBSERVED', observedRoute: true, routeCount: observed.count, layer: 'L3', protocol: 'IP' },
  }));
  const candidate = (connection: Connection | Edge): TopologyLink => ({ id: createUuid(), sourceNodeId: connection.source, sourcePort: connection.sourceHandle ?? '', targetNodeId: connection.target, targetPort: connection.targetHandle ?? '', layer: 'L4', protocol: 'TCP', status: 'UNVERIFIED' });
  const validConnection: IsValidConnection = connection => loaded && !busy && edges.length < 800 && !connectionError(draft().nodes, edges.map(asLink), candidate(connection));
  function connect(connection: Connection) {
    if (edges.length >= 800) { setError('最多添加 800 条连接'); return; }
    const link = candidate(connection); const invalid = connectionError(draft().nodes, edges.map(asLink), link);
    if (invalid) { setError(invalid); return; }
    setEdges(current => [...current, asEdge(link)]); setDirty(true); setRouteEvidence(undefined); setError(''); setMessage('已添加绿色出口 → 蓝色入口的期望连接');
  }
  function removeNode(id: string) {
    const removeIds = new Set([id]);
    let changed = true;
    while (changed) {
      changed = false;
      for (const node of nodes) if (node.data.model.parentId && removeIds.has(node.data.model.parentId) && !removeIds.has(node.id)) { removeIds.add(node.id); changed = true; }
    }
    setNodes(current => current.filter(node => !removeIds.has(node.id))); setEdges(current => current.filter(edge => !removeIds.has(edge.source) && !removeIds.has(edge.target))); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setMessage(removeIds.size > 1 ? '文件夹及全部子级节点和连接已删除' : '节点及关联连接已删除');
  }
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
    {folderId && <nav className="topology-breadcrumb" aria-label="拓扑层级"><button className="secondary" onClick={() => { setFolderId(null); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }}>主拓扑</button>{folderPath().map((item, index) => <span key={item.id}><span className="muted">/</span>{index === folderPath().length - 1 ? <strong>{item.name}</strong> : <button className="secondary" onClick={() => enterFolder(item.id)}>{item.name}</button>}</span>)}</nav>}
    <div className="page-heading"><div><h1>{isP0Lab ? '拓扑到实际路由 P0 实验' : folder?.name ?? '网络拓扑实验室'}</h1><p>{folderId ? '编辑此文件夹内的期望网络拓扑；下方可逐级浏览 Namespace、Ingress、Service 和 Pod。' : '双击文件夹进入子拓扑。绿色出口 → 蓝色入口，用多端口节点组织期望转发路径。'}</p></div><div className="topology-actions"><button className="secondary" disabled={busy} onClick={load}>{dirty ? '重新读取（丢弃未保存）' : '↻ 重新读取'}</button>{!folderId && <button className="secondary" disabled={!loaded || busy || assetPickerBusy} onClick={() => void loadHostAssets()}>{assetPickerBusy ? '读取主机…' : '＋ 添加主机资产'}</button>}<button className="primary" disabled={!loaded || busy || !dirty || !etag} onClick={save}>{busy ? '处理中…' : '保存草稿'}</button>{isP0Lab && <button className="primary" disabled={!loaded || busy || !!draftError(draft()) || !!p0RouteError(draft())} onClick={applyP0Route}>{busy ? '处理中…' : '应用到 FRP 实际路由'}</button>}</div></div>
    <div className="alert">右键画布添加交换机、转发器或 K3s 文件夹；右键节点调整端口，右键连线删除。每个端口仅连接一条线；同色连接、自连接、跨层级连接与环路均不允许。{isP0Lab ? 'P0 实验仅接受 Gateway → NAT Host → Service，并把配置部署到隔离 FRP 容器。' : '拓扑及内部编辑保存为期望草稿，不直接修改主机路由表或 Kubernetes 资源。'}</div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {routeEvidence && <div className="alert" role="status"><strong>实际路由已验证</strong> · {routeEvidence.route} · HTTP {routeEvidence.httpStatus} · 响应 {routeEvidence.responseBody} · SHA-256 {routeEvidence.configSha256}<pre className="p0-route-diff">{routeEvidence.diff}</pre></div>}
    <div className="topology-tools"><div className="port-legend"><span><i className="input-dot" />蓝色：入口</span><span><i className="output-dot" />绿色：出口</span>{Object.entries(linkStateLabels).map(([state, label]) => <span key={state}><i className={`state-dot ${state.toLowerCase()}`} />{label}</span>)}</div><div className="topology-actions"><button className="secondary" disabled={!loaded || busy} onClick={e => { const rect = e.currentTarget.getBoundingClientRect(); const canvas = canvasRef.current?.getBoundingClientRect(); openMenu({ preventDefault() {}, clientX: rect.left, clientY: rect.bottom + 8 }, undefined, undefined, canvas ? { x: canvas.left + canvas.width / 2, y: canvas.top + canvas.height / 2 } : undefined); }}>＋ 添加节点</button><button className="secondary" disabled={busy || !activeEdges.length} onClick={() => { setEdges(current => current.filter(edge => !activeEdges.some(active => active.id === edge.id))); setDirty(true); setRouteEvidence(undefined); }}>清空本层连接</button></div></div>
    <section className="topology card" aria-label="拓扑编辑器" ref={canvasRef}>
      <ReactFlow<DeviceNode> nodes={activeDisplayNodes} edges={folderId ? activeEdges : [...activeEdges, ...observedRouteEdges]} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
        onNodesChange={changes => { onNodesChange(changes); if (changes.some(c => c.type === 'position' && c.position || c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onEdgesChange={changes => { onEdgesChange(changes); if (changes.some(c => c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onConnect={connect} isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => { if (state.toHandle && !state.isValid) setError('连接无效：仅允许未占用的绿色出口 → 蓝色入口，且不能形成自连接或环路'); }}
        onPaneContextMenu={e => openMenu(e)} onNodeContextMenu={(e, node) => openMenu(e, node.id)} onNodeDoubleClick={(_, node) => { if (node.data.model.kind === 'FOLDER') enterFolder(node.id); }} onEdgeContextMenu={(e, edge) => { if (!edge.data?.observedRoute) openMenu(e, undefined, edge.id); }}
        onPaneClick={() => setMenu(undefined)} onMoveStart={() => { setMenu(undefined); setGatewayAssetOpen({}); }}
        onNodesDelete={deleted => { deleted.forEach(node => removeNode(node.id)); }}
        nodesDraggable={!busy} nodesConnectable={loaded && !busy} elementsSelectable={!busy} deleteKeyCode={busy ? null : ['Backspace', 'Delete']}
        fitView minZoom={.2} maxZoom={1.7} noWheelClassName="nowheel" translateExtent={[[-11000, -11000], [11000, 11000]]} nodeExtent={[[-10000, -10000], [10000, 10000]]}>
        <Background color="#dbe3ee" gap={24} /><Controls />
      </ReactFlow>
    </section>
    {!folderId && <section className="service-network card" aria-labelledby="host-routes-title">
      <div className="card-title"><div><h2 id="host-routes-title">主机路由观测与自动下一跳匹配</h2><p>读取已绑定主机的 Agent 路由快照，并按网卡地址关联主拓扑中的 Host 或配置了 IP 的 Router</p></div></div>
      {rootAssetNodes.length ? <div className="service-network-list">{rootAssetNodes.map(node => {
        const asset = hostAssets[node.data.model.assetId!];
        if (!asset) return <article className="service-network-item" key={node.id}><strong>{node.data.model.name}</strong><p>正在读取主机和路由快照，或当前会话无权读取。</p></article>;
        return <article className="service-network-item" key={node.id}>
          <div className="service-network-title"><div><strong>{asset.host.hostname}</strong><small>{asset.inventory.observedAt ? `路由观测于 ${new Date(asset.inventory.observedAt).toLocaleString()}` : 'Agent 尚未上报网络快照'}</small></div><span className={`badge ${asset.host.status === 'ONLINE' ? 'online' : asset.host.status === 'OFFLINE' ? 'offline' : 'unknown'}`}>{asset.host.status}</span></div>
          {asset.inventory.routes.length ? <div className="host-route-list">{asset.inventory.routes.map((route, index) => {
            const nextHop = resolveRouteNextHop(route.gateway, asset.host.id, hostByAddress);
            const summary = nextHop.kind === 'MATCHED' ? `${asset.host.hostname} → ${nextHop.host.hostname}${nextHop.host.kind === 'ROUTER' ? '（Router）' : ''}`
              : nextHop.kind === 'DIRECT' ? '直连路由'
                : `via ${route.gateway}${nextHop.kind === 'AMBIGUOUS' ? '（地址匹配到多个 Host/Router）' : nextHop.kind === 'SELF' ? '（下一跳地址属于自身）' : '（未匹配主拓扑 Host/Router）'}`;
            return <div className="host-route-row" key={`${route.destination}-${route.table}-${route.metric}-${index}`}><code>{route.destination}</code><span>{summary}</span><small>{route.interfaceName || '—'} · table {route.table} · metric {route.metric} · {route.routeType}</small></div>;
          })}</div> : <div className="empty">暂无路由记录</div>}
        </article>;
      })}</div> : <div className="empty">先在主拓扑添加已登记的主机资产，系统会自动读取并匹配其路由表。</div>}
      <p className="service-network-empty">匹配结果是只读观测关联；当前 Provider 尚未实现通用主机路由下发。</p>
    </section>}
    {folderId && <section className="service-network card" aria-labelledby="kubernetes-hierarchy-title">
      <div className="card-title"><div><h2 id="kubernetes-hierarchy-title">{folder?.name ?? 'K3s'} · Namespace 资源视图</h2><p>{folder?.clusterId ? `读取已登记集群 ${kubernetesClusters.find(cluster => cluster.id === folder.clusterId)?.name ?? folder.clusterId}。` : '读取默认 Kubernetes 集群。'}点击 Namespace 展开 Ingress、Service 与 Pod，再打开 Pod 查看容器、端口和重启计数。</p></div><button className="secondary" disabled={kubernetesBusy} onClick={() => { setKubernetesNamespaces(undefined); setKubernetesError(''); setKubernetesBusy(true); void api<KubernetesTopologyNamespaceSnapshot>(topologyNamespacesPath(), 'GET', undefined, {}, !!activeFolderClusterId).then(result => setKubernetesNamespaces(result.data)).catch(e => setKubernetesError((e as Error).message)).finally(() => setKubernetesBusy(false)); }}>↻ 刷新 Namespace</button></div>
      {kubernetesError && <div className="alert error" role="alert">{kubernetesError}{!isMock && ' · 请启用 Kubernetes 服务发现并为 Core 身份授予 Namespace、Pod、Service、Ingress 的 get/list 权限。'}</div>}
      {kubernetesNamespaces && <div className="service-network-meta"><span><b>{kubernetesNamespaces.sourceId}</b></span><span>观测于 {new Date(kubernetesNamespaces.observedAt).toLocaleString()}</span>{mockBusiness && <span className="badge pending">Mock 示例</span>}</div>}
      {kubernetesBusy && !kubernetesNamespaces ? <div className="empty">读取集群 Namespace…</div> : kubernetesNamespaces?.namespaces.length ? <div className="kubernetes-namespace-list">{kubernetesNamespaces.namespaces.map(namespace => {
        const detail = namespaceDetails[`${folderId}:${activeFolderClusterId ?? 'default'}:${namespace.name}`];
        const expanded = expandedNamespace === namespace.name;
        return <article className="kubernetes-namespace" key={namespace.name}>
          <button className="kubernetes-namespace-heading" aria-expanded={expanded} onClick={() => { if (expanded) setExpandedNamespace(undefined); else if (detail) setExpandedNamespace(namespace.name); else void loadNamespace(namespace.name); }}><span><strong>▰ {namespace.name}</strong><small>Namespace · {namespace.phase}</small></span><span>{expanded ? '收起 ↑' : '展开资源 ↓'}</span></button>
          {expanded && detail && <div className="kubernetes-resource-view">
            <div className="service-network-meta"><span>刷新于 {new Date(detail.observedAt).toLocaleString()}</span><span>{detail.ingresses.length} Ingress · {detail.services.length} Service · {detail.pods.length} Pod</span></div>
            <h3>Ingress 路由</h3>{detail.ingresses.length ? detail.ingresses.map(ingress => <div className="kubernetes-resource-row" key={ingress.name}><strong>{ingress.name}</strong><small>{ingress.ingressClassName ?? '未指定 IngressClass'} · {ingress.loadBalancerAddresses.join(', ') || '无 LoadBalancer 地址'}</small>{ingress.routes.map((route, index) => <code key={`${route.host}-${route.path}-${index}`}>{route.host ?? '*'}{route.path ?? '/'} → {route.serviceName}:{route.servicePortName ?? route.servicePort ?? '?'}</code>)}</div>) : <p className="service-network-empty">此 Namespace 没有 Ingress。</p>}
            <h3>Service</h3>{detail.services.length ? detail.services.map(service => <div className="kubernetes-resource-row" key={service.name}><strong>{service.name}</strong><small>{service.type} · {service.clusterIps.join(', ') || service.externalName || '无 ClusterIP'}</small>{service.ports.map((port, index) => <code key={`${port.name}-${index}`}>{port.name ? `${port.name} · ` : ''}{port.port}/{port.protocol} → {port.targetPort ?? port.port}</code>)}</div>) : <p className="service-network-empty">此 Namespace 没有 Service。</p>}
            <h3>Pods</h3>{detail.pods.length ? detail.pods.map(pod => {
              const podKey = `${namespace.name}/${pod.name}`;
              const open = expandedPod === podKey;
              return <div className="kubernetes-resource-row pod-row" key={pod.name}><button className="pod-open" aria-expanded={open} onClick={() => setExpandedPod(open ? undefined : podKey)}><strong>▣ {pod.name}</strong><span className={`badge ${pod.ready ? 'online' : 'offline'}`}>{pod.phase} · {pod.ready ? 'Ready' : 'NotReady'}</span><small>{pod.podIp ?? '无 Pod IP'} · {pod.nodeName ?? '未调度'}</small></button>{open && <div className="pod-detail-view"><strong>Pod 内视图</strong><small>创建于 {pod.createdAt ? new Date(pod.createdAt).toLocaleString() : '未知'}</small>{pod.containers.length ? pod.containers.map(container => <div className="pod-container" key={container.name}><span><b>{container.name}</b> · {container.image ?? '镜像未知'}</span><small>{container.ready ? 'Ready' : 'NotReady'} · 重启 {container.restartCount} 次 · {container.ports.map(port => `${port.name ? `${port.name}:` : ''}${port.containerPort}/${port.protocol}`).join(', ') || '无端口'}</small></div>) : <p className="service-network-empty">Pod 没有容器清单。</p>}</div>}</div>;
            }) : <p className="service-network-empty">此 Namespace 没有 Pod。</p>}
          </div>}
          {expanded && !detail && kubernetesBusy && <div className="empty">读取 Namespace 资源…</div>}
        </article>;
      })}</div> : <div className="empty">{kubernetesNamespaces ? '此集群没有 Namespace。' : '尚未读取集群清单。'}</div>}
    </section>}
    {!folderId && <section className="service-network card" aria-labelledby="service-network-title">
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
    </section>}
    <NetbirdProviderPanel />
    <div className="topology-caption"><span>全拓扑 {nodes.length} 个节点 · {edges.length} 条连接；当前层 {activeNodes.length} 个节点 · {activeEdges.length} 条连接 · {dirty ? '有未保存的修改' : '草稿未修改'}</span><span role="status">{message || (busy ? '正在读取草稿…' : '')}</span></div>
    {menu && <div className="topology-menu" role="menu" aria-label="拓扑右键菜单" ref={menuRef} style={{ left: menu.x, top: menu.y } as CSSProperties}>
      {contextNode ? <><strong>{contextNode.name} · {contextNode.inputCount} 入 / {contextNode.outputCount} 出</strong>{contextNode.kind === 'FOLDER' && <button role="menuitem" onClick={() => enterFolder(contextNode.id)}>打开层级视图</button>}{(['inputCount', 'outputCount'] as const).map(direction => <div key={direction} className="menu-port-actions">{[1, -1].map(delta => <button role="menuitem" key={delta} disabled={!portChangeCandidate(contextNode, direction, delta)} onClick={() => changePorts(contextNode, direction, delta)}>{delta > 0 ? '增加' : '减少'}{direction === 'inputCount' ? '入口' : '出口'}</button>)}</div>)}<button role="menuitem" className="danger" onClick={() => removeNode(contextNode.id)}>删除节点及关联连接</button></> : menu.edgeId ? <><strong>期望连接 · {linkStateLabels[(contextEdge?.data?.state ?? 'PENDING') as keyof typeof linkStateLabels]} · L4/{(contextEdge?.data?.protocol ?? 'TCP') as string}</strong><button role="menuitem" className="danger" onClick={() => { setEdges(current => current.filter(e => e.id !== menu.edgeId)); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); }}>删除连接</button></> : <><strong>添加节点</strong>{(Object.keys(nodePresets) as TopologyNodeKind[]).map(kind => <button role="menuitem" key={kind} onClick={() => addNode(kind)}><span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small></button>)}</>}
      <button role="menuitem" className="menu-cancel" onClick={() => setMenu(undefined)}>关闭菜单 · Esc</button>
    </div>}
    {assetPickerOpen && <div className="modal-backdrop"><section className="modal topology-asset-picker" role="dialog" aria-modal="true" aria-labelledby="topology-asset-picker-title"><div className="card-title"><div><span className="eyebrow">REGISTERED HOSTS</span><h2 id="topology-asset-picker-title">添加主机资产到主拓扑</h2></div><button className="icon-button" aria-label="关闭添加主机资产" onClick={() => setAssetPickerOpen(false)}>×</button></div><p>选择已登记主机后，拓扑会绑定该资产并自动读取 Agent 上报的路由表。</p><div className="topology-asset-list">{assetChoices.map(host => { const bound = nodes.some(node => node.data.model.assetId === host.id); return <button className="topology-asset-option" key={host.id} disabled={bound} onClick={() => addHostAsset(host)}><span><strong>{host.hostname}</strong><small>{host.os ?? 'OS 未知'} · {host.arch ?? '架构未知'} · {host.agentId ? 'Agent 已绑定' : '等待 Agent'}</small></span><span className={`badge ${host.status === 'ONLINE' ? 'online' : host.status === 'OFFLINE' ? 'offline' : 'unknown'}`}>{bound ? '已添加' : host.status}</span></button>; })}</div>{assetNextCursor && <button className="secondary" disabled={assetPickerBusy} onClick={() => void loadMoreHostAssets()}>{assetPickerBusy ? '读取中…' : '加载更多主机'}</button>}{!assetChoices.length && <div className="empty">还没有已登记主机。</div>}</section></div>}
  </>;
}
export default function Topology() { return <ReactFlowProvider><Editor /></ReactFlowProvider>; }
