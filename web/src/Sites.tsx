import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type { AppStoreDeployment, AppStoreDeploymentList, Host, HostPage, ReverseProxySite, ReverseProxySiteList } from './lib/types';
import './sites.css';

const STATE_LABELS: Record<string, string> = { DRAFT: '草稿', APPLIED: '已生效', DISABLED: '已停用', FAILED: '失败' };

/**
 * 网站（反向代理）控制台：把「域名 → 主机上 127.0.0.1:端口」登记为站点，应用时
 * 编译为受管 Nginx 路由下发。图形化建站，替代手写 server 块；SSL 证书待后续接入。
 */
export function Sites({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [deployments, setDeployments] = useState<AppStoreDeployment[]>([]);
  const [sites, setSites] = useState<ReverseProxySite[]>([]);
  const [loading, setLoading] = useState(!isMock);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供站点接口，切换到 Live API 后可创建并下发站点。' : '');
  const [hostId, setHostId] = useState('');
  const [domain, setDomain] = useState('');
  const [listenAddress, setListenAddress] = useState('');
  const [listenPort, setListenPort] = useState('80');
  const [upstreamPort, setUpstreamPort] = useState('8080');
  const [healthPath, setHealthPath] = useState('/');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [tlsSite, setTlsSite] = useState<ReverseProxySite | null>(null);
  const [certPem, setCertPem] = useState('');
  const [keyPem, setKeyPem] = useState('');
  const [tlsPort, setTlsPort] = useState('443');

  const load = useCallback(async () => {
    if (isMock) return;
    setLoading(true);
    try {
      const result = await api<ReverseProxySiteList>(
        '/reverse-proxy-sites', 'GET', undefined, {}, true);
      setSites(result.data.items);
      setError('');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isMock) return;
    void load();
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        setHosts(result.data.items);
        setHostId(current => current || result.data.items[0]?.id || '');
      })
      .catch(() => {});
    void api<AppStoreDeploymentList>('/app-store/deployments', 'GET', undefined, {}, true)
      .then(result => setDeployments(result.data.items))
      .catch(() => {});
  }, [load]);

  function applyDeployment(key: string) {
    const deployment = deployments.find(item => `${item.hostId}/${item.containerName}` === key);
    if (!deployment) return;
    setHostId(deployment.hostId);
    if (deployment.hostPort) setUpstreamPort(String(deployment.hostPort));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    setBusy('create');
    try {
      const body = {
        hostId, domain: domain.trim(), listenAddress: listenAddress.trim(),
        listenPort: Number(listenPort), upstreamPort: Number(upstreamPort),
        healthPath: healthPath.trim() || '/',
      };
      if (editingId) {
        await api<ReverseProxySite>(`/reverse-proxy-sites/${editingId}`, 'PUT', body, {}, true);
        notify(`站点 ${domain.trim()} 已更新，需要重新应用才会生效`);
      } else {
        await api<ReverseProxySite>('/reverse-proxy-sites', 'POST', body, {}, true);
        notify(`站点 ${domain.trim()} 已创建`);
        setDomain('');
        setListenAddress('');
      }
      setEditingId(null);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function apply(site: ReverseProxySite) {
    setBusy(site.id);
    try {
      await api<ReverseProxySite>(`/reverse-proxy-sites/${site.id}/apply`, 'POST', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      notify(`站点 ${site.domain} 已下发到受管 Nginx`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  async function disable(site: ReverseProxySite) {
    setBusy(site.id);
    try {
      await api<ReverseProxySite>(`/reverse-proxy-sites/${site.id}/disable`, 'POST', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      notify(`站点 ${site.domain} 已停用，受管路由已撤回`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  function startEdit(site: ReverseProxySite) {
    setEditingId(site.id);
    setHostId(site.hostId);
    setDomain(site.domain);
    setListenAddress(site.listenAddress);
    setListenPort(String(site.listenPort));
    setUpstreamPort(String(site.upstreamPort));
    setHealthPath(site.healthPath);
    setError('');
  }

  function cancelEdit() {
    setEditingId(null);
    setDomain('');
    setListenAddress('');
    setError('');
  }

  function openTls(site: ReverseProxySite) {
    setTlsSite(site);
    setCertPem('');
    setKeyPem('');
    setTlsPort(String(site.tlsName ? site.listenPort : 443));
    setError('');
  }

  async function submitTls(event: FormEvent) {
    event.preventDefault();
    if (!tlsSite) return;
    setBusy(tlsSite.id);
    try {
      await api<ReverseProxySite>(`/reverse-proxy-sites/${tlsSite.id}/tls`, 'POST', {
        certificatePem: certPem, privateKeyPem: keyPem, listenPort: Number(tlsPort),
      }, { 'Idempotency-Key': createUuid() }, true);
      await api<ReverseProxySite>(`/reverse-proxy-sites/${tlsSite.id}/apply`, 'POST', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      notify(`站点 ${tlsSite.domain} 已切换 HTTPS`);
      setTlsSite(null);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  async function remove(site: ReverseProxySite) {
    if (!window.confirm(`删除站点 ${site.domain}？仅删除记录，不会改动主机上的 Nginx 配置。`)) return;
    setBusy(site.id);
    try {
      await api(`/reverse-proxy-sites/${site.id}`, 'DELETE', undefined, {}, true);
      notify(`站点 ${site.domain} 已删除`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  return <section className="sites-workspace">
    <header className="sites-header">
      <div><h2>网站</h2><p>域名反向代理：登记站点并下发到主机上的受管 Nginx，无需手写 server 块。上游固定为本机 127.0.0.1:端口（可直接指向容器发布端口）。站点可随时「停用」——停用会撤回该主机上由 NaNPanel 管理的路由文件，启用了再下发。SSL 证书将在后续接入。</p></div>
    </header>
    {error && <div className="alert error" role="alert">{error}</div>}
    <div className="sites-grid">
      <form className="card sites-form" onSubmit={submit}>
        <span className="eyebrow">NEW SITE</span>
        <h3>{editingId ? '编辑站点' : '登记站点'}</h3>
        {deployments.length > 0 && <label className="field"><span>从已部署容器带入</span>
          <select value="" onChange={event => applyDeployment(event.target.value)}>
            <option value="">选择已部署容器（自动填主机与上游端口）</option>
            {deployments.map(item => <option key={`${item.hostId}/${item.containerName}`}
              value={`${item.hostId}/${item.containerName}`}>
              {item.containerName} · {item.hostname}{item.hostPort ? ` · 127.0.0.1:${item.hostPort}` : ' · 未发布端口'}
            </option>)}
          </select></label>}
        <label className="field"><span>主机</span>
          <select value={hostId} onChange={event => setHostId(event.target.value)} disabled={!hosts.length}>
            {!hosts.length && <option value="">无可用主机</option>}
            {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}
          </select></label>
        <label className="field"><span>域名</span>
          <input value={domain} onChange={event => setDomain(event.target.value)}
            placeholder="shop.example.com" required /></label>
        <label className="field"><span>监听地址</span>
          <input value={listenAddress} onChange={event => setListenAddress(event.target.value)}
            placeholder="主机的一个 IP，例如 192.0.2.10" required /></label>
        <div className="sites-row">
          <label className="field"><span>监听端口</span>
            <input type="number" min={1} max={65535} value={listenPort}
              onChange={event => setListenPort(event.target.value)} required /></label>
          <label className="field"><span>上游端口</span>
            <input type="number" min={1} max={65535} value={upstreamPort}
              onChange={event => setUpstreamPort(event.target.value)} required /></label>
        </div>
        <label className="field"><span>健康检查路径</span>
          <input value={healthPath} onChange={event => setHealthPath(event.target.value)} placeholder="/" /></label>
        <div className="sites-form-actions">
          <button className="primary" type="submit" disabled={isMock || busy === 'create' || !hostId}>
            {busy === 'create' ? '提交中…' : editingId ? '保存修改' : '创建站点'}
          </button>
          {editingId && <button type="button" className="secondary" disabled={busy === 'create'}
            onClick={cancelEdit}>取消编辑</button>}
        </div>
      </form>

      <div className="card sites-list">
        <div className="sites-list-heading"><h3>已登记站点</h3><span>{sites.length}</span></div>
        {loading && <p className="application-muted">正在读取站点…</p>}
        {!loading && sites.length === 0 && <p className="application-muted">还没有站点。左侧登记域名与上游端口后即可下发。</p>}
        <ul>{sites.map(site => <li key={site.id}>
          <div className="sites-item-copy">
            <strong>{site.domain}{site.tlsName && <span className="sites-badge">HTTPS</span>}</strong>
            <small>{site.hostname} · {site.listenAddress}:{site.listenPort} → 127.0.0.1:{site.upstreamPort}{site.healthPath}</small>
          </div>
          <span className={`sites-state sites-state-${site.state.toLowerCase()}`}>{STATE_LABELS[site.state] ?? site.state}</span>
          <div className="sites-item-actions">
            <button type="button" className="secondary"
              disabled={isMock || busy === site.id || site.state === 'APPLIED'}
              title={site.state === 'APPLIED' ? '已生效站点需先停用后再修改' : '修改域名、地址、端口或健康检查路径'}
              onClick={() => startEdit(site)}>编辑</button>
            <button type="button" className="secondary" disabled={isMock || busy === site.id}
              title="粘贴证书与私钥，切换到受管 HTTPS 终止"
              onClick={() => openTls(site)}>{site.tlsName ? '更新证书' : 'HTTPS'}</button>
            {site.state === 'APPLIED'
              ? <button type="button" className="secondary" disabled={isMock || busy === site.id}
                  onClick={() => void disable(site)}>{busy === site.id ? '处理中…' : '停用'}</button>
              : <button type="button" className="secondary" disabled={isMock || busy === site.id}
                  onClick={() => void apply(site)}>{busy === site.id ? '处理中…' : site.state === 'DISABLED' ? '启用' : '应用'}</button>}
            <button type="button" className="secondary" disabled={isMock || busy === site.id || site.state === 'APPLIED'}
              title={site.state === 'APPLIED' ? '已生效站点需先停用后再删除' : '删除站点记录'}
              onClick={() => void remove(site)}>删除</button>
          </div>
        </li>)}</ul>
      </div>
    </div>
    {tlsSite && <div className="modal-backdrop" onMouseDown={event => {
      if (event.target === event.currentTarget && busy === '') setTlsSite(null);
    }}>
      <section className="modal sites-tls-dialog" role="dialog" aria-modal="true"
        aria-labelledby="sites-tls-title">
        <div className="card-title">
          <div><span className="eyebrow">MANAGED TLS</span>
            <h2 id="sites-tls-title">为 {tlsSite.domain} 启用 HTTPS</h2>
            <p>证书与私钥写入主机侧受管目录（root 拥有、0600），Core 不保存私钥。提交后会立即重新下发路由并以 443 监听。</p></div>
          <button type="button" className="icon-button" aria-label="关闭" disabled={busy !== ''}
            onClick={() => setTlsSite(null)}>×</button>
        </div>
        <form onSubmit={submitTls}>
          <label className="field"><span>证书 PEM</span>
            <textarea value={certPem} onChange={event => setCertPem(event.target.value)} rows={6}
              placeholder="-----BEGIN CERTIFICATE-----" required /></label>
          <label className="field"><span>私钥 PEM</span>
            <textarea value={keyPem} onChange={event => setKeyPem(event.target.value)} rows={6}
              placeholder="-----BEGIN PRIVATE KEY-----" required /></label>
          <label className="field"><span>HTTPS 端口</span>
            <input type="number" min={1} max={65535} value={tlsPort}
              onChange={event => setTlsPort(event.target.value)} required /></label>
          <div className="form-actions">
            <button type="button" className="secondary" disabled={busy !== ''}
              onClick={() => setTlsSite(null)}>取消</button>
            <button className="primary" type="submit" disabled={busy !== ''}>
              {busy !== '' ? '下发中…' : '启用 HTTPS'}</button>
          </div>
        </form>
      </section>
    </div>}
  </section>;
}