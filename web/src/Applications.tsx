import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  ConnectionMode, Handle, MarkerType, Position, ReactFlowProvider,
  useNodesState, useReactFlow,
  type Connection, type Edge, type Node, type NodeProps,
} from '@xyflow/react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import { parseDocument, stringify as stringifyYaml } from 'yaml';
import { connectionError, createTopologyNode, draftError, nodePresets, validNodeShape } from './lib/topology-model';
import type {
  KubernetesApplication,
  KubernetesApplicationCreateRequest,
  KubernetesApplicationDetail,
  KubernetesApplicationList,
  KubernetesApplicationPreview,
  KubernetesApplicationTopologyNode,
  KubernetesApplicationTopologyAddition,
  KubernetesApplicationNetworkTopology,
  KubernetesApplicationNetworkTopologyWrite,
  KubernetesApplicationNetworkRuntime,
  KubernetesApplicationVersion,
  KubernetesApplicationVersionDraftRequest,
  KubernetesApplicationVersionList,
  KubernetesClusterList,
  KubernetesClusterSummary,
  KubernetesTopologyNamespaceSnapshot,
  TopologyDraftWrite,
  TopologyLink,
  TopologyNode,
  TopologyNodeKind,
} from './lib/types';
import { TopologyCanvas } from './topology/TopologyCanvas';
import { TopologyContextMenu, TopologyContextMenuHeading, TopologyContextMenuItem } from './topology/TopologyContextMenu';
import './applications.css';

type View = 'topology' | 'network' | 'versions';
type Editable = { replicas?: number; image?: string; targetDeployment?: string; servicePort?: number; targetPort?: string; serviceProtocol?: 'TCP' | 'UDP' | 'SCTP' };
type PendingApplicationNode = KubernetesApplicationTopologyAddition & {
  id: string;
  position: { x: number; y: number };
  sourceYaml?: string;
};
type KubernetesApplicationSource = KubernetesApplicationCreateRequest['source'];
const creationSources: { id: KubernetesApplicationSource; label: string; hint: string }[] = [
  { id: 'FORM', label: '图形化创建', hint: '镜像、端口、副本与环境变量' },
  { id: 'DOCKER_RUN', label: 'Docker run', hint: '导入 docker run 命令' },
  { id: 'DOCKER_COMPOSE', label: 'Compose', hint: '导入 Docker Compose YAML' },
  { id: 'KUBERNETES', label: 'Kubernetes YAML', hint: '导入 Deployment / Service' },
  { id: 'BOOKINFO', label: 'Bookinfo', hint: '创建微服务测试示例' },
];

const stateLabels: Record<string, string> = {
  DEPLOYED: '运行中', APPLYING: '部署中', FAILED: '失败', PARTIAL: '部分完成',
  DRAFT: '待确认', READY: '就绪', PROGRESSING: '更新中', MISSING: '资源缺失', PENDING: '待部署',
  UNAVAILABLE: '状态未知', ACTIVE: '已配置', CONFIGURED: '已配置', UNKNOWN: '未知',
};

function stateClass(state: string) {
  return state.toLowerCase().replaceAll('_', '-');
}

function additionSourceYaml(addition: KubernetesApplicationTopologyAddition) {
  const scalar = (value: string) => JSON.stringify(value);
  if (addition.kind === 'Deployment') {
    const name = scalar(addition.name);
    return [
      'apiVersion: apps/v1', 'kind: Deployment', 'metadata:', `  name: ${name}`, 'spec:',
      `  replicas: ${addition.replicas ?? 1}`, '  selector:', '    matchLabels:', `      app: ${name}`,
      '  template:', '    metadata:', '      labels:', `        app: ${name}`, '    spec:',
      '      containers:', '        - name: app', `          image: ${scalar(addition.image ?? 'nginx:1.27')}`,
      '          imagePullPolicy: IfNotPresent', '',
    ].join('\n');
  }
  const target = scalar(addition.targetDeployment ?? '');
  const serviceTarget = scalar(addition.targetPort ?? '80');
  return [
    'apiVersion: v1', 'kind: Service', 'metadata:', `  name: ${scalar(addition.name)}`, 'spec:',
    '  type: ClusterIP', '  selector:', `    app: ${target}`, '  ports:', '    - name: http',
    `      port: ${addition.servicePort ?? 80}`, `      protocol: ${addition.serviceProtocol ?? 'TCP'}`,
    `      targetPort: ${serviceTarget}`, '',
  ].join('\n');
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
  const [displayName, setDisplayName] = useState('新建应用');
  const [applicationName, setApplicationName] = useState('web-app');
  const [createSource, setCreateSource] = useState<KubernetesApplicationSource>('FORM');
  const [image, setImage] = useState('nginx:1.27');
  const [replicas, setReplicas] = useState('1');
  const [containerPort, setContainerPort] = useState('80');
  const [servicePort, setServicePort] = useState('80');
  const [environmentText, setEnvironmentText] = useState('');
  const [definition, setDefinition] = useState('');
  const [preview, setPreview] = useState<KubernetesApplicationPreview>();
  const [changes, setChanges] = useState<Record<string, Editable>>({});
  const [yamlSourceChanges, setYamlSourceChanges] = useState<Record<string, string>>({});
  const [additions, setAdditions] = useState<PendingApplicationNode[]>([]);
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
      setYamlSourceChanges({});
      setAdditions([]);
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
  const hasYamlChanges = nodeList.some(node => yamlSourceChanges[node.id] !== undefined
    && yamlSourceChanges[node.id] !== node.sourceYaml);
  const deployments = useMemo(() => nodeList.filter(node => node.kind === 'Deployment'), [nodeList]);
  const services = useMemo(() => nodeList.filter(node => node.kind === 'Service'), [nodeList]);
  const accounts = useMemo(() => nodeList.filter(node => node.kind === 'ServiceAccount'), [nodeList]);
  const updateNode = useCallback((node: KubernetesApplicationTopologyNode, value: Editable) => {
    if (node.id.startsWith('pending:')) {
      setAdditions(current => current.map(addition => addition.id === node.id
        ? { ...addition, ...value, sourceYaml: undefined } : addition));
      return;
    }
    setChanges(current => ({ ...current, [node.id]: { ...current[node.id], ...value } }));
    setYamlSourceChanges(current => {
      if (!Object.hasOwn(current, node.id)) return current;
      const next = { ...current };
      delete next[node.id];
      return next;
    });
  }, []);

  const saveResourceYaml = useCallback((node: KubernetesApplicationTopologyNode, sourceYaml: string) => {
    if (node.id.startsWith('pending:')) {
      setAdditions(current => current.map(addition => addition.id === node.id
        ? { ...addition, sourceYaml } : addition));
      return;
    }
    setChanges(current => {
      if (!Object.hasOwn(current, node.id)) return current;
      const next = { ...current };
      delete next[node.id];
      return next;
    });
    setYamlSourceChanges(current => ({ ...current, [node.id]: sourceYaml }));
  }, []);

  function buildCreateRequest(): KubernetesApplicationCreateRequest {
    const environment: Record<string, string> = {};
    if (createSource === 'FORM' && environmentText.trim()) {
      for (const line of environmentText.split('\n').map(value => value.trim()).filter(Boolean)) {
        const separator = line.indexOf('=');
        if (separator < 1) throw new Error(`环境变量必须写成 KEY=VALUE：${line}`);
        const key = line.slice(0, separator).trim();
        if (!key) throw new Error('环境变量名称不能为空。');
        if (Object.hasOwn(environment, key)) throw new Error(`环境变量重复：${key}`);
        environment[key] = line.slice(separator + 1);
      }
    }
    const numeric = (value: string, label: string) => {
      if (!value.trim()) return null;
      const parsed = Number(value);
      if (!Number.isInteger(parsed)) throw new Error(`${label}必须是整数。`);
      return parsed;
    };
    return {
      clusterId,
      namespace,
      applicationName: createSource === 'BOOKINFO' ? 'bookinfo' : applicationName.trim(),
      displayName: displayName.trim(),
      source: createSource,
      image: createSource === 'FORM' ? image.trim() : null,
      replicas: createSource === 'FORM' ? numeric(replicas, '副本数') : null,
      containerPort: createSource === 'FORM' ? numeric(containerPort, '容器端口') : null,
      servicePort: createSource === 'FORM' && containerPort.trim()
        ? numeric(servicePort, 'Service 端口') : null,
      environment,
      definition: createSource === 'DOCKER_RUN' || createSource === 'DOCKER_COMPOSE'
        || createSource === 'KUBERNETES' ? definition : null,
    };
  }

  function beginCreate() {
    setError('');
    setWizardStep(1);
    setPreview(undefined);
    setCreateSource('FORM');
    setDisplayName('新建应用');
    setApplicationName('web-app');
    setImage('nginx:1.27');
    setReplicas('1');
    setContainerPort('80');
    setServicePort('80');
    setEnvironmentText('');
    setDefinition('');
    setWizard(true);
  }

  async function submitCreateWizard(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!clusterId || !namespace) { setError('请先选择已登记集群和现有 Namespace。'); return; }
    setError('');
    let request: KubernetesApplicationCreateRequest;
    try {
      request = buildCreateRequest();
    } catch (cause) {
      setError((cause as Error).message);
      return;
    }
    if (wizardStep === 1) {
      setBusy('preview');
      try {
        const result = await api<KubernetesApplicationPreview>(
          '/kubernetes/applications/preview', 'POST', request, {}, true,
        );
        setPreview(result.data);
        setWizardStep(2);
      } catch (cause) {
        setError((cause as Error).message);
      } finally {
        setBusy('');
      }
      return;
    }
    setBusy('create');
    try {
      const result = await api<KubernetesApplicationDetail>('/kubernetes/applications', 'POST', request, {}, true);
      setItems(current => [result.data.application, ...current.filter(item => item.id !== result.data.application.id)]);
      setSelectedId(result.data.application.id);
      setDetail(result.data);
      setVersions([]);
      setChanges({});
      setYamlSourceChanges({});
      setWizard(false);
      setWizardStep(1);
      await loadApplication(result.data.application.id);
      notify(`${result.data.application.displayName} 已部署到 ${result.data.application.clusterName} / ${namespace}`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function createDraft() {
    if (!detail || hasDraft) return;
    const yamlEdits: NonNullable<KubernetesApplicationVersionDraftRequest['yamlEdits']> =
      nodeList.flatMap(node => {
        const sourceYaml = yamlSourceChanges[node.id];
        return sourceYaml !== undefined && sourceYaml !== node.sourceYaml
          ? [{ resourceId: node.id, sourceYaml }] : [];
      });
    const yamlEditedIds = new Set(yamlEdits.map(edit => edit.resourceId));
    const edits: KubernetesApplicationVersionDraftRequest['edits'] = nodeList.flatMap(node => {
      if (yamlEditedIds.has(node.id)) return [];
      const changed = changes[node.id];
      if (!changed) return [];
      const edit: NonNullable<KubernetesApplicationVersionDraftRequest['edits']>[number] = {
        resourceId: node.id,
        replicas: node.kind === 'Deployment' && changed.replicas !== undefined
          && changed.replicas !== node.replicas ? changed.replicas : null,
        image: node.kind === 'Deployment' && changed.image !== undefined
          && changed.image.trim() !== node.image ? changed.image.trim() : null,
        targetDeployment: node.kind === 'Service' && changed.targetDeployment !== undefined
          && changed.targetDeployment !== node.targetDeployment ? changed.targetDeployment : null,
        servicePort: node.kind === 'Service' && changed.servicePort !== undefined
          && changed.servicePort !== node.servicePort ? changed.servicePort : null,
        targetPort: node.kind === 'Service' && changed.targetPort !== undefined
          && changed.targetPort !== node.targetPort ? changed.targetPort : null,
        serviceProtocol: node.kind === 'Service' && changed.serviceProtocol !== undefined
          && changed.serviceProtocol !== node.serviceProtocol ? changed.serviceProtocol : null,
      };
      if (edit.replicas === null && edit.image === null && edit.targetDeployment === null
          && edit.servicePort === null && edit.targetPort === null && edit.serviceProtocol === null) return [];
      return [edit];
    });
    const yamlAdditions: NonNullable<KubernetesApplicationVersionDraftRequest['yamlAdditions']> =
      additions.flatMap(addition => addition.sourceYaml
        ? [{ sourceYaml: addition.sourceYaml }] : []);
    const newResources: KubernetesApplicationTopologyAddition[] = additions.flatMap(
      ({ id: _id, position: _position, sourceYaml: _sourceYaml, ...resource }) =>
        _sourceYaml ? [] : [resource],
    );
    if (!edits.length && !yamlEdits.length && !newResources.length && !yamlAdditions.length) {
      setError('请先修改已有资源，或在拓扑空白处右键添加 Deployment / Service。'); return;
    }
    setBusy('draft');
    setError('');
    try {
      const result = await api<KubernetesApplicationDetail>(
        `/kubernetes/applications/${encodeURIComponent(detail.application.id)}/versions`, 'POST',
        { baseVersion: detail.application.currentVersion, edits, additions: newResources,
          yamlEdits, yamlAdditions }, {}, true,
      );
      setDetail(result.data);
      setChanges({});
      setYamlSourceChanges({});
      await loadApplication(result.data.application.id);
      setView('versions');
      notify('版本差异已生成；当前未修改集群，请检查 diff 后显式部署。');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  function removePendingNode(nodeId: string) {
    setAdditions(current => current.filter(addition => addition.id !== nodeId));
    setChanges(current => {
      const next = { ...current };
      delete next[nodeId];
      return next;
    });
    setYamlSourceChanges(current => {
      if (!Object.hasOwn(current, nodeId)) return current;
      const next = { ...current };
      delete next[nodeId];
      return next;
    });
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
    {error && <div className="alert error" role="alert">{error}</div>}
    {isMock && <div className="alert">Mock 演示环境没有真实集群应用接口。此页面在 Live API 模式下连接已登记集群。</div>}
    <div className="application-layout">
      <aside className="application-list-panel">
        <div className="application-list-heading"><div><span className="eyebrow">APPLICATIONS</span><strong>应用列表</strong></div><div className="application-list-heading-actions"><button type="button" className="secondary" title="刷新应用列表" aria-label="刷新应用列表" onClick={() => void loadList()} disabled={loading}>↻</button><button type="button" className="primary" onClick={beginCreate} disabled={isMock}>＋ 创建</button><span className="application-count">{items.length}</span></div></div>
        {loading && <p className="application-muted">正在读取应用…</p>}
        {!loading && items.length === 0 && !error && <div className="application-empty-small"><b>◇</b><span>还没有应用</span><small>使用图形化向导或导入容器清单创建应用。</small><button className="link" onClick={beginCreate}>创建第一个应用 →</button></div>}
        <div className="application-list">
          {items.map(item => <button key={item.id} className={`application-list-item ${selectedId === item.id ? 'active' : ''}`} onClick={() => { setSelectedId(item.id); setView('topology'); }}>
            <span className="application-symbol">▦</span><span className="application-list-copy"><strong>{item.displayName}</strong><small>{item.clusterName} · {item.namespace}</small><small>版本 v{item.currentVersion} · {item.templateId}</small></span><AppState value={item.state} />
          </button>)}
        </div>
        <div className="application-list-footer"><span className="application-live-dot" />已接入的应用清单</div>
      </aside>
      <section className="application-content">
        {!selectedApp || !detail ? <div className="application-blank"><div className="application-blank-icon">▦</div><h2>{items.length ? '选择一个应用' : '开始管理 Kubernetes 应用'}</h2><p>应用拓扑根据部署 YAML 自动生成。创建版本后检查资源 diff，再手动确认部署。</p><button className="primary" onClick={beginCreate} disabled={isMock}>＋ 创建应用</button></div> : <>
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
            <button role="tab" aria-selected={view === 'network'} className={view === 'network' ? 'active' : ''} onClick={() => setView('network')}>⇄ 虚拟网络设备</button>
            <button role="tab" aria-selected={view === 'versions'} className={view === 'versions' ? 'active' : ''} onClick={() => setView('versions')}>◷ 版本历史 <span>{versions.length}</span></button>
            <div className="application-tab-actions">{view !== 'network' && <button className="secondary" onClick={() => void loadApplication(detail.application.id)}>刷新应用资源</button>}{view === 'topology' && <button className="primary" disabled={(!Object.keys(changes).length && !hasYamlChanges && !additions.length) || additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment && !addition.sourceYaml) || hasDraft || busy !== ''} title={additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment && !addition.sourceYaml) ? '请将新增 Service 连到 Deployment 后再生成差异' : undefined} onClick={() => void createDraft()}>{busy === 'draft' ? '生成差异中…' : '生成版本差异 →'}</button>}</div>
          </div>
          <div className="application-view-content">
          {view === 'topology' ? <>
            {hasDraft && <div className="application-draft-banner"><span>◈</span><div><strong>v{currentDraft?.version} 有待确认差异</strong><small>检查版本历史中的 YAML diff，确认后部署；拓扑编辑会先锁定到该草稿。</small></div><button className="link" onClick={() => setView('versions')}>查看 diff →</button></div>}
            <section className="application-topology-card">
              <div className="application-section-heading"><div><span className="eyebrow">YAML DERIVED TOPOLOGY</span><h3>应用拓扑</h3><p>连线来自 Service selector 与 Deployment Pod 标签的匹配结果；ServiceAccount 也可单独查看和编辑。</p></div><span className="application-topology-legend"><i className="service-dot" /> Service <i className="deployment-dot" /> Deployment <i className="account-dot" /> ServiceAccount</span></div>
              <div className="application-topology-canvas">
                <ApplicationTopologyFlow
                  key={detail.application.id}
                  resources={nodeList}
                  topologyEdges={detail.topology.edges}
                  changes={changes}
                  yamlSourceChanges={yamlSourceChanges}
                  additions={additions}
                  hasDraft={hasDraft}
                  busy={busy !== ''}
                  onUpdate={updateNode}
                  onSourceYamlSave={saveResourceYaml}
                  onAdditionChange={addition => setAdditions(current => current.some(item => item.id === addition.id)
                    ? current.map(item => item.id === addition.id ? addition : item) : [...current, addition])}
                  onRemoveAddition={removePendingNode}
                />
              </div>
              {additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment) && <div className="alert" role="status">新增 Service 尚未连接到 Deployment；拖动 Service 绿色端口到 Deployment 蓝色端口后即可生成差异。</div>}
              <div className="application-topology-footnote">空白处右键添加 Deployment 或 Service；拖动节点调整布局，拖动 Service 绿色端口到 Deployment 蓝色端口可连接/重接服务。每张资源卡都可编辑独立源 YAML；变更先生成版本 diff，只有显式确认部署才会写入集群。</div>
            </section>
          </> : view === 'versions' ? <section className="application-versions-card">
            <div className="application-section-heading"><div><span className="eyebrow">IMMUTABLE MANIFEST HISTORY</span><h3>版本历史</h3><p>草稿不会修改集群；只有状态为“待确认”的版本可提交部署。</p></div></div>
            {!versions.length ? <div className="application-empty-history">暂无版本记录</div> : <div className="application-version-list">{versions.map(version => <VersionCard key={version.version} version={version} busy={busy === `deploy-${version.version}`} onDeploy={() => void deployDraft(version)} />)}</div>}
          </section> : null}
          <div className={`application-network-topology ${view === 'network' ? 'visible' : ''}`} aria-hidden={view !== 'network'}>
            <ApplicationNetworkTopologyCanvas
              key={`network-${detail.application.id}`}
              applicationId={detail.application.id}
              resources={nodeList}
              topologyEdges={detail.topology.edges}
              yamlSourceChanges={yamlSourceChanges}
              busy={busy !== ''}
              onResourceYamlSave={saveResourceYaml}
            />
          </div>
          </div>
        </>}
      </section>
    </div>
    {wizard && <div className="modal-backdrop"><div className="modal application-wizard" role="dialog" aria-modal="true" aria-labelledby="application-create-title">
      <div className="application-wizard-header"><div><span className="eyebrow">APPLICATION · CREATE WIZARD</span><h2 id="application-create-title">创建 Kubernetes 应用</h2></div><button className="icon-button" aria-label="关闭" onClick={() => { if (!busy) setWizard(false); }}>×</button></div>
      <div className="application-wizard-steps"><span className={wizardStep === 1 ? 'active' : ''}><i>1</i>配置来源</span><b /><span className={wizardStep === 2 ? 'active' : ''}><i>2</i>预览并确认</span></div>
      {error && <div className="alert error application-wizard-error" role="alert">{error}</div>}
      {busy && <div className="application-operation-progress" role="status"><i />{busy === 'preview' ? '正在解析输入、检查资源并生成 YAML 预览…' : busy === 'create' ? '正在登记应用，创建 Namespace 资源并校验拓扑…' : '正在处理应用请求…'}</div>}
      <form onSubmit={submitCreateWizard}>
        {wizardStep === 1 ? <fieldset disabled={busy !== ''} className="application-wizard-fieldset"><div className="application-wizard-fields application-wizard-inputs">
          <div className="application-source-grid" aria-label="应用创建方式">{creationSources.map(source => <button type="button" aria-pressed={createSource === source.id} key={source.id} className={`application-source-card ${createSource === source.id ? 'active' : ''}`} onClick={() => { setCreateSource(source.id); if (source.id === 'BOOKINFO') { setApplicationName('bookinfo'); setDisplayName('Bookinfo 测试应用'); } else if (applicationName === 'bookinfo') { setApplicationName('web-app'); setDisplayName('新建应用'); } setPreview(undefined); }}>{source.label}<small>{source.hint}</small></button>)}</div>
          <div className="application-wizard-targets">
            <label className="field"><span>应用显示名称</span><input value={displayName} maxLength={80} required onChange={event => setDisplayName(event.target.value)} /></label>
            <label className="field"><span>应用标识（DNS 名称）</span><input value={createSource === 'BOOKINFO' ? 'bookinfo' : applicationName} disabled={createSource === 'BOOKINFO'} maxLength={63} pattern="[a-z0-9]([a-z0-9-]*[a-z0-9])?" required onChange={event => setApplicationName(event.target.value)} /></label>
            <label className="field"><span>目标集群</span><select required value={clusterId} onChange={event => setClusterId(event.target.value)}><option value="">选择已登记集群</option>{clusters.map(cluster => <option key={cluster.id} value={cluster.id}>{cluster.name} · {cluster.kind}</option>)}</select></label>
            <label className="field"><span>目标 Namespace</span><select required value={namespace} disabled={!namespaces.length} onChange={event => setNamespace(event.target.value)}><option value="">{namespaces.length ? '选择 Namespace' : '读取 Namespace…'}</option>{namespaces.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
          </div>
          {!clusters.length && !loading && <div className="alert">尚未登记 Kubernetes 集群。请先前往“集群管理”导入集群并授予 Namespace 级资源权限。</div>}
          {clusters.length > 0 && !namespaces.length && <div className="alert">无法读取 Namespace。请确认集群凭据具有 Namespace get/list 权限。</div>}
          {createSource === 'FORM' && <div className="application-source-fields">
            <label className="field"><span>容器镜像</span><input value={image} required placeholder="nginx:1.27" onChange={event => setImage(event.target.value)} /></label>
            <div className="application-inline-fields"><label className="field"><span>副本数（0–20）</span><input type="number" min="0" max="20" value={replicas} required onChange={event => setReplicas(event.target.value)} /></label><label className="field"><span>容器端口（可选）</span><input type="number" min="1" max="65535" value={containerPort} onChange={event => setContainerPort(event.target.value)} /></label><label className="field"><span>Service 端口</span><input type="number" min="1" max="65535" value={servicePort} disabled={!containerPort} onChange={event => setServicePort(event.target.value)} /></label></div>
            <label className="field"><span>环境变量（每行 KEY=VALUE）</span><textarea rows={3} value={environmentText} placeholder={'APP_MODE=production\nLOG_LEVEL=info'} onChange={event => setEnvironmentText(event.target.value)} /></label>
            <small className="application-security-note">环境变量会明文写入 Deployment YAML，请勿填写密码、令牌或其他 Secret。</small>
          </div>}
          {createSource === 'DOCKER_RUN' && <label className="field application-definition-field"><span>Docker run 命令</span><textarea rows={5} required value={definition} placeholder={'docker run -d --name web -p 8080:80 nginx:1.27'} onChange={event => setDefinition(event.target.value)} /></label>}
          {createSource === 'DOCKER_COMPOSE' && <label className="field application-definition-field"><span>Docker Compose YAML</span><textarea rows={7} required value={definition} placeholder={'services:\n  web:\n    image: nginx:1.27\n    ports:\n      - "8080:80"'} onChange={event => setDefinition(event.target.value)} /></label>}
          {createSource === 'KUBERNETES' && <label className="field application-definition-field"><span>Kubernetes Deployment / Service YAML</span><textarea rows={7} required value={definition} placeholder={'apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: web\n...'} onChange={event => setDefinition(event.target.value)} /></label>}
          {createSource === 'BOOKINFO' && <div className="application-template-summary"><span className="template-icon">✦</span><div><strong>Bookinfo 微服务示例</strong><small>4 Services · 6 Deployments · 4 ServiceAccounts</small></div><span className="template-tag">Istio 可选</span></div>}
        </div></fieldset> : <div className="application-create-review">
          <div className="application-review-heading"><div><span className="eyebrow">MANIFEST PREVIEW</span><h3>{displayName}</h3><p>{clusters.find(cluster => cluster.id === clusterId)?.name} · {namespace} · {applicationName}</p></div><span className="application-review-total">{preview?.totalResources ?? 0}<small>个资源</small></span></div>
          <div className="bookinfo-resource-grid">{Object.entries(preview?.resourceCounts ?? {}).map(([kind, count]) => <span key={kind}><b>{String(count).padStart(2, '0')}</b>{kind}</span>)}</div>
          {!!preview?.notes.length && <ul className="application-preview-notes">{preview.notes.map(note => <li key={note}>{note}</li>)}</ul>}
          <pre className="application-manifest-preview">{preview?.manifestYaml ?? ''}</pre>
          <p className="application-confirm-copy">确认后将创建初始版本并部署到所选 Namespace。已有同名资源时会拒绝创建，不接管集群中现有资源。</p>
        </div>}
        <div className="form-actions application-wizard-actions"><button type="button" className="secondary" onClick={() => { if (wizardStep === 2) { setWizardStep(1); setPreview(undefined); } else setWizard(false); }} disabled={busy !== ''}>{wizardStep === 2 ? '修改配置' : '取消'}</button>{wizardStep === 1 ? <button type="submit" className="primary" disabled={!clusterId || !namespace || !displayName.trim() || busy !== ''}>{busy === 'preview' ? '正在解析并生成预览…' : '生成清单预览 →'}</button> : <button className="primary" disabled={busy !== ''}>{busy === 'create' ? '正在登记并部署…' : '确认创建并部署'}</button>}</div>
      </form>
    </div></div>}
  </div>;
}

type ApplicationFlowNodeData = {
  resource: KubernetesApplicationTopologyNode;
  changes: Editable;
  controlsDisabled: boolean;
  sourceYamlEdited: boolean;
  networkMode?: boolean;
  onEditSourceYaml: (resource: KubernetesApplicationTopologyNode) => void;
};
type ApplicationFlowNode = Node<ApplicationFlowNodeData, 'application'>;
type ApplicationContextMenuState = {
  x: number;
  y: number;
  point?: { x: number; y: number };
  resource?: KubernetesApplicationTopologyNode;
  connectedTo?: string;
};

function applicationFlowNodePosition(node: KubernetesApplicationTopologyNode, index: number) {
  if (node.kind === 'Service') return { x: 40, y: 42 + index * 224 };
  if (node.kind === 'ServiceAccount') return { x: 1_190, y: 42 + index * 224 };
  return { x: 430 + (index % 2) * 380, y: 36 + Math.floor(index / 2) * 224 };
}

function deploymentTargetName(name: string) {
  return name.replace(/-v[0-9]+$/, '');
}

function deploymentTarget(resource: KubernetesApplicationTopologyNode) {
  return resource.targetDeployment ?? deploymentTargetName(resource.name);
}

function ApplicationFlowNodeCard({ data, selected }: NodeProps<ApplicationFlowNode>) {
  const { resource, changes, controlsDisabled } = data;
  const deployment = resource.kind === 'Deployment';
  const service = resource.kind === 'Service';
  const account = resource.kind === 'ServiceAccount';
  const title = deployment ? 'DEPLOYMENT' : service ? 'SERVICE' : 'SERVICE ACCOUNT';
  const servicePortSummary = resource.servicePorts.map((port, index) => {
    const displayedPort = index === 0 ? changes.servicePort ?? port.port : port.port;
    const displayedTarget = index === 0 ? changes.targetPort ?? port.targetPort : port.targetPort;
    const displayedProtocol = index === 0 ? changes.serviceProtocol ?? port.protocol : port.protocol;
    return `${port.name ? `${port.name}: ` : ''}${displayedPort}/${displayedProtocol} → ${displayedTarget}`;
  }).join(' · ');
  return <article className={`application-flow-node ${resource.kind.toLowerCase()} ${selected ? 'selected' : ''}`}>
    {deployment && <Handle id="deployment-input" type="target" position={Position.Left} className="application-flow-handle deployment-input" isConnectable={!controlsDisabled} />}
    {service && <Handle id="service-output" type="source" position={Position.Right} className="application-flow-handle service-output" isConnectable={!controlsDisabled} />}
    {deployment && data.networkMode && <>
      <Handle id="network-in-1" type="target" position={Position.Top} className="application-network-handle network-in" isConnectable={!controlsDisabled} />
      <Handle id="network-out-1" type="source" position={Position.Bottom} className="application-network-handle network-out" isConnectable={!controlsDisabled} />
    </>}
    <div className="application-flow-node-heading">
      <span className={`node-type ${resource.kind.toLowerCase()}`}>{title}</span>
      <AppState value={resource.state} />
    </div>
    <strong title={resource.name}>{resource.name}</strong>
    {deployment ? <>
      <small>{resource.readyReplicas ?? 0}/{changes.replicas ?? resource.replicas ?? 0} Ready</small>
      <small className="application-node-image">{changes.image ?? resource.image ?? '未声明镜像'}</small>
    </> : service ? <>
      <small>ClusterIP · {servicePortSummary || `${resource.servicePort ?? '—'}/${resource.serviceProtocol ?? 'TCP'} → ${resource.targetPort ?? '—'}`}</small>
      <small>后端：{changes.targetDeployment ?? resource.targetDeployment ?? '通过拓扑连线指定'}</small>
    </> : <>
      <small>应用级身份资源</small>
      <small>ServiceAccount Token 自动挂载已关闭</small>
    </>}
    <div className="application-flow-node-footer">
      {data.sourceYamlEdited && <small className="application-yaml-pending">YAML 待生成版本差异</small>}
      <button type="button" className="application-yaml-button nodrag" disabled={controlsDisabled}
        onClick={event => { event.stopPropagation(); data.onEditSourceYaml(resource); }}>编辑源 YAML</button>
    </div>
  </article>;
}

const applicationFlowNodeTypes = { application: ApplicationFlowNodeCard };

type ApplicationTopologyFlowProps = {
  resources: KubernetesApplicationTopologyNode[];
  topologyEdges: KubernetesApplicationDetail['topology']['edges'];
  changes: Record<string, Editable>;
  yamlSourceChanges: Record<string, string>;
  additions: PendingApplicationNode[];
  hasDraft: boolean;
  busy: boolean;
  onUpdate: (node: KubernetesApplicationTopologyNode, value: Editable) => void;
  onSourceYamlSave: (node: KubernetesApplicationTopologyNode, sourceYaml: string) => void;
  onAdditionChange: (addition: PendingApplicationNode) => void;
  onRemoveAddition: (id: string) => void;
};

function ApplicationTopologyFlow(props: ApplicationTopologyFlowProps) {
  return <ReactFlowProvider><ApplicationTopologyFlowCanvas {...props} /></ReactFlowProvider>;
}

function ApplicationTopologyFlowCanvas({
  resources, topologyEdges, changes, yamlSourceChanges, additions, hasDraft, busy, onUpdate,
  onSourceYamlSave, onAdditionChange, onRemoveAddition,
}: ApplicationTopologyFlowProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<ApplicationFlowNode>([]);
  const { fitView, screenToFlowPosition } = useReactFlow<ApplicationFlowNode, Edge>();
  const [connectionMessage, setConnectionMessage] = useState('');
  const [editingNode, setEditingNode] = useState<KubernetesApplicationTopologyNode>();
  const [yamlEditorNode, setYamlEditorNode] = useState<KubernetesApplicationTopologyNode>();
  const [contextMenu, setContextMenu] = useState<ApplicationContextMenuState>();
  const openSourceYamlEditor = useCallback((resource: KubernetesApplicationTopologyNode) => {
    if (changes[resource.id] && !window.confirm('此资源已有表单字段修改。继续编辑源 YAML 后，保存的 YAML 会替代这些表单修改。继续吗？')) return;
    setYamlEditorNode(resource);
  }, [changes]);
  const allResources = useMemo(() => [...resources, ...additions.map(addition => ({
    id: addition.id,
    kind: addition.kind,
    name: addition.name,
    replicas: addition.replicas,
    readyReplicas: null,
    image: addition.image,
    targetDeployment: addition.kind === 'Deployment' ? addition.name : addition.targetDeployment,
    servicePort: addition.servicePort,
    targetPort: addition.targetPort,
    serviceProtocol: addition.serviceProtocol,
    servicePorts: addition.kind === 'Service' ? [{
      name: 'http', port: addition.servicePort ?? 80,
      targetPort: addition.targetPort ?? '80', protocol: addition.serviceProtocol ?? 'TCP',
    }] : [],
    state: 'PENDING' as const,
    sourceYaml: addition.sourceYaml ?? additionSourceYaml(addition),
  }))], [resources, additions]);
  const visibleResources = useMemo(
    () => allResources.filter(resource => ['Service', 'Deployment', 'ServiceAccount'].includes(resource.kind)),
    [allResources],
  );
  const edges = useMemo<Edge[]>(() => {
    const deployments = visibleResources.filter(resource => resource.kind === 'Deployment');
    const services = visibleResources.filter(resource => resource.kind === 'Service');
    const currentServiceIds = new Set(topologyEdges.map(edge => edge.source));
    const adjustedServices = new Set(services
      .filter(resource => changes[resource.id]?.targetDeployment !== undefined || !currentServiceIds.has(resource.id))
      .map(resource => resource.id));
    const current = topologyEdges
      .filter(edge => !adjustedServices.has(edge.source))
      .map(edge => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourceHandle: 'service-output',
        targetHandle: 'deployment-input',
        type: 'nanpanelBezier' as const,
        markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
        style: { stroke: '#91addc', strokeWidth: 2 },
        selectable: false,
        deletable: false,
      }));
    const changed = services.flatMap(service => {
      const target = changes[service.id]?.targetDeployment ?? service.targetDeployment;
      if (!adjustedServices.has(service.id) || !target) return [];
      return deployments.filter(deployment => deploymentTarget(deployment) === target)
        .map(deployment => ({
          id: `${service.id}->${deployment.id}`,
          source: service.id,
          target: deployment.id,
          sourceHandle: 'service-output',
          targetHandle: 'deployment-input',
          type: 'nanpanelBezier' as const,
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
      const accountIndex = new Map(visibleResources.filter(resource => resource.kind === 'ServiceAccount')
        .map((resource, index) => [resource.id, index]));
      const pendingPositions = new Map(additions.map(addition => [addition.id, addition.position]));
      return visibleResources.map(resource => ({
        id: resource.id,
        type: 'application' as const,
        position: previousPositions.get(resource.id)
          ?? pendingPositions.get(resource.id)
          ?? applicationFlowNodePosition(resource, resource.kind === 'Service'
            ? serviceIndex.get(resource.id) ?? 0 : resource.kind === 'Deployment'
              ? deploymentIndex.get(resource.id) ?? 0 : accountIndex.get(resource.id) ?? 0),
        data: {
          resource: { ...resource,
            sourceYaml: yamlSourceChanges[resource.id] ?? resource.sourceYaml },
          changes: changes[resource.id] ?? {},
          controlsDisabled: busy || hasDraft,
          sourceYamlEdited: resource.id.startsWith('pending:')
            ? Boolean(additions.find(addition => addition.id === resource.id)?.sourceYaml)
            : yamlSourceChanges[resource.id] !== undefined
              && yamlSourceChanges[resource.id] !== resource.sourceYaml,
          onEditSourceYaml: openSourceYamlEditor,
        },
        draggable: !busy && !hasDraft,
      }));
    });
  }, [visibleResources, additions, changes, yamlSourceChanges, busy, hasDraft,
    openSourceYamlEditor, setNodes]);

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

  function addNode(kind: 'Service' | 'Deployment') {
    if (busy || hasDraft) return;
    const existingNames = new Set(visibleResources
      .filter(resource => resource.kind === kind).map(resource => resource.name));
    const prefix = kind === 'Deployment' ? 'deployment' : 'service';
    let suffix = 1;
    while (existingNames.has(`${prefix}-${suffix}`)) suffix++;
    const name = `${prefix}-${suffix}`;
    const point = contextMenu?.point
      ?? screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 });
    const deployment = visibleResources.find(resource => resource.kind === 'Deployment');
    const addition: PendingApplicationNode = {
      id: `pending:${kind}/${name}`,
      kind,
      name,
      replicas: kind === 'Deployment' ? 1 : null,
      image: kind === 'Deployment' ? 'nginx:1.27' : null,
      targetDeployment: kind === 'Service' && deployment ? deploymentTarget(deployment) : null,
      servicePort: kind === 'Service' ? 80 : null,
      targetPort: kind === 'Service' ? '80' : null,
      serviceProtocol: kind === 'Service' ? 'TCP' : null,
      position: point,
    };
    onAdditionChange(addition);
    setContextMenu(undefined);
  }

  return <>
    <TopologyCanvas<ApplicationFlowNode, Edge>
      nodes={nodes}
      edges={edges}
      nodeTypes={applicationFlowNodeTypes}
      onNodesChange={onNodesChange}
      onConnect={connect}
      onCanvasContextMenu={event => {
        if (busy || hasDraft) return;
        setContextMenu({
          x: event.clientX,
          y: event.clientY,
          point: screenToFlowPosition({ x: event.clientX, y: event.clientY }),
        });
      }}
      onNodeContextMenu={(event, node) => {
        event.preventDefault();
        if (!busy && !hasDraft) setContextMenu({ x: event.clientX, y: event.clientY, resource: node.data.resource });
      }}
      onEdgeContextMenu={(event, edge) => {
        event.preventDefault();
        if (busy || hasDraft) return;
        const service = nodes.find(node => node.id === edge.source)?.data.resource;
        const deployment = nodes.find(node => node.id === edge.target)?.data.resource;
        if (service?.kind === 'Service') setContextMenu({ x: event.clientX, y: event.clientY, resource: service, connectedTo: deployment?.name });
      }}
      isValidConnection={validConnection}
      connectionMode={ConnectionMode.Strict}
      onConnectEnd={(_, state) => {
        if (state.toHandle && !state.isValid) setConnectionMessage('只允许从 Service 输出端连接到 Deployment 输入端。');
      }}
      nodesDraggable={!busy && !hasDraft}
      nodesConnectable={!busy && !hasDraft}
      elementsSelectable={false}
      deleteKeyCode={null}
      minZoom={0.3}
      maxZoom={1.8}
      noWheelClassName="nowheel"
      fitView
    />
    {contextMenu && <TopologyContextMenu x={contextMenu.x} y={contextMenu.y} onClose={() => setContextMenu(undefined)}>
      {contextMenu.resource ? <>
        <TopologyContextMenuHeading>{contextMenu.connectedTo
          ? `${contextMenu.resource.name} → ${contextMenu.connectedTo}`
          : `${contextMenu.resource.kind === 'Service' ? 'Service' : '工作负载'} · ${contextMenu.resource.name}`}</TopologyContextMenuHeading>
        {contextMenu.resource.kind !== 'ServiceAccount' && <TopologyContextMenuItem onClick={() => { setEditingNode(contextMenu.resource); setContextMenu(undefined); }}>
          {contextMenu.resource.kind === 'Service' ? '编辑端口与连接' : '编辑副本与镜像'}
        </TopologyContextMenuItem>}
        <TopologyContextMenuItem onClick={() => { openSourceYamlEditor(contextMenu.resource!); setContextMenu(undefined); }}>
          编辑此卡片源 YAML
        </TopologyContextMenuItem>
        {!contextMenu.connectedTo && contextMenu.resource.id.startsWith('pending:') && <TopologyContextMenuItem className="danger" onClick={() => {
          onRemoveAddition(contextMenu.resource!.id);
          setContextMenu(undefined);
        }}>撤销新增节点</TopologyContextMenuItem>}
      </> : <>
        <TopologyContextMenuHeading>添加应用节点</TopologyContextMenuHeading>
        <TopologyContextMenuItem onClick={() => addNode('Deployment')}>添加 Deployment 工作负载</TopologyContextMenuItem>
        <TopologyContextMenuItem onClick={() => addNode('Service')}>添加 ClusterIP Service</TopologyContextMenuItem>
      </>}
    </TopologyContextMenu>}
    {connectionMessage && <div className="application-flow-message" role="status">{connectionMessage}</div>}
    {editingNode && <ApplicationNodeSettingsDialog
      key={editingNode.id}
      node={editingNode}
      changes={changes[editingNode.id] ?? {}}
      disabled={busy || hasDraft}
      onClose={() => setEditingNode(undefined)}
      onSave={value => { onUpdate(editingNode, value); setEditingNode(undefined); }}
    />}
    {yamlEditorNode && <ApplicationSourceYamlDialog
      node={yamlEditorNode}
      disabled={busy || hasDraft}
      onClose={() => setYamlEditorNode(undefined)}
      onSave={sourceYaml => {
        if (yamlEditorNode.id.startsWith('pending:')) {
          const addition = additions.find(item => item.id === yamlEditorNode.id);
          if (addition) onAdditionChange({ ...addition, sourceYaml });
        } else {
          onSourceYamlSave(yamlEditorNode, sourceYaml);
        }
        setYamlEditorNode(undefined);
      }}
    />}
  </>;
}

type ApplicationDeviceFlowNodeData = {
  model: TopologyNode;
  disabled: boolean;
  onEditYaml: (node: TopologyNode) => void;
};
type ApplicationDeviceFlowNode = Node<ApplicationDeviceFlowNodeData, 'application-device'>;
type ApplicationNetworkFlowNode = ApplicationFlowNode | ApplicationDeviceFlowNode;
type NetworkMenu = { x: number; y: number; point?: { x: number; y: number }; nodeId?: string; edgeId?: string };
type NetworkDraft = KubernetesApplicationNetworkTopologyWrite;

const virtualApplicationKinds = (Object.keys(nodePresets) as TopologyNodeKind[])
  .filter(kind => !['GATEWAY', 'HOST', 'SERVICE', 'FOLDER'].includes(kind));
const topologyYamlFields = [
  'kind', 'name', 'description', 'position', 'inputCount', 'outputCount', 'ports',
  'parentId', 'assetId', 'clusterId', 'ipAddress', 'providerId', 'switchBackend',
  'firewallPolicy', 'routerConfiguration',
];

function topologyNodeYaml(node: TopologyNode) {
  return stringifyYaml({
    apiVersion: 'topology.nanpanel.io/v1alpha1',
    kind: 'TopologyNode',
    metadata: { id: node.id },
    spec: Object.fromEntries(topologyYamlFields.map(field => [
      field, (node as unknown as Record<string, unknown>)[field] ?? null,
    ])),
  }, { indent: 2, lineWidth: 0 });
}

function ApplicationDeviceCard({ data, selected }: NodeProps<ApplicationDeviceFlowNode>) {
  const { model, disabled } = data;
  const portHeight = Math.max(model.inputCount, model.outputCount, 1) * 25 + 12;
  return <article className={`application-network-device ${selected ? 'selected' : ''}`}>
    <div className="application-network-device-heading"><span>{model.kind}</span><small>{nodePresets[model.kind].description}</small></div>
    <strong title={model.name}>{model.name}</strong>
    <div className="application-network-device-ports" style={{ height: portHeight }}>
      {model.ports.filter(port => port.direction === 'INPUT').map((port, index) => <div key={port.id} className="application-network-port input" style={{ top: 8 + index * 25 }}>
        <Handle id={port.id} type="target" position={Position.Left} isConnectable={!disabled} />
        <span>{port.id} · {port.layer}/{port.protocols.join(', ')}</span>
      </div>)}
      {model.ports.filter(port => port.direction === 'OUTPUT').map((port, index) => <div key={port.id} className="application-network-port output" style={{ top: 8 + index * 25 }}>
        <span>{port.id} · {port.layer}/{port.protocols.join(', ')}</span>
        <Handle id={port.id} type="source" position={Position.Right} isConnectable={!disabled} />
      </div>)}
    </div>
    {model.description && <p className="application-network-device-description">{model.description}</p>}
    <div className="application-network-device-footer"><small>{model.providerId ? `Provider ${model.providerId}` : '期望虚拟设备'}</small>
      <button type="button" className="application-yaml-button nodrag" disabled={disabled}
        onClick={event => { event.stopPropagation(); data.onEditYaml(model); }}>编辑源 YAML</button></div>
  </article>;
}

const applicationNetworkNodeTypes = {
  application: ApplicationFlowNodeCard,
  'application-device': ApplicationDeviceCard,
};

function ApplicationNetworkTopologyCanvas({
  applicationId, resources, topologyEdges, yamlSourceChanges, busy, onResourceYamlSave,
}: {
  applicationId: string;
  resources: KubernetesApplicationTopologyNode[];
  topologyEdges: KubernetesApplicationDetail['topology']['edges'];
  yamlSourceChanges: Record<string, string>;
  busy: boolean;
  onResourceYamlSave: (node: KubernetesApplicationTopologyNode, yaml: string) => void;
}) {
  const [draft, setDraft] = useState<NetworkDraft>({ nodes: [], links: [], attachments: [] });
  const [etag, setEtag] = useState('');
  const [nodes, setNodes, updateNodes] = useNodesState<ApplicationNetworkFlowNode>([]);
  const { fitView, screenToFlowPosition } = useReactFlow<ApplicationNetworkFlowNode, Edge>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [menu, setMenu] = useState<NetworkMenu>();
  const [yamlNode, setYamlNode] = useState<TopologyNode>();
  const [resourceYamlNode, setResourceYamlNode] = useState<KubernetesApplicationTopologyNode>();
  const [yamlError, setYamlError] = useState('');
  const [yamlSource, setYamlSource] = useState('');
  const [runtime, setRuntime] = useState<KubernetesApplicationNetworkRuntime | null>(null);
  const [runtimeBusy, setRuntimeBusy] = useState('');

  const refreshRuntime = useCallback(async () => {
    try {
      const result = await api<KubernetesApplicationNetworkRuntime>(
        `/kubernetes/applications/${encodeURIComponent(applicationId)}/network-topology/runtime`,
        'GET', undefined, {}, true,
      );
      setRuntime(result.data);
    } catch (cause) {
      if ((cause as { status?: number }).status === 404) setRuntime(null);
      else setError((cause as Error).message);
    }
  }, [applicationId]);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setError('');
    void refreshRuntime();
    void api<KubernetesApplicationNetworkTopology>(
      `/kubernetes/applications/${encodeURIComponent(applicationId)}/network-topology`,
      'GET', undefined, {}, true,
    ).then(result => {
      if (!current) return;
      setDraft({ nodes: result.data.nodes, links: result.data.links,
        attachments: result.data.attachments });
      setEtag(result.etag ?? '');
      setDirty(false);
    }).catch(cause => { if (current) setError((cause as Error).message); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [applicationId, refreshRuntime]);

  useEffect(() => {
    if (!runtime || !['APPLYING', 'VERIFYING', 'COMPENSATING'].includes(runtime.state)) return;
    const timer = window.setInterval(() => void refreshRuntime(), 3000);
    return () => window.clearInterval(timer);
  }, [runtime?.state, refreshRuntime]);

  const runRuntimeAction = async (action: 'apply' | 'verify' | 'compensate') => {
    const labels = { apply: 'Apply', verify: 'Verify', compensate: 'Compensate' };
    const reason = window.prompt(`请输入${labels[action]}原因（最多 500 字）`);
    if (!reason?.trim()) return;
    setRuntimeBusy(action);
    setError('');
    setMessage('');
    try {
      const result = await api<KubernetesApplicationNetworkRuntime>(
        `/kubernetes/applications/${encodeURIComponent(applicationId)}/network-topology/runtime/${action}`,
        'POST', { reason: reason.trim() }, {}, true,
      );
      setRuntime(result.data);
      setMessage(`${labels[action]} 已提交；运行时状态会自动刷新。`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setRuntimeBusy('');
    }
  };

  const visibleResources = useMemo(() => resources.filter(resource =>
    ['Service', 'Deployment', 'ServiceAccount'].includes(resource.kind)), [resources]);
  const selectorEdges = useMemo<Edge[]>(() => topologyEdges.map(edge => ({
    id: edge.id, source: edge.source, target: edge.target,
    sourceHandle: 'service-output', targetHandle: 'deployment-input',
    type: 'nanpanelBezier', markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
    style: { stroke: '#91addc', strokeWidth: 2 }, selectable: false, deletable: false,
  })), [topologyEdges]);
  const nodeIds = useMemo(() => new Set(draft.nodes.map(node => node.id)), [draft.nodes]);

  useEffect(() => {
    const serviceIndex = new Map(visibleResources.filter(resource => resource.kind === 'Service')
      .map((resource, index) => [resource.id, index]));
    const deploymentIndex = new Map(visibleResources.filter(resource => resource.kind === 'Deployment')
      .map((resource, index) => [resource.id, index]));
    const accountIndex = new Map(visibleResources.filter(resource => resource.kind === 'ServiceAccount')
      .map((resource, index) => [resource.id, index]));
    const applicationNodes: ApplicationFlowNode[] = visibleResources.map(resource => ({
      id: resource.id,
      type: 'application',
      position: applicationFlowNodePosition(resource, resource.kind === 'Service'
        ? serviceIndex.get(resource.id) ?? 0 : resource.kind === 'Deployment'
          ? deploymentIndex.get(resource.id) ?? 0 : accountIndex.get(resource.id) ?? 0),
      data: {
        resource: { ...resource, sourceYaml: yamlSourceChanges[resource.id] ?? resource.sourceYaml },
        changes: {}, controlsDisabled: saving || busy, networkMode: true,
        sourceYamlEdited: yamlSourceChanges[resource.id] !== undefined
          && yamlSourceChanges[resource.id] !== resource.sourceYaml,
        onEditSourceYaml: setResourceYamlNode,
      },
      draggable: !saving && !busy,
    }));
    const deviceNodes: ApplicationDeviceFlowNode[] = draft.nodes.map(model => ({
      id: model.id, type: 'application-device', position: model.position,
      data: { model, disabled: saving || busy, onEditYaml: node => {
        setYamlNode(node);
        setYamlSource(topologyNodeYaml(node));
        setYamlError('');
      } },
      draggable: !saving && !busy,
    }));
    setNodes([...applicationNodes, ...deviceNodes]);
  }, [visibleResources, draft.nodes, yamlSourceChanges, saving, busy, setNodes]);

  useEffect(() => {
    if (!nodes.length) return;
    const frame = requestAnimationFrame(() => { void fitView({ padding: 0.12, duration: 160 }); });
    return () => cancelAnimationFrame(frame);
  }, [applicationId, loading, nodes.length, fitView]);

  const deviceEdges: Edge[] = draft.links.map(link => ({
    id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort,
    target: link.targetNodeId, targetHandle: link.targetPort, type: 'nanpanelBezier',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' },
    data: { layer: link.layer, protocol: link.protocol, state: 'PENDING' },
  }));
  const attachmentEdges: Edge[] = draft.attachments.flatMap(attachment => {
    if (!nodeIds.has(attachment.nodeId)) return [];
    const workload = visibleResources.find(resource => resource.id === attachment.resourceId
      && resource.kind === 'Deployment');
    if (!workload) return [];
    const resourceToDevice = attachment.direction === 'RESOURCE_TO_DEVICE';
    return [{
      id: attachment.id,
      source: resourceToDevice ? attachment.resourceId : attachment.nodeId,
      sourceHandle: resourceToDevice ? 'network-out-1' : attachment.devicePort,
      target: resourceToDevice ? attachment.nodeId : attachment.resourceId,
      targetHandle: resourceToDevice ? attachment.devicePort : 'network-in-1',
      type: 'nanpanelBezier',
      markerEnd: { type: MarkerType.ArrowClosed, color: '#55a883' },
      style: { stroke: '#55a883', strokeWidth: 2.2 },
      data: { applicationAttachment: true },
    }];
  });
  const edges = [...selectorEdges, ...deviceEdges, ...attachmentEdges];

  const onFlowNodesChange = (changes: Parameters<typeof updateNodes>[0]) => {
    updateNodes(changes);
    const positions = new Map(changes.flatMap(change => change.type === 'position' && change.position
      ? [[change.id, change.position] as const] : []));
    if (!positions.size) return;
    setDraft(current => ({ ...current, nodes: current.nodes.map(node => ({
      ...node, position: positions.get(node.id) ?? node.position,
    })) }));
    setDirty(true);
  };

  function makeDeviceLink(connection: Connection | Edge): TopologyLink | null {
    const source = draft.nodes.find(node => node.id === connection.source);
    const target = draft.nodes.find(node => node.id === connection.target);
    const sourcePort = source?.ports.find(port => port.id === connection.sourceHandle);
    const targetPort = target?.ports.find(port => port.id === connection.targetHandle);
    if (!source || !target || !sourcePort || !targetPort) return null;
    const order = ['ETHERNET', 'IP', 'TCP', 'UDP', 'SCTP', 'HTTP', 'HTTPS'] as const;
    const protocol = order.find(value => sourcePort.layer === targetPort.layer
      && sourcePort.protocols.includes(value) && targetPort.protocols.includes(value));
    if (!protocol) return null;
    return { id: createUuid(), sourceNodeId: source.id, sourcePort: sourcePort.id,
      targetNodeId: target.id, targetPort: targetPort.id, layer: sourcePort.layer,
      protocol, status: 'UNVERIFIED' };
  }

  function validConnection(connection: Connection | Edge) {
    const source = nodes.find(node => node.id === connection.source);
    const target = nodes.find(node => node.id === connection.target);
    if (!source || !target || saving || busy) return false;
    if (source.type === 'application-device' && target.type === 'application-device') {
      const candidate = makeDeviceLink(connection);
      return !!candidate && draft.links.length < 800
        && !connectionError(draft.nodes, draft.links, candidate);
    }
    const application = source.type === 'application' ? source
      : target.type === 'application' ? target : undefined;
    const device = source.type === 'application-device' ? source
      : target.type === 'application-device' ? target : undefined;
    if (!application || !device || application.data.resource.kind !== 'Deployment') return false;
    const resourceToDevice = source.type === 'application';
    const correctHandle = resourceToDevice
      ? connection.sourceHandle === 'network-out-1'
      : connection.targetHandle === 'network-in-1';
    const devicePortId = resourceToDevice ? connection.targetHandle : connection.sourceHandle;
    const expected = resourceToDevice ? 'INPUT' : 'OUTPUT';
    const port = device.data.model.ports.find(item => item.id === devicePortId);
    if (!correctHandle || !port || port.direction !== expected) return false;
    return !draft.attachments.some(attachment => attachment.resourceId === application.id
      || attachment.nodeId === device.id && attachment.devicePort === devicePortId);
  }

  function connect(connection: Connection) {
    const source = nodes.find(node => node.id === connection.source);
    const target = nodes.find(node => node.id === connection.target);
    if (!source || !target) return;
    if (source.type === 'application-device' && target.type === 'application-device') {
      const link = makeDeviceLink(connection);
      if (!link) return;
      const invalid = connectionError(draft.nodes, draft.links, link);
      if (invalid) { setMessage(invalid); return; }
      setDraft(current => ({ ...current, links: [...current.links, link] }));
      setDirty(true);
      setMessage('已添加虚拟设备链路；保存后仍需配置实际数据面接入。');
      return;
    }
    const application = source.type === 'application' ? source
      : target.type === 'application' ? target : undefined;
    const device = source.type === 'application-device' ? source
      : target.type === 'application-device' ? target : undefined;
    if (!application || !device || application.data.resource.kind !== 'Deployment') return;
    const resourceToDevice = source.type === 'application';
    const portId = resourceToDevice ? connection.targetHandle : connection.sourceHandle;
    const attachment = {
      id: createUuid(), resourceId: application.id, nodeId: device.id,
      devicePort: portId ?? '',
      direction: resourceToDevice ? 'RESOURCE_TO_DEVICE' as const : 'DEVICE_TO_RESOURCE' as const,
    };
    setDraft(current => ({ ...current, attachments: [...current.attachments, attachment] }));
    setDirty(true);
    setMessage(`已将 ${application.data.resource.name} 接入虚拟设备；保存关系不会自动改动 Pod 网络。`);
  }

  function addDevice(kind: TopologyNodeKind) {
    if (saving || busy) return;
    const used = new Set(draft.nodes.filter(node => node.kind === kind).map(node => node.name));
    const prefix = nodePresets[kind].name;
    let index = 1;
    while (used.has(`${prefix} ${index}`)) index++;
    const point = menu?.point ?? screenToFlowPosition({
      x: window.innerWidth / 2, y: window.innerHeight / 2,
    });
    const model = createTopologyNode(kind, point);
    model.name = `${prefix} ${index}`;
    setDraft(current => ({ ...current, nodes: [...current.nodes, model] }));
    setDirty(true);
    setMenu(undefined);
  }

  function removeDevice(nodeId: string) {
    setDraft(current => ({
      ...current,
      nodes: current.nodes.filter(node => node.id !== nodeId),
      links: current.links.filter(link => link.sourceNodeId !== nodeId && link.targetNodeId !== nodeId),
      attachments: current.attachments.filter(attachment => attachment.nodeId !== nodeId),
    }));
    setDirty(true);
    setMenu(undefined);
  }

  function removeEdge(edgeId: string) {
    setDraft(current => ({ ...current,
      links: current.links.filter(link => link.id !== edgeId),
      attachments: current.attachments.filter(attachment => attachment.id !== edgeId),
    }));
    setDirty(true);
    setMenu(undefined);
  }

  function saveDeviceYaml(sourceYaml: string) {
    if (!yamlNode) return;
    try {
      const document = parseDocument(sourceYaml, { uniqueKeys: true });
      if (document.errors.length) throw new Error(document.errors[0].message);
      const parsed: unknown = document.toJS({ maxAliasCount: 16 });
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('YAML 根节点必须是对象。');
      const object = parsed as Record<string, unknown>;
      const hasExact = (value: unknown, keys: string[]) => !!value && typeof value === 'object'
        && !Array.isArray(value) && Object.keys(value).length === keys.length
        && keys.every(key => Object.hasOwn(value, key));
      const metadata = object.metadata;
      const spec = object.spec;
      if (!hasExact(object, ['apiVersion', 'kind', 'metadata', 'spec'])
          || object.apiVersion !== 'topology.nanpanel.io/v1alpha1'
          || object.kind !== 'TopologyNode' || !hasExact(metadata, ['id'])
          || (metadata as Record<string, unknown>).id !== yamlNode.id
          || !hasExact(spec, topologyYamlFields)) {
        throw new Error('文档必须是有效 TopologyNode YAML，并保持 metadata.id 与所有 spec 字段。');
      }
      const updated = { ...yamlNode, ...(spec as Partial<TopologyNode>),
        id: yamlNode.id, kind: yamlNode.kind, parentId: null, assetId: null, clusterId: null };
      if (!validNodeShape(updated)) throw new Error('节点 kind 与端口数量不符合约束。');
      const candidate: TopologyDraftWrite = {
        nodes: draft.nodes.map(node => node.id === updated.id ? updated : node),
        links: draft.links,
      };
      const invalid = draftError(candidate);
      if (invalid) throw new Error(`修改会使当前虚拟拓扑无效：${invalid}`);
      if (draft.attachments.some(attachment => attachment.nodeId === updated.id
          && !updated.ports.some(port => port.id === attachment.devicePort))) {
        throw new Error('不能移除已连接工作负载的设备端口。');
      }
      setDraft(current => ({ ...current, nodes: current.nodes.map(node =>
        node.id === updated.id ? updated : node) }));
      setDirty(true);
      setYamlNode(undefined);
      setYamlError('');
    } catch (cause) {
      setYamlError((cause as Error).message);
    }
  }

  async function saveTopology() {
    if (!dirty || saving || busy || !etag) return;
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const payload: NetworkDraft = { ...draft,
        nodes: draft.nodes.map(node => ({ ...node,
          position: nodes.find(flowNode => flowNode.id === node.id)?.position ?? node.position,
        })) };
      const result = await api<KubernetesApplicationNetworkTopology>(
        `/kubernetes/applications/${encodeURIComponent(applicationId)}/network-topology`,
        'PUT', payload, { 'If-Match': etag }, true,
      );
      setDraft({ nodes: result.data.nodes, links: result.data.links,
        attachments: result.data.attachments });
      setEtag(result.etag ?? '');
      setDirty(false);
      setMessage(`应用网络拓扑 Revision ${result.data.generation} 已保存。`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return <section className="application-network-card">
    <div className="application-section-heading"><div><span className="eyebrow">APPLICATION NETWORK GRAPH</span><h3>应用资源与虚拟设备</h3><p>Deployment 接入点和设备链路按应用独立保存；目标运行路径保留默认 CNI，单节点使用隔离 veth/bridge，跨节点使用按应用隔离的 VXLAN VNI。</p></div>
      <button className="primary" disabled={!dirty || saving || busy || loading || isMock} onClick={() => void saveTopology()}>{saving ? '保存中…' : `保存应用拓扑${dirty ? ' *' : ''}`}</button></div>
    <div className="application-network-note" role="note">运行时仅在显式 Apply 后接入真实流量：单个已映射 Ready 的 K3s Node、Multus bridge 次级网卡、隔离 netns Helper、宿主路由过滤和 Service EndpointSlice 同步。集群需预装 Multus 并授予所需 RBAC；当前单节点路径不启用 VXLAN，跨节点 VNI/underlay 仍需后续 Provider。</div>
    <div className="application-network-runtime" aria-label="应用虚拟网络运行时">
      <div className="application-network-runtime-status">
        <strong>{runtime ? `运行时：${runtime.state}` : '运行时：未接入'}</strong>
        <span>{runtime?.detail ?? '保存拓扑不会修改集群；Apply、Verify 和 Compensate 均需显式操作。'}</span>
      </div>
      <div className="application-network-runtime-actions">
        {(!runtime || runtime.state === 'COMPENSATED') && <button className="primary" disabled={saving || busy || loading || dirty || runtimeBusy !== '' || isMock}
          onClick={() => void runRuntimeAction('apply')}>{runtimeBusy === 'apply' ? '提交中…' : 'Apply 真实流量'}</button>}
        {runtime && ['APPLIED', 'VERIFY_FAILED'].includes(runtime.state) && <button className="secondary" disabled={runtimeBusy !== '' || busy || isMock}
          onClick={() => void runRuntimeAction('verify')}>{runtimeBusy === 'verify' ? '验证中…' : 'Verify'}</button>}
        {runtime && ['APPLIED', 'VERIFY_FAILED', 'PARTIAL', 'FAILED'].includes(runtime.state) && <button className="danger" disabled={runtimeBusy !== '' || busy || isMock}
          onClick={() => void runRuntimeAction('compensate')}>{runtimeBusy === 'compensate' ? '补偿中…' : 'Compensate 恢复'}</button>}
        <button className="secondary" disabled={runtimeBusy !== ''} onClick={() => void refreshRuntime()}>刷新状态</button>
      </div>
    </div>
    {loading && <div className="application-muted">正在读取应用网络拓扑…</div>}
    <div className="application-network-canvas">
      <TopologyCanvas<ApplicationNetworkFlowNode, Edge>
        nodes={nodes} edges={edges} nodeTypes={applicationNetworkNodeTypes}
        onNodesChange={onFlowNodesChange} onConnect={connect}
        onCanvasContextMenu={event => {
          if (saving || busy) return;
          setMenu({ x: event.clientX, y: event.clientY,
            point: screenToFlowPosition({ x: event.clientX, y: event.clientY }) });
        }}
        onNodeContextMenu={(event, node) => {
          event.preventDefault();
          if (!saving && !busy && node.type === 'application-device') {
            setMenu({ x: event.clientX, y: event.clientY, nodeId: node.id });
          }
        }}
        onEdgeContextMenu={(event, edge) => {
          event.preventDefault();
          if (!saving && !busy && !selectorEdges.some(item => item.id === edge.id)) {
            setMenu({ x: event.clientX, y: event.clientY, edgeId: edge.id });
          }
        }}
        isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => {
          if (state.toHandle && !state.isValid) setMessage('虚拟设备只连接兼容端口；Deployment 使用顶部/底部网卡端口接入。');
        }}
        nodesDraggable={!saving && !busy} nodesConnectable={!saving && !busy}
        elementsSelectable deleteKeyCode={null} minZoom={0.25} maxZoom={1.8}
        noWheelClassName="nowheel" fitView
      />
    </div>
    <div className="application-topology-footnote">右键空白处添加 SWITCH、ROUTER、FIREWALL、Packet Tap、MERGE、SPLIT、FORWARDER、LOAD_BALANCER、MIRROR 或 CUSTOM。Deployment 网卡端口可连接虚拟设备；虚拟设备卡片可编辑源 YAML。</div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {message && <div className="application-network-message" role="status">{message}</div>}
    {menu && <TopologyContextMenu x={menu.x} y={menu.y} onClose={() => setMenu(undefined)}>
      {menu.nodeId ? <>
        <TopologyContextMenuHeading>{draft.nodes.find(node => node.id === menu.nodeId)?.name ?? '虚拟设备'}</TopologyContextMenuHeading>
        <TopologyContextMenuItem onClick={() => {
          const node = draft.nodes.find(item => item.id === menu.nodeId);
          if (node) { setYamlNode(node); setYamlSource(topologyNodeYaml(node)); setYamlError(''); }
          setMenu(undefined);
        }}>编辑源 YAML</TopologyContextMenuItem>
        <TopologyContextMenuItem className="danger" onClick={() => removeDevice(menu.nodeId!)}>删除设备及接入链路</TopologyContextMenuItem>
      </> : menu.edgeId ? <>
        <TopologyContextMenuHeading>应用网络链路</TopologyContextMenuHeading>
        <TopologyContextMenuItem className="danger" onClick={() => removeEdge(menu.edgeId!)}>删除连接</TopologyContextMenuItem>
      </> : <>
        <TopologyContextMenuHeading>添加虚拟网络设备</TopologyContextMenuHeading>
        {virtualApplicationKinds.map(kind => <TopologyContextMenuItem key={kind} onClick={() => addDevice(kind)}>
          <span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small>
        </TopologyContextMenuItem>)}
      </>}
    </TopologyContextMenu>}
    {yamlNode && <ApplicationVirtualDeviceYamlDialog node={yamlNode} sourceYaml={yamlSource}
      error={yamlError} disabled={saving || busy} onChange={setYamlSource}
      onClose={() => setYamlNode(undefined)} onSave={saveDeviceYaml} />}
    {resourceYamlNode && <ApplicationSourceYamlDialog node={resourceYamlNode}
      disabled={saving || busy} onClose={() => setResourceYamlNode(undefined)}
      onSave={sourceYaml => {
        onResourceYamlSave(resourceYamlNode, sourceYaml);
        setResourceYamlNode(undefined);
      }} />}
  </section>;
}

function ApplicationVirtualDeviceYamlDialog({
  node, sourceYaml, error, disabled, onChange, onClose, onSave,
}: {
  node: TopologyNode;
  sourceYaml: string;
  error: string;
  disabled: boolean;
  onChange: (value: string) => void;
  onClose: () => void;
  onSave: (value: string) => void;
}) {
  return <div className="modal-backdrop"><section className="modal topology-node-yaml-dialog" role="dialog" aria-modal="true" aria-labelledby="application-network-yaml-title">
    <div className="card-title"><div><span className="eyebrow">APPLICATION TOPOLOGY SOURCE YAML</span><h2 id="application-network-yaml-title">{node.kind} · {node.name}</h2><p>保存只更新当前应用的虚拟拓扑草稿，不会立即配置 Pod 网络。</p></div><button type="button" className="icon-button" aria-label="关闭 YAML 编辑器" onClick={onClose}>×</button></div>
    <form onSubmit={event => { event.preventDefault(); onSave(sourceYaml); }}>
      <label className="topology-node-yaml-label" htmlFor="application-network-node-yaml">源 YAML</label>
      <textarea id="application-network-node-yaml" className="topology-node-source-yaml" spellCheck={false}
        autoCapitalize="off" autoCorrect="off" maxLength={262_144} rows={22}
        value={sourceYaml} onChange={event => onChange(event.target.value)} />
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={disabled}>保存到应用拓扑草稿</button></div>
    </form>
  </section></div>;
}

function ApplicationSourceYamlDialog({
  node, disabled, onClose, onSave,
}: {
  node: KubernetesApplicationTopologyNode;
  disabled: boolean;
  onClose: () => void;
  onSave: (sourceYaml: string) => void;
}) {
  const [sourceYaml, setSourceYaml] = useState(node.sourceYaml);
  const [error, setError] = useState('');
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!sourceYaml.trim() || sourceYaml.length > 262_144) {
      setError('本项 YAML 必须为 1–262144 个字符。');
      return;
    }
    onSave(sourceYaml);
  };
  return <div className="modal-backdrop"><section className="modal application-yaml-dialog" role="dialog" aria-modal="true" aria-labelledby="application-yaml-title">
    <div className="card-title"><div><span className="eyebrow">RESOURCE SOURCE YAML</span><h2 id="application-yaml-title">{node.kind} · {node.name}</h2><p>该资源必须保持 kind 和名称；保存后先生成版本差异，不会立即修改集群。</p></div><button className="icon-button" aria-label="关闭 YAML 编辑器" onClick={onClose}>×</button></div>
    <form onSubmit={submit}>
      <label className="application-yaml-label" htmlFor="application-source-yaml">源 YAML</label>
      <textarea id="application-source-yaml" className="application-source-yaml" spellCheck={false}
        autoCapitalize="off" autoCorrect="off" maxLength={262_144} rows={22}
        value={sourceYaml} onChange={event => { setSourceYaml(event.target.value); setError(''); }} />
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={disabled}>保存到拓扑草稿</button></div>
    </form>
  </section></div>;
}

function ApplicationNodeSettingsDialog({
  node, changes, disabled, onClose, onSave,
}: {
  node: KubernetesApplicationTopologyNode;
  changes: Editable;
  disabled: boolean;
  onClose: () => void;
  onSave: (value: Editable) => void;
}) {
  const [replicas, setReplicas] = useState(String(changes.replicas ?? node.replicas ?? 1));
  const [image, setImage] = useState(changes.image ?? node.image ?? '');
  const [servicePort, setServicePort] = useState(String(changes.servicePort ?? node.servicePort ?? ''));
  const [targetPort, setTargetPort] = useState(changes.targetPort ?? node.targetPort ?? String(node.servicePort ?? ''));
  const [serviceProtocol, setServiceProtocol] = useState<Editable['serviceProtocol']>(changes.serviceProtocol ?? node.serviceProtocol ?? 'TCP');
  const [error, setError] = useState('');
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (node.kind === 'Deployment') {
      const count = Number(replicas);
      if (!Number.isInteger(count) || count < 0 || count > 20 || !image.trim()) {
        setError('副本数必须为 0–20 的整数，镜像不能为空。'); return;
      }
      onSave({ replicas: count, image: image.trim() });
      return;
    }
    const port = Number(servicePort);
    if (!Number.isInteger(port) || port < 1 || port > 65535 || !targetPort.trim()) {
      setError('Service 端口必须为 1–65535 的整数，目标端口不能为空。'); return;
    }
    onSave({ servicePort: port, targetPort: targetPort.trim(), serviceProtocol });
  };
  const deployment = node.kind === 'Deployment';
  return <div className="modal-backdrop"><section className="modal application-node-settings" role="dialog" aria-modal="true" aria-labelledby="application-node-settings-title">
    <div className="card-title"><div><span className="eyebrow">APPLICATION RESOURCE</span><h2 id="application-node-settings-title">{node.kind === 'Service' ? 'Service 配置' : '工作负载配置'}</h2></div><button className="icon-button" aria-label="关闭资源配置" onClick={onClose}>×</button></div>
    <p className="application-node-settings-name"><strong>{node.name}</strong><AppState value={node.state} /></p>
    <form onSubmit={submit}>
      {deployment ? <>
        <label className="field"><span>副本数（0–20）</span><input type="number" min={0} max={20} required value={replicas} onChange={event => setReplicas(event.target.value)} /></label>
        <label className="field"><span>容器镜像</span><input required maxLength={512} value={image} onChange={event => setImage(event.target.value)} /></label>
      </> : <>
        <label className="field"><span>Service 端口（首个端口）</span><input type="number" min={1} max={65535} required value={servicePort} onChange={event => setServicePort(event.target.value)} /></label>
        <label className="field"><span>Pod 目标端口（数字或命名端口）</span><input required maxLength={15} value={targetPort} onChange={event => setTargetPort(event.target.value)} /></label>
        <label className="field"><span>网络协议</span><select value={serviceProtocol} onChange={event => setServiceProtocol(event.target.value as Editable['serviceProtocol'])}><option value="TCP">TCP</option><option value="UDP">UDP</option><option value="SCTP">SCTP</option></select></label>
        <div className="alert">后端 Deployment 由拓扑连线决定；新连线会生成 Service selector 的 YAML diff。</div>
      </>}
      {error && <div className="alert error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={disabled}>保存到拓扑草稿</button></div>
    </form>
  </section></div>;
}

function VersionCard({ version, busy, onDeploy }: { version: KubernetesApplicationVersion; busy: boolean; onDeploy: () => void }) {
  const [expanded, setExpanded] = useState(version.state === 'DRAFT');
  return <article className={`application-version-card ${version.state.toLowerCase()}`}>
    <div className="application-version-summary"><div className="version-number"><span>VERSION</span><strong>v{version.version}</strong></div><div className="version-main"><div><AppState value={version.state} /><small>基于 v{version.baseVersion} · {new Date(version.createdAt).toLocaleString('zh-CN')}</small></div><code>sha256:{version.manifestSha256.slice(0, 12)}…</code></div><button className="link" aria-expanded={expanded} onClick={() => setExpanded(value => !value)}>{expanded ? '收起 diff' : '查看 diff'}　⌄</button>{version.state === 'DRAFT' && <button className="primary" disabled={busy} onClick={onDeploy}>{busy ? '部署中…' : '确认部署 v' + version.version}</button>}</div>
    {expanded && <div className="application-diff-wrap">{version.errorMessage && <div className="alert error">{version.errorMessage}</div>}{version.manifestDiff ? <pre className="application-diff">{version.manifestDiff.split('\n').map((line, index) => <span key={`${index}-${line}`} className={line.startsWith('+') ? 'added' : line.startsWith('-') ? 'removed' : ''}>{line || ' '}{'\n'}</span>)}</pre> : <p className="application-muted">此版本没有资源差异文本。</p>}</div>}
  </article>;
}
