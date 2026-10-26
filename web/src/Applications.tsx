import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import {
  Background, ConnectionMode, Controls, Handle, MarkerType, MiniMap, Position, ReactFlowProvider,
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
  KubernetesApplicationExecTicket,
  KubernetesApplicationList,
  KubernetesApplicationPreview,
  KubernetesApplicationRuntimeDeployment,
  KubernetesApplicationRuntimeEventList,
  KubernetesApplicationRuntimeLog,
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

type View = 'topology' | 'versions';
type ApplicationDrawer = 'applications' | 'info' | null;
type RuntimeDeploymentView = KubernetesApplicationRuntimeDeployment;
type RuntimeEventList = KubernetesApplicationRuntimeEventList;
type RuntimeLog = KubernetesApplicationRuntimeLog;
type ApplicationExecTicket = KubernetesApplicationExecTicket;
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
  DEPLOYED: '运行中', APPLYING: '部署中', FAILED: '部署失败', PARTIAL: '部分完成',
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
  const [runtimeDeployment, setRuntimeDeployment] = useState('');
  const closeRuntimeDeployment = useCallback(() => setRuntimeDeployment(''), []);
  const [drawer, setDrawer] = useState<ApplicationDrawer>(null);
  const [toolbarMenuOpen, setToolbarMenuOpen] = useState(false);
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
    {error && <div className="application-page-alert alert error" role="alert">{error}</div>}
    {isMock && <div className="application-page-alert alert" role="status">Mock 演示环境没有真实集群应用接口。此页面在 Live API 模式下连接已登记集群。</div>}
    <div className="application-layout">
      {selectedApp && detail ? <section className="application-content">
        <div className="application-workspace-toolbar">
          <div className="application-toolbar-context">
            <button type="button" className="application-float-button" aria-label="打开应用列表" title="应用列表"
              aria-expanded={drawer === 'applications'} onClick={() => setDrawer('applications')}>☷<span>应用</span></button>
            {runtimeDeployment && <button type="button" className="application-float-button" aria-label="返回应用拓扑"
              title="返回拓扑" onClick={() => setRuntimeDeployment('')}>←<span>拓扑</span></button>}
            <span className="application-current-name" title={runtimeDeployment || detail.application.displayName}>
              {runtimeDeployment || detail.application.displayName}</span>
            <button type="button" className="application-float-button icon-only" aria-label="应用信息" title="应用信息"
              aria-expanded={drawer === 'info'} onClick={() => setDrawer('info')}>ⓘ</button>
          </div>
          <div className="application-toolbar-actions">
            {!runtimeDeployment && <button type="button" className={`application-float-button ${view === 'versions' ? 'active' : ''}`}
              aria-label={view === 'topology' ? '打开版本历史' : '返回拓扑设置'}
              onClick={() => { setView(current => current === 'topology' ? 'versions' : 'topology'); setToolbarMenuOpen(false); }}>
              {view === 'topology' ? '◷' : '⌘'}<span>{view === 'topology' ? `版本 ${versions.length}` : '拓扑'}</span>
            </button>}
            {!runtimeDeployment && <div className="application-toolbar-menu-wrap">
              <button type="button" className="application-float-button icon-only" aria-label="更多应用操作" title="更多操作"
                aria-haspopup="menu" aria-expanded={toolbarMenuOpen} onClick={() => setToolbarMenuOpen(open => !open)}>⋯</button>
              {toolbarMenuOpen && <div className="application-toolbar-menu" role="menu">
                <button type="button" role="menuitem" disabled={loading || busy !== ''}
                  onClick={() => { void loadApplication(detail.application.id); setToolbarMenuOpen(false); }}>刷新应用资源</button>
                {view === 'topology' && <button type="button" role="menuitem"
                  disabled={(!Object.keys(changes).length && !hasYamlChanges && !additions.length)
                    || additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment && !addition.sourceYaml)
                    || hasDraft || busy !== ''}
                  title={additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment && !addition.sourceYaml)
                    ? '请将新增 Service 连到 Deployment 后再生成差异' : undefined}
                  onClick={() => { void createDraft(); setToolbarMenuOpen(false); }}>
                  {busy === 'draft' ? '生成差异中…' : '生成版本差异'}
                </button>}
              </div>}
            </div>}
          </div>
        </div>
        <div className="application-view-content">
          {runtimeDeployment && <div className="application-runtime-backdrop" onMouseDown={event => {
            if (event.target === event.currentTarget) closeRuntimeDeployment();
          }}>
            <ApplicationDeploymentDetail key={`${detail.application.id}-${runtimeDeployment}`}
              applicationId={detail.application.id} applicationName={detail.application.displayName}
              deploymentName={runtimeDeployment} namespace={detail.application.namespace}
              onBack={closeRuntimeDeployment} />
          </div>}
          {view === 'versions' && <section className="application-versions-card">
            <div className="application-section-heading"><div><span className="eyebrow">IMMUTABLE MANIFEST HISTORY</span><h3>版本历史</h3><p>草稿不会修改集群；只有状态为“待确认”的版本可提交部署。</p></div></div>
            {!versions.length ? <div className="application-empty-history">暂无版本记录</div> : <div className="application-version-list">{versions.map(version => <VersionCard key={version.version} version={version} busy={busy === `deploy-${version.version}`} onDeploy={() => void deployDraft(version)} />)}</div>}
          </section>}
          <div className={`application-network-topology ${view === 'topology' ? 'visible' : ''}`}
            aria-hidden={view !== 'topology' || Boolean(runtimeDeployment)}>
            <ReactFlowProvider>
              <ApplicationNetworkTopologyCanvas
                key={`application-topology-${detail.application.id}`}
                applicationId={detail.application.id}
                resources={nodeList}
                topologyEdges={detail.topology.edges}
                yamlSourceChanges={yamlSourceChanges}
                changes={changes}
                additions={additions}
                hasDraft={hasDraft}
                currentDraft={currentDraft}
                busy={busy !== ''}
                onResourceYamlSave={saveResourceYaml}
                onResourceUpdate={updateNode}
                onOpenDeployment={resource => setRuntimeDeployment(resource.name)}
                onAdditionChange={addition => setAdditions(current => current.some(item => item.id === addition.id)
                  ? current.map(item => item.id === addition.id ? addition : item) : [...current, addition])}
                onRemoveAddition={removePendingNode}
                onShowVersions={() => setView('versions')}
              />
            </ReactFlowProvider>
          </div>
        </div>
      </section> : <section className="application-content application-empty-workspace">
        <div className="application-workspace-toolbar">
          <div className="application-toolbar-context">
            <button type="button" className="application-float-button" aria-label="打开应用列表" title="应用列表"
              aria-expanded={drawer === 'applications'} onClick={() => setDrawer('applications')}>☷<span>应用</span></button>
            <span className="application-current-name">NaNPanel 应用拓扑</span>
          </div>
          <button type="button" className="application-float-button" onClick={beginCreate} disabled={isMock}>＋<span>创建应用</span></button>
        </div>
        <div className="application-blank"><div className="application-blank-icon">▦</div><h2>{loading ? '正在读取应用…' : items.length ? '选择一个应用' : '开始管理 Kubernetes 应用'}</h2><p>应用拓扑根据部署 YAML 自动生成。创建版本后检查资源 diff，再手动确认部署。</p><button className="primary" onClick={beginCreate} disabled={isMock}>＋ 创建应用</button></div>
      </section>}
    </div>
    {drawer && <div className="application-drawer-backdrop" onMouseDown={event => {
      if (event.target === event.currentTarget) setDrawer(null);
    }}>
      <aside className={`application-page-drawer ${drawer === 'applications' ? 'from-left' : 'from-right'}`}
        role="dialog" aria-modal="true" aria-labelledby="application-drawer-title">
        <div className="application-drawer-heading">
          <div><span className="eyebrow">{drawer === 'applications' ? 'APPLICATIONS' : 'APPLICATION INFO'}</span>
            <h2 id="application-drawer-title">{drawer === 'applications' ? '应用列表' : '应用信息'}</h2></div>
          <button type="button" className="application-float-button icon-only" aria-label="关闭侧边栏" onClick={() => setDrawer(null)}>×</button>
        </div>
        {drawer === 'applications' ? <>
          <div className="application-list-heading">
            <strong>{items.length} 个已登记应用</strong>
            <div className="application-list-heading-actions">
              <button type="button" className="secondary" title="刷新应用列表" aria-label="刷新应用列表" onClick={() => void loadList()} disabled={loading}>↻</button>
              <button type="button" className="primary" onClick={() => { beginCreate(); setDrawer(null); }} disabled={isMock}>＋ 创建</button>
            </div>
          </div>
          {loading && <p className="application-muted">正在读取应用…</p>}
          {!loading && items.length === 0 && !error && <div className="application-empty-small"><b>◇</b><span>还没有应用</span><small>使用图形化向导或导入容器清单创建应用。</small><button className="link" onClick={() => { beginCreate(); setDrawer(null); }}>创建第一个应用 →</button></div>}
          <div className="application-list">
            {items.map(item => <button key={item.id} className={`application-list-item ${selectedId === item.id ? 'active' : ''}`} onClick={() => {
              setSelectedId(item.id); setView('topology'); setRuntimeDeployment('');
              setDrawer(null); setToolbarMenuOpen(false);
            }}>
              <span className="application-symbol">▦</span><span className="application-list-copy"><strong>{item.displayName}</strong><small>{item.clusterName} · {item.namespace}</small><small>版本 v{item.currentVersion} · {item.templateId}</small></span><AppState value={item.state} />
            </button>)}
          </div>
          <div className="application-list-footer"><span className="application-live-dot" />已接入的应用清单</div>
        </> : detail && <>
          <div className="application-info-title"><span className="application-title-icon">▦</span><div><h3>{detail.application.displayName}</h3><AppState value={detail.application.state} /></div></div>
          <dl className="application-info-list">
            <div><dt>目标集群</dt><dd>{detail.application.clusterName}</dd></div>
            <div><dt>Namespace</dt><dd>{detail.application.namespace}</dd></div>
            <div><dt>应用模板</dt><dd>{detail.application.templateId}</dd></div>
            <div><dt>当前版本</dt><dd>v{detail.application.currentVersion}</dd></div>
          </dl>
          <div className="application-info-metrics">
            <div><strong>{deployments.length}</strong><span>Deployments</span></div>
            <div><strong>{services.length}</strong><span>Services</span></div>
            <div><strong>{accounts.length}</strong><span>ServiceAccounts</span></div>
            <div><strong>{versions.filter(item => item.state === 'DEPLOYED').length}</strong><span>已部署版本</span></div>
          </div>
          <button type="button" className="secondary application-info-history" onClick={() => {
            setRuntimeDeployment(''); setView('versions'); setDrawer(null);
          }}>查看版本历史 · {versions.length} →</button>
        </>}
      </aside>
    </div>}
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

function ApplicationDeploymentDetail({ applicationId, applicationName, deploymentName, namespace,
  onBack }: { applicationId: string; applicationName: string; deploymentName: string;
  namespace: string; onBack: () => void }) {
  const dialogRef = useRef<HTMLElement>(null);
  const onBackRef = useRef(onBack);
  const [runtime, setRuntime] = useState<RuntimeDeploymentView>();
  const [podUid, setPodUid] = useState('');
  const [containerName, setContainerName] = useState('');
  const [tab, setTab] = useState<'overview' | 'events' | 'logs' | 'exec'>('overview');
  const [events, setEvents] = useState<RuntimeEventList['items']>([]);
  const [logs, setLogs] = useState<RuntimeLog>();
  const [execTicket, setExecTicket] = useState<ApplicationExecTicket>();
  const [execReason, setExecReason] = useState('');
  const [refreshToken, setRefreshToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const currentPod = runtime?.pods.find(pod => pod.uid === podUid);

  useEffect(() => { onBackRef.current = onBack; }, [onBack]);

  useEffect(() => {
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onBackRef.current();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      if (previouslyFocused?.isConnected) previouslyFocused.focus();
    };
  }, []);

  const refreshRuntime = useCallback(async () => {
    const result = await api<RuntimeDeploymentView>(
      `/kubernetes/applications/${encodeURIComponent(applicationId)}/deployments/${encodeURIComponent(deploymentName)}/runtime`,
      'GET', undefined, {}, true,
    );
    setRuntime(result.data);
    setPodUid(current => result.data.pods.some(pod => pod.uid === current)
      ? current : result.data.pods[0]?.uid ?? '');
  }, [applicationId, deploymentName]);

  useEffect(() => {
    let current = true;
    setLoading(true);
    setError('');
    void refreshRuntime().catch(cause => {
      if (current) setError((cause as Error).message);
    }).finally(() => { if (current) setLoading(false); });
    const timer = window.setInterval(() => {
      void refreshRuntime().catch(cause => { if (current) setError((cause as Error).message); });
    }, 10_000);
    return () => { current = false; window.clearInterval(timer); };
  }, [refreshRuntime]);

  useEffect(() => {
    if (!currentPod) { setContainerName(''); return; }
    setContainerName(current => currentPod.containers.some(container => container.name === current)
      ? current : currentPod.containers[0]?.name ?? '');
  }, [currentPod?.uid, currentPod?.containers]);

  useEffect(() => {
    if (!currentPod || !['events', 'logs'].includes(tab)) return;
    let current = true;
    setBusy(tab);
    setError('');
    if (tab === 'events') setEvents([]);
    else setLogs(undefined);
    const path = `/kubernetes/applications/${encodeURIComponent(applicationId)}/deployments/${encodeURIComponent(deploymentName)}/pods/${encodeURIComponent(currentPod.uid)}`;
    const request = tab === 'events'
      ? api<RuntimeEventList>(`${path}/events`, 'GET', undefined, {}, true)
          .then(result => { if (current) setEvents(result.data.items); })
      : containerName
        ? api<RuntimeLog>(`${path}/logs?container=${encodeURIComponent(containerName)}&tailLines=300`, 'GET', undefined, {}, true)
            .then(result => { if (current) setLogs(result.data); })
        : Promise.resolve();
    void request.catch(cause => { if (current) setError((cause as Error).message); })
      .finally(() => { if (current) setBusy(''); });
    return () => { current = false; };
  }, [applicationId, deploymentName, currentPod?.uid, containerName, tab, refreshToken]);

  const startExec = async () => {
    if (!currentPod || !containerName) return;
    setBusy('exec');
    setError('');
    try {
      const path = `/kubernetes/applications/${encodeURIComponent(applicationId)}/deployments/${encodeURIComponent(deploymentName)}/pods/${encodeURIComponent(currentPod.uid)}/exec-tickets`;
      const result = await api<ApplicationExecTicket>(path, 'POST', {
        container: containerName, reason: execReason.trim(),
      }, {}, true);
      setExecTicket(result.data);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  };

  return <section ref={dialogRef} className="application-runtime-detail" role="dialog" aria-modal="true"
    aria-labelledby="application-runtime-dialog-title" tabIndex={-1}>
    <header className="application-runtime-heading">
      <div><span className="eyebrow">APPLICATION RUNTIME</span><h2 id="application-runtime-dialog-title">{deploymentName}</h2>
        <p>{applicationName}<i>·</i>{namespace}<i>·</i>Deployment 详情</p></div>
      <div className="application-runtime-heading-actions">
        {runtime && <AppState value={runtime.state} />}
        <button type="button" className="secondary" onClick={() => {
          setLoading(true); void refreshRuntime().catch(cause => setError((cause as Error).message))
            .finally(() => setLoading(false));
        }} disabled={loading}>↻ 刷新</button>
        <button type="button" className="secondary" onClick={onBack}>关闭详情</button>
      </div>
    </header>
    <div className="application-runtime-layout">
      <aside className="application-runtime-pods">
        <div className="application-runtime-pods-heading"><strong>Pod</strong><span>{runtime?.pods.length ?? 0}</span></div>
        {loading && <p className="application-muted">正在读取 Pod…</p>}
        {!loading && runtime?.pods.length === 0 && <p className="application-runtime-empty">当前 Deployment 没有运行中的 Pod。</p>}
        {runtime?.pods.map(pod => <button key={pod.uid} type="button"
          className={`application-runtime-pod ${pod.uid === podUid ? 'active' : ''}`}
          onClick={() => { setPodUid(pod.uid); setExecTicket(undefined); }}>
          <strong title={pod.name}>{pod.name}</strong><span>{pod.phase || 'Unknown'}</span>
          <small>{pod.containers.filter(container => container.ready).length}/{pod.containers.length} 容器就绪</small>
        </button>)}
      </aside>
      <div className="application-runtime-main">
        <div className="application-runtime-summary">
          <div><small>Pod</small><strong title={currentPod?.name}>{currentPod?.name ?? '—'}</strong></div>
          <div><small>运行阶段</small><strong>{currentPod?.phase ?? '—'}</strong></div>
          <div><small>节点</small><strong title={currentPod?.nodeName ?? ''}>{currentPod?.nodeName ?? '—'}</strong></div>
          <label><small>容器</small><select value={containerName} disabled={!currentPod?.containers.length}
            onChange={event => { setContainerName(event.target.value); setExecTicket(undefined); }}>
            {currentPod?.containers.map(container => <option key={container.name} value={container.name}>{container.name}</option>)}
          </select></label>
        </div>
        <nav className="application-runtime-tabs" aria-label="Deployment 运行时功能">
          {([['overview', '概览'], ['events', '事件'], ['logs', '日志'], ['exec', '控制台 Exec']] as const).map(([id, label]) =>
            <button key={id} type="button" className={tab === id ? 'active' : ''}
              aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}>{label}</button>)}
        </nav>
        {error && <div className="application-runtime-error" role="alert">{error}</div>}
        {tab === 'overview' && <div className="application-runtime-containers">
          <div className="application-runtime-content-heading"><div><h3>容器状态</h3><p>只显示此应用 Deployment 所拥有的 Pod。</p></div></div>
          {!currentPod && <p className="application-runtime-empty">选择一个 Pod 查看状态。</p>}
          {currentPod?.containers.map(container => <article className="application-runtime-container" key={container.name}>
            <span className={`application-runtime-indicator ${container.ready ? 'ready' : ''}`} />
            <div><strong>{container.name}</strong><small>{container.image}</small></div>
            <span>{container.state}{container.ready ? ' · Ready' : ''}</span>
          </article>)}
        </div>}
        {tab === 'events' && <div className="application-runtime-events">
          <div className="application-runtime-content-heading"><div><h3>Pod 事件</h3><p>仅查询当前 Pod UID 对应的 Kubernetes Events。</p></div>
            <button className="secondary" disabled={!currentPod || busy === 'events'} onClick={() => {
              if (currentPod) setRefreshToken(value => value + 1);
            }}>{busy === 'events' ? '读取中…' : '刷新'}</button></div>
          {!busy && !events.length && <p className="application-runtime-empty">暂无事件。</p>}
          {events.map((event, index) => <article className="application-runtime-event" key={`${event.timestamp}-${event.reason}-${index}`}>
            <span className={event.type === 'Warning' ? 'warning' : ''}>{event.type || 'Normal'}</span>
            <div><strong>{event.reason || 'Event'}</strong><p>{event.message || '—'}</p>
              <small>{event.source || 'kubelet'}{event.count > 1 ? ` · ${event.count} 次` : ''}</small></div>
            <time>{event.timestamp ? new Date(event.timestamp).toLocaleString() : '—'}</time>
          </article>)}
        </div>}
        {tab === 'logs' && <div className="application-runtime-logs">
          <div className="application-runtime-content-heading"><div><h3>容器日志</h3><p>最近 300 行；日志可能包含应用自身输出的敏感内容。</p></div>
            <button className="secondary" disabled={!currentPod || !containerName || busy === 'logs'} onClick={() => {
              if (currentPod) setRefreshToken(value => value + 1);
            }}>{busy === 'logs' ? '读取中…' : '刷新日志'}</button></div>
          {logs?.content ? <pre className="application-runtime-log-output">{logs.content}</pre>
            : <p className="application-runtime-empty">{busy === 'logs' ? '正在读取日志…' : '暂无日志或容器尚未启动。'}</p>}
        </div>}
        {tab === 'exec' && <div className="application-runtime-exec">
          <div className="application-runtime-content-heading"><div><h3>交互式容器 Shell</h3>
            <p>连接目标为所选 Pod 的 {containerName || '容器'}，仅启动容器内的 sh。</p></div>
            {!execTicket && <button className="primary" disabled={!currentPod || !containerName
              || !execReason.trim() || busy === 'exec'}
              onClick={() => void startExec()}>{busy === 'exec' ? '正在连接…' : '连接容器'}</button>}
            {execTicket && <button className="secondary" onClick={() => setExecTicket(undefined)}>断开</button>}
          </div>
          {execTicket ? <ApplicationExecConsole ticket={execTicket} onEnded={() => setExecTicket(undefined)} />
            : <div className="application-runtime-exec-empty">
              <label>Exec 操作原因<textarea maxLength={500} rows={3} value={execReason}
                onChange={event => setExecReason(event.target.value)}
                placeholder="说明本次进入容器的排查或维护目的" /></label>
              <p>申请、开始和结束会记录在审计日志中；终端输入与输出不会落盘。授权票据 30 秒内有效且只能使用一次。</p>
            </div>}
        </div>}
      </div>
    </div>
  </section>;
}

function ApplicationExecConsole({ ticket, onEnded }: {
  ticket: ApplicationExecTicket; onEnded: () => void }) {
  const element = useRef<HTMLDivElement>(null);
  const onEndedRef = useRef(onEnded);
  onEndedRef.current = onEnded;
  useEffect(() => {
    if (!element.current) return;
    const terminal = new Terminal({ cursorBlink: true, convertEol: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13,
      theme: { background: '#07111f', foreground: '#dbeafe', cursor: '#83b8ff' } });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(element.current);
    fit.fit();
    terminal.focus();
    const address = new URL(`/api/v1${ticket.websocketPath}`, window.location.href);
    address.protocol = address.protocol === 'https:' ? 'wss:' : 'ws:';
    const connection = new WebSocket(address.toString());
    connection.addEventListener('open', () => connection.send(JSON.stringify({ type: 'auth',
      ticket: ticket.ticket, columns: terminal.cols, rows: terminal.rows })));
    connection.addEventListener('message', message => {
      try {
        const event = JSON.parse(String(message.data)) as { type?: string; data?: string; message?: string };
        if (event.type === 'ready') terminal.write('\r\n\x1b[32mConnected to container shell\x1b[0m\r\n');
        else if (event.type === 'output' && event.data) {
          const bytes = Uint8Array.from(atob(event.data), character => character.charCodeAt(0));
          terminal.write(bytes);
        } else if (event.type === 'error') terminal.write(`\r\n\x1b[31m${event.message || 'Exec failed'}\x1b[0m\r\n`);
      } catch {
        terminal.write('\r\nInvalid terminal response.\r\n');
      }
    });
    connection.addEventListener('close', () => {
      terminal.write('\r\n\x1b[90mConnection closed\x1b[0m\r\n');
      onEndedRef.current();
    });
    connection.addEventListener('error', () => terminal.write('\r\n\x1b[31mWebSocket connection failed\x1b[0m\r\n'));
    const input = terminal.onData(data => {
      if (connection.readyState === WebSocket.OPEN) connection.send(JSON.stringify({ type: 'input', data }));
    });
    const resize = terminal.onResize(size => {
      if (connection.readyState === WebSocket.OPEN) connection.send(JSON.stringify({
        type: 'resize', columns: size.cols, rows: size.rows }));
    });
    const fitOnResize = () => {
      fit.fit();
      if (connection.readyState === WebSocket.OPEN) connection.send(JSON.stringify({
        type: 'resize', columns: terminal.cols, rows: terminal.rows }));
    };
    window.addEventListener('resize', fitOnResize);
    return () => {
      window.removeEventListener('resize', fitOnResize);
      input.dispose(); resize.dispose();
      if (connection.readyState === WebSocket.OPEN) connection.send(JSON.stringify({ type: 'close' }));
      connection.close();
      terminal.dispose();
    };
  }, [ticket]);
  return <div className="application-exec-console" ref={element} aria-label="容器交互式终端" />;
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
  return <article className={`application-flow-node ${resource.kind.toLowerCase()} ${selected ? 'selected' : ''}`}
    title={deployment ? '单击查看此 Deployment 的 Pod、事件、日志与 Exec' : undefined}>
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
      {deployment && <small className="application-node-runtime-hint">单击查看事件、日志与 Exec</small>}
      {data.sourceYamlEdited && <small className="application-yaml-pending">YAML 待生成版本差异</small>}
      <button type="button" className="application-yaml-button nodrag" disabled={controlsDisabled}
        onClick={event => { event.stopPropagation(); data.onEditSourceYaml(resource); }}>编辑源 YAML</button>
    </div>
  </article>;
}

type ApplicationDeviceFlowNodeData = {
  model: TopologyNode;
  disabled: boolean;
  onEditYaml: (node: TopologyNode) => void;
};
type ApplicationDeviceFlowNode = Node<ApplicationDeviceFlowNodeData, 'application-device'>;
type ApplicationNetworkFlowNode = ApplicationFlowNode | ApplicationDeviceFlowNode;
type NetworkMenu = {
  x: number;
  y: number;
  point?: { x: number; y: number };
  nodeId?: string;
  resourceId?: string;
  edgeId?: string;
  connectedTo?: string;
};
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

type TopologyLayoutNode = {
  id: string;
  type: 'application' | 'application-device';
  kind: string;
  name: string;
  portCount: number;
};

function layoutApplicationTopology(nodes: TopologyLayoutNode[], edges: Edge[]) {
  const nodeById = new Map(nodes.map(node => [node.id, node]));
  const adjacency = new Map(nodes.map(node => [node.id, new Set<string>()]));
  for (const edge of edges) {
    if (!edge.source || !edge.target || !nodeById.has(edge.source) || !nodeById.has(edge.target)) continue;
    adjacency.get(edge.source)?.add(edge.target);
    adjacency.get(edge.target)?.add(edge.source);
  }

  const stableNodeOrder = (left: TopologyLayoutNode, right: TopologyLayoutNode) =>
    left.name.localeCompare(right.name) || left.kind.localeCompare(right.kind) || left.id.localeCompare(right.id);
  const sortedNodes = [...nodes].sort(stableNodeOrder);
  const visited = new Set<string>();
  const positions = new Map<string, { x: number; y: number }>();
  let componentTop = 56;

  for (const seed of sortedNodes) {
    if (visited.has(seed.id)) continue;
    const component: TopologyLayoutNode[] = [];
    const queue = [seed.id];
    visited.add(seed.id);
    while (queue.length) {
      const current = queue.shift()!;
      const node = nodeById.get(current);
      if (node) component.push(node);
      for (const neighbor of adjacency.get(current) ?? []) {
        if (visited.has(neighbor)) continue;
        visited.add(neighbor);
        queue.push(neighbor);
      }
    }

    const deployments = component.filter(node => node.type === 'application' && node.kind === 'Deployment');
    const distance = new Map<string, number>();
    const roots = deployments.length ? deployments : component.filter(node => node.kind === 'Service');
    const distanceQueue = (roots.length ? roots : [component[0]]).map(node => node.id);
    for (const root of distanceQueue) distance.set(root, 0);
    for (let index = 0; index < distanceQueue.length; index++) {
      const current = distanceQueue[index];
      for (const neighbor of adjacency.get(current) ?? []) {
        if (!component.some(node => node.id === neighbor) || distance.has(neighbor)) continue;
        distance.set(neighbor, (distance.get(current) ?? 0) + 1);
        distanceQueue.push(neighbor);
      }
    }

    const layerById = new Map(component.map(node => {
      const hops = distance.get(node.id) ?? 0;
      if (node.type === 'application') {
        if (node.kind === 'Service') return [node.id, 0] as const;
        if (node.kind === 'Deployment') return [node.id, deployments.length ? 1 : 0] as const;
        return [node.id, deployments.length ? 3 : 0] as const;
      }
      return [node.id, deployments.length ? Math.max(2, hops + 1) : hops] as const;
    }));
    const layers = [...new Set(layerById.values())].sort((left, right) => left - right);
    const orderById = new Map<string, number>();
    const layerNodes = new Map<number, TopologyLayoutNode[]>();

    for (const layer of layers) {
      const members = component.filter(node => layerById.get(node.id) === layer);
      const previousLayer = layers.filter(value => value < layer).at(-1);
      if (previousLayer !== undefined) {
        members.sort((left, right) => {
          const barycenter = (node: TopologyLayoutNode) => {
            const neighbors = [...(adjacency.get(node.id) ?? [])]
              .filter(id => layerById.get(id) === previousLayer)
              .map(id => orderById.get(id)).filter((value): value is number => value !== undefined);
            return neighbors.length ? neighbors.reduce((sum, value) => sum + value, 0) / neighbors.length : Infinity;
          };
          return barycenter(left) - barycenter(right) || stableNodeOrder(left, right);
        });
      } else members.sort(stableNodeOrder);
      layerNodes.set(layer, members);
      members.forEach((node, index) => orderById.set(node.id, index));
    }

    const maxCardHeight = Math.max(172, ...component.map(node => node.type === 'application-device'
      ? 162 + node.portCount * 25 : 172));
    const rowGap = Math.max(224, maxCardHeight + 44);
    const maxRows = Math.max(1, ...[...layerNodes.values()].map(layer => layer.length));
    for (const [layer, members] of layerNodes) {
      members.forEach((node, index) => positions.set(node.id, {
        x: 36 + layer * 452,
        y: componentTop + 36 + index * rowGap,
      }));
    }
    componentTop += maxRows * rowGap + 112;
  }
  return positions;
}

function ApplicationNetworkTopologyCanvas({
  applicationId, resources, topologyEdges, yamlSourceChanges, changes, additions, hasDraft,
  currentDraft, busy, onResourceYamlSave, onResourceUpdate, onAdditionChange, onRemoveAddition,
  onShowVersions, onOpenDeployment,
}: {
  applicationId: string;
  resources: KubernetesApplicationTopologyNode[];
  topologyEdges: KubernetesApplicationDetail['topology']['edges'];
  yamlSourceChanges: Record<string, string>;
  changes: Record<string, Editable>;
  additions: PendingApplicationNode[];
  hasDraft: boolean;
  currentDraft?: KubernetesApplicationVersion;
  busy: boolean;
  onResourceYamlSave: (node: KubernetesApplicationTopologyNode, yaml: string) => void;
  onResourceUpdate: (node: KubernetesApplicationTopologyNode, value: Editable) => void;
  onOpenDeployment: (node: KubernetesApplicationTopologyNode) => void;
  onAdditionChange: (addition: PendingApplicationNode) => void;
  onRemoveAddition: (id: string) => void;
  onShowVersions: () => void;
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
  const [editingResource, setEditingResource] = useState<KubernetesApplicationTopologyNode>();
  const [yamlNode, setYamlNode] = useState<TopologyNode>();
  const [resourceYamlNode, setResourceYamlNode] = useState<KubernetesApplicationTopologyNode>();
  const [yamlError, setYamlError] = useState('');
  const [yamlSource, setYamlSource] = useState('');
  const [runtime, setRuntime] = useState<KubernetesApplicationNetworkRuntime | null>(null);
  const [runtimeBusy, setRuntimeBusy] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [nodeMenuOpen, setNodeMenuOpen] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const hasFittedInitialViewport = useRef(false);

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

  const visibleResources = useMemo(() => [...resources, ...additions.map(addition => ({
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
  }))].filter(resource => ['Service', 'Deployment', 'ServiceAccount'].includes(resource.kind)),
  [resources, additions]);
  const selectorEdges = useMemo<Edge[]>(() => {
    const services = visibleResources.filter(resource => resource.kind === 'Service');
    const deployments = visibleResources.filter(resource => resource.kind === 'Deployment');
    const currentServiceIds = new Set(topologyEdges.map(edge => edge.source));
    const adjustedServices = new Set(services
      .filter(resource => changes[resource.id]?.targetDeployment !== undefined || !currentServiceIds.has(resource.id))
      .map(resource => resource.id));
    const current = topologyEdges.filter(edge => !adjustedServices.has(edge.source)).map(edge => ({
      id: edge.id, source: edge.source, target: edge.target,
      sourceHandle: 'service-output', targetHandle: 'deployment-input',
      type: 'nanpanelBezier' as const,
      markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
      style: { stroke: '#91addc', strokeWidth: 2 }, selectable: false, deletable: false,
    }));
    const changed = services.flatMap(service => {
      const targetName = changes[service.id]?.targetDeployment ?? service.targetDeployment;
      if (!adjustedServices.has(service.id) || !targetName) return [];
      return deployments.filter(deployment => deploymentTarget(deployment) === targetName).map(deployment => ({
        id: `${service.id}->${deployment.id}`, source: service.id, target: deployment.id,
        sourceHandle: 'service-output', targetHandle: 'deployment-input',
        type: 'nanpanelBezier' as const,
        markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
        style: { stroke: '#91addc', strokeWidth: 2 }, selectable: false, deletable: false,
      }));
    });
    return [...current, ...changed];
  }, [visibleResources, topologyEdges, changes]);
  const nodeIds = useMemo(() => new Set(draft.nodes.map(node => node.id)), [draft.nodes]);

  const deviceEdges = useMemo<Edge[]>(() => draft.links.map(link => ({
    id: link.id, source: link.sourceNodeId, sourceHandle: link.sourcePort,
    target: link.targetNodeId, targetHandle: link.targetPort, type: 'nanpanelBezier',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#4a86ed' },
    data: { layer: link.layer, protocol: link.protocol, state: 'PENDING' },
  })), [draft.links]);
  const attachmentEdges = useMemo<Edge[]>(() => draft.attachments.flatMap(attachment => {
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
      type: 'nanpanelBezier' as const,
      markerEnd: { type: MarkerType.ArrowClosed, color: '#55a883' },
      style: { stroke: '#55a883', strokeWidth: 2.2 },
      data: { applicationAttachment: true },
    }];
  }), [draft.attachments, nodeIds, visibleResources]);
  const edges = useMemo(() => [...selectorEdges, ...deviceEdges, ...attachmentEdges],
    [selectorEdges, deviceEdges, attachmentEdges]);

  useEffect(() => {
    if (loading) return;
    const applicationNodes: ApplicationFlowNode[] = visibleResources.map(resource => ({
      id: resource.id,
      type: 'application',
      position: { x: 0, y: 0 },
      data: {
        resource: { ...resource, sourceYaml: yamlSourceChanges[resource.id] ?? resource.sourceYaml },
        changes: changes[resource.id] ?? {}, controlsDisabled: saving || busy || hasDraft,
        networkMode: true,
        sourceYamlEdited: yamlSourceChanges[resource.id] !== undefined
          && yamlSourceChanges[resource.id] !== resource.sourceYaml,
        onEditSourceYaml: setResourceYamlNode,
      },
      draggable: !saving && !busy && !hasDraft,
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
    const layoutNodes: TopologyLayoutNode[] = [
      ...applicationNodes.map(node => ({
        id: node.id, type: node.type, kind: node.data.resource.kind, name: node.data.resource.name,
        portCount: 0,
      })),
      ...deviceNodes.map(node => ({
        id: node.id, type: node.type, kind: node.data.model.kind, name: node.data.model.name,
        portCount: Math.max(...(['INPUT', 'OUTPUT'] as const).map(direction =>
          node.data.model.ports.filter(port => port.direction === direction).length)),
      })),
    ];
    const layoutPositions = layoutApplicationTopology(layoutNodes, edges);
    const pendingPositions = new Map(additions.map(addition => [addition.id, addition.position]));
    setNodes(current => {
      const previousPositions = new Map(current.map(node => [node.id, node.position]));
      const occupied = deviceNodes.map(node => ({
        position: previousPositions.get(node.id) ?? node.position,
        height: 162 + Math.max(node.data.model.ports.filter(port => port.direction === 'INPUT').length,
          node.data.model.ports.filter(port => port.direction === 'OUTPUT').length) * 25,
      }));
      for (const node of applicationNodes) {
        const preserved = previousPositions.get(node.id);
        const pending = pendingPositions.get(node.id);
        if (preserved || pending) {
          occupied.push({ position: preserved ?? pending!, height: 172 });
        }
      }
      const freePosition = (origin: { x: number; y: number }, height: number) => {
        const fits = (candidate: { x: number; y: number }) => occupied.every(item =>
          candidate.x + 368 <= item.position.x || item.position.x + 368 <= candidate.x
          || candidate.y + height + 28 <= item.position.y || item.position.y + item.height + 28 <= candidate.y);
        if (fits(origin)) return origin;
        for (let radius = 1; radius < 16; radius++) {
          const candidates = [
            { x: origin.x, y: origin.y - radius * 224 },
            { x: origin.x, y: origin.y + radius * 224 },
            { x: origin.x - radius * 452, y: origin.y },
            { x: origin.x + radius * 452, y: origin.y },
            { x: origin.x - radius * 452, y: origin.y - radius * 224 },
            { x: origin.x + radius * 452, y: origin.y + radius * 224 },
          ];
          const candidate = candidates.find(fits);
          if (candidate) return candidate;
        }
        return origin;
      };
      const positionedApplications = applicationNodes.map(node => {
        const preserved = previousPositions.get(node.id);
        const pending = pendingPositions.get(node.id);
        const layout = layoutPositions.get(node.id) ?? node.position;
        const position = preserved ?? pending ?? freePosition(layout, 172);
        if (!preserved && !pending) occupied.push({ position, height: 172 });
        return { ...node, position };
      });
      const positionedDevices = deviceNodes.map(node => ({
        ...node, position: previousPositions.get(node.id) ?? node.position,
      }));
      return [...positionedApplications, ...positionedDevices];
    });
  }, [loading, visibleResources, draft.nodes, yamlSourceChanges, changes, additions, saving, busy, hasDraft,
    edges, setNodes]);

  useEffect(() => {
    if (loading || !nodes.length || hasFittedInitialViewport.current) return;
    const adjacency = new Map(nodes.map(node => [node.id, new Set<string>()]));
    for (const edge of edges) {
      adjacency.get(edge.source)?.add(edge.target);
      adjacency.get(edge.target)?.add(edge.source);
    }
    const visited = new Set<string>();
    const components: string[][] = [];
    for (const node of nodes) {
      if (visited.has(node.id)) continue;
      const component: string[] = [];
      const queue = [node.id];
      visited.add(node.id);
      for (let index = 0; index < queue.length; index++) {
        const current = queue[index];
        component.push(current);
        for (const neighbor of adjacency.get(current) ?? []) {
          if (visited.has(neighbor)) continue;
          visited.add(neighbor);
          queue.push(neighbor);
        }
      }
      components.push(component);
    }
    const hasDeployment = (component: string[]) => component.some(id => {
      const node = nodes.find(item => item.id === id);
      return node?.type === 'application' && node.data.resource.kind === 'Deployment';
    });
    components.sort((left, right) => Number(hasDeployment(right)) - Number(hasDeployment(left))
      || right.length - left.length);
    const focusIds = components[0] ?? nodes.map(node => node.id);
    let secondFrame = 0;
    const firstFrame = requestAnimationFrame(() => {
      secondFrame = requestAnimationFrame(() => {
        void fitView({ nodes: focusIds.map(id => ({ id })), padding: 0.22,
          minZoom: 0.72, maxZoom: 1.05, duration: 0 });
        hasFittedInitialViewport.current = true;
      });
    });
    return () => {
      cancelAnimationFrame(firstFrame);
      if (secondFrame) cancelAnimationFrame(secondFrame);
    };
  }, [loading, nodes.length, edges.length, fitView]);

  function canvasCenter() {
    const bounds = canvasRef.current?.getBoundingClientRect();
    return screenToFlowPosition({
      x: bounds ? bounds.left + bounds.width / 2 : window.innerWidth / 2,
      y: bounds ? bounds.top + bounds.height / 2 : window.innerHeight / 2,
    });
  }

  const onFlowNodesChange = (changes: Parameters<typeof updateNodes>[0]) => {
    updateNodes(changes);
    const positions = new Map(changes.flatMap(change => change.type === 'position' && nodeIds.has(change.id)
      && change.position && change.dragging === false
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
    if (source.type === 'application' && target.type === 'application') {
      return !hasDraft && source.data.resource.kind === 'Service'
        && target.data.resource.kind === 'Deployment';
    }
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
    if (source.type === 'application' && target.type === 'application') {
      if (source.data.resource.kind !== 'Service' || target.data.resource.kind !== 'Deployment') return;
      const targetName = deploymentTarget(target.data.resource);
      const currentTarget = changes[source.id]?.targetDeployment ?? source.data.resource.targetDeployment;
      if (targetName !== currentTarget) {
        onResourceUpdate(source.data.resource, { targetDeployment: targetName });
        setMessage(`已将 ${source.data.resource.name} 连接到 ${targetName}；检查版本差异后再部署。`);
      }
      return;
    }
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
    const point = menu?.point ?? canvasCenter();
    const model = createTopologyNode(kind, point);
    model.name = `${prefix} ${index}`;
    setDraft(current => ({ ...current, nodes: [...current.nodes, model] }));
    setDirty(true);
    setMenu(undefined);
  }

  function addResource(kind: 'Service' | 'Deployment') {
    if (saving || busy || hasDraft) return;
    const existingNames = new Set(visibleResources
      .filter(resource => resource.kind === kind).map(resource => resource.name));
    const prefix = kind === 'Deployment' ? 'deployment' : 'service';
    let suffix = 1;
    while (existingNames.has(`${prefix}-${suffix}`)) suffix++;
    const name = `${prefix}-${suffix}`;
    const point = menu?.point ?? canvasCenter();
    const deployment = visibleResources.find(resource => resource.kind === 'Deployment');
    onAdditionChange({
      id: `pending:${kind}/${name}`, kind, name,
      replicas: kind === 'Deployment' ? 1 : null,
      image: kind === 'Deployment' ? 'nginx:1.27' : null,
      targetDeployment: kind === 'Service' && deployment ? deploymentTarget(deployment) : null,
      servicePort: kind === 'Service' ? 80 : null,
      targetPort: kind === 'Service' ? '80' : null,
      serviceProtocol: kind === 'Service' ? 'TCP' : null,
      position: point,
    });
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
    <div className="application-network-canvas" ref={canvasRef}>
      <TopologyCanvas<ApplicationNetworkFlowNode, Edge>
        nodes={nodes} edges={edges} nodeTypes={applicationNetworkNodeTypes}
        onNodesChange={onFlowNodesChange} onConnect={connect}
        onNodeClick={(_, node) => {
          if (node.type === 'application' && node.data.resource.kind === 'Deployment'
              && !node.data.resource.id.startsWith('pending:')) {
            onOpenDeployment(node.data.resource);
          }
        }}
        onCanvasContextMenu={event => {
          if (saving || busy) return;
          setMenu({ x: event.clientX, y: event.clientY,
            point: screenToFlowPosition({ x: event.clientX, y: event.clientY }) });
        }}
        onNodeContextMenu={(event, node) => {
          event.preventDefault();
          if (saving || busy) return;
          setMenu(node.type === 'application-device'
            ? { x: event.clientX, y: event.clientY, nodeId: node.id }
            : { x: event.clientX, y: event.clientY, resourceId: node.id });
        }}
        onEdgeContextMenu={(event, edge) => {
          event.preventDefault();
          if (saving || busy) return;
          const source = nodes.find(node => node.id === edge.source);
          const target = nodes.find(node => node.id === edge.target);
          if (source?.type === 'application' && target?.type === 'application'
              && source.data.resource.kind === 'Service') {
            setMenu({ x: event.clientX, y: event.clientY, resourceId: source.id,
              connectedTo: target.data.resource.name });
          } else if (!selectorEdges.some(item => item.id === edge.id)) {
            setMenu({ x: event.clientX, y: event.clientY, edgeId: edge.id });
          }
        }}
        isValidConnection={validConnection} connectionMode={ConnectionMode.Strict}
        onConnectEnd={(_, state) => {
          if (state.toHandle && !state.isValid) setMessage('连接需符合端口方向与类型；Service 连接 Deployment，Deployment 网卡端口连接虚拟设备。');
        }}
        nodesDraggable={!saving && !busy} nodesConnectable={!saving && !busy}
        elementsSelectable deleteKeyCode={null} minZoom={0.25} maxZoom={1.8}
        noWheelClassName="nowheel"
      >
        <Background color="#dbe3ee" gap={24} />
        <Controls position="bottom-left" showFitView={false} showInteractive={false} />
        <MiniMap position="bottom-right" pannable zoomable maskColor="#dfe7f2a8"
          nodeColor={node => node.type === 'application-device' ? '#758dad' : '#5f91dc'} />
      </TopologyCanvas>
      <div className="application-floating-tool-stack">
        <div className="application-node-menu-wrap">
          <button type="button" className="application-canvas-fab primary" aria-label="添加拓扑节点" title="添加节点"
            aria-haspopup="menu" aria-expanded={nodeMenuOpen} disabled={saving || busy}
            onClick={() => setNodeMenuOpen(open => !open)}>＋<span>添加节点</span></button>
          {nodeMenuOpen && <div className="application-node-flyout" role="menu" aria-label="添加拓扑节点">
            <strong>应用资源</strong>
            <button type="button" role="menuitem" disabled={hasDraft} onClick={() => { addResource('Deployment'); setNodeMenuOpen(false); }}>添加 Deployment</button>
            <button type="button" role="menuitem" disabled={hasDraft} onClick={() => { addResource('Service'); setNodeMenuOpen(false); }}>添加 ClusterIP Service</button>
            <strong>虚拟网络设备</strong>
            {virtualApplicationKinds.map(kind => <button type="button" role="menuitem" key={kind}
              onClick={() => { addDevice(kind); setNodeMenuOpen(false); }}>
              {nodePresets[kind].name}<small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small>
            </button>)}
          </div>}
        </div>
        <button type="button" className="application-canvas-fab" aria-label="拓扑设置与运行时" title="拓扑设置"
          aria-expanded={settingsOpen} onClick={() => setSettingsOpen(true)}>⚙<span>拓扑设置</span></button>
      </div>
      {loading && <div className="application-canvas-status" role="status">正在读取拓扑…</div>}
      {additions.some(addition => addition.kind === 'Service' && !addition.targetDeployment && !addition.sourceYaml)
        && <div className="application-canvas-warning" role="status">新增 Service 尚未连接到 Deployment；连接 Service 与 Deployment 后即可生成差异。</div>}
      {(error || message) && <div className={`application-canvas-notice ${error ? 'error' : ''}`}
        role={error ? 'alert' : 'status'}>{error || message}</div>}
    </div>
    {settingsOpen && <div className="application-network-drawer-backdrop" onMouseDown={event => {
      if (event.target === event.currentTarget) setSettingsOpen(false);
    }}>
      <aside className="application-network-drawer" role="dialog" aria-modal="true" aria-labelledby="application-network-drawer-title">
        <div className="application-drawer-heading"><div><span className="eyebrow">APPLICATION TOPOLOGY SETTINGS</span>
          <h2 id="application-network-drawer-title">拓扑与运行时</h2></div>
          <button type="button" className="application-float-button icon-only" aria-label="关闭拓扑设置" onClick={() => setSettingsOpen(false)}>×</button>
        </div>
        <p className="application-network-drawer-intro">在画布中编辑 Deployment、Service、ServiceAccount 和虚拟网络设备；右键卡片可编辑资源或源 YAML。</p>
        <button className="primary application-network-save" disabled={!dirty || saving || busy || loading || isMock}
          onClick={() => void saveTopology()}>{saving ? '保存中…' : `保存应用拓扑${dirty ? ' *' : ''}`}</button>
        {hasDraft && currentDraft && <div className="application-draft-banner"><span>◈</span><div><strong>v{currentDraft.version} 有待确认差异</strong><small>拓扑资源已锁定编辑；检查 YAML diff 并显式确认后才会部署。</small></div><button className="link" onClick={() => { setSettingsOpen(false); onShowVersions(); }}>查看 diff →</button></div>}
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
        <div className="application-topology-footnote">Service 与 Deployment 连线用于生成资源 YAML 差异；Deployment 顶部/底部端口用于连接虚拟设备，虚拟设备之间可建立多层转发链路。拓扑与资源变更需分别保存，只有显式确认版本或 Apply 才会修改运行环境。</div>
      </aside>
    </div>}
    {menu && <TopologyContextMenu x={menu.x} y={menu.y} onClose={() => setMenu(undefined)}>
      {menu.resourceId ? (() => {
        const resource = visibleResources.find(node => node.id === menu.resourceId);
        if (!resource) return null;
        return <>
          <TopologyContextMenuHeading>{menu.connectedTo
            ? `${resource.name} → ${menu.connectedTo}` : `${resource.kind} · ${resource.name}`}</TopologyContextMenuHeading>
          {resource.kind !== 'ServiceAccount' && <TopologyContextMenuItem onClick={() => {
            setEditingResource(resource);
            setMenu(undefined);
          }}>{resource.kind === 'Service' ? '编辑端口与连接' : '编辑副本与镜像'}</TopologyContextMenuItem>}
          <TopologyContextMenuItem onClick={() => {
            setResourceYamlNode(resource);
            setMenu(undefined);
          }}>编辑此卡片源 YAML</TopologyContextMenuItem>
          {!menu.connectedTo && resource.id.startsWith('pending:') && <TopologyContextMenuItem className="danger" onClick={() => {
            onRemoveAddition(resource.id);
            setMenu(undefined);
          }}>撤销新增节点</TopologyContextMenuItem>}
        </>;
      })() : menu.nodeId ? <>
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
        <TopologyContextMenuHeading>添加应用拓扑节点</TopologyContextMenuHeading>
        <TopologyContextMenuItem disabled={hasDraft} onClick={() => addResource('Deployment')}>添加 Deployment 工作负载</TopologyContextMenuItem>
        <TopologyContextMenuItem disabled={hasDraft} onClick={() => addResource('Service')}>添加 ClusterIP Service</TopologyContextMenuItem>
        <TopologyContextMenuHeading>添加虚拟网络设备</TopologyContextMenuHeading>
        {virtualApplicationKinds.map(kind => <TopologyContextMenuItem key={kind} onClick={() => addDevice(kind)}>
          <span>{nodePresets[kind].name}</span><small>{nodePresets[kind].inputs} 入 / {nodePresets[kind].outputs} 出</small>
        </TopologyContextMenuItem>)}
      </>}
    </TopologyContextMenu>}
    {editingResource && <ApplicationNodeSettingsDialog
      key={editingResource.id} node={editingResource} changes={changes[editingResource.id] ?? {}}
      disabled={busy || hasDraft} onClose={() => setEditingResource(undefined)}
      onSave={value => { onResourceUpdate(editingResource, value); setEditingResource(undefined); }} />}
    {yamlNode && <ApplicationVirtualDeviceYamlDialog node={yamlNode} sourceYaml={yamlSource}
      error={yamlError} disabled={saving || busy} onChange={setYamlSource}
      onClose={() => setYamlNode(undefined)} onSave={saveDeviceYaml} />}
    {resourceYamlNode && <ApplicationSourceYamlDialog node={resourceYamlNode}
      disabled={saving || busy || hasDraft} onClose={() => setResourceYamlNode(undefined)}
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
