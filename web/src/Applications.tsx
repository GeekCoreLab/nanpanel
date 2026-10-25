import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  ConnectionMode, Handle, MarkerType, Position, ReactFlowProvider,
  useNodesState, useReactFlow,
  type Connection, type Edge, type Node, type NodeProps,
} from '@xyflow/react';
import { api, isMock } from './lib/api';
import type {
  KubernetesApplication,
  KubernetesApplicationCreateRequest,
  KubernetesApplicationDetail,
  KubernetesApplicationList,
  KubernetesApplicationPreview,
  KubernetesApplicationTopologyNode,
  KubernetesApplicationVersion,
  KubernetesApplicationVersionDraftRequest,
  KubernetesApplicationVersionList,
  KubernetesClusterList,
  KubernetesClusterSummary,
  KubernetesTopologyNamespaceSnapshot,
} from './lib/types';
import { TopologyCanvas } from './topology/TopologyCanvas';
import './applications.css';

type View = 'topology' | 'versions';
type Editable = { replicas?: number; image?: string; targetDeployment?: string; servicePort?: number; targetPort?: string; serviceProtocol?: 'TCP' | 'UDP' | 'SCTP' };
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
    if (!edits.length) { setError('请先修改拓扑中的副本、镜像、Service 连线或端口配置。'); return; }
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
      <div className="applications-actions"><button className="secondary" onClick={() => void loadList()} disabled={loading}>↻ 刷新</button><button className="primary" onClick={beginCreate} disabled={isMock}>＋ 创建应用</button></div>
    </div>
    {error && <div className="alert error" role="alert">{error}</div>}
    {isMock && <div className="alert">Mock 演示环境没有真实集群应用接口。此页面在 Live API 模式下连接已登记集群。</div>}
    <div className="application-layout">
      <aside className="application-list-panel">
        <div className="application-list-heading"><div><span className="eyebrow">APPLICATIONS</span><strong>应用列表</strong></div><span className="application-count">{items.length}</span></div>
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
              <div className="application-topology-footnote">拖动节点调整布局；拖动 Service 绿色端口到 Deployment 蓝色端口可重接服务；右键卡片配置副本、镜像或 Service 端口。修改会生成 YAML diff，拓扑布局只保留在当前视图。</div>
            </section>
          </> : <section className="application-versions-card">
            <div className="application-section-heading"><div><span className="eyebrow">IMMUTABLE MANIFEST HISTORY</span><h3>版本历史</h3><p>草稿不会修改集群；只有状态为“待确认”的版本可提交部署。</p></div></div>
            {!versions.length ? <div className="application-empty-history">暂无版本记录</div> : <div className="application-version-list">{versions.map(version => <VersionCard key={version.version} version={version} busy={busy === `deploy-${version.version}`} onDeploy={() => void deployDraft(version)} />)}</div>}
          </section>}
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
};
type ApplicationFlowNode = Node<ApplicationFlowNodeData, 'application'>;

function applicationFlowNodePosition(node: KubernetesApplicationTopologyNode, index: number) {
  if (node.kind === 'Service') return { x: 40, y: 42 + index * 128 };
  return { x: 430 + (index % 2) * 340, y: 36 + Math.floor(index / 2) * 174 };
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
      <small>{resource.readyReplicas ?? 0}/{changes.replicas ?? resource.replicas ?? 0} Ready</small>
      <small className="application-node-image">{changes.image ?? resource.image ?? '未声明镜像'}</small>
    </> : <>
      <small>ClusterIP · {changes.servicePort ?? resource.servicePort ?? '—'}/{changes.serviceProtocol ?? resource.serviceProtocol ?? 'TCP'} → {changes.targetPort ?? resource.targetPort ?? '—'}</small>
      <small>后端：{changes.targetDeployment ?? resource.targetDeployment ?? '通过拓扑连线指定'}</small>
    </>}
    <small className="application-node-edit-hint">右键配置</small>
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
  const [editingNode, setEditingNode] = useState<KubernetesApplicationTopologyNode>();
  const visibleResources = useMemo(
    () => resources.filter(resource => resource.kind === 'Service' || resource.kind === 'Deployment'),
    [resources],
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
        type: 'nanpanelBezier' as const,
        markerEnd: { type: MarkerType.ArrowClosed, color: '#91addc' },
        style: { stroke: '#91addc', strokeWidth: 2 },
        selectable: false,
        deletable: false,
      }));
    const changed = services.flatMap(service => {
      const target = changes[service.id]?.targetDeployment;
      if (target === undefined) return [];
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
      return visibleResources.map(resource => ({
        id: resource.id,
        type: 'application' as const,
        position: previousPositions.get(resource.id)
          ?? applicationFlowNodePosition(resource, serviceIndex.get(resource.id) ?? deploymentIndex.get(resource.id) ?? 0),
        data: {
          resource,
          changes: changes[resource.id] ?? {},
          controlsDisabled: busy || hasDraft,
        },
        draggable: !busy && !hasDraft,
      }));
    });
  }, [visibleResources, changes, busy, hasDraft, setNodes]);

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
    <TopologyCanvas<ApplicationFlowNode, Edge>
      nodes={nodes}
      edges={edges}
      nodeTypes={applicationFlowNodeTypes}
      onNodesChange={onNodesChange}
      onConnect={connect}
      onNodeContextMenu={(event, node) => {
        event.preventDefault();
        if (!busy && !hasDraft) setEditingNode(node.data.resource);
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
    {connectionMessage && <div className="application-flow-message" role="status">{connectionMessage}</div>}
    {editingNode && <ApplicationNodeSettingsDialog
      key={editingNode.id}
      node={editingNode}
      changes={changes[editingNode.id] ?? {}}
      disabled={busy || hasDraft}
      onClose={() => setEditingNode(undefined)}
      onSave={value => { onUpdate(editingNode, value); setEditingNode(undefined); }}
    />}
  </>;
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
