import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  Background, ConnectionMode, Controls, Handle, MarkerType, Position, ReactFlow, ReactFlowProvider,
  useNodesState, useReactFlow,
  type Connection, type Edge, type Node, type NodeProps,
} from '@xyflow/react';
import { api, isMock } from './lib/api';
import type {
  KubernetesApplication,
  KubernetesApplicationDetail,
  KubernetesApplicationList,
  KubernetesApplicationTopologyNode,
  KubernetesApplicationVersion,
  KubernetesApplicationVersionDraftRequest,
  KubernetesApplicationVersionList,
  KubernetesClusterList,
  KubernetesClusterSummary,
  KubernetesTopologyNamespaceSnapshot,
} from './lib/types';
import './applications.css';

type View = 'topology' | 'versions';
type Editable = { replicas?: number; image?: string; targetDeployment?: string };

const stateLabels: Record<string, string> = {
  DEPLOYED: '运行中', APPLYING: '部署中', FAILED: '失败', PARTIAL: '部分完成',
  DRAFT: '待确认', READY: '就绪', PROGRESSING: '更新中', MISSING: '资源缺失',
  UNAVAILABLE: '状态未知', ACTIVE: '已配置', CONFIGURED: '已配置', UNKNOWN: '未知',
};

function stateClass(state: string) {
  return state.toLowerCase().replaceAll('_', '-');
}

function AppState({ value }: { value: string }) {
  return <span className={`application-state ${stateClass(value)}`}><i />{stateLabels[value] ?? value}</span>;
}

export default function Applications({ notify }: { notify: (message: string) => void }) {
  const [items, setItems] = useState<KubernetesApplication[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<KubernetesApplicationDetail>();
  const [versions, setVersions] = useState<KubernetesApplicationVersion[]>([]);
  const [clusters, setClusters] = useState<KubernetesClusterSummary[]>([]);
  const [clusterId, setClusterId] = useState('');
  const [namespaces, setNamespaces] = useState<string[]>([]);
  const [namespace, setNamespace] = useState('');
  const [displayName, setDisplayName] = useState('Bookinfo 测试应用');
  const [changes, setChanges] = useState<Record<string, Editable>>({});
  const [view, setView] = useState<View>('topology');
  const [wizard, setWizard] = useState(false);
  const [wizardStep, setWizardStep] = useState(1);
  const [loading, setLoading] = useState(!isMock);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock ? 'Mock 演示环境尚未提供 Kubernetes 应用接口。切换到 Live API 后可创建和部署应用。' : '');

  async function loadList() {
    if (isMock) return;
    setLoading(true);
    setError('');
    try {
      const [appResult, clusterResult] = await Promise.all([
        api<KubernetesApplicationList>('/kubernetes/applications', 'GET', undefined, {}, true),
        api<KubernetesClusterList>('/kubernetes/clusters', 'GET', undefined, {}, true),
      ]);
      setItems(appResult.data.items);
      setClusters(clusterResult.data.items);
      setSelectedId(current => appResult.data.items.some(item => item.id === current)
        ? current : appResult.data.items[0]?.id ?? '');
      setClusterId(current => current || clusterResult.data.items[0]?.id || '');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function loadApplication(applicationId: string) {
    if (!applicationId || isMock) return;
    try {
      const [result, history] = await Promise.all([
        api<KubernetesApplicationDetail>(`/kubernetes/applications/${encodeURIComponent(applicationId)}`, 'GET', undefined, {}, true),
        api<KubernetesApplicationVersionList>(`/kubernetes/applications/${encodeURIComponent(applicationId)}/versions`, 'GET', undefined, {}, true),
      ]);
      setDetail(result.data);
      setVersions(history.data.items);
      setChanges({});
    } catch (cause) {
      setError((cause as Error).message);
    }
  }

  useEffect(() => { void loadList(); }, []);
  useEffect(() => { if (selectedId) void loadApplication(selectedId); else setDetail(undefined); }, [selectedId]);
  useEffect(() => {
    if (!clusterId || isMock) { setNamespaces([]); setNamespace(''); return; }
    let current = true;
    setNamespaces([]);
    setNamespace('');
    void api<KubernetesTopologyNamespaceSnapshot>(
      `/kubernetes/clusters/${encodeURIComponent(clusterId)}/topology/namespaces`, 'GET', undefined, {}, true,
    ).then(result => {
      if (!current) return;
      const names = result.data.namespaces.map(item => item.name);
      setNamespaces(names);
      setNamespace(names.includes('default') ? 'default' : names[0] ?? '');
    }).catch(cause => { if (current) setError((cause as Error).message); });
    return () => { current = false; };
  }, [clusterId]);

  const selectedApp = items.find(item => item.id === selectedId);
  const currentDraft = versions.find(item => item.state === 'DRAFT');
  const hasDraft = Boolean(currentDraft);
  const nodeList = detail?.topology.nodes ?? [];
  const deployments = useMemo(() => nodeList.filter(node => node.kind === 'Deployment'), [nodeList]);
  const services = useMemo(() => nodeList.filter(node => node.kind === 'Service'), [nodeList]);
  const accounts = useMemo(() => nodeList.filter(node => node.kind === 'ServiceAccount'), [nodeList]);
  const updateNode = useCallback((node: KubernetesApplicationTopologyNode, value: Editable) => {
    setChanges(current => ({ ...current, [node.id]: { ...current[node.id], ...value } }));
  }, []);

  async function createBookinfo(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!clusterId || !namespace) { setError('请先选择已登记集群和现有 Namespace。'); return; }
    setBusy('create');
    setError('');
    try {
      const result = await api<KubernetesApplicationDetail>('/kubernetes/applications/bookinfo', 'POST', {
        clusterId, namespace, displayName: displayName.trim(),
      }, {}, true);
      setItems(current => [result.data.application, ...current.filter(item => item.id !== result.data.application.id)]);
      setSelectedId(result.data.application.id);
      setDetail(result.data);
      setVersions([]);
      setChanges({});
      setWizard(false);
      setWizardStep(1);
      await loadApplication(result.data.application.id);
      notify(`Bookinfo 已部署到 ${result.data.application.clusterName} / ${namespace}`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function createDraft() {
    if (!detail || hasDraft) return;
    const edits: KubernetesApplicationVersionDraftRequest['edits'] = nodeList.flatMap(node => {
      const changed = changes[node.id];
      if (!changed) return [];
      const edit: KubernetesApplicationVersionDraftRequest['edits'][number] = {
        resourceId: node.id,
        replicas: node.kind === 'Deployment' && changed.replicas !== undefined
          && changed.replicas !== node.replicas ? changed.replicas : null,
        image: node.kind === 'Deployment' && changed.image !== undefined
          && changed.image.trim() !== node.image ? changed.image.trim() : null,
        targetDeployment: node.kind === 'Service' && changed.targetDeployment !== undefined
          && changed.targetDeployment !== node.targetDeployment ? changed.targetDeployment : null,
      };
      if (edit.replicas === null && edit.image === null && edit.targetDeployment === null) return [];
      return [edit];
    });
    if (!edits.length) { setError('请先修改拓扑中的副本数、镜像或 Service 连接。'); return; }
    setBusy('draft');
    setError('');
    try {
      const result = await api<KubernetesApplicationDetail>(
        `/kubernetes/applications/${encodeURIComponent(detail.application.id)}/versions`, 'POST',
        { baseVersion: detail.application.currentVersion, edits }, {}, true,
      );
      setDetail(result.data);
      setChanges({});
      await loadApplication(result.data.application.id);
      setView('versions');
      notify('版本差异已生成；当前未修改集群，请检查 diff 后显式部署。');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function deployDraft(version: KubernetesApplicationVersion) {
    if (!detail || version.state !== 'DRAFT') return;
    const confirmed = window.confirm(`确认将 ${detail.application.displayName} 从 v${version.baseVersion} 部署为 v${version.version}？\n\n部署前请核对资源级 YAML diff。`);
    if (!confirmed) return;
    setBusy(`deploy-${version.version}`);
    setError('');
    try {
      const result = await api<KubernetesApplicationDetail>(
        `/kubernetes/applications/${encodeURIComponent(detail.application.id)}/versions/${version.version}/deploy`,
        'POST', {}, {}, true,
      );
      setDetail(result.data);
      setItems(current => current.map(item => item.id === result.data.application.id ? result.data.application : item));
      await loadApplication(result.data.application.id);
      setView('topology');
      notify(`${result.data.application.displayName} 已部署为 v${version.version}`);
    } catch (cause) {
      setError((cause as Error).message);
      await loadList();
      await loadApplication(detail.application.id);
    } finally {
      setBusy('');
    }
  }

  return <div className="applications-page">
    <div className="applications-heading">
      <div><span className="eyebrow">KUBERNETES WORKLOADS</span><h1>应用管理</h1><p>将 Kubernetes 工作负载作为应用管理。拓扑由版本 YAML 生成，修改先形成 diff，确认后才部署。</p></div>
      <div className="applications-actions"><button className="secondary" onClick={() => void loadList()} disabled={loading}>↻ 刷新</button><button className="primary" onClick={() => { setError(''); setWizard(true); }} disabled={isMock}>＋ 创建应用</button></div>
    </div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {isMock && <div className="alert">Mock 演示环境没有真实集群应用接口。此页面在 Live API 模式下连接已登记集群。</div>}
    <div className="application-layout">
      <aside className="application-list-panel">
        <div className="application-list-heading"><div><span className="eyebrow">APPLICATIONS</span><strong>应用列表</strong></div><span className="application-count">{items.length}</span></div>
        {loading && <p className="application-muted">正在读取应用…</p>}
        {!loading && items.length === 0 && !error && <div className="application-empty-small"><b>◇</b><span>还没有应用</span><small>从 Bookinfo 示例开始体验拓扑和版本管理。</small><button className="link" onClick={() => setWizard(true)}>创建测试应用 →</button></div>}
        <div className="application-list">
          {items.map(item => <button key={item.id} className={`application-list-item ${selectedId === item.id ? 'active' : ''}`} onClick={() => { setSelectedId(item.id); setView('topology'); }}>
            <span className="application-symbol">▦</span><span className="application-list-copy"><strong>{item.displayName}</strong><small>{item.clusterName} · {item.namespace}</small><small>版本 v{item.currentVersion} · {item.templateId}</small></span><AppState value={item.state} />
          </button>)}
        </div>
        <div className="application-list-footer"><span className="application-live-dot" />已接入的应用清单</div>
      </aside>
      <section className="application-content">
        {!selectedApp || !detail ? <div className="application-blank"><div className="application-blank-icon">▦</div><h2>{items.length ? '选择一个应用' : '开始管理 Kubernetes 应用'}</h2><p>应用拓扑根据部署 YAML 自动生成。创建版本后检查资源 diff，再手动确认部署。</p><button className="primary" onClick={() => setWizard(true)} disabled={isMock}>＋ 创建 Bookinfo 测试应用</button></div> : <>
          <div className="application-detail-heading">
            <div className="application-detail-title"><span className="application-title-icon">▦</span><div><div className="application-title-line"><h2>{detail.application.displayName}</h2><AppState value={detail.application.state} /></div><p><span className="mono">{detail.application.clusterName}</span><i>›</i><span>{detail.application.namespace}</span><i>›</i><span>{detail.application.templateId}</span></p></div></div>
            <div className="application-version-chip"><small>当前版本</small><strong>v{detail.application.currentVersion}</strong></div>
          </div>
          <div className="application-metrics">
            <div><span>工作负载</span><strong>{deployments.length}</strong><small>Deployments</small></div>
            <div><span>服务</span><strong>{services.length}</strong><small>Services</small></div>
            <div><span>ServiceAccount</span><strong>{accounts.length}</strong><small>Namespace 范围</small></div>
            <div><span>版本历史</span><strong>{versions.filter(item => item.state === 'DEPLOYED').length}</strong><small>已部署版本</small></div>
          </div>
          <div className="application-tabs" role="tablist" aria-label="应用管理视图">
            <button role="tab" aria-selected={view === 'topology'} className={view === 'topology' ? 'active' : ''} onClick={() => setView('topology')}>⌘ 拓扑与配置</button>
            <button role="tab" aria-selected={view === 'versions'} className={view === 'versions' ? 'active' : ''} onClick={() => setView('versions')}>◷ 版本历史 <span>{versions.length}</span></button>
            <div className="application-tab-actions"><button className="secondary" onClick={() => void loadApplication(detail.application.id)}>刷新拓扑</button>{view === 'topology' && <button className="primary" disabled={!Object.keys(changes).length || hasDraft || busy !== ''} onClick={() => void createDraft()}>{busy === 'draft' ? '生成差异中…' : '生成版本差异 →'}</button>}</div>
          </div>
          {view === 'topology' ? <>
            {hasDraft && <div className="application-draft-banner"><span>◈</span><div><strong>v{currentDraft?.version} 有待确认差异</strong><small>检查版本历史中的 YAML diff，确认后部署；拓扑编辑会先锁定到该草稿。</small></div><button className="link" onClick={() => setView('versions')}>查看 diff →</button></div>}
            <section className="application-topology-card">
              <div className="application-section-heading"><div><span className="eyebrow">YAML DERIVED TOPOLOGY</span><h3>应用拓扑</h3><p>连线来自 Service selector 与 Deployment Pod 标签的匹配结果。</p></div><span className="application-topology-legend"><i className="service-dot" /> Service <i className="deployment-dot" /> Deployment</span></div>
              <div className="application-topology-canvas">
                <ApplicationTopologyFlow
                  key={detail.application.id}
                  resources={nodeList}
                  topologyEdges={detail.topology.edges}
                  changes={changes}
                  hasDraft={hasDraft}
                  busy={busy !== ''}
                  onUpdate={updateNode}
                />
              </div>
              {!!accounts.length && <div className="application-account-row"><span>ServiceAccount</span>{accounts.map(account => <span className="account-chip" key={account.id}>♙ {account.name}</span>)}</div>}
              <div className="application-topology-footnote">拖动节点调整布局；拖动 Service 绿色端口到 Deployment 蓝色端口可重接服务。节点布局只影响当前视图，副本、镜像和连接修改会进入 YAML diff。</div>
            </section>
          </> : <section className="application-versions-card">
            <div className="application-section-heading"><div><span className="eyebrow">IMMUTABLE MANIFEST HISTORY</span><h3>版本历史</h3><p>草稿不会修改集群；只有状态为“待确认”的版本可提交部署。</p></div></div>
            {!versions.length ? <div className="application-empty-history">暂无版本记录</div> : <div className="application-version-list">{versions.map(version => <VersionCard key={version.version} version={version} busy={busy === `deploy-${version.version}`} onDeploy={() => void deployDraft(version)} />)}</div>}
          </section>}
        </>}
      </section>
    </div>
    {wizard && <div className="modal-backdrop"><div className="modal application-wizard" role="dialog" aria-modal="true" aria-labelledby="bookinfo-title">
      <div className="application-wizard-header"><div><span className="eyebrow">APPLICATION TEMPLATE · 01 / 01</span><h2 id="bookinfo-title">创建 Bookinfo 测试应用</h2></div><button className="icon-button" aria-label="关闭" onClick={() => { if (!busy) setWizard(false); }}>×</button></div>
      <div className="application-wizard-steps"><span className="active"><i>1</i>选择目标</span><b /><span className={wizardStep === 2 ? 'active' : ''}><i>2</i>检查清单</span></div>
      <form onSubmit={createBookinfo}>
        {wizardStep === 1 ? <div className="application-wizard-fields">
          <label className="field"><span>应用名称</span><input value={displayName} maxLength={80} required onChange={event => setDisplayName(event.target.value)} /></label>
          <label className="field"><span>目标集群</span><select required value={clusterId} onChange={event => setClusterId(event.target.value)}><option value="">选择已登记集群</option>{clusters.map(cluster => <option key={cluster.id} value={cluster.id}>{cluster.name} · {cluster.kind}</option>)}</select></label>
          <label className="field"><span>目标 Namespace</span><select required value={namespace} disabled={!namespaces.length} onChange={event => setNamespace(event.target.value)}><option value="">{namespaces.length ? '选择 Namespace' : '读取 Namespace…'}</option>{namespaces.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
          {!clusters.length && !loading && <div className="alert">尚未登记 Kubernetes 集群。请先前往“集群管理”导入集群并授予 Namespace 级资源权限。</div>}
          {clusters.length > 0 && !namespaces.length && <div className="alert">无法读取 Namespace。请确认集群凭据具有 Namespace get/list 权限。</div>}
          <div className="application-template-summary"><span className="template-icon">✦</span><div><strong>Bookinfo 微服务示例</strong><small>4 Services · 6 Deployments · 4 ServiceAccounts</small></div><span className="template-tag">Istio 可选</span></div>
        </div> : <div className="application-bookinfo-review"><h3>将创建以下命名空间资源</h3><div className="bookinfo-resource-grid"><span><b>04</b>Service</span><span><b>06</b>Deployment</span><span><b>04</b>ServiceAccount</span></div><p>集群：<strong>{clusters.find(cluster => cluster.id === clusterId)?.name}</strong></p><p>Namespace：<strong>{namespace}</strong></p><div className="alert">不会安装 Istio、创建 Ingress 或读取 Secret。若目标中存在同名资源，创建会被拒绝且不会接管。</div></div>}
        <div className="form-actions application-wizard-actions"><button type="button" className="secondary" onClick={() => wizardStep === 2 ? setWizardStep(1) : setWizard(false)} disabled={busy !== ''}>{wizardStep === 2 ? '上一步' : '取消'}</button>{wizardStep === 1 ? <button type="button" className="primary" disabled={!clusterId || !namespace || !displayName.trim()} onClick={() => setWizardStep(2)}>下一步 →</button> : <button className="primary" disabled={busy === 'create'}>{busy === 'create' ? '正在部署…' : '确认创建并部署'}</button>}</div>
      </form>
    </div></div>}
  </div>;
}

type ApplicationFlowNodeData = {
  resource: KubernetesApplicationTopologyNode;
  changes: Editable;
  targets: string[];
  controlsDisabled: boolean;
  onUpdate: (node: KubernetesApplicationTopologyNode, value: Editable) => void;
};
type ApplicationFlowNode = Node<ApplicationFlowNodeData, 'application'>;

function applicationFlowNodePosition(node: KubernetesApplicationTopologyNode, index: number) {
  if (node.kind === 'Service') return { x: 40, y: 42 + index * 128 };
  return { x: 430 + (index % 2) * 340, y: 36 + Math.floor(index / 2) * 174 };
}

function deploymentTargetName(name: string) {
  return name.replace(/-v[0-9]+$/, '');
}

function ApplicationFlowNodeCard({ data, selected }: NodeProps<ApplicationFlowNode>) {
  const { resource, changes, targets, controlsDisabled, onUpdate } = data;
  const deployment = resource.kind === 'Deployment';
  return <article className={`application-flow-node ${deployment ? 'deployment' : 'service'} ${selected ? 'selected' : ''}`}>
    {deployment
      ? <Handle id="deployment-input" type="target" position={Position.Left} className="application-flow-handle deployment-input" isConnectable={!controlsDisabled} />
      : <Handle id="service-output" type="source" position={Position.Right} className="application-flow-handle service-output" isConnectable={!controlsDisabled} />}
    <div className="application-flow-node-heading">
      <span className={`node-type ${deployment ? 'deployment' : 'service'}`}>{deployment ? 'DEPLOYMENT' : 'SERVICE'}</span>
      <AppState value={resource.state} />
    </div>
    <strong title={resource.name}>{resource.name}</strong>
    {deployment ? <>
      <small>{resource.readyReplicas ?? 0}/{resource.replicas ?? 0} Ready</small>
      <label className="application-node-control nodrag nowheel"><span>副本数</span><input aria-label={`${resource.name} 副本数`} type="number" min={0} max={20} disabled={controlsDisabled} value={changes.replicas ?? resource.replicas ?? 1} onChange={event => onUpdate(resource, { replicas: Number(event.target.value) })} /></label>
      <label className="application-node-control nodrag nowheel"><span>容器镜像</span><input aria-label={`${resource.name} 容器镜像`} type="text" disabled={controlsDisabled} value={changes.image ?? resource.image ?? ''} onChange={event => onUpdate(resource, { image: event.target.value })} /></label>
    </> : <>
      <small>ClusterIP Service · 9080/TCP</small>
      <label className="application-node-control nodrag nowheel"><span>选择后端</span><select aria-label={`${resource.name} 后端 Deployment`} disabled={controlsDisabled} value={changes.targetDeployment ?? resource.targetDeployment ?? ''} onChange={event => onUpdate(resource, { targetDeployment: event.target.value })}>
        {targets.map(target => <option key={target} value={target}>{target}</option>)}
      </select></label>
    </>}
  </article>;
}

const applicationFlowNodeTypes = { application: ApplicationFlowNodeCard };

type ApplicationTopologyFlowProps = {
  resources: KubernetesApplicationTopologyNode[];
  topologyEdges: KubernetesApplicationDetail['topology']['edges'];
  changes: Record<string, Editable>;
  hasDraft: boolean;
  busy: boolean;
  onUpdate: (node: KubernetesApplicationTopologyNode, value: Editable) => void;
};

function ApplicationTopologyFlow(props: ApplicationTopologyFlowProps) {
  return <ReactFlowProvider><ApplicationTopologyFlowCanvas {...props} /></ReactFlowProvider>;
}

function ApplicationTopologyFlowCanvas({
  resources, topologyEdges, changes, hasDraft, busy, onUpdate,
}: ApplicationTopologyFlowProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<ApplicationFlowNode>([]);
  const { fitView } = useReactFlow<ApplicationFlowNode, Edge>();
  const [connectionMessage, setConnectionMessage] = useState('');
  const visibleResources = useMemo(
    () => resources.filter(resource => resource.kind === 'Service' || resource.kind === 'Deployment'),
    [resources],
  );
  const targets = useMemo(
    () => [...new Set(visibleResources.filter(resource => resource.kind === 'Deployment')
      .map(resource => deploymentTargetName(resource.name)))],
    [visibleResources],
  );
  const edges = useMemo<Edge[]>(() => {
    const deployments = visibleResources.filter(resource => resource.kind === 'Deployment');
    const services = visibleResources.filter(resource => resource.kind === 'Service');
    const adjustedServices = new Set(services
      .filter(resource => changes[resource.id]?.targetDeployment !== undefined)
      .map(resource => resource.id));
    const current = topologyEdges
      .filter(edge => !adjustedServices.has(edge.source))
      .map(edge => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourceHandle: 'service-output',
        targetHandle: 'deployment-input',
        type: 'smoothstep' as const,
        markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
        style: { stroke: '#91addc', strokeWidth: 2 },
        selectable: false,
        deletable: false,
      }));
    const changed = services.flatMap(service => {
      const target = changes[service.id]?.targetDeployment;
      if (target === undefined) return [];
      return deployments.filter(deployment => deploymentTargetName(deployment.name) === target)
        .map(deployment => ({
          id: `${service.id}->${deployment.id}`,
          source: service.id,
          target: deployment.id,
          sourceHandle: 'service-output',
          targetHandle: 'deployment-input',
          type: 'smoothstep' as const,
          markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
          style: { stroke: '#91addc', strokeWidth: 2 },
          selectable: false,
          deletable: false,
        }));
    });
    return [...current, ...changed];
  }, [visibleResources, topologyEdges, changes]);
  const flowKey = `${visibleResources.map(resource => resource.id).join('|')}`;

  useEffect(() => {
    setNodes(current => {
      const previousPositions = new Map(current.map(node => [node.id, node.position]));
      const serviceIndex = new Map(visibleResources.filter(resource => resource.kind === 'Service')
        .map((resource, index) => [resource.id, index]));
      const deploymentIndex = new Map(visibleResources.filter(resource => resource.kind === 'Deployment')
        .map((resource, index) => [resource.id, index]));
      return visibleResources.map(resource => ({
        id: resource.id,
        type: 'application' as const,
        position: previousPositions.get(resource.id)
          ?? applicationFlowNodePosition(resource, serviceIndex.get(resource.id) ?? deploymentIndex.get(resource.id) ?? 0),
        data: {
          resource,
          changes: changes[resource.id] ?? {},
          targets,
          controlsDisabled: busy || hasDraft,
          onUpdate,
        },
        draggable: !busy,
      }));
    });
  }, [visibleResources, changes, targets, busy, hasDraft, onUpdate, setNodes]);

  useEffect(() => {
    if (!nodes.length) return;
    const frame = requestAnimationFrame(() => { void fitView({ padding: 0.16, duration: 180 }); });
    return () => cancelAnimationFrame(frame);
  }, [flowKey, nodes.length, fitView]);

  const validConnection = (connection: Edge | Connection) => {
    const source = nodes.find(node => node.id === connection.source);
    const target = nodes.find(node => node.id === connection.target);
    return !busy && !hasDraft && source?.data.resource.kind === 'Service'
      && target?.data.resource.kind === 'Deployment';
  };

  function connect(connection: Connection) {
    const source = nodes.find(node => node.id === connection.source);
    const target = nodes.find(node => node.id === connection.target);
    if (!source || !target || source.data.resource.kind !== 'Service'
        || target.data.resource.kind !== 'Deployment') return;
    const targetName = target.data.resource.targetDeployment ?? deploymentTargetName(target.data.resource.name);
    const currentTarget = changes[source.id]?.targetDeployment ?? source.data.resource.targetDeployment;
    if (targetName === currentTarget) {
      setConnectionMessage(`${source.data.resource.name} 已连接到 ${targetName}。`);
      return;
    }
    onUpdate(source.data.resource, { targetDeployment: targetName });
    setConnectionMessage(`已将 ${source.data.resource.name} 重接到 ${targetName}；生成版本差异后可检查 YAML。`);
  }

  return <>
    <ReactFlow<ApplicationFlowNode, Edge>
      nodes={nodes}
      edges={edges}
      nodeTypes={applicationFlowNodeTypes}
      onNodesChange={onNodesChange}
      onConnect={connect}
      isValidConnection={validConnection}
      connectionMode={ConnectionMode.Strict}
      onConnectEnd={(_, state) => {
        if (state.toHandle && !state.isValid) setConnectionMessage('只允许从 Service 输出端连接到 Deployment 输入端。');
      }}
      nodesDraggable={!busy}
      nodesConnectable={!busy && !hasDraft}
      elementsSelectable={false}
      deleteKeyCode={null}
      minZoom={0.3}
      maxZoom={1.8}
      noWheelClassName="nowheel"
      fitView
    >
      <Background color="#dbe3ee" gap={22} />
      <Controls />
    </ReactFlow>
    {connectionMessage && <div className="application-flow-message" role="status">{connectionMessage}</div>}
  </>;
}

function VersionCard({ version, busy, onDeploy }: { version: KubernetesApplicationVersion; busy: boolean; onDeploy: () => void }) {
  const [expanded, setExpanded] = useState(version.state === 'DRAFT');
  return <article className={`application-version-card ${version.state.toLowerCase()}`}>
    <div className="application-version-summary"><div className="version-number"><span>VERSION</span><strong>v{version.version}</strong></div><div className="version-main"><div><AppState value={version.state} /><small>基于 v{version.baseVersion} · {new Date(version.createdAt).toLocaleString('zh-CN')}</small></div><code>sha256:{version.manifestSha256.slice(0, 12)}…</code></div><button className="link" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>{expanded ? '收起 diff' : '查看 diff'}　⌄</button>{version.state === 'DRAFT' && <button className="primary" disabled={busy} onClick={onDeploy}>{busy ? '部署中…' : '确认部署 v' + version.version}</button>}</div>
    {expanded && <div className="application-diff-wrap">{version.errorMessage && <div className="alert error">{version.errorMessage}</div>}{version.manifestDiff ? <pre className="application-diff">{version.manifestDiff.split('\n').map((line, index) => <span key={`${index}-${line}`} className={line.startsWith('+') ? 'added' : line.startsWith('-') ? 'removed' : ''}>{line || ' '}{'\n'}</span>)}</pre> : <p className="application-muted">此版本没有资源差异文本。</p>}</div>}
  </article>;
}
