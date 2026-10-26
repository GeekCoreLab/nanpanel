import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { WheelEvent as ReactWheelEvent } from 'react';
import { createPortal } from 'react-dom';
import { parseDocument, stringify as stringifyYaml } from 'yaml';
import {
  ReactFlowProvider, Handle, Position, ConnectionMode,
  MarkerType, useNodesState, useEdgesState, useReactFlow, useUpdateNodeInternals,
  type Node, type NodeProps, type Edge, type Connection, type IsValidConnection,
} from '@xyflow/react';
import { api, isMock, isP0Lab } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import LinuxNetnsProviderPanel from './LinuxNetnsProviderPanel';
import NetbirdProviderPanel from './NetbirdProviderPanel';
import NetworkProviderCapabilities from './NetworkProviderCapabilities';
import { compareTopologyDrafts, connectionError, connectionReviewMessage, createTopologyNode, draftError, nodePresets, providerSupportsDevice, resolveRouteNextHop, unmatchedRouteGateways, validNodeShape } from './lib/topology-model';
import { TopologyCanvas } from './topology/TopologyCanvas';
import { TopologyContextMenu, TopologyContextMenuGroup, TopologyContextMenuHeading, TopologyContextMenuItem } from './topology/TopologyContextMenu';
import './topology-status.css';
import './topology-hierarchy.css';
import './topology-workspaces.css';
import './gateway-asset-picker.css';
import './service-network.css';
import './topology-node-settings.css';
import './topology-revisions.css';
import './network-provider-capabilities.css';
import type { FirewallPolicy, FirewallRule, Host, HostNetworkInventory, HostPage, KubernetesClusterList, KubernetesClusterSummary, KubernetesServiceInventorySnapshot, KubernetesServiceProbeSnapshot, KubernetesTopologyNamespaceDetail, KubernetesTopologyNamespaceSnapshot, NetworkProvider, RouterConfiguration, TopologyDraft, TopologyDraftWrite, TopologyLayer, TopologyLink, TopologyNode, TopologyNodeKind, TopologyProtocol, TopologyRevisionList, TopologyRevisionSummary } from './lib/types';

type DeviceNode = Node<{
  model: TopologyNode;
  asset?: Host;
  observedOnly?: boolean;
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
  onEditSourceYaml?: (node: TopologyNode) => void;
}, 'device'>;
type P0RouteEvidence = { status: 'VERIFIED'; route: string; httpStatus: number; responseBody: string; configSha256: string; diff: string; verifiedAt: string };
const virtualKinds: TopologyNodeKind[] = ['SWITCH', 'FIREWALL', 'PACKET_TAP', 'MERGE', 'SPLIT', 'FORWARDER', 'LOAD_BALANCER', 'MIRROR', 'CUSTOM'];
const asNode = (model: TopologyNode, asset?: Host): DeviceNode => ({ id: model.id, type: 'device', position: model.position, data: { model: { ...model, parentId: model.parentId ?? null, assetId: model.assetId ?? null, clusterId: model.clusterId ?? null, ipAddress: model.ipAddress ?? null }, asset } });
const asEdge = (link: TopologyLink): Edge => ({ id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort, target: link.targetNodeId, targetHandle: link.targetPort, type: 'nanpanelBezier', markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' }, data: { status: 'UNVERIFIED', state: link.state ?? 'PENDING', layer: link.layer, protocol: link.protocol }, ariaLabel: `期望连接，${link.state ?? 'PENDING'} · ${link.layer}/${link.protocol}` });
const asLink = (edge: Edge): TopologyLink => ({ id: edge.id, sourceNodeId: edge.source, sourcePort: edge.sourceHandle ?? '', targetNodeId: edge.target, targetPort: edge.targetHandle ?? '', layer: (edge.data?.layer as TopologyLink['layer']) ?? 'L4', protocol: (edge.data?.protocol as TopologyLink['protocol']) ?? 'TCP', status: 'UNVERIFIED' });
const linkStateLabels = { PENDING: '待部署', VERIFIED: '已验证', ERROR: '异常', UNSUPPORTED: '不可支持', OBSERVED: 'Agent 路由观测' } as const;
const estimatedNodeHeight = (node: Pick<TopologyNode, 'kind' | 'inputCount' | 'outputCount'>) => {
  const portRows = Math.max(node.inputCount, node.outputCount, 1) * 26 + 16;
  const controls = node.kind === 'GATEWAY' ? 80 : node.kind === 'ROUTER' ? 64 : node.kind === 'FOLDER' ? 52 : 0;
  return 132 + portRows + controls;
};

const topologyNodeYamlApiVersion = 'topology.nanpanel.io/v1alpha1';
const topologyNodeYamlFields = [
  'kind', 'name', 'description', 'position', 'inputCount', 'outputCount', 'ports',
  'parentId', 'assetId', 'clusterId', 'ipAddress', 'providerId', 'switchBackend',
  'firewallPolicy', 'routerConfiguration',
];

function topologyNodeSourceYaml(node: TopologyNode) {
  return stringifyYaml({
    apiVersion: topologyNodeYamlApiVersion,
    kind: 'TopologyNode',
    metadata: { id: node.id },
    spec: Object.fromEntries(topologyNodeYamlFields.map(field => [
      field,
      (node as unknown as Record<string, unknown>)[field] ?? null,
    ])),
  }, { indent: 2, lineWidth: 0 });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function hasExactKeys(value: unknown, keys: string[]): value is Record<string, unknown> {
  return isRecord(value) && Object.keys(value).length === keys.length
    && keys.every(key => Object.hasOwn(value, key));
}

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
  return <div className={`topology-device ${node.kind === 'FOLDER' ? 'is-folder' : ''} ${data.observedOnly ? 'is-observed' : ''} ${selected ? 'is-selected' : ''}`}>
    <div className="device-title"><span className="device-symbol">{node.kind === 'FOLDER' ? '▰' : node.kind === 'SWITCH' ? '▦' : virtualKinds.includes(node.kind) ? '⇄' : '▤'}</span><div><strong>{node.name}</strong><small>{data.observedOnly ? 'Agent 自动发现 · 只读观测' : node.assetId ? `主机资产 · ${data.asset?.status ?? '读取中'}` : nodePresets[node.kind].description}</small></div></div>
    {node.kind === 'FOLDER' && <div className="folder-cluster-control nodrag"><label htmlFor={`folder-cluster-${node.id}`}>关联 Kubernetes 集群</label><select id={`folder-cluster-${node.id}`} className="nodrag nowheel" aria-label={`${node.name}关联 Kubernetes 集群`} value={node.clusterId ?? ''} onChange={event => data.onFolderClusterSelect?.(node.id, event.target.value || null)}><option value="">使用默认集群</option>{(data.clusterChoices ?? []).map(cluster => <option value={cluster.id} key={cluster.id}>{cluster.name} · {cluster.healthStatus === 'HEALTHY' ? '健康' : '不可用'}</option>)}</select></div>}
    {node.kind === 'ROUTER' && (data.observedOnly
      ? <div className="router-address-control"><label>Agent 观察到的下一跳</label><code>{node.ipAddress}</code></div>
      : <div className="router-address-control nodrag"><label htmlFor={`router-address-${node.id}`}>下一跳 IP 地址</label><input id={`router-address-${node.id}`} className="nodrag" inputMode="text" maxLength={45} placeholder="例如 10.0.0.1" value={node.ipAddress ?? ''} onChange={event => data.onRouterAddressChange?.(node.id, event.target.value)} /></div>)}
    {node.kind === 'GATEWAY' && <div className="gateway-asset-control nodrag">
      <label htmlFor={`gateway-asset-search-${node.id}`}>绑定主机资产 <span className={`gateway-asset-source ${data.gatewayAssetMock ? 'is-mock' : ''}`}>{data.gatewayAssetMock ? 'Mock 演示数据' : '实时资产'}</span></label>
      <div className="gateway-asset-input-row"><input ref={gatewaySearchInput} id={`gateway-asset-search-${node.id}`} className="nodrag" role="combobox" aria-label="搜索 Gateway 主机资产" aria-autocomplete="list" aria-expanded={!!data.gatewayAssetOpen} aria-controls={gatewayListId} placeholder={data.asset?.hostname ?? '输入主机名搜索…'} value={data.gatewayAssetSearch ?? ''} onFocus={() => data.onGatewayAssetFocus?.(node.id)} onBlur={() => data.onGatewayAssetClose?.(node.id)} onChange={event => data.onGatewayAssetSearch?.(node.id, event.target.value)} onKeyDown={event => { if (event.key === 'Escape') data.onGatewayAssetClose?.(node.id); }} />
        {node.assetId && <button type="button" className="gateway-asset-clear nodrag" aria-label="取消主机绑定" title="取消主机绑定" onClick={() => data.onGatewayAssetSelect?.(node.id, null)}>×</button>}
      </div>
      {data.gatewayAssetOpen && gatewayDropdownPosition && createPortal(<div className="gateway-asset-dropdown nowheel" id={gatewayListId} role="listbox" aria-label="可绑定的主机资产" onWheelCapture={scrollGatewayAssetList} style={{ position: 'fixed', left: gatewayDropdownPosition.left, top: gatewayDropdownPosition.top, width: gatewayDropdownPosition.width, maxHeight: gatewayDropdownPosition.maxHeight }}>
        {filteredGatewayChoices.map(host => {
          const boundElsewhere = (data.gatewayBoundAssetIds ?? []).includes(host.id) && host.id !== node.assetId;
          return <button type="button" className="gateway-asset-option nodrag" role="option" aria-selected={node.assetId === host.id} disabled={boundElsewhere} key={host.id} onMouseDown={event => event.preventDefault()} onClick={() => data.onGatewayAssetSelect?.(node.id, host)}><span><strong>{host.hostname}</strong><small>{host.os ?? 'OS 未知'} · {host.arch ?? '架构未知'}</small></span><span className={`badge ${boundElsewhere ? '' : host.status === 'ONLINE' ? 'online' : host.status === 'OFFLINE' ? 'offline' : 'unknown'}`}>{boundElsewhere ? '已绑定' : host.status}</span></button>;
        })}
        {!filteredGatewayChoices.length && <div className="gateway-asset-empty">{data.gatewayAssetLoading ? '读取主机资产…' : data.gatewayAssetError ?? '没有匹配的主机资产'}</div>}
        {data.gatewayAssetNextCursor && <button type="button" className="gateway-asset-more nodrag" disabled={data.gatewayAssetLoading} onMouseDown={event => event.preventDefault()} onClick={() => data.onGatewayAssetLoadMore?.(node.id)}>{data.gatewayAssetLoading ? '读取中…' : '加载更多主机'}</button>}
      </div>, document.body)}
    </div>}
    <div className="device-ports" style={{ height }}>
      {Array.from({ length: node.inputCount }, (_, i) => { const port = node.ports.find(value => value.id === `in-${i + 1}`); return <div className="device-port port-in" key={`in-${i + 1}`} style={{ top: 12 + i * 26 }}><Handle id={`in-${i + 1}`} type="target" position={Position.Left} aria-label={`${node.name} 入口 ${i + 1}`} /><span>{node.kind === 'FOLDER' && i === 0 ? 'Ingress 接入' : `入口 ${i + 1}`} · {port?.layer ?? 'L4'}/{port?.protocols.join(',') ?? 'TCP'}</span></div>; })}
      {Array.from({ length: node.outputCount }, (_, i) => { const port = node.ports.find(value => value.id === `out-${i + 1}`); return <div className="device-port port-out" key={`out-${i + 1}`} style={{ top: 12 + i * 26 }}><span>{node.kind === 'FOLDER' && i === 0 ? '集群服务' : `出口 ${i + 1}`} · {port?.layer ?? 'L4'}/{port?.protocols.join(',') ?? 'TCP'}</span><Handle id={`out-${i + 1}`} type="source" position={Position.Right} aria-label={`${node.name} 出口 ${i + 1}`} /></div>; })}
    </div>{node.description && <p className="device-description">{node.description}</p>}<div className="device-status">{node.kind === 'FOLDER' ? '双击打开层级视图' : node.providerId ? `Provider ${node.providerId} · 尚未部署` : node.assetId ? `资产 ${data.asset?.hostname ?? node.assetId}` : '期望节点 · 未部署'}</div>
    <div className="device-yaml-footer"><button type="button" className="device-yaml-button nodrag" disabled={!!data.observedOnly}
      title={data.observedOnly ? 'Agent 观测节点为只读' : '编辑该节点的源 YAML'}
      onClick={event => { event.stopPropagation(); data.onEditSourceYaml?.(node); }}>编辑源 YAML</button></div>
  </div>;
}

function TopologyNodeSourceYamlDialog({
  node, disabled, error, onClose, onSave,
}: {
  node: TopologyNode;
  disabled: boolean;
  error: string;
  onClose: () => void;
  onSave: (sourceYaml: string) => void;
}) {
  const [sourceYaml, setSourceYaml] = useState(() => topologyNodeSourceYaml(node));
  return <div className="modal-backdrop"><section className="modal topology-node-yaml-dialog" role="dialog" aria-modal="true" aria-labelledby="topology-node-yaml-title">
    <div className="card-title"><div><span className="eyebrow">TOPOLOGY NODE SOURCE YAML</span><h2 id="topology-node-yaml-title">{node.kind} · {node.name}</h2><p>id 与 kind 固定；保存只更新拓扑草稿，不会立即应用到隔离 netns。</p></div><button type="button" className="icon-button" aria-label="关闭 YAML 编辑器" onClick={onClose}>×</button></div>
    <form onSubmit={event => { event.preventDefault(); onSave(sourceYaml); }}>
      <label className="topology-node-yaml-label" htmlFor="topology-node-source-yaml">源 YAML</label>
      <textarea id="topology-node-source-yaml" className="topology-node-source-yaml" spellCheck={false}
        autoCapitalize="off" autoCorrect="off" maxLength={262_144} rows={22}
        value={sourceYaml} onChange={event => setSourceYaml(event.target.value)} />
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={disabled}>保存到拓扑草稿</button></div>
    </form>
  </section></div>;
}

const nodeTypes = { device: Device };
type Menu = { x: number; y: number; point: { x: number; y: number }; nodeId?: string; edgeId?: string };
type SwitchBackend = 'BRIDGE' | 'OPENVSWITCH';
type NodeSettings = { nodeId: string; name: string; description: string; layer: TopologyLayer; protocols: TopologyProtocol[]; providerId: string; switchBackend: SwitchBackend; firewallPolicy: FirewallPolicy; routerConfiguration: RouterConfiguration };
const protocolOptions: Record<TopologyLayer, TopologyProtocol[]> = {
  L2: ['ETHERNET'], L3: ['IP'], L4: ['TCP', 'UDP', 'SCTP'], L7: ['HTTP', 'HTTPS'],
};

function Editor() {
  const [nodes, setNodes, onNodesChange] = useNodesState<DeviceNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [etag, setEtag] = useState(''); const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false); const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState(''); const [error, setError] = useState('');
  const [serviceInventory, setServiceInventory] = useState<KubernetesServiceInventorySnapshot>();
  const [serviceProbes, setServiceProbes] = useState<KubernetesServiceProbeSnapshot>();
  const [serviceInventoryError, setServiceInventoryError] = useState(''); const [inventoryBusy, setInventoryBusy] = useState(false);
  const [inventoryClock, setInventoryClock] = useState(Date.now());
  const inventoryRequestInFlight = useRef(false);
  const [serviceSearch, setServiceSearch] = useState('');
  const [folderId, setFolderId] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<'topology' | 'istio' | 'providers'>('topology');
  const [hostAssets, setHostAssets] = useState<Record<string, { host: Host; inventory: HostNetworkInventory }>>({});
  const hostAssetLoads = useRef(new Set<string>());
  const [assetChoices, setAssetChoices] = useState<Host[]>([]); const [assetNextCursor, setAssetNextCursor] = useState<string | null>(null); const [assetPickerOpen, setAssetPickerOpen] = useState(false); const [assetPickerBusy, setAssetPickerBusy] = useState(false);
  const [gatewayAssetPages, setGatewayAssetPages] = useState<Record<string, { choices: Host[]; nextCursor: string | null; loading: boolean; error?: string }>>({}); const [gatewayAssetSearch, setGatewayAssetSearch] = useState<Record<string, string>>({}); const [gatewayAssetOpen, setGatewayAssetOpen] = useState<Record<string, boolean>>({});
  const gatewayAssetRequest = useRef<Record<string, number>>({});
  const [kubernetesClusters, setKubernetesClusters] = useState<KubernetesClusterSummary[]>([]);
  const [kubernetesNamespaces, setKubernetesNamespaces] = useState<KubernetesTopologyNamespaceSnapshot>();
  const [namespaceDetails, setNamespaceDetails] = useState<Record<string, KubernetesTopologyNamespaceDetail>>({});
  const [expandedNamespace, setExpandedNamespace] = useState<string>(); const [expandedPod, setExpandedPod] = useState<string>(); const [kubernetesError, setKubernetesError] = useState(''); const [kubernetesBusy, setKubernetesBusy] = useState(false);
  const [routeEvidence, setRouteEvidence] = useState<P0RouteEvidence>();
  const [revisionHistoryOpen, setRevisionHistoryOpen] = useState(false);
  const [revisionSummaries, setRevisionSummaries] = useState<TopologyRevisionSummary[]>([]);
  const [selectedRevision, setSelectedRevision] = useState<TopologyDraft>();
  const [revisionLoading, setRevisionLoading] = useState(false);
  const [revisionError, setRevisionError] = useState('');
  const [menu, setMenu] = useState<Menu>(); const canvasRef = useRef<HTMLElement>(null);
  const [nodeSettings, setNodeSettings] = useState<NodeSettings>(); const [nodeSettingsError, setNodeSettingsError] = useState('');
  const [sourceYamlNode, setSourceYamlNode] = useState<TopologyNode>(); const [sourceYamlError, setSourceYamlError] = useState('');
  const [networkProviders, setNetworkProviders] = useState<NetworkProvider[]>([]);
  const [networkProvidersError, setNetworkProvidersError] = useState('');
  const observedRouterSignatureRef = useRef('');
  const { screenToFlowPosition, fitView } = useReactFlow<DeviceNode>();
  const updateNodeInternals = useUpdateNodeInternals();
  const activeFolderClusterId = nodes.find(node => node.id === folderId)?.data.model.clusterId ?? null;
  const settingsNode = nodes.find(node => node.id === nodeSettings?.nodeId)?.data.model;
  const compatibleProviders = settingsNode ? networkProviders.filter(provider =>
    providerSupportsDevice(provider, settingsNode.kind, nodeSettings?.layer ?? 'L4',
      nodeSettings?.protocols ?? [])) : [];
  const topologyNamespacesPath = (clusterId = activeFolderClusterId) => clusterId
    ? `/kubernetes/clusters/${encodeURIComponent(clusterId)}/topology/namespaces`
    : '/kubernetes/topology/namespaces';
  const draft = (): TopologyDraftWrite => ({ nodes: nodes.filter(node => !node.data.observedOnly).map(n => ({ ...n.data.model, parentId: n.data.model.parentId ?? null, assetId: n.data.model.assetId ?? null, clusterId: n.data.model.clusterId ?? null, position: n.position })), links: edges.map(asLink) });
  async function load() {
    setBusy(true); setError(''); setMenu(undefined);
    try { const r = await api<TopologyDraft>('/topology/draft', 'GET', undefined, {}, true); const payload = { nodes: r.data.nodes.map(node => ({ ...node, parentId: node.parentId ?? null, assetId: node.assetId ?? null, clusterId: node.clusterId ?? null })), links: r.data.links.map(({ state: _state, ...link }) => link) }; const invalid = draftError(payload); if (invalid) throw new Error(invalid); setNodes(payload.nodes.map(node => asNode(node))); setEdges(r.data.links.map(asEdge)); setFolderId(null); setWorkspace('topology'); setEtag(r.etag ?? ''); setDirty(false); setLoaded(true); setMessage(`已读取 Revision ${r.data.generation}；链路状态来自运行观测`); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function openRevisionHistory() {
    setRevisionHistoryOpen(true); setRevisionError(''); setSelectedRevision(undefined); setRevisionLoading(true);
    try {
      const result = await api<TopologyRevisionList>('/topology/revisions?limit=50', 'GET', undefined, {}, true);
      setRevisionSummaries(result.data.items);
      if (result.data.items.length) {
        const revision = await api<TopologyDraft>(`/topology/revisions/${encodeURIComponent(result.data.items[0].revisionId)}`, 'GET', undefined, {}, true);
        setSelectedRevision(revision.data);
      }
    } catch (e) { setRevisionError((e as Error).message); }
    finally { setRevisionLoading(false); }
  }
  async function selectRevision(revisionId: string) {
    setRevisionLoading(true); setRevisionError(''); setSelectedRevision(undefined);
    try {
      const revision = await api<TopologyDraft>(`/topology/revisions/${encodeURIComponent(revisionId)}`, 'GET', undefined, {}, true);
      setSelectedRevision(revision.data);
    } catch (e) { setRevisionError((e as Error).message); }
    finally { setRevisionLoading(false); }
  }
  function replayRevision() {
    if (!selectedRevision) return;
    if (dirty && !window.confirm('当前画布有未保存修改。回放历史版本会替换这些修改，但不会立即保存或部署。继续吗？')) return;
    setNodes(selectedRevision.nodes.map(node => asNode(node)));
    setEdges(selectedRevision.links.map(link => asEdge(link)));
    setFolderId(null); setWorkspace('topology'); setDirty(true); setRouteEvidence(undefined);
    setRevisionHistoryOpen(false);
    setMessage(`Revision ${selectedRevision.generation} 已回放为未保存草稿；保存前不会应用网络规则`);
    requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  async function loadServiceInventory(refresh = false) {
    if (isMock) {
      setServiceInventory(undefined);
      setServiceInventoryError('服务网络只显示真实 Kubernetes 与 Istio 数据；Mock 模式不会提供模拟服务图。');
      return;
    }
    if (inventoryRequestInFlight.current) return;
    inventoryRequestInFlight.current = true;
    setInventoryBusy(true); setServiceInventoryError('');
    try {
      const result = await api<KubernetesServiceInventorySnapshot>(refresh ? '/kubernetes/service-inventory/refresh' : '/kubernetes/service-inventory', refresh ? 'POST' : 'GET', undefined, {}, true);
      setServiceInventory(result.data);
      setInventoryClock(Date.now());
      const probes = await api<KubernetesServiceProbeSnapshot>('/kubernetes/service-probes', 'GET', undefined, {}, true); setServiceProbes(probes.data);
    }
    catch (e) { setServiceInventoryError((e as Error).message); }
    finally { inventoryRequestInFlight.current = false; setInventoryBusy(false); }
  }
  async function loadHostAssets() {
    setAssetPickerBusy(true); setError('');
    try {
      const result = await api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true);
      setAssetChoices(result.data.items); setAssetNextCursor(result.data.nextCursor); setAssetPickerOpen(true);
    } catch (e) { setError((e as Error).message); }
    finally { setAssetPickerBusy(false); }
  }
  async function loadMoreHostAssets() {
    if (!assetNextCursor) return;
    setAssetPickerBusy(true);
    try {
      const result = await api<HostPage>(`/hosts?limit=100&cursor=${encodeURIComponent(assetNextCursor)}`, 'GET', undefined, {}, true);
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
      const result = await api<HostPage>(`/hosts${query}`, 'GET', undefined, {}, true);
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
      const result = await api<KubernetesTopologyNamespaceDetail>(`${topologyNamespacesPath()}/${encodeURIComponent(namespace)}`, 'GET', undefined, {}, true);
      setNamespaceDetails(current => ({ ...current, [`${folderId}:${activeFolderClusterId ?? 'default'}:${namespace}`]: result.data }));
    } catch (e) { setKubernetesError((e as Error).message); }
    finally { setKubernetesBusy(false); }
  }
  useEffect(() => { void load(); }, []);
  useEffect(() => {
    let cancelled = false;
    void api<NetworkProvider[]>('/network-providers', 'GET', undefined, {}, true)
      .then(result => { if (!cancelled) { setNetworkProviders(result.data); setNetworkProvidersError(''); } })
      .catch(reason => { if (!cancelled) setNetworkProvidersError((reason as Error).message); });
    return () => { cancelled = true; };
  }, []);
  useEffect(() => {
    if (isMock) return;
    void loadServiceInventory();
    const timer = window.setInterval(() => {
      setInventoryClock(Date.now());
      if (!document.hidden) void loadServiceInventory();
    }, 30_000);
    return () => window.clearInterval(timer);
  }, []);
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
    const missing = [...new Set(nodes.map(node => node.data.model.assetId).filter((value): value is string => !!value))]
      .filter(id => !known.has(id) && !hostAssetLoads.current.has(id));
    if (!missing.length) return;
    missing.forEach(id => hostAssetLoads.current.add(id));
    void Promise.all(missing.map(async id => {
      try {
        const [host, inventory] = await Promise.all([api<Host>(`/hosts/${id}`, 'GET', undefined, {}, true), api<HostNetworkInventory>(`/hosts/${id}/network-inventory`, 'GET', undefined, {}, true)]);
        return [id, { host: host.data, inventory: inventory.data }] as const;
      } catch { return null; }
    })).then(results => {
      const ready = Object.fromEntries(results.filter((entry): entry is NonNullable<typeof entry> => !!entry));
      if (Object.keys(ready).length) {
        setHostAssets(current => ({ ...current, ...ready }));
        setNodes(current => current.map(node => ready[node.data.model.assetId ?? ''] ? { ...node, data: { ...node.data, asset: ready[node.data.model.assetId!].host } } : node));
      }
    }).finally(() => missing.forEach(id => hostAssetLoads.current.delete(id)));
  }, [nodes, hostAssets]);
  useEffect(() => {
    if (!folderId) { setKubernetesNamespaces(undefined); setExpandedNamespace(undefined); setExpandedPod(undefined); return; }
    let cancelled = false; setKubernetesNamespaces(undefined); setExpandedNamespace(undefined); setExpandedPod(undefined); setKubernetesBusy(true); setKubernetesError('');
    void api<KubernetesTopologyNamespaceSnapshot>(topologyNamespacesPath(), 'GET', undefined, {}, true)
      .then(result => { if (!cancelled) setKubernetesNamespaces(result.data); })
      .catch(e => { if (!cancelled) setKubernetesError((e as Error).message); })
      .finally(() => { if (!cancelled) setKubernetesBusy(false); });
    return () => { cancelled = true; };
  }, [folderId, activeFolderClusterId]);
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
    setMenu({ x: event.clientX, y: event.clientY, point: screenToFlowPosition(insertionPoint ?? { x: event.clientX, y: event.clientY }), nodeId, edgeId });
  }
  function updateFirewallRule(index: number, patch: Partial<FirewallRule>) {
    if (!nodeSettings) return;
    const rules = nodeSettings.firewallPolicy.rules.map((rule, ruleIndex) => ruleIndex === index
      ? { ...rule, ...patch } : rule);
    setNodeSettings({ ...nodeSettings, firewallPolicy: { ...nodeSettings.firewallPolicy, rules } });
  }
  function updateRouterInterface(index: number, patch: Partial<RouterConfiguration['interfaces'][number]>) {
    if (!nodeSettings) return;
    const interfaces = nodeSettings.routerConfiguration.interfaces.map((iface, row) => row === index ? { ...iface, ...patch } : iface);
    setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, interfaces } });
  }
  function updateRouterRoute(index: number, patch: Partial<RouterConfiguration['routes'][number]>) {
    if (!nodeSettings) return;
    const routes = nodeSettings.routerConfiguration.routes.map((route, row) => row === index ? { ...route, ...patch } : route);
    setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, routes } });
  }
  function openSourceYamlEditor(node: TopologyNode) {
    setSourceYamlNode(node);
    setSourceYamlError('');
    setMenu(undefined);
  }
  function saveSourceYaml(sourceYaml: string) {
    const original = sourceYamlNode;
    if (!original) return;
    if (!sourceYaml.trim() || sourceYaml.length > 262_144) {
      setSourceYamlError('本项 YAML 必须为 1–262144 个字符。');
      return;
    }
    try {
      const document = parseDocument(sourceYaml, { uniqueKeys: true });
      if (document.errors.length) throw new Error(document.errors[0].message);
      const parsed: unknown = document.toJS({ maxAliasCount: 16 });
      const specKeys = topologyNodeYamlFields;
      if (!hasExactKeys(parsed, ['apiVersion', 'kind', 'metadata', 'spec'])) {
        throw new Error('文档必须符合 topology.nanpanel.io/v1alpha1 TopologyNode 格式，并保持 metadata.id 与全部 spec 字段。');
      }
      const metadata = parsed.metadata;
      const parsedSpec = parsed.spec;
      if (parsed.apiVersion !== topologyNodeYamlApiVersion || parsed.kind !== 'TopologyNode'
          || !hasExactKeys(metadata, ['id']) || metadata.id !== original.id
          || !hasExactKeys(parsedSpec, specKeys)) {
        throw new Error('文档必须符合 topology.nanpanel.io/v1alpha1 TopologyNode 格式，并保持 metadata.id 与全部 spec 字段。');
      }
      const spec = parsedSpec as unknown as Partial<TopologyNode>;
      if (spec.kind !== original.kind) throw new Error('源 YAML 不能更改节点 kind。');
      const updated: TopologyNode = { ...original, ...spec, id: original.id, kind: original.kind };
      const currentDraft = draft();
      const candidate = {
        ...currentDraft,
        nodes: currentDraft.nodes.map(node => node.id === updated.id ? updated : node),
      };
      const invalid = draftError(candidate);
      if (invalid) throw new Error(`节点 YAML 会使当前拓扑无效：${invalid}`);
      const providerUsesOvs = candidate.nodes.some(node => node.providerId === 'linux-netns'
        && node.kind === 'SWITCH' && node.switchBackend === 'OPENVSWITCH');
      if (providerUsesOvs && candidate.nodes.some(node => node.providerId === 'linux-netns'
          && ['FIREWALL', 'PACKET_TAP'].includes(node.kind))) {
        throw new Error('Open vSwitch 切片当前不能与同一 linux-netns Provider 下的 Firewall 或 Packet Tap 共存。');
      }
      setNodes(current => current.map(node => node.id === updated.id
        ? { ...node, position: updated.position, data: { ...node.data, model: updated } }
        : node));
      setSourceYamlNode(undefined);
      setSourceYamlError('');
      setDirty(true);
      setRouteEvidence(undefined);
      setMessage(`已将 ${updated.name} 的 YAML 解析为拓扑草稿；保存草稿后才会创建新 Revision`);
    } catch (cause) {
      setSourceYamlError((cause as Error).message);
    }
  }
  function openNodeSettings(node: TopologyNode) {
    const firstPort = node.ports[0];
    setNodeSettings({ nodeId: node.id, name: node.name, description: node.description ?? '', layer: firstPort?.layer ?? 'L4', protocols: [...(firstPort?.protocols ?? ['TCP'])], providerId: node.providerId ?? '', switchBackend: node.switchBackend ?? 'BRIDGE', firewallPolicy: { defaultAction: node.firewallPolicy?.defaultAction ?? 'DROP', rules: (node.firewallPolicy?.rules ?? []).map(rule => ({ ...rule })) }, routerConfiguration: { interfaces: (node.routerConfiguration?.interfaces ?? []).map(iface => ({ ...iface })), routes: (node.routerConfiguration?.routes ?? []).map(route => ({ ...route })) } });
    setNodeSettingsError(''); setMenu(undefined);
  }
  function saveNodeSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!nodeSettings) return;
    if (!nodeSettings.protocols.length || nodeSettings.protocols.some(protocol => !protocolOptions[nodeSettings.layer].includes(protocol))) {
      setNodeSettingsError('至少选择一个与接口网络层相符的协议。'); return;
    }
    const current = nodes.find(node => node.id === nodeSettings.nodeId)?.data.model;
    if (!current) { setNodeSettings(undefined); return; }
    if (nodeSettings.providerId && !compatibleProviders.some(provider => provider.id === nodeSettings.providerId)) {
      setNodeSettingsError('所选 Provider 未声明此设备、网络层、协议及完整变更生命周期能力。'); return;
    }
    const updated = { ...current, name: nodeSettings.name.trim(), description: nodeSettings.description.trim() || null,
      providerId: nodeSettings.providerId || null,
      switchBackend: current.kind === 'SWITCH' ? nodeSettings.switchBackend : null,
      firewallPolicy: current.kind === 'FIREWALL' ? nodeSettings.firewallPolicy : null,
      routerConfiguration: current.kind === 'ROUTER' && (nodeSettings.routerConfiguration.interfaces.length || nodeSettings.routerConfiguration.routes.length) ? nodeSettings.routerConfiguration : null,
      ports: current.ports.map(port => ({ ...port, layer: nodeSettings.layer, protocols: [...nodeSettings.protocols] })) };
    const candidateNodes = nodes.map(node => node.id === current.id ? updated : node.data.model);
    const providerUsesOvs = candidateNodes.some(node => node.providerId === 'linux-netns' && node.kind === 'SWITCH' && node.switchBackend === 'OPENVSWITCH');
    if (providerUsesOvs && candidateNodes.some(node => node.providerId === 'linux-netns' && ['FIREWALL', 'PACKET_TAP'].includes(node.kind))) {
      setNodeSettingsError('Open vSwitch 切片当前不能与同一 linux-netns Provider 下的 Firewall 或 Packet Tap 共存。'); return;
    }
    const invalid = draftError({ nodes: candidateNodes, links: edges.map(asLink) });
    if (invalid) { setNodeSettingsError(`这些接口设置会使当前拓扑无效：${invalid}`); return; }
    setNodes(existing => existing.map(node => node.id === current.id ? { ...node, data: { ...node.data, model: updated } } : node));
    setNodeSettings(undefined); setNodeSettingsError(''); setDirty(true); setRouteEvidence(undefined); setMessage(`已更新 ${updated.name} 的节点与接口信息${updated.providerId ? `，绑定 Provider ${updated.providerId}` : ''}`);
  }
  function addNode(kind: TopologyNodeKind) {
    if (nodes.filter(node => !node.data.observedOnly).length >= 100) { setError('最多添加 100 个节点'); return; }
    const point = menu?.point ?? screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
    const height = estimatedNodeHeight({ kind, inputCount: nodePresets[kind].inputs, outputCount: nodePresets[kind].outputs });
    // Keep the clicked position when free; otherwise find a nearby free grid cell.
    let position: { x: number; y: number } | undefined;
    for (let ring = 0; ring <= 20 && !position; ring++) {
      for (let dx = -ring; dx <= ring && !position; dx++) {
        for (let dy = -ring; dy <= ring; dy++) {
          if (ring && Math.abs(dx) !== ring && Math.abs(dy) !== ring) continue;
          const candidate = { x: Math.max(-10000, Math.min(10000, point.x + dx * 300)), y: Math.max(-10000, Math.min(10000, point.y + dy * (height + 40))) };
          if (!nodes.filter(n => (n.data.model.parentId ?? null) === folderId).some(n => candidate.x < n.position.x + 270 && candidate.x + 270 > n.position.x && candidate.y < n.position.y + estimatedNodeHeight(n.data.model) + 40 && candidate.y + height + 40 > n.position.y)) { position = candidate; break; }
        }
      }
    }
    if (!position) { setError('附近没有可用位置，请平移画布后重试'); return; }
    const model = { ...createTopologyNode(kind, position), parentId: folderId, assetId: null };
    setNodes(current => [...current, asNode(model)]); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); setError(''); setMessage(`已添加${model.name}：${model.inputCount} 入 / ${model.outputCount} 出`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  function addHostAsset(host: Host) {
    if (nodes.some(node => node.data.model.assetId === host.id)) { setError('该主机资产已添加到主拓扑'); return; }
    if (nodes.filter(node => !node.data.observedOnly).length >= 100) { setError('最多添加 100 个节点'); return; }
    const canvas = canvasRef.current?.getBoundingClientRect();
    const point = screenToFlowPosition({ x: canvas ? canvas.left + canvas.width / 2 : window.innerWidth / 2, y: canvas ? canvas.top + canvas.height / 2 : window.innerHeight / 2 });
    const height = estimatedNodeHeight({ kind: 'HOST', inputCount: nodePresets.HOST.inputs, outputCount: nodePresets.HOST.outputs });
    let position: { x: number; y: number } | undefined;
    const rootNodes = nodes.filter(node => (node.data.model.parentId ?? null) === null);
    for (let ring = 0; ring <= 20 && !position; ring++) {
      for (let dx = -ring; dx <= ring && !position; dx++) {
        for (let dy = -ring; dy <= ring; dy++) {
          if (ring && Math.abs(dx) !== ring && Math.abs(dy) !== ring) continue;
          const candidate = { x: Math.max(-10000, Math.min(10000, point.x + dx * 300)), y: Math.max(-10000, Math.min(10000, point.y + dy * (height + 40))) };
          if (!rootNodes.some(node => candidate.x < node.position.x + 270 && candidate.x + 270 > node.position.x && candidate.y < node.position.y + estimatedNodeHeight(node.data.model) + 40 && candidate.y + height + 40 > node.position.y)) {
            position = candidate;
            break;
          }
        }
      }
    }
    if (!position) { setError('附近没有可用位置，请平移画布后重试'); return; }
    const base = createTopologyNode('HOST', position);
    const model = { ...base, name: host.hostname, assetId: host.id, parentId: null };
    setNodes(current => [...current, asNode(model, host)]); setDirty(true); setRouteEvidence(undefined); setAssetPickerOpen(false); setError(''); setMessage(`已将主机资产 ${host.hostname} 添加到最外层拓扑`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
  }
  function enterFolder(id: string) {
    const folder = nodes.find(node => node.id === id)?.data.model;
    if (!folder || folder.kind !== 'FOLDER') return;
    setFolderId(id); setWorkspace('topology'); setMenu(undefined); setMessage(`正在编辑 ${folder.name} 的层级拓扑`); requestAnimationFrame(() => { void fitView({ padding: .2 }); });
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
    const onEditSourceYaml = (model: TopologyNode) => openSourceYamlEditor({ ...model, position: node.position });
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
          gatewayAssetMock: isMock,
          gatewayAssetNextCursor: gatewayAssetPages[node.id]?.nextCursor,
          gatewayBoundAssetIds: nodes.map(item => item.data.model.assetId).filter((id): id is string => !!id),
          onGatewayAssetFocus: openGatewayAssetPicker,
          onGatewayAssetSearch: changeGatewayAssetSearch,
          onGatewayAssetClose: (id: string) => setGatewayAssetOpen(current => ({ ...current, [id]: false })),
          onGatewayAssetSelect: selectGatewayAsset,
          onGatewayAssetLoadMore: loadMoreGatewayHostAssets,
          onEditSourceYaml,
        },
      };
    }
    if (node.data.model.kind === 'FOLDER') {
      return { ...node, data: { ...node.data, clusterChoices: kubernetesClusters, onFolderClusterSelect: selectFolderCluster, onEditSourceYaml } };
    }
    if (node.data.model.kind === 'ROUTER') {
      return { ...node, data: { ...node.data, onRouterAddressChange: selectRouterAddress, onEditSourceYaml } };
    }
    return { ...node, data: { ...node.data, onEditSourceYaml } };
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
  const observedRoutes = rootAssetNodes.flatMap(node => {
    const asset = hostAssets[node.data.model.assetId!];
    return asset?.inventory.routes.map(route => ({ sourceHostId: asset.host.id, gateway: route.gateway })) ?? [];
  });
  const routeSourceHostIds = new Map(rootAssetNodes.flatMap(node => {
    const asset = hostAssets[node.data.model.assetId!];
    return asset ? [[asset.host.id, asset.host.id] as const] : [];
  }));
  const occupiedRootNodes = nodes.filter(node => !node.data.model.parentId && !node.data.observedOnly);
  const autoRouterNodes = folderId ? [] : unmatchedRouteGateways(observedRoutes, routeSourceHostIds, hostByAddress)
    .map((address, index) => {
      const width = 270;
      const height = estimatedNodeHeight({ kind: 'ROUTER', inputCount: 1, outputCount: 1 });
      let position: { x: number; y: number } | undefined;
      for (let ring = 0; ring <= 20 && !position; ring++) {
        for (let dx = -ring; dx <= ring && !position; dx++) {
          for (let dy = -ring; dy <= ring; dy++) {
            if (ring && Math.abs(dx) !== ring && Math.abs(dy) !== ring) continue;
            const candidate = {
              x: Math.max(0, ...rootAssetNodes.map(node => node.position.x)) + 360 + dx * 300,
              y: 80 + index * (height + 40) + dy * (height + 40),
            };
            const overlaps = occupiedRootNodes.some(node => candidate.x < node.position.x + 270
              && candidate.x + width > node.position.x
              && candidate.y < node.position.y + estimatedNodeHeight(node.data.model) + 40
              && candidate.y + height > node.position.y);
            if (!overlaps) { position = candidate; break; }
          }
        }
      }
      const model = createTopologyNode('ROUTER', position ?? {
        x: Math.max(0, ...rootAssetNodes.map(node => node.position.x)) + 360,
        y: 80 + index * (height + 40),
      });
      model.id = `observed-router-${encodeURIComponent(address)}`;
      model.name = `自动发现 Router ${address}`;
      model.ipAddress = address;
      const node = asNode(model);
      node.data.observedOnly = true;
      node.initialWidth = 230;
      node.initialHeight = 180;
      node.selectable = false;
      node.draggable = false;
      node.deletable = false;
      occupiedRootNodes.push(node);
      const owners = hostByAddress.get(address) ?? [];
      owners.push({ id: node.id, hostname: model.name, kind: 'ROUTER' });
      hostByAddress.set(address, owners);
      return node;
    });
  const observedRouterSignature = autoRouterNodes.map(node => node.id).join('|');
  useEffect(() => {
    setNodes(current => {
      const configured = current.filter(node => !node.data.observedOnly);
      const existingIds = current.filter(node => node.data.observedOnly).map(node => node.id).sort();
      const desiredIds = autoRouterNodes.map(node => node.id).sort();
      if (existingIds.length === desiredIds.length && existingIds.every((id, index) => id === desiredIds[index])) return current;
      return [...configured, ...autoRouterNodes];
    });
  }, [observedRouterSignature, setNodes]);
  const renderedObservedRouterSignature = nodes.filter(node => node.data.observedOnly).map(node => node.id).sort().join('|');
  useEffect(() => {
    const desiredSignature = autoRouterNodes.map(node => node.id).sort().join('|');
    if (!loaded || folderId || menu || renderedObservedRouterSignature !== desiredSignature || observedRouterSignatureRef.current === desiredSignature) return;
    observedRouterSignatureRef.current = desiredSignature;
    const fitTimer = window.setTimeout(() => {
      autoRouterNodes.forEach(node => updateNodeInternals(node.id));
      requestAnimationFrame(() => void fitView({ padding: .2, includeHiddenNodes: true }));
    }, 120);
    return () => window.clearTimeout(fitTimer);
  }, [fitView, folderId, loaded, menu, observedRouterSignature, renderedObservedRouterSignature, updateNodeInternals]);
  const rootNodeByNextHopId = new Map(rootAssetNodes.map(node => [node.data.model.assetId!, node]));
  for (const node of nodes.filter(item => !item.data.observedOnly && !item.data.model.parentId && item.data.model.kind === 'ROUTER')) {
    const address = node.data.model.ipAddress?.trim().toLowerCase();
    if (!address) continue;
    const owners = hostByAddress.get(address) ?? [];
    if (!owners.some(owner => owner.id === node.id)) owners.push({ id: node.id, hostname: node.data.model.name, kind: 'ROUTER' });
    hostByAddress.set(address, owners);
    rootNodeByNextHopId.set(node.id, node);
  }
  for (const node of autoRouterNodes) rootNodeByNextHopId.set(node.id, node);
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
    target: observed.target.id, targetHandle: 'in-1', type: 'nanpanelBezier', selectable: false,
    deletable: false, focusable: false, markerEnd: { type: MarkerType.ArrowClosed, color: '#527eaf' },
    data: { state: 'OBSERVED', observedRoute: true, routeCount: observed.count, layer: 'L3', protocol: 'IP' },
  }));
  const candidate = (connection: Connection | Edge): TopologyLink => {
    const source = nodes.find(node => node.id === connection.source)?.data.model;
    const target = nodes.find(node => node.id === connection.target)?.data.model;
    const sourcePort = source?.ports.find(port => port.id === connection.sourceHandle);
    const targetPort = target?.ports.find(port => port.id === connection.targetHandle);
    const sharedProtocols = sourcePort && targetPort && sourcePort.layer === targetPort.layer
      ? sourcePort.protocols.filter(protocol => targetPort.protocols.includes(protocol)) : [];
    const protocol = (['ETHERNET', 'IP', 'TCP', 'UDP', 'SCTP', 'HTTP', 'HTTPS'] as const)
      .find(value => sharedProtocols.includes(value));
    return {
      id: createUuid(), sourceNodeId: connection.source, sourcePort: connection.sourceHandle ?? '',
      targetNodeId: connection.target, targetPort: connection.targetHandle ?? '',
      layer: sourcePort?.layer ?? targetPort?.layer ?? 'L4', protocol: protocol ?? 'TCP', status: 'UNVERIFIED',
    };
  };
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
    const reference = node.ports.find(port => port.direction === (input ? 'INPUT' : 'OUTPUT'));
    const addedPort = { id: `${input ? 'in' : 'out'}-${nextCount}`, direction: input ? 'INPUT' as const : 'OUTPUT' as const, layer: reference?.layer ?? 'L4' as const, protocols: reference?.protocols ?? ['TCP', 'UDP'] as ('TCP' | 'UDP')[] };
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
  const visibleNetworkServices = serviceInventory?.services.filter(service =>
    service.serviceName.toLocaleLowerCase().includes(serviceSearch.trim().toLocaleLowerCase())) ?? [];
  const totalServiceEndpoints = serviceInventory?.services.reduce((count, service) =>
    count + service.endpoints.length, 0) ?? 0;
  const readyServiceEndpoints = serviceInventory?.services.reduce((count, service) =>
    count + service.endpoints.filter(endpoint => endpoint.ready === true).length, 0) ?? 0;
  const istioTraffic = serviceInventory?.istio.traffic.filter(edge =>
    `${edge.sourceWorkload} ${edge.destinationService} ${edge.destinationWorkload}`
      .toLocaleLowerCase().includes(serviceSearch.trim().toLocaleLowerCase())) ?? [];
  const observedIstioRequests = serviceInventory?.istio.traffic.reduce((count, edge) =>
    count + edge.requestCount, 0) ?? 0;
  const revisionDiff = selectedRevision ? compareTopologyDrafts(selectedRevision, draft()) : undefined;
  const topologyNodeName = (nodeId: string) => draft().nodes.find(node => node.id === nodeId)?.name
    ?? selectedRevision?.nodes.find(node => node.id === nodeId)?.name ?? nodeId;
  const topologyLinkName = (link: TopologyLink) => `${topologyNodeName(link.sourceNodeId)}:${link.sourcePort} → ${topologyNodeName(link.targetNodeId)}:${link.targetPort}`;
  const inventoryStale = serviceInventory?.observedAt
    ? inventoryClock - new Date(serviceInventory.observedAt).getTime() > 120_000 : false;
  const istioTelemetryStale = serviceInventory?.istio.observedAt
    ? inventoryClock - new Date(serviceInventory.istio.observedAt).getTime() > 120_000 : false;
  return <>
    {folderId && <nav className="topology-breadcrumb" aria-label="拓扑层级"><button className="secondary" onClick={() => { setFolderId(null); setWorkspace('topology'); requestAnimationFrame(() => { void fitView({ padding: .2 }); }); }}>主拓扑</button>{folderPath().map((item, index) => <span key={item.id}><span className="muted">/</span>{index === folderPath().length - 1 ? <strong>{item.name}</strong> : <button className="secondary" onClick={() => enterFolder(item.id)}>{item.name}</button>}</span>)}</nav>}
    <div className="page-heading"><div><h1>{isP0Lab ? '拓扑到实际路由 P0 实验' : folder?.name ?? '网络拓扑实验室'}</h1><p>{folderId ? '编辑此文件夹内的期望网络拓扑；下方可逐级浏览 Namespace、Ingress、Service 和 Pod。' : workspace === 'istio' ? '查看 Kubernetes Service、EndpointSlice 与 Istio Envoy 的真实流量观测。' : workspace === 'providers' ? '管理已接入的网络 Provider 配置。' : '双击文件夹进入子拓扑。绿色出口 → 蓝色入口，用多端口节点组织期望转发路径。'}</p></div><div className="topology-actions">{(workspace === 'topology' || !!folderId || isP0Lab) && <><button className="secondary" disabled={busy} onClick={load}>{dirty ? '重新读取（丢弃未保存）' : '↻ 重新读取'}</button><button className="primary" disabled={!loaded || busy || !dirty || !etag} onClick={save}>{busy ? '处理中…' : '保存草稿'}</button></>}{!folderId && workspace === 'topology' && !isP0Lab && <button className="secondary" disabled={!loaded || busy || revisionLoading} onClick={() => void openRevisionHistory()}>{revisionLoading ? '读取版本…' : '版本比较 / 回放'}</button>}{!folderId && workspace === 'topology' && <button className="secondary" disabled={!loaded || busy || assetPickerBusy} onClick={() => void loadHostAssets()}>{assetPickerBusy ? '读取主机…' : '＋ 添加主机资产'}</button>}{isP0Lab && <button className="primary" disabled={!loaded || busy || !!draftError(draft()) || !!p0RouteError(draft())} onClick={applyP0Route}>{busy ? '处理中…' : '应用到 FRP 实际路由'}</button>}</div></div>
    <div className="alert">{workspace === 'topology' || folderId || isP0Lab ? <>右键画布添加交换机、转发器或 K3s 文件夹；右键节点调整端口，右键连线删除。每个端口仅连接一条线；同色连接、自连接、跨层级连接与环路均不允许。{isP0Lab ? 'P0 实验仅接受 Gateway → NAT Host → Service，并把配置部署到隔离 FRP 容器。' : '拓扑及内部编辑保存为期望草稿，不直接修改主机路由表或 Kubernetes 资源。'}</> : workspace === 'istio' ? '此视图只展示 Core 从 Kubernetes API 与 Istio Envoy 采集的数据；Mock 模式会明确提示未连接，不生成服务或流量样例。' : 'Provider 配置单独呈现，不与主拓扑和运行观测混排。'}</div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {routeEvidence && <div className="alert" role="status"><strong>实际路由已验证</strong> · {routeEvidence.route} · HTTP {routeEvidence.httpStatus} · 响应 {routeEvidence.responseBody} · SHA-256 {routeEvidence.configSha256}<pre className="p0-route-diff">{routeEvidence.diff}</pre></div>}
    {!folderId && !isP0Lab && <nav className="topology-workspace-tabs" aria-label="网络工作区">
      <button type="button" aria-current={workspace === 'topology' ? 'page' : undefined} onClick={() => setWorkspace('topology')}><strong>拓扑编辑</strong><small>主机、Gateway 与路由</small></button>
      <button type="button" aria-current={workspace === 'istio' ? 'page' : undefined} onClick={() => setWorkspace('istio')}><strong>Istio 服务网络</strong><small>{isMock ? '仅真实数据' : serviceInventory?.istio.status ?? '读取中'}</small></button>
      <button type="button" aria-current={workspace === 'providers' ? 'page' : undefined} onClick={() => setWorkspace('providers')}><strong>网络接入</strong><small>Provider 配置</small></button>
    </nav>}
    {(workspace === 'topology' || !!folderId || isP0Lab) && <>
    <div className="topology-tools"><div className="port-legend"><span><i className="input-dot" />蓝色：入口</span><span><i className="output-dot" />绿色：出口</span>{Object.entries(linkStateLabels).map(([state, label]) => <span key={state}><i className={`state-dot ${state.toLowerCase()}`} />{label}</span>)}</div><div className="topology-actions"><button className="secondary" disabled={!loaded || busy} onClick={e => { const rect = e.currentTarget.getBoundingClientRect(); const canvas = canvasRef.current?.getBoundingClientRect(); openMenu({ preventDefault() {}, clientX: rect.left, clientY: rect.bottom + 8 }, undefined, undefined, canvas ? { x: canvas.left + canvas.width / 2, y: canvas.top + canvas.height / 2 } : undefined); }}>＋ 添加节点</button><button className="secondary" disabled={busy || !activeEdges.length} onClick={() => { setEdges(current => current.filter(edge => !activeEdges.some(active => active.id === edge.id))); setDirty(true); setRouteEvidence(undefined); }}>清空本层连接</button></div></div>
    <section className="topology card" aria-label="拓扑编辑器" ref={canvasRef}>
      <TopologyCanvas<DeviceNode, Edge> nodes={activeDisplayNodes} edges={folderId ? activeEdges : [...activeEdges, ...observedRouteEdges]} nodeTypes={nodeTypes}
        onNodesChange={changes => { onNodesChange(changes); if (changes.some(c => c.type === 'position' && c.position || c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onEdgesChange={changes => { onEdgesChange(changes); if (changes.some(c => c.type === 'remove')) { setDirty(true); setRouteEvidence(undefined); } }}
        onConnect={connect} reviewConnection={connection => connectionReviewMessage(draft().nodes, candidate(connection))} isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => { if (state.toHandle && !state.isValid) setError('连接无效：仅允许未占用的绿色出口 → 蓝色入口，且不能形成自连接或环路'); }}
        onCanvasContextMenu={openMenu} onNodeContextMenu={(e, node) => { if (!node.data.observedOnly) openMenu(e, node.id); }} onNodeDoubleClick={(_, node) => { if (!node.data.observedOnly && node.data.model.kind === 'FOLDER') enterFolder(node.id); }} onEdgeContextMenu={(e, edge) => { if (!edge.data?.observedRoute) openMenu(e, undefined, edge.id); }}
        onPaneClick={() => setMenu(undefined)} onMoveStart={event => { if (event) { setMenu(undefined); setGatewayAssetOpen({}); } }}
        onNodesDelete={deleted => { deleted.forEach(node => removeNode(node.id)); }}
        nodesDraggable={!busy} nodesConnectable={loaded && !busy} elementsSelectable={!busy} deleteKeyCode={busy ? null : ['Backspace', 'Delete']}
        fitView minZoom={.2} maxZoom={1.7} noWheelClassName="nowheel" translateExtent={[[-11000, -11000], [11000, 11000]]} nodeExtent={[[-10000, -10000], [10000, 10000]]}>
      </TopologyCanvas>
    </section>
    {!folderId && workspace === 'topology' && <section className="service-network card" aria-labelledby="host-routes-title">
      <div className="card-title"><div><h2 id="host-routes-title">主机路由观测与自动下一跳匹配</h2><p>读取已绑定主机的 Agent 路由快照；未建模的唯一下一跳会自动显示为只读 Router</p></div></div>
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
      <div className="card-title"><div><h2 id="kubernetes-hierarchy-title">{folder?.name ?? 'K3s'} · Namespace 资源视图</h2><p>{folder?.clusterId ? `读取已登记集群 ${kubernetesClusters.find(cluster => cluster.id === folder.clusterId)?.name ?? folder.clusterId}。` : '读取默认 Kubernetes 集群。'}点击 Namespace 展开 Ingress、Service 与 Pod，再打开 Pod 查看容器、端口和重启计数。</p></div><button className="secondary" disabled={kubernetesBusy} onClick={() => { setKubernetesNamespaces(undefined); setKubernetesError(''); setKubernetesBusy(true); void api<KubernetesTopologyNamespaceSnapshot>(topologyNamespacesPath(), 'GET', undefined, {}, true).then(result => setKubernetesNamespaces(result.data)).catch(e => setKubernetesError((e as Error).message)).finally(() => setKubernetesBusy(false)); }}>↻ 刷新 Namespace</button></div>
      {kubernetesError && <div className="alert error" role="alert">{kubernetesError}{!isMock && ' · 请启用 Kubernetes 服务发现并为 Core 身份授予 Namespace、Pod、Service、Ingress 的 get/list 权限。'}</div>}
      {kubernetesNamespaces && <div className="service-network-meta"><span><b>{kubernetesNamespaces.sourceId}</b></span><span>观测于 {new Date(kubernetesNamespaces.observedAt).toLocaleString()}</span>{isMock && <span className="badge pending">Mock 示例</span>}</div>}
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
    </>}
    {!folderId && !isP0Lab && workspace === 'istio' && <section className="service-network card" aria-labelledby="service-network-title">
      <div className="card-title"><div><h2 id="service-network-title">Istio 服务网络</h2><p>Service / EndpointSlice 来自 Kubernetes API；流量、响应码和 mTLS 来自 Istio Envoy 实时指标</p></div>{!isMock && <button className="secondary" disabled={inventoryBusy} onClick={() => void loadServiceInventory(true)}>{inventoryBusy ? '读取中…' : '↻ 刷新实时数据'}</button>}</div>
      {isMock ? <div className="service-network-live-required" role="note"><span className="service-network-live-icon">◉</span><div><strong>实时 Istio 数据尚未连接</strong><p>服务网络不显示 Mock 样例。切换到连接 K3s Core 的实时模式后，页面才会读取真实 Service、EndpointSlice 和 Envoy 指标。</p></div></div> : <>
        {serviceInventoryError && <div className="alert error" role="alert">{serviceInventoryError} · 检查 Core 集群身份、namespace、Pod 读取权限及 Istio Envoy metrics 可达性。</div>}
        {serviceInventory && <>
          <div className="service-network-meta"><span>集群 <b>{serviceInventory.sourceId}</b></span><span>namespace <b>{serviceInventory.namespace}</b></span><span>清单 {serviceInventory.observedAt ? new Date(serviceInventory.observedAt).toLocaleString() : '未采集'}{inventoryStale && <b className="badge pending">清单过期</b>}</span><span className={`badge ${istioTelemetryStale ? 'pending' : serviceInventory.istio.status === 'LIVE' ? 'online' : serviceInventory.istio.status === 'PARTIAL' ? 'pending' : 'unknown'}`}>Istio {serviceInventory.istio.status}{istioTelemetryStale ? ' · 观测过期' : ''}</span></div>
          <div className="service-network-overview">
            <article><span>Services</span><strong>{serviceInventory.services.length}</strong><small>{readyServiceEndpoints}/{totalServiceEndpoints} 个 Endpoint Ready</small></article>
            <article><span>Istio sidecar</span><strong>{serviceInventory.istio.sidecarsReady}/{serviceInventory.istio.sidecarsDiscovered}</strong><small>{serviceInventory.istio.sidecarsScraped} 个代理取得指标</small></article>
            <article><span>Envoy requests</span><strong>{observedIstioRequests.toLocaleString()}</strong><small>各代理自启动以来的目的端累计值</small></article>
            <article><span>mTLS requests</span><strong>{serviceInventory.istio.traffic.filter(edge => edge.securityPolicy === 'MUTUAL_TLS').reduce((count, edge) => count + edge.requestCount, 0).toLocaleString()}</strong><small>{serviceProbes?.enabled ? 'TCP 连通探测另列，不代表 HTTP 策略' : '基于 Istio destination reporter'}</small></article>
          </div>
          <div className="service-network-controls"><label htmlFor="service-network-search">筛选 Service 或 workload</label><input id="service-network-search" type="search" value={serviceSearch} onChange={event => setServiceSearch(event.target.value)} placeholder="输入 service / workload 名称" /></div>
          <div className="service-network-content">
            <section className="service-network-services" aria-labelledby="service-network-services-title"><div className="service-network-section-heading"><div><h3 id="service-network-services-title">Kubernetes Services</h3><small>展开单项查看端点与 sidecar 状态</small></div><span>{visibleNetworkServices.length} / {serviceInventory.services.length}</span></div>
              {visibleNetworkServices.length ? <div className="service-network-list">{visibleNetworkServices.map(service => {
                const ready = service.endpoints.filter(endpoint => endpoint.ready === true).length;
                const sidecarReady = service.endpoints.filter(endpoint => endpoint.istioProxyStatus === 'READY').length;
                return <details className="service-network-item" key={service.serviceName}>
                  <summary><span className="service-network-name"><strong>{service.serviceName}</strong><small>{service.type} · {service.clusterIps.length ? service.clusterIps.join(', ') : service.externalName ?? '无 ClusterIP'}</small></span><span className="service-network-summary-counts"><b>{ready}/{service.endpoints.length}</b><small>Ready · {sidecarReady} sidecar</small></span></summary>
                  <div className="service-network-ports">{service.ports.map((port, index) => <span className="chip" key={`${port.name ?? 'port'}-${index}`}>{port.name ? `${port.name} · ` : ''}{port.port}/{port.protocol} → {port.targetPort}</span>)}</div>
                  {service.endpoints.length ? <div className="service-network-endpoints">{service.endpoints.map((endpoint, index) => {
                    const endpointProbes = serviceProbes?.probes.filter(probe => probe.serviceName === service.serviceName && probe.address === endpoint.address) ?? [];
                    return <div key={`${endpoint.address}-${index}`}><span className={`badge ${endpoint.ready === true ? 'online' : endpoint.ready === false ? 'offline' : 'unknown'}`}>{endpoint.ready === true ? 'Ready' : endpoint.ready === false ? 'Not ready' : 'Unknown'}</span><code>{endpoint.address}</code><span>{endpoint.targetKind && endpoint.targetName ? `${endpoint.targetKind}/${endpoint.targetName}` : '无 Pod 身份'}</span><span className={`badge ${endpoint.istioProxyStatus === 'READY' ? 'available' : endpoint.istioProxyStatus === 'NOT_INJECTED' ? 'pending' : 'unknown'}`} title={endpoint.istioProxyVersion ?? undefined}>Istio {endpoint.istioProxyStatus === 'READY' ? endpoint.istioProxyVersion ?? 'Ready' : endpoint.istioProxyStatus === 'NOT_INJECTED' ? '未注入' : endpoint.istioProxyStatus === 'NOT_READY' ? 'Sidecar 未就绪' : '未知'}</span><small>{endpoint.nodeName ?? endpoint.addressType}{endpoint.terminating ? ' · 正在终止' : ''}</small>{serviceProbes?.enabled && <span className="service-network-probe">{endpointProbes.length ? endpointProbes.map(probe => <span className={`badge ${probe.outcome === 'SUCCEEDED' ? 'succeeded' : probe.outcome === 'FAILED' ? 'failed' : 'unknown'}`} key={`${probe.port}/${probe.protocol}`} title={`清单观测 ${probe.inventoryObservedAt} · 探测 ${probe.observedAt}`}>{probe.protocol}/{probe.port} {probe.outcome}</span>) : <span className="badge pending">尚无 TCP 结果</span>}</span>}</div>;
                  })}</div> : <p className="service-network-empty">当前没有 EndpointSlice 地址</p>}
                </details>;
              })}</div> : <div className="empty">{serviceInventory.services.length ? '没有匹配的 Service。' : serviceInventory.observedAt ? '该 namespace 当前没有 Service。' : '等待首次采集。'}</div>}
            </section>
            <aside className="service-network-traffic" aria-labelledby="service-network-traffic-title"><div className="service-network-section-heading"><div><h3 id="service-network-traffic-title">Istio 实际流量</h3><small>{serviceInventory.istio.observedAt ? `采样于 ${new Date(serviceInventory.istio.observedAt).toLocaleTimeString()}` : '等待 Envoy 指标'}</small></div><span>{istioTraffic.length} 条</span></div>
              {istioTraffic.length ? <div className="service-network-flow-list">{istioTraffic.slice(0, 10).map((flow, index) => <article className="service-network-flow" key={`${flow.sourceNamespace}/${flow.sourceWorkload}-${flow.destinationService}-${flow.responseCode}-${flow.securityPolicy}-${index}`}><div className="service-network-flow-path"><span><b>{flow.sourceWorkload}</b><small>{flow.sourceNamespace}</small></span><i aria-hidden="true">→</i><span><b>{flow.destinationService}</b><small>{flow.destinationWorkload}</small></span></div><div className="service-network-flow-stats"><span className={`badge ${flow.responseCode.startsWith('2') ? 'online' : flow.responseCode.startsWith('4') ? 'pending' : 'offline'}`}>HTTP {flow.responseCode}</span><span className={`badge ${flow.securityPolicy === 'MUTUAL_TLS' ? 'available' : 'unknown'}`}>{flow.securityPolicy === 'MUTUAL_TLS' ? 'mTLS' : flow.securityPolicy === 'PLAINTEXT' ? '明文' : '安全未知'}</span><strong>{flow.requestCount.toLocaleString()} req</strong></div></article>)}{istioTraffic.length > 10 && <details className="service-network-more-flows"><summary>展开其余 {istioTraffic.length - 10} 条观测</summary>{istioTraffic.slice(10).map((flow, index) => <article className="service-network-flow" key={`more-${flow.sourceNamespace}/${flow.sourceWorkload}-${flow.destinationService}-${flow.responseCode}-${index}`}><div className="service-network-flow-path"><span><b>{flow.sourceWorkload}</b><small>{flow.sourceNamespace}</small></span><i aria-hidden="true">→</i><span><b>{flow.destinationService}</b><small>{flow.destinationWorkload}</small></span></div><div className="service-network-flow-stats"><span>HTTP {flow.responseCode}</span><span>{flow.securityPolicy}</span><strong>{flow.requestCount.toLocaleString()} req</strong></div></article>)}</details>}</div> : <div className="service-network-no-flows">{serviceInventory.istio.status === 'NOT_INSTALLED' ? '此 namespace 没有 Istio sidecar。' : serviceInventory.istio.status === 'NOT_READY' ? 'Istio sidecar 尚未 Ready。' : serviceInventory.istio.status === 'UNAVAILABLE' ? 'Sidecar 存在，但 Envoy metrics 端点不可达。' : serviceInventory.istio.status === 'NOT_COLLECTED' ? '该快照尚无 Istio 指标，请刷新。' : '最近采样没有服务请求；实际流量会显示在这里。'}</div>}
            </aside>
          </div>
          {serviceInventory.istio.status === 'PARTIAL' && <p className="service-network-footnote">当前为部分采样：每轮最多读取 24 个 Ready Envoy sidecar，500 ms 超时；指标按代理生命周期累计。</p>}
          {serviceProbes && serviceProbes.enabled && <p className="service-network-footnote">TCP 探测独立于 Istio HTTP 流量统计；它仅证明到 Ready 私网端口可建立 TCP 连接。</p>}
        </>}
        {!serviceInventory && !serviceInventoryError && <div className="empty">正在读取真实 Kubernetes 与 Istio 快照…</div>}
      </>}
    </section>}
    {!folderId && workspace === 'providers' && <><NetworkProviderCapabilities /><LinuxNetnsProviderPanel ready={loaded && !dirty} expectedEtag={etag} /><NetbirdProviderPanel /></>}
    {(workspace === 'topology' || !!folderId || isP0Lab) && <div className="topology-caption"><span>全拓扑 {nodes.filter(node => !node.data.observedOnly).length} 个配置节点 · Agent 自动发现 {autoRouterNodes.length} 个 Router；期望连接 {edges.length} 条 · 当前层 {activeNodes.filter(node => !node.data.observedOnly).length} 个节点 · {activeEdges.length} 条期望连接 · {dirty ? '有未保存的修改' : '草稿未修改'}</span><span role="status">{message || (busy ? '正在读取草稿…' : '')}</span></div>}
    {menu && <TopologyContextMenu x={menu.x} y={menu.y} onClose={() => setMenu(undefined)}>
      {contextNode ? <>
        <TopologyContextMenuHeading>{contextNode.name} · {contextNode.inputCount} 入 / {contextNode.outputCount} 出</TopologyContextMenuHeading>
        {contextNode.kind === 'FOLDER' && <TopologyContextMenuItem onClick={() => enterFolder(contextNode.id)}>打开层级视图</TopologyContextMenuItem>}
        <TopologyContextMenuItem onClick={() => openNodeSettings(contextNode)}>编辑节点信息</TopologyContextMenuItem>
        {(['inputCount', 'outputCount'] as const).map(direction => <TopologyContextMenuGroup key={direction}>{[1, -1].map(delta => <TopologyContextMenuItem key={delta} disabled={!portChangeCandidate(contextNode, direction, delta)} closeOnSelect={false} onClick={() => changePorts(contextNode, direction, delta)}>{delta > 0 ? '增加' : '减少'}{direction === 'inputCount' ? '入口' : '出口'}</TopologyContextMenuItem>)}</TopologyContextMenuGroup>)}
        <TopologyContextMenuItem className="danger" onClick={() => removeNode(contextNode.id)}>删除节点及关联连接</TopologyContextMenuItem>
      </> : menu.edgeId ? <>
        <TopologyContextMenuHeading>期望连接 · {linkStateLabels[(contextEdge?.data?.state ?? 'PENDING') as keyof typeof linkStateLabels]} · {contextEdge?.data?.layer as string}/{(contextEdge?.data?.protocol ?? 'TCP') as string}</TopologyContextMenuHeading>
        <TopologyContextMenuItem className="danger" onClick={() => { setEdges(current => current.filter(e => e.id !== menu.edgeId)); setDirty(true); setRouteEvidence(undefined); setMenu(undefined); }}>删除连接</TopologyContextMenuItem>
      </> : <>
        <TopologyContextMenuHeading>添加节点</TopologyContextMenuHeading>
        {(Object.keys(nodePresets) as TopologyNodeKind[]).map(kind => <TopologyContextMenuItem key={kind} onClick={() => addNode(kind)}><span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small></TopologyContextMenuItem>)}
      </>}
    </TopologyContextMenu>}
    {nodeSettings && <div className="modal-backdrop"><section className="modal topology-node-settings" role="dialog" aria-modal="true" aria-labelledby="topology-node-settings-title">
      <div className="card-title"><div><span className="eyebrow">TOPOLOGY NODE</span><h2 id="topology-node-settings-title">节点与接口信息</h2></div><button className="icon-button" aria-label="关闭节点设置" onClick={() => setNodeSettings(undefined)}>×</button></div>
      <form onSubmit={saveNodeSettings}>
        <label className="field"><span>节点名称</span><input required maxLength={100} value={nodeSettings.name} onChange={event => setNodeSettings({ ...nodeSettings, name: event.target.value })} /></label>
        <label className="field"><span>用途说明</span><textarea maxLength={500} rows={3} value={nodeSettings.description} onChange={event => setNodeSettings({ ...nodeSettings, description: event.target.value })} placeholder="填写流量策略、镜像目标或设备能力备注" /></label>
        <label className="field"><span>统一接口网络层</span><select value={nodeSettings.layer} onChange={event => { const layer = event.target.value as TopologyLayer; setNodeSettings({ ...nodeSettings, layer, protocols: [protocolOptions[layer][0]] }); }}>{(['L2', 'L3', 'L4', 'L7'] as TopologyLayer[]).map(layer => <option key={layer} value={layer}>{layer}</option>)}</select></label>
        <fieldset className="topology-protocol-options"><legend>接口协议</legend>{protocolOptions[nodeSettings.layer].map(protocol => <label key={protocol}><input type="checkbox" checked={nodeSettings.protocols.includes(protocol)} onChange={event => setNodeSettings({ ...nodeSettings, protocols: event.target.checked ? [...nodeSettings.protocols, protocol] : nodeSettings.protocols.filter(value => value !== protocol) })} />{protocol}</label>)}</fieldset>
        {settingsNode && ['ROUTER', 'FIREWALL', 'SWITCH', 'PACKET_TAP'].includes(settingsNode.kind) && <>
          <label className="field"><span>设备 Provider</span><select value={nodeSettings.providerId} disabled={!compatibleProviders.length && !nodeSettings.providerId} onChange={event => setNodeSettings({ ...nodeSettings, providerId: event.target.value })}>
            <option value="">仅保存期望节点</option>
            {nodeSettings.providerId && !compatibleProviders.some(provider => provider.id === nodeSettings.providerId) && <option value={nodeSettings.providerId} disabled>{nodeSettings.providerId}（未加载或能力不匹配）</option>}
            {compatibleProviders.map(provider => <option key={provider.id} value={provider.id}>{provider.name ?? provider.id} ({provider.id})</option>)}
          </select></label>
          <small>{networkProvidersError ? `无法读取 Core Provider 能力：${networkProvidersError}` : compatibleProviders.length ? 'Provider 必须声明此设备、接口层/协议及完整变更和补偿阶段。绑定只记录执行目标，不代表网络规则已经应用。' : '当前没有已加载 Provider 声明支持此设备及接口能力；节点仍只作为期望配置保存。'}</small>
          {settingsNode.kind === 'SWITCH' && nodeSettings.providerId === 'linux-netns' && <label className="field"><span>交换机数据面</span><select aria-label="交换机数据面" value={nodeSettings.switchBackend} onChange={event => setNodeSettings({ ...nodeSettings, switchBackend: event.target.value as SwitchBackend })}><option value="BRIDGE">Linux Bridge</option><option value="OPENVSWITCH">Open vSwitch（用户态）</option></select><small>Open vSwitch 需要执行主机报告 network.topology.ovs；当前隔离切片不支持同图混用 Firewall 或 Packet Tap。</small></label>}
        </>}
        {settingsNode?.kind === 'FIREWALL' && <section className="topology-firewall-policy" aria-label="期望防火墙策略">
          <h3>防火墙期望策略</h3>
          <p>仅保存规则意图；需要单独执行 Provider 操作后才会应用。最多 64 条，未匹配流量执行默认动作。</p>
          <label className="field"><span>默认动作</span><select value={nodeSettings.firewallPolicy.defaultAction} onChange={event => setNodeSettings({ ...nodeSettings, firewallPolicy: { ...nodeSettings.firewallPolicy, defaultAction: event.target.value as FirewallPolicy['defaultAction'] } })}><option value="DROP">DROP</option><option value="ACCEPT">ACCEPT</option></select></label>
          <div className="topology-firewall-rules">{nodeSettings.firewallPolicy.rules.map((rule, index) => <div className="topology-firewall-rule" key={`${rule.inputPort}-${rule.outputPort}-${index}`}>
            <label><span>入口</span><select aria-label={`规则 ${index + 1} 入口`} value={rule.inputPort} onChange={event => updateFirewallRule(index, { inputPort: event.target.value })}>{(settingsNode.ports.filter(port => port.direction === 'INPUT')).map(port => <option key={port.id} value={port.id}>{port.id}</option>)}</select></label>
            <label><span>出口</span><select aria-label={`规则 ${index + 1} 出口`} value={rule.outputPort} onChange={event => updateFirewallRule(index, { outputPort: event.target.value })}>{(settingsNode.ports.filter(port => port.direction === 'OUTPUT')).map(port => <option key={port.id} value={port.id}>{port.id}</option>)}</select></label>
            <label><span>协议</span><select aria-label={`规则 ${index + 1} 协议`} value={rule.protocol} onChange={event => updateFirewallRule(index, { protocol: event.target.value as FirewallRule['protocol'] })}><option>TCP</option><option>UDP</option><option>SCTP</option></select></label>
            <label><span>目标端口</span><input aria-label={`规则 ${index + 1} 目标端口`} type="number" min={1} max={65535} value={rule.destinationPort || ''} onChange={event => updateFirewallRule(index, { destinationPort: event.target.value === '' ? 0 : Number(event.target.value) })} /></label>
            <label><span>动作</span><select aria-label={`规则 ${index + 1} 动作`} value={rule.action} onChange={event => updateFirewallRule(index, { action: event.target.value as FirewallRule['action'] })}><option value="ACCEPT">ACCEPT</option><option value="DROP">DROP</option></select></label>
            <button type="button" className="secondary" aria-label={`删除规则 ${index + 1}`} onClick={() => setNodeSettings({ ...nodeSettings, firewallPolicy: { ...nodeSettings.firewallPolicy, rules: nodeSettings.firewallPolicy.rules.filter((_, ruleIndex) => ruleIndex !== index) } })}>删除</button>
          </div>)}</div>
          <button type="button" className="secondary" disabled={nodeSettings.firewallPolicy.rules.length >= 64} onClick={() => setNodeSettings({ ...nodeSettings, firewallPolicy: { ...nodeSettings.firewallPolicy, rules: [...nodeSettings.firewallPolicy.rules, { inputPort: settingsNode.ports.find(port => port.direction === 'INPUT')?.id ?? '', outputPort: settingsNode.ports.find(port => port.direction === 'OUTPUT')?.id ?? '', protocol: 'TCP', destinationPort: 443, action: 'ACCEPT' }] } })}>＋ 添加规则</button>
        </section>}
        {settingsNode?.kind === 'ROUTER' && <section className="topology-firewall-policy" aria-label="Router IPv4 地址与静态路由">
          <h3>Router IPv4 配置</h3>
          <p>地址与静态路由由 linux-netns Provider 应用在该拓扑专属 netns。将 Provider 接口设为 L2/ETHERNET 才能执行；只支持 IPv4，每个已连接接口需配置一个地址，下一跳必须属于所选接口的直连网段。</p>
          <div className="topology-firewall-rules">{nodeSettings.routerConfiguration.interfaces.map((iface, index) => <div className="topology-firewall-rule" key={`router-interface-${index}`}>
            <label className="field"><span>接口</span><select aria-label={`Router 接口 ${index + 1}`} value={iface.port} onChange={event => updateRouterInterface(index, { port: event.target.value })}>{settingsNode.ports.map(port => <option value={port.id} key={port.id}>{port.id} · {port.direction === 'INPUT' ? '入口' : '出口'}</option>)}</select></label>
            <label className="field"><span>IPv4/CIDR</span><input aria-label={`Router 接口 ${index + 1} IPv4 CIDR`} required value={iface.address} onChange={event => updateRouterInterface(index, { address: event.target.value })} placeholder="198.18.1.1/24" /></label>
            <button type="button" className="secondary" aria-label={`删除 Router 接口地址 ${index + 1}`} onClick={() => setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, interfaces: nodeSettings.routerConfiguration.interfaces.filter((_, row) => row !== index) } })}>删除</button>
          </div>)}</div>
          <button type="button" className="secondary" disabled={nodeSettings.routerConfiguration.interfaces.length >= 16} onClick={() => { const used = new Set(nodeSettings.routerConfiguration.interfaces.map(iface => iface.port)); const port = settingsNode.ports.find(value => !used.has(value.id)); if (port) setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, interfaces: [...nodeSettings.routerConfiguration.interfaces, { port: port.id, address: '' }] } }); }}>＋ 添加接口地址</button>
          <h3>静态路由</h3>
          <div className="topology-firewall-rules">{nodeSettings.routerConfiguration.routes.map((route, index) => <div className="topology-firewall-rule" key={`router-route-${index}`}>
            <label className="field"><span>目标网段</span><input aria-label={`Router 静态路由 ${index + 1} 目标网段`} required value={route.destination} onChange={event => updateRouterRoute(index, { destination: event.target.value })} placeholder="198.18.2.0/24" /></label>
            <label className="field"><span>下一跳（可空）</span><input aria-label={`Router 静态路由 ${index + 1} 下一跳`} value={route.gateway ?? ''} onChange={event => updateRouterRoute(index, { gateway: event.target.value.trim() || null })} placeholder="直连路由可留空" /></label>
            <label className="field"><span>出口接口</span><select aria-label={`Router 静态路由 ${index + 1} 出口接口`} value={route.outputPort} onChange={event => updateRouterRoute(index, { outputPort: event.target.value })}>{settingsNode.ports.filter(port => port.direction === 'OUTPUT').map(port => <option value={port.id} key={port.id}>{port.id}</option>)}</select></label>
            <button type="button" className="secondary" aria-label={`删除 Router 静态路由 ${index + 1}`} onClick={() => setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, routes: nodeSettings.routerConfiguration.routes.filter((_, row) => row !== index) } })}>删除</button>
          </div>)}</div>
          <button type="button" className="secondary" disabled={nodeSettings.routerConfiguration.routes.length >= 64 || !nodeSettings.routerConfiguration.interfaces.length} onClick={() => { const port = settingsNode.ports.find(value => value.direction === 'OUTPUT' && nodeSettings.routerConfiguration.interfaces.some(iface => iface.port === value.id)); if (port) setNodeSettings({ ...nodeSettings, routerConfiguration: { ...nodeSettings.routerConfiguration, routes: [...nodeSettings.routerConfiguration.routes, { destination: '0.0.0.0/0', gateway: null, outputPort: port.id }] } }); }}>＋ 添加静态路由</button>
        </section>}
        {nodeSettingsError && <div className="alert error" role="alert">{nodeSettingsError}</div>}
        <div className="form-actions"><button type="button" className="secondary" onClick={() => setNodeSettings(undefined)}>取消</button><button className="primary">保存节点设置</button></div>
      </form>
    </section></div>}
    {sourceYamlNode && <TopologyNodeSourceYamlDialog key={sourceYamlNode.id} node={sourceYamlNode} disabled={busy}
      error={sourceYamlError} onClose={() => { setSourceYamlNode(undefined); setSourceYamlError(''); }} onSave={saveSourceYaml} />}
    {revisionHistoryOpen && <div className="modal-backdrop"><section className="modal topology-revision-dialog" role="dialog" aria-modal="true" aria-labelledby="topology-revision-title">
      <div className="card-title"><div><span className="eyebrow">IMMUTABLE TOPOLOGY REVISIONS</span><h2 id="topology-revision-title">版本比较与回放</h2><p>回放只载入为当前未保存草稿；需要再次保存创建新 Revision，且不会应用网络规则。</p></div><button className="icon-button" aria-label="关闭版本比较" onClick={() => setRevisionHistoryOpen(false)}>×</button></div>
      {revisionError && <div className="alert error" role="alert">{revisionError}</div>}
      <div className="topology-revision-layout">
        <div className="topology-revision-list" aria-label="历史拓扑版本">
          {revisionSummaries.map(revision => <button type="button" key={revision.revisionId} aria-pressed={selectedRevision?.revisionId === revision.revisionId} onClick={() => void selectRevision(revision.revisionId)}><strong>Revision {revision.generation}</strong><small>{revision.nodeCount} 节点 · {revision.linkCount} 链路</small><time>{new Date(revision.updatedAt).toLocaleString()}</time></button>)}
          {!revisionLoading && !revisionSummaries.length && <p className="empty">没有可读取的历史 Revision。</p>}
        </div>
        <div className="topology-revision-diff">
          {revisionLoading && <p className="empty">读取 Revision…</p>}
          {!revisionLoading && revisionDiff && <>
            <p className="muted">Revision {selectedRevision?.generation} → 当前画布{dirty ? '（含未保存修改）' : ''}</p>
            <h3>节点 · +{revisionDiff.nodes.added.length} / −{revisionDiff.nodes.removed.length} / 修改 {revisionDiff.nodes.changed.length}</h3>
            <ul>{revisionDiff.nodes.added.map(node => <li className="added" key={`node-added-${node.id}`}>+ {node.name}</li>)}{revisionDiff.nodes.removed.map(node => <li className="removed" key={`node-removed-${node.id}`}>− {node.name}</li>)}{revisionDiff.nodes.changed.map(change => <li className="changed" key={`node-changed-${change.after.id}`}>~ {change.before.name} → {change.after.name} · {change.fields.join(', ')}</li>)}</ul>
            <h3>链路 · +{revisionDiff.links.added.length} / −{revisionDiff.links.removed.length} / 修改 {revisionDiff.links.changed.length}</h3>
            <ul>{revisionDiff.links.added.map(link => <li className="added" key={`link-added-${link.id}`}>+ {topologyLinkName(link)}</li>)}{revisionDiff.links.removed.map(link => <li className="removed" key={`link-removed-${link.id}`}>− {topologyLinkName(link)}</li>)}{revisionDiff.links.changed.map(change => <li className="changed" key={`link-changed-${change.after.id}`}>~ {topologyLinkName(change.after)} · {change.fields.join(', ')}</li>)}</ul>
            {!revisionDiff.nodes.added.length && !revisionDiff.nodes.removed.length && !revisionDiff.nodes.changed.length && !revisionDiff.links.added.length && !revisionDiff.links.removed.length && !revisionDiff.links.changed.length && <p className="empty">该 Revision 与当前画布一致。</p>}
          </>}
          {!revisionLoading && !selectedRevision && !revisionError && <p className="empty">选择一个 Revision 查看与当前画布的差异。</p>}
        </div>
      </div>
      <div className="form-actions"><button type="button" className="secondary" onClick={() => setRevisionHistoryOpen(false)}>关闭</button><button type="button" className="primary" disabled={!selectedRevision || revisionLoading} onClick={replayRevision}>回放为未保存草稿</button></div>
    </section></div>}
    {assetPickerOpen && <div className="modal-backdrop"><section className="modal topology-asset-picker" role="dialog" aria-modal="true" aria-labelledby="topology-asset-picker-title"><div className="card-title"><div><span className="eyebrow">REGISTERED HOSTS</span><h2 id="topology-asset-picker-title">添加主机资产到主拓扑</h2></div><button className="icon-button" aria-label="关闭添加主机资产" onClick={() => setAssetPickerOpen(false)}>×</button></div><p>选择已登记主机后，拓扑会绑定该资产并自动读取 Agent 上报的路由表。</p><div className="topology-asset-list">{assetChoices.map(host => { const bound = nodes.some(node => node.data.model.assetId === host.id); return <button className="topology-asset-option" key={host.id} disabled={bound} onClick={() => addHostAsset(host)}><span><strong>{host.hostname}</strong><small>{host.os ?? 'OS 未知'} · {host.arch ?? '架构未知'} · {host.agentId ? 'Agent 已绑定' : '等待 Agent'}</small></span><span className={`badge ${host.status === 'ONLINE' ? 'online' : host.status === 'OFFLINE' ? 'offline' : 'unknown'}`}>{bound ? '已添加' : host.status}</span></button>; })}</div>{assetNextCursor && <button className="secondary" disabled={assetPickerBusy} onClick={() => void loadMoreHostAssets()}>{assetPickerBusy ? '读取中…' : '加载更多主机'}</button>}{!assetChoices.length && <div className="empty">还没有已登记主机。</div>}</section></div>}
  </>;
}
export default function Topology() { return <ReactFlowProvider><Editor /></ReactFlowProvider>; }
