import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type {
  AppStoreApp, AppStoreCatalog, AppStoreDeployment, AppStoreDeploymentList, ContainerDeployPayload, Host, HostPage,
  HostHelperOperationRequest, Operation,
} from './lib/types';
import './app-store.css';

type DeployTarget = { app: AppStoreApp };

export function AppStore() {
  const [catalog, setCatalog] = useState<AppStoreCatalog | null>(null);
  const [hosts, setHosts] = useState<Host[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [deploying, setDeploying] = useState<DeployTarget | null>(null);
  const [deployments, setDeployments] = useState<AppStoreDeployment[]>([]);
  const [deploymentBusy, setDeploymentBusy] = useState('');
  const [notice, setNotice] = useState('');

  const loadDeployments = useCallback(async () => {
    try {
      const result = await api<AppStoreDeploymentList>(
        '/app-store/deployments', 'GET', undefined, {}, true);
      setDeployments(result.data.items);
    } catch {
      /* the deployed list is best-effort; keep the catalog usable */
    }
  }, []);

  useEffect(() => { void loadDeployments(); }, [loadDeployments]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void api<AppStoreCatalog>('/app-store/catalog', 'GET', undefined, {}, true)
      .then(result => { if (active) setCatalog(result.data); })
      .catch(cause => { if (active) setError((cause as Error).message); })
      .finally(() => { if (active) setLoading(false); });
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => { if (active) setHosts(result.data.items); })
      .catch(() => {});
    return () => { active = false; };
  }, []);

  const grouped = useMemo(() => {
    const apps = catalog?.items ?? [];
    const filtered = query.trim()
      ? apps.filter(app => (app.name + app.summary + app.id).toLowerCase().includes(query.trim().toLowerCase()))
      : apps;
    const groups = new Map<string, AppStoreApp[]>();
    for (const app of filtered) {
      const list = groups.get(app.category) ?? [];
      list.push(app);
      groups.set(app.category, list);
    }
    return groups;
  }, [catalog, query]);

  const deployableHosts = useMemo(() => hosts.filter(host =>
    host.status === 'ONLINE' && host.capabilities.includes('container.deploy')), [hosts]);

  const uninstall = async (deployment: AppStoreDeployment) => {
    const host = hosts.find(item => item.id === deployment.hostId);
    if (!host) { setNotice('目标主机当前未登记或不在线，无法卸载。'); return; }
    if (!window.confirm(`确认卸载 ${deployment.containerName}？命名数据卷会保留。`)) return;
    setDeploymentBusy(deployment.containerName);
    setError('');
    setNotice('');
    try {
      const request: HostHelperOperationRequest = {
        capability: 'container.remove', target: deployment.containerName,
        reason: `卸载应用商店容器 ${deployment.containerName}`, timeoutSeconds: 60,
        expectedGeneration: host.generation,
      };
      await api<Operation>(`/hosts/${host.id}/operations`, 'POST', request,
        { 'Idempotency-Key': createUuid() }, true);
      setNotice(`已提交卸载 ${deployment.containerName}；结果可在“操作任务”查看。`);
      window.setTimeout(() => void loadDeployments(), 1500);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setDeploymentBusy('');
    }
  };

  return <section className="app-store">
    <div className="card app-store-heading">
      <div><span className="eyebrow">APP STORE · ONE-CLICK DEPLOY</span>
        <h2>应用商店</h2>
        <p>从仓库内清单一键把 MySQL、Redis、Nginx、WordPress 等部署到已登记的 Docker / Podman 主机。</p></div>
      <div className="app-store-toolbar">
        <input aria-label="搜索应用" type="search" placeholder="⌕ 搜索应用名称或摘要…"
          value={query} onChange={event => setQuery(event.target.value)} />
        <button type="button" className="secondary" onClick={() => { setCatalog(null); setError(''); }}
          disabled={loading}>↻ 刷新</button>
      </div>
    </div>
    {loading && <p className="app-store-status" role="status">正在读取应用目录…</p>}
    {error && <div className="alert error" role="alert">{error}</div>}
    {!loading && !error && catalog && <>
      {deployableHosts.length === 0 && !isMock && <div className="alert">尚无报告 <code>container.deploy</code> 能力且在线的主机。请先在“主机资产”中接入 Agent 与 Helper。</div>}
      <div className="app-store-meta">目录版本 v{catalog.version} · {catalog.items.length} 个应用 · {deployableHosts.length} 台可部署主机</div>
      <section className="card app-store-deployments">
        <div className="app-store-category-heading">
          <strong>已部署容器</strong>
          <small>{deployments.length} 个 · NaNPanel 通过 Helper 部署（含应用商店与主机面板）</small>
        </div>
        {notice && <div className="alert success" role="status">{notice}</div>}
        {deployments.length === 0
          ? <p className="app-store-muted">暂无已部署容器。成功部署应用后会显示在这里，可一键卸载。</p>
          : <ul className="app-store-deployment-list">{deployments.map(item => <li key={`${item.hostId}:${item.containerName}`}>
              <div><strong>{item.containerName}</strong>
                <small>{item.hostname} · {new Date(item.deployedAt).toLocaleString('zh-CN')}</small></div>
              <button type="button" className="danger" disabled={deploymentBusy !== '' || isMock}
                onClick={() => void uninstall(item)}>{deploymentBusy === item.containerName ? '卸载中…' : '卸载'}</button>
            </li>)}</ul>}
      </section>
      {[...grouped.entries()].map(([category, apps]) => <section key={category} className="card app-store-category">
        <div className="app-store-category-heading">
          <strong>{catalog.categories.find(item => item.id === category)?.label ?? category}</strong>
          <small>{apps.length} 个应用</small>
        </div>
        <div className="app-store-grid">
          {apps.map(app => <article key={app.id} className={`app-store-card ${app.deployableNow ? 'deployable' : 'pending'}`}>
            <div className="app-store-card-heading">
              <strong>{app.name}</strong>
              <span className={`app-store-badge ${app.deployableNow ? 'ready' : 'later'}`}>
                {app.deployableNow ? '现在可部署' : '待扩展'}
              </span>
            </div>
            <p className="app-store-summary">{app.summary}</p>
            <div className="app-store-facts">
              <span><code>{app.image}</code></span>
              {app.defaultPort !== null && <span>端口 {app.defaultPort}</span>}
              {app.volumes.length > 0 && <span>{app.volumes.length} 数据卷</span>}
              {app.environment.length > 0 && <span>{app.environment.length} 环境变量</span>}
            </div>
            {!app.deployableNow && <p className="app-store-note">{app.deployNote}</p>}
            <button type="button" className="primary app-store-deploy"
              disabled={deployableHosts.length === 0 || isMock}
              onClick={() => setDeploying({ app })}>部署到主机</button>
          </article>)}
        </div>
      </section>)}
    </>}
    {deploying && <AppStoreDeployDialog app={deploying.app} hosts={deployableHosts}
      onClose={() => setDeploying(null)} onDeployed={() => void loadDeployments()} />}
  </section>;
}

function AppStoreDeployDialog({ app, hosts, onClose, onDeployed }: {
  app: AppStoreApp; hosts: Host[]; onClose: () => void; onDeployed: () => void;
}) {
  const [hostId, setHostId] = useState(hosts[0]?.id ?? '');
  const [containerName, setContainerName] = useState(`nanpanel-${app.id}`);
  const [hostPort, setHostPort] = useState('');
  const [containerPort, setContainerPort] = useState(app.defaultPort !== null ? String(app.defaultPort) : '');
  const [memoryMb, setMemoryMb] = useState('256');
  const [stateful, setStateful] = useState(app.volumes.length > 0);
  const [envValues, setEnvValues] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState<Operation | null>(null);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError('');
    setSubmitted(null);
    const host = hosts.find(item => item.id === hostId);
    if (!host) { setError('请选择目标主机。'); return; }
    const environment = app.environment
      .map(item => ({ name: item.name, value: envValues[item.name] ?? '' }))
      .filter(item => item.value !== '')
      .map(item => `${item.name}=${item.value}`);
    const missingRequired = app.environment
      .filter(item => item.required && !(envValues[item.name] ?? '').trim())
      .map(item => item.name);
    if (missingRequired.length) { setError(`缺少必填环境变量：${missingRequired.join('、')}。`); return; }
    const port = Number(containerPort);
    if (app.defaultPort !== null && (!Number.isInteger(port) || port < 1 || port > 65535)) {
      setError('容器端口必须是 1–65535 的整数。'); return;
    }
    const memory = Number(memoryMb);
    if (!Number.isInteger(memory) || memory < 64 || memory > 2048) {
      setError('内存限制必须是 64–2048 MiB 的整数。'); return;
    }
    const payload: ContainerDeployPayload = {
      image: app.image,
      hostPort: hostPort.trim() ? Number(hostPort.trim()) : null,
      containerPort: port || null,
      environment,
      volumes: app.volumes.map(volume => ({ name: volume.name, mountPath: volume.mountPath })),
      memoryMb: memory,
      stateful,
    };
    const request: HostHelperOperationRequest = {
      capability: 'container.deploy', target: containerName.trim(), payload,
      reason: reason.trim(), timeoutSeconds: 300, expectedGeneration: host.generation,
    };
    setBusy(true);
    try {
      const result = await api<Operation>(`/hosts/${host.id}/operations`, 'POST', request,
        { 'Idempotency-Key': createUuid() }, true);
      setSubmitted(result.data);
      onDeployed();
      // Record advisory metadata so the site form can pre-fill the published loopback port.
      void api('/app-store/deployments', 'POST', {
        hostId: host.id, containerName: containerName.trim(), image: app.image,
        hostPort: hostPort.trim() ? Number(hostPort.trim()) : null, containerPort: port || null,
      }, {}, true).catch(() => { /* metadata is advisory; never block the deploy result */ });
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return <div className="modal-backdrop"><section className="modal app-store-deploy-dialog" role="dialog"
    aria-modal="true" aria-labelledby="app-store-deploy-title">
    <div className="card-title">
      <div><span className="eyebrow">ONE-CLICK DEPLOY</span>
        <h2 id="app-store-deploy-title">部署 {app.name}</h2>
        <p>{app.summary}</p></div>
      <button type="button" className="icon-button" aria-label="关闭" onClick={onClose} disabled={busy}>×</button>
    </div>
    {submitted ? <div className="alert success">已提交部署任务 <code>{submitted.id.slice(0, 8)}</code>，状态
      {submitted.status === 'UNKNOWN' ? '未知（可稍后到“操作任务”查询）' : submitted.status}。可到“操作任务”跟踪，成功后容器即运行在
      {hosts.find(item => item.id === hostId)?.hostname}。</div> : null}
    {error && <div className="alert error" role="alert">{error}</div>}
    {!app.deployableNow && <div className="alert">{app.deployNote}</div>}
    <form onSubmit={submit}>
      <label className="field"><span>目标主机</span>
        <select value={hostId} onChange={event => setHostId(event.target.value)}>
          {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname}</option>)}
        </select></label>
      <div className="app-store-deploy-grid">
        <label className="field"><span>容器名</span>
          <input required maxLength={63} pattern="[A-Za-z0-9][A-Za-z0-9_.-]{0,62}"
            value={containerName} onChange={event => setContainerName(event.target.value)} /></label>
        <label className="field"><span>Loopback 主机端口（可选）</span>
          <input type="number" min={1} max={65535} value={hostPort} placeholder="仅绑定 127.0.0.1"
            onChange={event => setHostPort(event.target.value)} /></label>
        {app.defaultPort !== null && <label className="field"><span>容器端口</span>
          <input type="number" min={1} max={65535} required value={containerPort}
            onChange={event => setContainerPort(event.target.value)} /></label>}
        <label className="field"><span>内存限制 (MiB)</span>
          <input type="number" min={64} max={2048} required value={memoryMb}
            onChange={event => setMemoryMb(event.target.value)} /></label>
      </div>
      {app.volumes.length > 0 && <div className="app-store-deploy-fact"><span>命名数据卷</span>
        <ul>{app.volumes.map(volume => <li key={volume.name}><code>{volume.name}</code> → <code>{volume.mountPath}</code>
          <small>{volume.description}</small></li>)}</ul></div>}
      {app.environment.length > 0 && <div className="app-store-deploy-fact"><span>环境变量</span>
        {app.environment.map(item => <label className="field" key={item.name}>
          <span>{item.name}{item.required ? '（必填）' : ''} · {item.description}</span>
          <input type={item.secret ? 'password' : 'text'} autoComplete={item.secret ? 'new-password' : 'off'}
            value={envValues[item.name] ?? ''} required={item.required}
            onChange={event => setEnvValues(current => ({ ...current, [item.name]: event.target.value }))} />
        </label>)}</div>}
      <label className="app-store-stateful"><input type="checkbox" checked={stateful}
        onChange={event => setStateful(event.target.checked)} />
        <span>有状态部署（授予最小初始化能力并允许写入数据卷，仍保留内存/CPU/进程与能力边界）</span></label>
      <label className="field"><span>部署原因</span>
        <textarea required maxLength={500} rows={2} value={reason} placeholder="说明本次部署的目的"
          onChange={event => setReason(event.target.value)} /></label>
      <div className="form-actions">
        <button type="button" className="secondary" onClick={onClose} disabled={busy}>取消</button>
        <button type="submit" className="primary" disabled={busy || submitted !== null}>
          {busy ? '部署中…' : '一键部署'}</button>
      </div>
    </form>
  </section></div>;
}