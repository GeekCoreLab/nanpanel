import { StrictMode, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import Topology from './Topology';
import '@xyflow/react/dist/style.css';
import '@xterm/xterm/css/xterm.css';
import { api, ApiError, isMock, mockBusiness } from './lib/api';
import { DEMO_PASSWORD } from './lib/mock';
import { createUuid } from './lib/uuid.ts';
import { agentMaintenanceCommand, type AgentMaintenanceMode } from './lib/agent-maintenance.ts';
import type { Host, HostPage, Agent, AgentInstallation, AgentUpdateOperation, SystemService, SystemServicePage, Operation, OperationPage, SessionContext, AdminOverview, AdminUser, AdminUserPage, PlatformSettings, AuditEventPage, WebSessionList, ApiKeyList, CreatedApiKey, BuiltinRoleList, AgentEnrollmentCreated, HostHelperOperationRequest, HostNetworkInventory, HostNetworkInterface, HostNetworkRoute, HostFileListing, SshProbeResult, SshSystemdStatusResult, SshSystemdJournalResult, HostSshConfig, SshHostKeyProbe, BastionSession, BastionSessionTicket, BastionCommand, BastionRecordingChunk, KubernetesClusterList, KubernetesRuntimeDiscovery, KubernetesRuntimeDiscoveryList, KubernetesClusterImportRequest, KubernetesClusterSummary, KubernetesClusterNodeList, KubernetesClusterNode, KubernetesManagedDeployment, KubernetesManagedDeploymentCreateRequest, KubernetesManagedDeploymentScaleRequest, KubernetesManagedDeploymentList, KubernetesTopologyNamespaceSnapshot, KubernetesApplicationRelease, KubernetesApplicationReleaseCreateRequest, KubernetesApplicationReleaseList, HostApplicationRelease, HostApplicationReleaseCreateRequest, HostApplicationReleaseList, K3sRolloutList, K3sRolloutPlan, K3sInstallationList, K3sInstallationPlan } from './lib/types';
import './api-keys.css';
import './kubernetes-clusters.css';
import './kubernetes-upgrade.css';
import './host-application-releases.css';
import './style.css';
import './kubernetes-management.css';
const nav = [['overview', '◫', '总览'], ['hosts', '▤', '主机资产'], ['topology', '⌘', '网络拓扑'], ['clusters', '☸', 'Kubernetes 集群'], ['operations', '◷', '操作任务'], ['users', '♙', '管理员'], ['audit', '☷', '审计日志'], ['settings', '⚙', '平台设置'], ['account', '◎', '账号与会话']];
type KubernetesWorkspace = 'overview' | 'clusters' | 'nodes' | 'upgrades' | 'installation';
const kubernetesTabs: { id: Exclude<KubernetesWorkspace, 'installation'>; icon: string; label: string; description: string }[] = [
 { id: 'overview', icon: '◫', label: '集群概览', description: '健康状态与资源入口' },
 { id: 'clusters', icon: '▦', label: '集群管理', description: '登记、连接与资源' },
 { id: 'nodes', icon: '▤', label: '节点管理', description: 'Node 状态与主机映射' },
 { id: 'upgrades', icon: '↻', label: '集群升级', description: '逐节点升级计划' },
];
const labels: Record<string, string> = { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未知 / 结果未知', ACTIVE: '运行中', INACTIVE: '未运行', AVAILABLE: '可用', UNAVAILABLE: '不可用', SUCCEEDED: '成功', ACCEPTED: '已受理', FAILED: '失败', FORCE_TERMINATED: '已强制阻断', RUNNING: '执行中', PENDING: '等待中', CANCELLED: '已取消', DISABLED: '已禁用', DENIED: '已拒绝' };
function Badge({ value }: { value: string }) { return <span className={`badge ${value.toLowerCase()}`}><i />{labels[value] ?? value}</span>; }
function Empty({ children = '暂无数据' }: { children?: ReactNode }) { return <div className="empty"><span>▧</span><p>{children}</p></div>; }
function Field({ label, children }: { label: string; children: ReactNode }) { return <label className="field"><span>{label}</span>{children}</label>; }
function time(value: string | null | undefined) { return value ? new Date(value).toLocaleString('zh-CN') : '—'; }
function useResource<T>(path: string, interval = 0, realInHybrid = false) {
 const [data, setData] = useState<T>(); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [tick, setTick] = useState(0);
 useEffect(() => { if (!path) { setData(undefined); setError(''); setLoading(false); return; } let active = true; setLoading(true); setError(''); const load = () => api<T>(path, 'GET', undefined, {}, realInHybrid).then(r => { if (active) { setData(r.data); setError(''); } }).catch(e => { if (active) setError(e.message); }).finally(() => { if (active) setLoading(false); }); void load(); const timer = interval ? setInterval(load, interval) : undefined; return () => { active = false; clearInterval(timer); }; }, [path, interval, tick, realInHybrid]);
 return { data, error, loading, refresh: () => setTick(t => t + 1) };
}
function ResourceState({ loading, error }: { loading: boolean; error: string }) { return error ? <div className="alert error" role="alert">{error}</div> : loading ? <p className="muted">正在读取数据…</p> : null; }
function App() {
 const [session, setSession] = useState<SessionContext | null>(null); const [ready, setReady] = useState(false); const [page, setPage] = useState(location.hash.slice(1) || 'overview'); const [message, setMessage] = useState(''); const [reauth, setReauth] = useState(false);
 useEffect(() => { api<SessionContext>('/auth/me').then(r => setSession(r.data)).catch(() => {}).finally(() => setReady(true)); const hash = () => setPage(location.hash.slice(1) || 'overview'); window.addEventListener('hashchange', hash); return () => window.removeEventListener('hashchange', hash); }, []);
 useEffect(() => { const handler = (event: Event) => { const e = (event as CustomEvent<ApiError>).detail; if (e.status === 401 && e.code !== 'INVALID_CREDENTIALS') setSession(null); if (e.code === 'PASSWORD_CHANGE_REQUIRED') location.hash = 'account'; if (e.code === 'REAUTHENTICATION_REQUIRED') setReauth(true); }; window.addEventListener('api-error', handler); return () => window.removeEventListener('api-error', handler); }, []);
 useEffect(() => { if (message) { const timer = setTimeout(() => setMessage(''), 4500); return () => clearTimeout(timer); } }, [message]);
 async function logout() { try { await api('/auth/logout', 'POST'); setSession(null); } catch (e) { setMessage((e as Error).message); } }
 if (!ready) return <div className="boot">NaNPanel <span>正在初始化控制台…</span></div>;
 if (!session) return <Login onLogin={setSession} />;
 const forced = session.user.mustChangePassword;
 return <div className="app"><aside className="sidebar"><a className="brand" href="#overview"><b className="brand-icon">N</b><strong>NaNPanel<span>INFRASTRUCTURE CONSOLE</span></strong></a><div className="workspace"><span className="workspace-icon">⌂</span><div>我的基础设施<small>个人工作空间</small></div><span className="muted">⌄</span></div><p className="nav-label">工作空间</p><nav>{nav.slice(0, 5).map(n => <a aria-current={page === n[0] ? 'page' : undefined} className={page === n[0] ? 'selected' : ''} href={`#${n[0]}`} key={n[0]}><span>{n[1]}</span>{n[2]}{n[0] === 'topology' && <small>LAB</small>}</a>)}<p className="nav-label">平台管理</p>{nav.slice(5).map(n => <a aria-current={page === n[0] ? 'page' : undefined} className={page === n[0] ? 'selected' : ''} href={`#${n[0]}`} key={n[0]}><span>{n[1]}</span>{n[2]}</a>)}</nav><div className="sidebar-bottom"><div className="mode-dot" />{isMock ? 'Mock 演示环境' : mockBusiness ? '真实认证与主机服务 · 其他业务模拟' : '真实 API 模式'}<small>v0.1.0 · {isMock ? '数据仅保存在内存' : '同源 Cookie 会话'}</small></div><div className="profile"><div className="avatar">{session.user.displayName.slice(0, 1)}</div><div>{session.user.displayName}<small>ADMIN 管理员</small></div><button className="icon-button" title="退出登录" onClick={logout}>↪</button></div></aside><div className="shell"><header className="topbar"><div><span className="muted">工作空间</span><span className="slash">/</span>{nav.find(n => n[0] === page)?.[2] ?? '总览'}</div><div className="top-actions"><span className="environment">{isMock ? '◉ Mock 模式' : mockBusiness ? '◉ 真实认证与主机服务 / 其他业务 Mock' : '◉ Live API'}</span><span className="avatar small">{session.user.displayName.slice(0, 1)}</span></div></header><main><div className="demo-banner"><span>◇</span><div>{isMock ? '当前为独立演示环境，所有主机、服务与操作均为模拟数据。刷新页面后重置。' : mockBusiness ? '登录、主机、Agent、systemd、拓扑草稿与 Kubernetes 集群使用真实后端；其他尚未接入的业务仍为模拟数据。' : '已连接真实 API。尚未实现的业务接口会返回错误。'}</div><span className="mono">{isMock ? 'MOCK' : 'LIVE'}</span></div>{forced ? <><div className="alert">请先修改初始密码，完成后重新登录。</div><Account onLogout={() => setSession(null)} allowApiKeys={false} /></> : <>{page === 'overview' && <Overview />}{page === 'hosts' && <Hosts notify={setMessage} />}{page === 'topology' && <Topology />}{page === 'clusters' && <KubernetesClusters notify={setMessage} />}{page === 'operations' && <Operations />}{page === 'users' && <Users self={session.user.id} notify={setMessage} />}{page === 'audit' && <Audit />}{page === 'settings' && <Settings notify={setMessage} />}{page === 'account' && <Account onLogout={() => setSession(null)} />}{!nav.some(n => n[0] === page) && <Empty>页面不存在，请通过左侧导航访问。</Empty>}</>}<footer>NaNPanel <span>控制面与数据面分离 · 让基础设施清晰可见</span><span>API 0.1.0-draft.2</span></footer></main></div>{message && <div className="toast" role="status">{message}</div>}{reauth && <div className="modal-backdrop"><div className="modal"><h2>重新验证身份</h2><p>此操作需要最近 5 分钟内的密码验证。验证后请重新读取资源，并手动确认提交。</p><Login compact username={session.user.username} onLogin={s => { setSession(s); setReauth(false); setMessage('验证成功，请刷新资源后重新确认操作'); }} /><button className="secondary" onClick={() => setReauth(false)}>取消</button></div></div>}</div>;
}
function Login({ onLogin, compact = false, username }: { onLogin: (s: SessionContext) => void; compact?: boolean; username?: string }) {
 const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
 async function submit(e: FormEvent<HTMLFormElement>) { e.preventDefault(); const form = new FormData(e.currentTarget); setBusy(true); setError(''); try { await api('/auth/csrf'); const r = await api<SessionContext>('/auth/login', 'POST', Object.fromEntries(form)); onLogin(r.data); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
 const form = <form onSubmit={submit}><Field label="用户名"><input name="username" autoComplete="username" required defaultValue={username ?? (isMock ? 'admin' : '')} readOnly={!!username} /></Field><Field label="密码"><input name="password" type="password" autoComplete="current-password" required defaultValue={isMock ? DEMO_PASSWORD : ''} /></Field>{error && <div className="alert error" role="alert">{error}</div>}<button className="primary full" disabled={busy}>{busy ? '正在验证…' : compact ? '验证身份' : '进入控制台 →'}</button></form>;
 return compact ? form : <div className="login"><div className="login-art"><a className="brand" href="#"><b className="brand-icon">N</b><strong>NaNPanel</strong></a><div><span className="eyebrow">YOUR INFRASTRUCTURE, CONNECTED.</span><h1>连接分散的节点，<br />掌控完整的全局。</h1><p>从云端到边缘，让主机、服务与网络<br />在同一个控制台中清晰可见。</p><div className="login-grid"><span>PUBLIC GATEWAY</span><i>┄┄┄</i><span>NAT HOST</span><i>┄┄┄</i><span>EDGE NODE</span></div></div><small>混合云基础设施控制面 · Java Core + Rust Agent</small></div><div className="login-panel"><div className="login-card"><span className="eyebrow">WELCOME BACK</span><h2>登录你的工作空间</h2><p className="muted">管理基础设施，从这里开始。</p>{isMock && <div className="alert">Mock 演示已预填账号密码，可直接登录。所有操作仅模拟。</div>}{form}<small className="muted">{isMock ? `演示账号：admin / ${DEMO_PASSWORD}` : '请使用部署时创建的管理员账号。'}</small></div></div></div>;
}
function PageHeading({ title, description, action }: { title: string; description: string; action?: ReactNode }) { return <div className="page-heading"><div><h1>{title}</h1><p>{description}</p></div>{action}</div>; }
function Overview() {
 const r = useResource<AdminOverview>('/admin/overview', 5000, true); const h = useResource<HostPage>('/hosts', 0, true); const o = useResource<OperationPage>('/operations', 2000, true); const a = useResource<AuditEventPage>('/admin/audit-events');
 const total = r.data?.hosts.total ?? 0; const online = r.data?.hosts.online ?? 0; const offline = r.data?.hosts.offline ?? 0; const unknown = r.data?.hosts.unknown ?? 0; const onlinePercent = total ? online / total * 100 : 0; const offlinePercent = total ? (online + offline) / total * 100 : 0;
 return <><PageHeading title="基础设施总览" description="你好，管理员。你的云端与边缘，一览无余。" action={<button className="secondary" onClick={() => { r.refresh(); h.refresh(); a.refresh(); }}>↻ 刷新数据</button>} /><ResourceState {...r} /><div className="stats">{[['主机总数', r.data?.hosts.total ?? '—', `${r.data?.hosts.online ?? '—'} 台在线`, '▤', 'blue'], ['在线主机', r.data?.hosts.online ?? '—', '管理通道已连接', '◎', 'green'], ['离线 / 未知', r.data ? r.data.hosts.offline + r.data.hosts.unknown : '—', '需要关注的节点', '△', 'amber'], ['进行中的任务', r.data ? r.data.operations.pending + r.data.operations.running : '—', `${r.data?.operations.unknown ?? 0} 个结果未知`, '◷', 'purple']].map(x => <div className="stat" key={String(x[0])}><div><span>{x[0]}</span><b className={`stat-icon ${x[4]}`}>{x[3]}</b></div><strong>{x[1]}</strong><small>{x[2]}</small></div>)}</div><div className="overview-grid"><section className="card"><div className="card-title"><div><h2>主机分布</h2><p>管理通道状态 · 最近观测</p></div><a href="#hosts">查看全部 ↗</a></div><ResourceState {...h} />{r.data && h.data && <div className="host-distribution"><div className="donut" style={{ background: `conic-gradient(#5387f4 0 ${onlinePercent}%, #e7b65b 0 ${offlinePercent}%, #e9edf4 0)` }}><div><strong>{total}</strong><span>主机节点</span></div></div><div className="legend">{[['ONLINE', '管理通道在线', online], ['OFFLINE', '管理通道离线', offline], ['UNKNOWN', '尚未确认状态', unknown]].map(([s, text, amount]) => <div key={String(s)}><Badge value={String(s)} /><span>{text}</span><b>{amount}</b></div>)}</div></div>}<div className="card-note">ⓘ 管理通道状态不代表业务流量健康状态</div></section><section className="card"><div className="card-title"><div><h2>控制面信息</h2><p>轻量控制，按需扩展</p></div><span className="square-icon">⌘</span></div><dl className="info-list"><div><dt>运行环境</dt><dd>{isMock ? '浏览器内 Mock' : '同源 API'}</dd></div><div><dt>主控架构</dt><dd>Java / Quarkus JVM</dd></div><div><dt>边缘组件</dt><dd>Rust Agent + root Helper</dd></div><div><dt>管理员账号</dt><dd>{r.data?.activeAdminUsers ?? '—'} 个 ACTIVE</dd></div><div><dt>最近观测</dt><dd>{time(r.data?.observedAt)}</dd></div></dl></section></div><section className="card"><div className="card-title"><div><h2>主机资产</h2><p>异构节点，统一管理</p></div><a href="#hosts">管理主机 →</a></div><HostTable hosts={h.data?.items ?? []} /></section><div className="overview-grid lower"><section className="card"><div className="card-title"><h2>最近操作</h2><a href="#operations">全部任务 ↗</a></div>{!o.data?.items.length ? <Empty>还没有操作任务，前往主机资产体验服务重启</Empty> : o.data.items.slice(0, 4).map(op => <div className="activity" key={op.id}><span className="activity-icon">↻</span><div><b>systemd 服务重启</b><small>{op.reason}</small></div><Badge value={op.status} /></div>)}</section><section className="card"><div className="card-title"><h2>平台动态</h2><a href="#audit">审计日志 ↗</a></div>{a.data?.items.slice(0, 4).map(e => <div className="activity" key={e.id}><span className="activity-icon green">✓</span><div><b>{e.summary}</b><small>{time(e.occurredAt)}</small></div></div>)}</section></div></>;
}
function HostTable({ hosts, onSelect }: { hosts: Host[]; onSelect?: (h: Host) => void }) { return <div className="table-wrap"><table><thead><tr><th>主机名称</th><th>状态</th><th>操作系统 / 架构</th><th>最近心跳</th><th /></tr></thead><tbody>{hosts.map((h, i) => <tr key={h.id}><td><div className="host-name"><span className={`host-icon ${i % 2 ? 'purple' : 'blue'}`}>▤</span><div><b>{h.hostname}</b><small className="mono">{h.id.slice(0, 8)}</small></div></div></td><td><Badge value={h.status} /></td><td>{h.os ?? '未知'} <span className="muted">/ {h.arch ?? '未知'}</span></td><td className="muted">{time(h.lastSeenAt)}</td><td>{onSelect ? <button className="link" onClick={() => onSelect(h)}>查看详情 →</button> : <a href="#hosts">详情 ↗</a>}</td></tr>)}</tbody></table>{!hosts.length && <Empty>没有符合条件的主机</Empty>}</div>; }
function shellQuote(value: string) { return "'" + value.replaceAll("'", "'\\''") + "'"; }
function enrollmentInstallCommand(enrollment: AgentEnrollmentCreated) {
 const origin = window.location.origin; const fingerprint = enrollment.controllerFingerprint.toLowerCase();
 const inner = [
  'set -Eeuo pipefail',
  'work=$(mktemp -d /tmp/nanpanel-agent-bootstrap.XXXXXX)',
  `trap 'rm -rf -- "$work"' EXIT`,
  `curl --insecure --fail --silent --show-error --location ${shellQuote(origin + '/api/v1/agent-controller-cert')} -o "$work/controller.pem"`,
  `fingerprint=$(openssl x509 -in "$work/controller.pem" -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')`,
  `test "$fingerprint" = ${shellQuote(fingerprint)} || { printf "Controller certificate fingerprint mismatch\\n" >&2; exit 1; }`,
  `curl --cacert "$work/controller.pem" --fail --silent --show-error --location ${shellQuote(origin + '/agent-install.sh')} -o "$work/agent-install.sh"`,
  `bash "$work/agent-install.sh" ${[origin, enrollment.hostId, enrollment.agentId, enrollment.token, fingerprint, String(enrollment.gatewayPort), (enrollment.relayEndpoints ?? []).join(','), (enrollment.relayFallback?.endpoints ?? []).join(','), enrollment.relayFallback?.signature ?? '', enrollment.relayFallback?.publicKey ?? ''].map(shellQuote).join(' ')}`,
 ].join('; ');
 return `sudo bash -c ${shellQuote(inner)}`;
}
function Hosts({ notify }: { notify: (s: string) => void }) {
 const [status, setStatus] = useState(''); const [query, setQuery] = useState(''); const [selected, setSelected] = useState<Host>(); const [cursors, setCursors] = useState<(string | null)[]>([null]); const [adding, setAdding] = useState(false); const params = new URLSearchParams(); if (status) params.set('status', status); if (query.trim()) params.set('search', query.trim()); const cursor = cursors[cursors.length - 1]; if (cursor) params.set('cursor', cursor); const queryString = params.toString(); const r = useResource<HostPage>('/hosts' + (queryString ? `?${queryString}` : ''), 0, true); const visibleHosts = r.data?.items ?? [];
 return <><PageHeading title="主机资产" description="统一查看 Linux 主机、Agent 能力与系统服务。" action={<div className="host-heading-actions"><button className="primary" onClick={() => setAdding(true)}>＋ 添加主机</button><button className="secondary" onClick={r.refresh}>↻ 刷新</button></div>} /><section className="card"><div className="toolbar"><input aria-label="搜索主机" placeholder="⌕ 搜索全部主机名称…" value={query} onChange={e => { setQuery(e.target.value); setCursors([null]); }} /><select aria-label="主机状态" value={status} onChange={e => { setStatus(e.target.value); setCursors([null]); }}><option value="">全部状态</option>{['ONLINE', 'OFFLINE', 'UNKNOWN'].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select><span className="muted">本页 {visibleHosts.length} 台</span></div><ResourceState {...r} /><HostTable hosts={visibleHosts} onSelect={setSelected} /><div className="form-actions"><button className="secondary" disabled={cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>上一页</button><span className="muted">第 {cursors.length} 页 · 默认每页 20 台</span><button className="secondary" disabled={!r.data?.nextCursor} onClick={() => { if (r.data?.nextCursor) setCursors(value => [...value, r.data!.nextCursor]); }}>下一页</button></div></section><HostApplicationReleases notify={notify} />{selected && <HostDetail host={selected} close={() => setSelected(undefined)} notify={notify} />}{adding && <HostEnrollmentDialog close={() => setAdding(false)} onCreated={() => { setStatus(''); setCursors([null]); r.refresh(); }} />}</>;
}

function HostApplicationReleases({ notify }: { notify: (message: string) => void }) {
 const hosts = useResource<HostPage>(isMock ? '' : '/hosts?limit=100', 30000, true);
 const releases = useResource<HostApplicationReleaseList>(isMock ? '' : '/host-application-releases', 5000, true);
 const [selected, setSelected] = useState<string[]>([]);
 const [addresses, setAddresses] = useState<Record<string, string>>({});
 const [busy, setBusy] = useState('');
 const [error, setError] = useState('');
 const required = ['container.deploy', 'container.start', 'container.stop', 'nginx.route.set'];
 const selectable = (host: Host) => host.status === 'ONLINE' && required.every(capability => host.capabilities.includes(capability));

 async function create(event: FormEvent<HTMLFormElement>) {
  event.preventDefault();
  const form = event.currentTarget;
  const values = new FormData(form);
  const targets = selected.flatMap(hostId => {
   const host = hosts.data?.items.find(item => item.id === hostId);
   return host ? [{ hostId: host.id, listenAddress: addresses[host.id]?.trim() ?? '', expectedGeneration: host.generation }] : [];
  });
  if (!targets.length || targets.some(target => !target.listenAddress)) {
   setError('请选择在线且报告所需能力的主机，并为每台填写已分配的监听地址。'); return;
  }
  const payload: HostApplicationReleaseCreateRequest = {
   name: String(values.get('name') ?? '').trim(), stableImage: String(values.get('stableImage') ?? '').trim(),
   image: String(values.get('image') ?? '').trim(), containerPort: Number(values.get('containerPort')),
   serverName: String(values.get('serverName') ?? '').trim(), listenPort: Number(values.get('listenPort')),
   healthPath: String(values.get('healthPath') ?? '').trim(), batchSize: Number(values.get('batchSize')),
   targets, reason: String(values.get('reason') ?? '').trim(),
  };
  setBusy('create'); setError('');
  try {
   const result = await api<HostApplicationRelease>('/host-application-releases', 'POST', payload, {}, true);
   form.reset(); setSelected([]); setAddresses({}); releases.refresh();
   notify(`已创建 ${payload.name} 的普通主机蓝绿发布草稿 ${result.data.id.slice(0, 8)}`);
  } catch (cause) { setError((cause as Error).message); }
  finally { setBusy(''); }
 }

 async function act(plan: HostApplicationRelease, action: 'start' | 'pause' | 'resume' | 'reconcile' | 'rollback') {
  setBusy(`${plan.id}:${action}`); setError('');
  try {
   await api<HostApplicationRelease>(`/host-application-releases/${encodeURIComponent(plan.id)}/${action}`, 'POST', {}, {}, true);
   releases.refresh();
   notify(`${plan.name}：${({ start: '已启动', pause: '已暂停', resume: '已批准下一批', reconcile: '已请求对账', rollback: '已请求回滚' })[action]}`);
  } catch (cause) { setError((cause as Error).message); releases.refresh(); }
  finally { setBusy(''); }
 }

 return <section className="card host-release-panel">
  <div className="card-title"><div><span className="eyebrow">DOCKER / PODMAN</span><h2>普通主机蓝绿发布</h2><p>通过 Agent 和 root Helper 部署候选容器，先验证稳定路由，再切换并检查实际 HTTP 健康响应。每批完成后需人工批准下一批。</p></div><button className="secondary" disabled={releases.loading || isMock} onClick={releases.refresh}>{releases.loading ? '读取中…' : '刷新发布'}</button></div>
  {isMock && <div className="alert">Mock 环境不连接真实 Agent、Helper 或 Nginx。</div>}
  {error && <div className="alert error" role="alert">{error}</div>}
  {!isMock && <form className="host-release-form" onSubmit={event => void create(event)}>
   <div className="host-release-grid">
    <Field label="应用名称"><input name="name" required maxLength={63} pattern="[a-z0-9]([a-z0-9-]*[a-z0-9])?" placeholder="例如 web" /></Field>
    <Field label="当前稳定镜像"><input name="stableImage" required maxLength={512} placeholder="registry.example/web:1.9.0" /></Field>
    <Field label="候选镜像"><input name="image" required maxLength={512} placeholder="registry.example/web:2.0.0" /></Field>
    <Field label="容器端口"><input name="containerPort" type="number" min={1} max={65535} defaultValue={8080} required /></Field>
    <Field label="HTTP 主机名"><input name="serverName" required maxLength={253} pattern="[A-Za-z0-9.-]+" placeholder="app.example.com" /></Field>
    <Field label="Nginx 监听端口"><input name="listenPort" type="number" min={1024} max={65535} defaultValue={18080} required /></Field>
    <Field label="健康检查路径"><input name="healthPath" required maxLength={256} pattern="/[A-Za-z0-9/._~-]*" defaultValue="/health" /></Field>
    <Field label="每批主机数"><input name="batchSize" type="number" min={1} max={50} defaultValue={1} required /></Field>
   </div>
   <div className="host-release-hosts"><div className="section-heading"><h3>按顺序选择主机和监听地址</h3><button type="button" className="secondary" onClick={hosts.refresh}>刷新主机</button></div><ResourceState {...hosts} />
    {(hosts.data?.items ?? []).map(host => {
     const order = selected.indexOf(host.id);
     const checked = order >= 0;
     return <label className={`host-release-target ${checked ? 'selected' : ''}`} key={host.id}>
     <input type="checkbox" disabled={!selectable(host)} checked={checked} onChange={event => setSelected(current => event.target.checked ? [...current, host.id] : current.filter(id => id !== host.id))} />
     <span className="host-release-target-name"><strong>{checked ? `${order + 1}. ` : ''}{host.hostname}</strong><small className="mono">{host.id.slice(0, 8)} · generation {host.generation}</small><small>{selectable(host) ? '在线并报告容器与 Nginx 路由能力' : `${host.status} · 缺少所需能力或 Agent 在线状态`}</small></span>
     <input aria-label={`${host.hostname} Nginx 监听地址`} disabled={!checked} value={addresses[host.id] ?? ''} onChange={event => setAddresses(current => ({ ...current, [host.id]: event.target.value }))} placeholder="此主机已分配的 IPv4 / IPv6 地址" />
     </label>;
    })}
    {!hosts.loading && !hosts.error && !hosts.data?.items.length && <p className="muted">尚无主机资产。</p>}
   </div>
   <Field label="发布原因"><textarea name="reason" required maxLength={500} placeholder="说明本次发布的变更和验证目标" /></Field>
   <div className="alert">每台主机必须预先配置 Helper 的 NANPANEL_NGINX_INCLUDE_DIR，并确保 Nginx 主配置精确 include 该目录的 *.conf。候选流量通过专属 HTTP 监听端口接入；防火墙策略仍由运维人员配置。</div>
   <button className="primary" disabled={!!busy || isMock || selected.length === 0}>{busy === 'create' ? '正在保存…' : '创建主机发布草稿'}</button>
  </form>}
  <ResourceState {...releases} />
  <div className="host-release-list">{(releases.data?.items ?? []).map(plan => <article className="host-release-plan" key={plan.id}>
   <div className="host-release-plan-heading"><div><strong>{plan.name} · {plan.stableImage} → {plan.image}</strong><small>{plan.serverName}:{plan.listenPort} · {plan.targets.length} 台主机 · 每批 {plan.batchSize} 台</small></div><Badge value={plan.state} /></div>
   {plan.errorMessage && <div className="alert error">{plan.errorCode}: {plan.errorMessage}</div>}
   <div className="table-wrap"><table><thead><tr><th>顺序</th><th>主机</th><th>状态</th><th>稳定 / 候选端口</th><th>最近步骤</th></tr></thead><tbody>{plan.targets.map(target => <tr key={`${plan.id}:${target.ordinal}`}><td>{target.ordinal + 1}</td><td className="mono">{target.hostId.slice(0, 8)} · {target.listenAddress}</td><td>{target.state}</td><td>{target.stablePort} / {target.candidatePort}</td><td>{target.detail ?? '—'}{target.operationId && <small className="mono">操作 {target.operationId.slice(0, 8)}</small>}</td></tr>)}</tbody></table></div>
   <div className="host-release-actions">
    {plan.state === 'DRAFT' && <button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'start')}>启动</button>}
    {plan.state === 'RUNNING' && <button className="secondary" disabled={!!busy || isMock} onClick={() => void act(plan, 'pause')}>暂停</button>}
    {plan.state === 'PAUSED' && <button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'resume')}>{plan.activeOrdinal > 0 && plan.activeOrdinal < plan.targets.length && plan.activeOrdinal % plan.batchSize === 0 ? '批准下一批' : '继续发布'}</button>}
    {plan.state === 'UNKNOWN' && <button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'reconcile')}>对账</button>}
    {['PAUSED', 'SUCCEEDED', 'FAILED'].includes(plan.state) && <button className="danger" disabled={!!busy || isMock} onClick={() => { if (window.confirm(`按逆序恢复 ${plan.name} 的稳定版本？`)) void act(plan, 'rollback'); }}>回滚已完成主机</button>}
   </div>
   <small className="mono">发布 {plan.id} · {plan.reason} · 创建于 {time(plan.createdAt)}</small>
  </article>)}{!releases.loading && !releases.error && !releases.data?.items.length && <p className="muted">暂无普通主机发布计划。</p>}</div>
 </section>;
}
function HostEnrollmentDialog({ close, onCreated }: { close: () => void; onCreated: () => void }) {
 const [hostname, setHostname] = useState(window.location.hostname);
 const [enrollment, setEnrollment] = useState<AgentEnrollmentCreated>();
 const [error, setError] = useState('');
 const [busy, setBusy] = useState(false);
 const [copied, setCopied] = useState(false);
 const [sshReason, setSshReason] = useState('');
 const [sshBusy, setSshBusy] = useState(false);
 const [sshError, setSshError] = useState('');
 const [sshOperation, setSshOperation] = useState<Operation>();
 const [sshKey, setSshKey] = useState('');
 const [directAddress, setDirectAddress] = useState(''); const [directPort, setDirectPort] = useState('22');
 const [directUsername, setDirectUsername] = useState('root');
 const [directAuthMethod, setDirectAuthMethod] = useState<'PASSWORD' | 'PRIVATE_KEY'>('PASSWORD');
 const [directPassword, setDirectPassword] = useState(''); const [directPrivateKey, setDirectPrivateKey] = useState('');
 const [directPassphrase, setDirectPassphrase] = useState(''); const [directConfigured, setDirectConfigured] = useState(false);
 const [directProbe, setDirectProbe] = useState<SshHostKeyProbe>(); const [directProbeReason, setDirectProbeReason] = useState('');
 const [directTrusted, setDirectTrusted] = useState(false); const [directBusy, setDirectBusy] = useState(false);

 useEffect(() => {
  if (!sshOperation || ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(sshOperation.status)) return;
  let active = true;
  const timer = window.setInterval(async () => {
   try {
    const result = await api<Operation>(`/operations/${sshOperation.id}`, 'GET', undefined, {}, true);
    if (active) {
     setSshOperation(result.data);
     if (result.data.status === 'FAILED') setSshKey('');
    }
   } catch { /* The operation remains available in the task list. */ }
  }, 2000);
  return () => { active = false; window.clearInterval(timer); };
 }, [sshOperation?.id, sshOperation?.status]);

 async function create(e: FormEvent) {
  e.preventDefault(); setBusy(true); setError('');
  if (directAddress.trim() && ((directAuthMethod === 'PASSWORD' && !directPassword) ||
      (directAuthMethod === 'PRIVATE_KEY' && !directPrivateKey.trim()))) {
   setError('填写 SSH 地址时也需要提供对应的密码或私钥。'); setBusy(false); return;
  }
  try {
   await api('/auth/csrf');
   const result = await api<AgentEnrollmentCreated>('/agent-enrollments', 'POST', { hostname: hostname.trim() }, {}, true);
   setEnrollment(result.data); onCreated();
   if (directAddress.trim()) await saveDirectSsh(result.data.hostId);
  } catch (e) { setError((e as Error).message); }
  finally { setBusy(false); }
 }

 async function saveDirectSsh(hostId = enrollment?.hostId) {
  if (!hostId) return;
  setDirectBusy(true); setSshError('');
  try {
   await api('/auth/csrf');
   await api(`/hosts/${hostId}/ssh/config`, 'PUT', {
    address: directAddress.trim(), port: Number(directPort), username: directUsername.trim(),
    authMethod: directAuthMethod,
    password: directAuthMethod === 'PASSWORD' ? directPassword : null,
    privateKey: directAuthMethod === 'PRIVATE_KEY' ? directPrivateKey : null,
    privateKeyPassphrase: directAuthMethod === 'PRIVATE_KEY' && directPassphrase ? directPassphrase : null,
   }, {}, true);
   setDirectPassword(''); setDirectPrivateKey(''); setDirectPassphrase('');
   setDirectConfigured(true); setDirectTrusted(false); setDirectProbe(undefined);
  } catch (error) { setSshError((error as Error).message); }
  finally { setDirectBusy(false); }
 }

 async function probeDirectSsh() {
  if (!enrollment) return;
  setDirectBusy(true); setSshError('');
  try {
   await api('/auth/csrf');
   const result = await api<SshHostKeyProbe>(`/hosts/${enrollment.hostId}/ssh/host-key/probe`, 'POST', { reason: directProbeReason.trim() }, {}, true);
   setDirectProbe(result.data); setDirectTrusted(result.data.matchesTrustedFingerprint);
  } catch (error) { setSshError((error as Error).message); }
  finally { setDirectBusy(false); }
 }

 async function trustDirectSsh() {
  if (!enrollment || !directProbe) return;
  setDirectBusy(true); setSshError('');
  try {
   await api(`/hosts/${enrollment.hostId}/ssh/host-key/trust`, 'POST', {
    fingerprint: directProbe.fingerprint, reason: directProbeReason.trim(),
   }, {}, true);
   setDirectTrusted(true);
  } catch (error) { setSshError((error as Error).message); }
  finally { setDirectBusy(false); }
 }

 async function copyCommand() {
  if (!enrollment) return;
  try { await navigator.clipboard.writeText(enrollmentInstallCommand(enrollment)); setCopied(true); }
  catch { setError('浏览器未允许剪贴板访问，请选择下方命令并手动复制。'); }
 }

 async function installOverSsh(e: FormEvent) {
  e.preventDefault(); if (!enrollment) return;
  setSshBusy(true); setSshError('');
  const key = sshKey || createUuid(); setSshKey(key);
  try {
   await api('/auth/csrf');
   const result = await api<Operation>(`/hosts/${enrollment.hostId}/ssh/agent/install`, 'POST', {
    enrollmentId: enrollment.enrollmentId, agentId: enrollment.agentId,
    token: enrollment.token, reason: sshReason.trim(),
   }, { 'Idempotency-Key': key }, true);
   setSshOperation(result.data);
   if (result.data.status === 'FAILED') setSshKey('');
  } catch (e) { setSshError((e as Error).message); }
  finally { setSshBusy(false); }
 }

 const directSshFields = <>
  <p className="muted">保存目标地址和认证凭据，完成 host key 人工核对后可在此安装 Agent。</p>
  <Field label="SSH 地址（可选）"><input maxLength={253} value={directAddress} onChange={e => setDirectAddress(e.target.value)} placeholder="例如 192.0.2.10 或 host.example.net" /></Field>
  {directAddress.trim() && <>
   <div className="ssh-form-grid"><Field label="端口"><input type="number" min="1" max="65535" required value={directPort} onChange={e => setDirectPort(e.target.value)} /></Field><Field label="用户名"><input required maxLength={128} value={directUsername} onChange={e => setDirectUsername(e.target.value)} /></Field></div>
   <Field label="SSH 认证方式"><select value={directAuthMethod} onChange={e => setDirectAuthMethod(e.target.value as 'PASSWORD' | 'PRIVATE_KEY')}><option value="PASSWORD">密码</option><option value="PRIVATE_KEY">SSH 私钥</option></select></Field>
   {directAuthMethod === 'PASSWORD' ? <Field label="SSH 密码"><input type="password" autoComplete="new-password" required value={directPassword} onChange={e => setDirectPassword(e.target.value)} /></Field> : <><Field label="SSH 私钥"><textarea required rows={5} autoComplete="off" value={directPrivateKey} onChange={e => setDirectPrivateKey(e.target.value)} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" /></Field><Field label="私钥口令（可选）"><input type="password" autoComplete="new-password" value={directPassphrase} onChange={e => setDirectPassphrase(e.target.value)} /></Field></>}
  </>}
 </>;

 return <div className="modal-backdrop"><section className="modal host-enrollment-modal" role="dialog" aria-modal="true" aria-labelledby="host-enrollment-title">
  <div className="card-title"><div><span className="eyebrow">HOST ENROLLMENT</span><h2 id="host-enrollment-title">添加主机</h2></div><button className="icon-button" aria-label="关闭添加主机" onClick={close}>×</button></div>
  {isMock && <div className="alert">当前为 Mock 演示，生成的接入码不能用于实际安装。</div>}
  {!enrollment ? <form onSubmit={create}>
   <p className="muted">为目标主机创建一条资产记录和 15 分钟有效的一次性接入码。首次心跳会自动补齐主机名、系统和架构。</p>
   <Field label="资产名称"><input autoFocus required maxLength={255} value={hostname} onChange={e => setHostname(e.target.value)} placeholder="例如 node-01" /></Field>
   <section className="restart-form"><h3>SSH 直连（可选）</h3>{directSshFields}</section>
   <div className="alert">Agent 使用独立 mTLS 证书接入并上报主机清单、systemd 服务观测；Helper 只接受固定类型操作并校验参数与目标路径。</div>
   {error && <div className="alert error" role="alert">{error}</div>}
   <div className="form-actions"><button type="button" className="secondary" onClick={close}>取消</button><button className="primary" disabled={busy || !hostname.trim()}>{busy ? '正在创建接入码…' : '生成安装命令'}</button></div>
  </form> : <div className="enrollment-result">
   <p>目标主机可运行以下命令自行安装；下载前会核对控制面板证书指纹，接入码仅在首次兑换时发送。</p>
   <dl className="info-list"><div><dt>主机资产 ID</dt><dd className="mono">{enrollment.hostId}</dd></div><div><dt>接入码有效期至</dt><dd>{time(enrollment.expiresAt)}</dd></div><div><dt>控制面板证书 SHA-256</dt><dd className="mono fingerprint">{enrollment.controllerFingerprint}</dd></div></dl>
   {!isMock && <>
    <textarea className="install-command" aria-label="Agent 安装命令" readOnly value={enrollmentInstallCommand(enrollment)} onFocus={e => e.currentTarget.select()} />
    <div className="form-actions"><button className="secondary" onClick={copyCommand}>{copied ? '已复制' : '复制安装命令'}</button><button className="primary" onClick={close}>完成</button></div>
    {directAddress.trim() && !directConfigured && <section className="restart-form"><h3>保存主机 SSH 直连配置</h3>{directSshFields}<div className="form-actions"><button type="button" className="primary" disabled={directBusy} onClick={() => void saveDirectSsh()}>{directBusy ? '正在加密保存…' : '加密保存 SSH 配置'}</button></div></section>}
    {directConfigured && <section className="restart-form"><h3>验证目标 SSH host key</h3><div className="alert">已加密保存：{directUsername}@{directAddress}:{directPort} · {directAuthMethod}</div><p className="muted">指纹探测阶段不会发送 SSH 密码或私钥；请通过可信渠道核对后再确认。</p><Field label="指纹核对原因"><input required maxLength={500} value={directProbeReason} onChange={e => setDirectProbeReason(e.target.value)} placeholder="说明为什么要连接此主机" /></Field><div className="form-actions"><button type="button" className="secondary" disabled={directBusy || !directProbeReason.trim()} onClick={() => void probeDirectSsh()}>{directBusy ? '探测中…' : '探测 host key 指纹'}</button></div>{directProbe && <div className="alert">目标 host key SHA-256：<code className="mono">{directProbe.fingerprint}</code>{directTrusted ? ' · 已信任' : ' · 请先核对指纹'}</div>}{directProbe?.requiresConfirmation && <button type="button" className="secondary" disabled={directBusy} onClick={() => void trustDirectSsh()}>我已核对并信任此 host key</button>}</section>}
    <form className="ssh-install-form" onSubmit={installOverSsh}>
     <h3>通过受限 SSH 初始化</h3>
     <p className="muted">使用已核对 host key 的资产 SSH 配置；Core 必须配置 HTTPS 公网 Origin。未知结果需先到操作任务对账。</p>
     <Field label="SSH 初始化原因"><input required maxLength={500} value={sshReason} onChange={e => setSshReason(e.target.value)} placeholder="说明授权的初始化目的" /></Field>
     {sshError && <div className="alert error" role="alert">{sshError}</div>}
     {sshOperation && <div className="alert" role="status">SSH 初始化任务 {labels[sshOperation.status] ?? sshOperation.status} · {sshOperation.id}{sshOperation.status === 'UNKNOWN' ? '；请对账后再处理接入码。' : ''}</div>}
     <div className="form-actions"><button className="primary" disabled={sshBusy || !directTrusted || !sshReason.trim() || Boolean(sshOperation && ['RUNNING', 'PENDING', 'UNKNOWN', 'SUCCEEDED'].includes(sshOperation.status))}>{sshBusy ? '正在提交 SSH 初始化…' : '通过 SSH 安装 Agent'}</button></div>
    </form>
   </>}
   {isMock && <div className="form-actions"><button className="primary" onClick={close}>完成</button></div>}
  </div>}
 </section></div>;
}
function HostDetail({ host, close, notify }: { host: Host; close: () => void; notify: (s: string) => void }) {
 const details = useResource<Host>(`/hosts/${host.id}`, 0, true);
 const currentHost = details.data ?? host;
 const agent = useResource<Agent>(currentHost.agentId ? `/agents/${currentHost.agentId}` : '', 0, true);
 const installation = useResource<AgentInstallation>(!isMock && currentHost.agentId ? '/agent-installation' : '', 0, true);
 const [maintenance, setMaintenance] = useState<AgentMaintenanceMode>();
 const [servicesOpen, setServicesOpen] = useState(false);
 const [networkOpen, setNetworkOpen] = useState(false);
 const [filesOpen, setFilesOpen] = useState(false);
 const selfUpdateReported = agent.data?.capabilities.includes('agent.self_update') ?? false;
 const selfUpdateSupported = selfUpdateReported && agent.data?.status === 'ONLINE' && agent.data.helperStatus === 'AVAILABLE';
 return <div className="modal-backdrop"><section className="drawer">
  <div className="card-title"><div><span className="eyebrow">HOST DETAILS</span><h2>{currentHost.hostname}</h2></div><button className="icon-button" aria-label="关闭主机详情" onClick={close}>×</button></div>
  <ResourceState {...details} /><Badge value={currentHost.status} /><p className="muted mono">{currentHost.id}</p>
  <div className="alert">离线状态来自最近心跳；Helper 状态和 Agent 能力来自最近一次在线观测。</div>
  <h3>Agent 与权限边界</h3>{currentHost.agentId ? <><ResourceState {...agent} />{agent.data && <><dl className="info-list"><div><dt>版本 / 实现</dt><dd>{agent.data.version ?? '未知'} / Rust</dd></div><div><dt>Helper 状态</dt><dd><Badge value={agent.data.helperStatus} /></dd></div><div><dt>已报告能力</dt><dd>{agent.data.capabilities.join(', ') || '—'}</dd></div></dl><div className="agent-maintenance-actions"><button className="secondary" onClick={() => setMaintenance('reinstall')}>重新安装 Agent</button><button className="secondary" onClick={() => setMaintenance('update')}>更新 Agent</button></div></>}</> : <p className="muted">尚未绑定 Agent</p>}
  <section className="systemd-summary"><div><h3>systemd 服务</h3><p className="muted">服务列表、重启和日志查看已收纳到详细视图。</p></div><button type="button" className="secondary" onClick={() => setServicesOpen(true)}>详细</button></section>
  <section className="systemd-summary"><div><h3>网卡、IP 与路由</h3><p className="muted">查看最近 Agent 心跳采集的接口、地址和路由表快照。</p></div><button type="button" className="secondary" onClick={() => setNetworkOpen(true)}>查看网络</button></section>
  <section className="systemd-summary"><div><h3>主机文件</h3><p className="muted">在目标主机上浏览和管理 UTF-8 文本文件，操作保留审计记录。</p></div><button type="button" className="secondary" disabled={!agent.data?.capabilities.some(capability => ['file.list', 'file.read', 'file.write', 'file.delete'].includes(capability))} onClick={() => setFilesOpen(true)}>文件管理</button></section>
  <HostSshBastionPanel key={`${host.id}-ssh-bastion`} hostId={host.id} hostName={currentHost.hostname} />
  <SshAccessPanel key={`${host.id}-ssh-rescue`} host={currentHost} notify={notify} />
  {agent.data && <HelperOperationPanel host={currentHost} agent={agent.data} notify={notify} refresh={() => { details.refresh(); agent.refresh(); }} />}
 </section>{servicesOpen && <SystemdServicesDialog host={currentHost} helperStatus={agent.data?.helperStatus} close={() => setServicesOpen(false)} notify={notify} />}{networkOpen && <HostNetworkDialog host={currentHost} close={() => setNetworkOpen(false)} />}{filesOpen && agent.data && <HostFileManagerDialog host={currentHost} agent={agent.data} close={() => setFilesOpen(false)} notify={notify} />}{maintenance && <AgentMaintenanceDialog agentId={currentHost.agentId ?? ''} mode={maintenance} installation={installation.data} installationError={installation.error} selfUpdateSupported={selfUpdateSupported} selfUpdateReported={selfUpdateReported} agentStatus={agent.data?.status} helperStatus={agent.data?.helperStatus} close={() => setMaintenance(undefined)} />}</div>;
}
function HostNetworkDialog({ host, close }: { host: Host; close: () => void }) {
 const inventory = useResource<HostNetworkInventory>(`/hosts/${host.id}/network-inventory`, 0, true);
 const [tab, setTab] = useState('interfaces');
 const interfaces = inventory.data?.interfaces ?? [];
 const addresses = interfaces.flatMap(networkInterface => networkInterface.addresses.map(address => ({ ...address, interfaceName: networkInterface.name })));
 const routes: HostNetworkRoute[] = inventory.data?.routes ?? [];
 return <SecondaryDrawer title={`${host.hostname} · 网络信息`} description="Agent 每 30 秒通过 iproute2 采集一次；离线时显示最近一次快照。" close={close}>
  <DrawerTabs label="网络信息分类" value={tab} onChange={setTab} tabs={[["interfaces", "网卡接口"], ["addresses", "IP 地址"], ["routes", "路由表"], ["overlay", "Overlay 路径"]]} />
  <div className="form-actions"><span className="muted">最近采集：{time(inventory.data?.observedAt)}</span><button type="button" className="secondary" onClick={inventory.refresh} disabled={inventory.loading}>↻ 刷新</button></div>
  <ResourceState {...inventory} />
  {tab === 'interfaces' && <div className="table-wrap"><table><thead><tr><th>接口</th><th>状态</th><th>MAC</th><th>MTU</th></tr></thead><tbody>{interfaces.map(networkInterface => <tr key={networkInterface.name}><td className="mono">{networkInterface.name}</td><td>{networkInterface.state || '—'}</td><td className="mono">{networkInterface.macAddress || '—'}</td><td>{networkInterface.mtu || '—'}</td></tr>)}</tbody></table>{interfaces.length === 0 && !inventory.loading && <Empty>暂无网卡快照；Agent 可能尚未安装 iproute2 或尚未发送首次心跳。</Empty>}</div>}
  {tab === 'addresses' && <div className="table-wrap"><table><thead><tr><th>接口</th><th>地址</th><th>族</th><th>前缀</th><th>Scope</th></tr></thead><tbody>{addresses.map((address, index) => <tr key={`${address.interfaceName}-${address.address}-${index}`}><td className="mono">{address.interfaceName}</td><td className="mono">{address.address}</td><td>{address.family === 'inet' ? 'IPv4' : 'IPv6'}</td><td>/{address.prefixLength}</td><td>{address.scope || '—'}</td></tr>)}</tbody></table>{addresses.length === 0 && !inventory.loading && <Empty>没有已观测到的 IP 地址</Empty>}</div>}
  {tab === 'routes' && <div className="table-wrap"><table><thead><tr><th>目标</th><th>网关</th><th>接口</th><th>表 / 协议</th><th>Metric</th></tr></thead><tbody>{routes.map((route, index) => <tr key={`${route.destination}-${route.interfaceName}-${index}`}><td className="mono">{route.destination}</td><td className="mono">{route.gateway || '直连'}</td><td className="mono">{route.interfaceName || '—'}</td><td>{route.table || '—'} / {route.protocol || '—'}</td><td>{route.metric}</td></tr>)}</tbody></table>{routes.length === 0 && !inventory.loading && <Empty>没有已观测到的路由</Empty>}</div>}
  {tab === 'overlay' && <>
   <div className="alert">Overlay 路径来自本机 NetBird 客户端状态；只展示 Peer 名称、连接状态和 P2P / Relay 类型，不包含外部地址、公钥或业务服务探测结果。</div>
   {inventory.data?.overlay ? <>
    <div className="form-actions"><span className="muted">Overlay 采集：{time(inventory.data.overlay.observedAt)}</span></div>
    <div className="table-wrap"><table><thead><tr><th>本机观察项</th><th>数量</th></tr></thead><tbody>
     <tr><td>已知 Peer</td><td>{inventory.data.overlay.peerCount}</td></tr>
     <tr><td>已连接 Peer</td><td>{inventory.data.overlay.connectedPeerCount}</td></tr>
     <tr><td>P2P 直连</td><td>{inventory.data.overlay.p2pPeerCount}</td></tr>
     <tr><td>Relay 中继</td><td>{inventory.data.overlay.relayedPeerCount}</td></tr>
    </tbody></table></div>
   </> : !inventory.loading && <Empty>没有 NetBird Overlay 汇总指标；逐 Peer 状态仍会单独显示。</Empty>}
   <h3>逐 Peer 路径</h3>
   {inventory.data?.overlayPeersObservedAt ? <>
    <div className="form-actions"><span className="muted">Peer 状态采集：{time(inventory.data.overlayPeersObservedAt)}</span></div>
    <div className="table-wrap"><table><thead><tr><th>Peer</th><th>连接状态</th><th>路径</th></tr></thead><tbody>
     {inventory.data.overlayPeers.map(peer => <tr key={peer.peerName}><td className="mono">{peer.peerName}</td><td>{peer.status === 'CONNECTED' ? '已连接' : peer.status === 'DISCONNECTED' ? '已断开' : peer.status === 'CONNECTING' ? '连接中' : '未知'}</td><td>{peer.connectionType === 'P2P' ? 'P2P 直连' : peer.connectionType === 'RELAY' ? 'Relay 中继' : '未知'}</td></tr>)}
    </tbody></table>{inventory.data.overlayPeers.length === 0 && <Empty>这次采集没有已知 Peer</Empty>}</div>
   </> : !inventory.loading && <Empty>没有逐 Peer 路径快照；需要安装带权限边界的 NetBird 客户端及 NaNPanel Helper。</Empty>}
  </>}
 </SecondaryDrawer>;
}

function HostFileManagerDialog({ host, agent, close, notify }: {
 host: Host; agent: Agent; close: () => void; notify: (message: string) => void;
}) {
 const [directoryPath, setDirectoryPath] = useState('/etc');
 const [filePath, setFilePath] = useState('');
 const [content, setContent] = useState('');
 const [reason, setReason] = useState('');
 const [operationId, setOperationId] = useState('');
 const [submitted, setSubmitted] = useState<{ capability: HostHelperOperationRequest['capability']; target: string }>();
 const [busy, setBusy] = useState(false);
 const [error, setError] = useState('');
 const operation = useResource<Operation>(operationId ? `/operations/${operationId}` : '', operationId ? 1200 : 0, true);
 const capabilities = agent.capabilities;
 const ready = agent.status === 'ONLINE' && agent.helperStatus === 'AVAILABLE';
 const operationRunning = operation.data && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(operation.data.status);
 const output = operation.data?.result && 'output' in operation.data.result ? operation.data.result.output : '';
 let listing: HostFileListing | undefined;
 if (submitted?.capability === 'file.list' && operation.data?.status === 'SUCCEEDED' && output) {
  try { listing = JSON.parse(output) as HostFileListing; } catch { listing = undefined; }
 }
 useEffect(() => {
  if (submitted?.capability === 'file.read' && operation.data?.status === 'SUCCEEDED' && output) {
   setContent(output); setFilePath(submitted.target);
  }
 }, [operation.data?.id, operation.data?.status, operation.data?.updatedAt, output, submitted]);
 async function run(capability: 'file.list' | 'file.read' | 'file.write' | 'file.delete', target: string, payload?: { content: string }) {
  if (!ready || !capabilities.includes(capability)) return;
  if (!reason.trim()) { setError('请填写本次文件操作原因'); return; }
  setBusy(true); setError('');
  const request: HostHelperOperationRequest = {
   capability, target, reason: reason.trim(), timeoutSeconds: 60, expectedGeneration: host.generation,
   ...(payload ? { payload } : {}),
  };
  try {
   const result = await api<Operation>(`/hosts/${host.id}/operations`, 'POST', request, { 'Idempotency-Key': createUuid() }, true);
   setSubmitted({ capability, target }); setOperationId(result.data.id);
   notify(`${isMock ? '模拟' : ''}文件操作任务已提交`);
  } catch (cause) { setError((cause as Error).message); }
  finally { setBusy(false); }
 }
 function parentDirectory(path: string) {
  if (path === '/') return '/';
  const parent = path.replace(/\/$/, '').split('/').slice(0, -1).join('/');
  return parent || '/';
 }
 const missingCapabilities = (['file.list', 'file.read', 'file.write', 'file.delete'] as const).filter(capability => !capabilities.includes(capability));
 return <SecondaryDrawer title={`${host.hostname} · 文件管理`} description="Root Helper 通过目标主机文件系统操作；路径不允许跳转组件或符号链接。读文件内容会保存在操作任务结果中。" close={close}>
  {!ready && <div className="alert">Agent 或 Helper 当前不可用。</div>}
  {missingCapabilities.length > 0 && <div className="alert">文件管理能力未上报。请确认主机 Helper 已更新并重新启动 Agent。</div>}
  <div className="alert">支持普通 UTF-8 文件：单次读取上限 64 KiB，写入上限 32 KiB；仅删除普通文件。写入操作会直接修改目标主机上的文件，请核对路径和内容。</div>
  <Field label="操作原因"><textarea required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} placeholder="说明本次浏览、读取或修改文件的目的" /></Field>
  <div className="form-actions"><button type="button" className="secondary" disabled={directoryPath === '/' || busy || Boolean(operationRunning)} onClick={() => { const path = parentDirectory(directoryPath); setDirectoryPath(path); void run('file.list', path); }}>上级目录</button><Field label="目录路径"><input maxLength={4096} value={directoryPath} onChange={event => setDirectoryPath(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); void run('file.list', directoryPath); } }} /></Field><button type="button" className="secondary" disabled={busy || Boolean(operationRunning) || !capabilities.includes('file.list')} onClick={() => void run('file.list', directoryPath)}>读取目录</button></div>
  <ResourceState {...operation} />
  {operation.data?.error && <div className="alert error">{operation.data.error.message}</div>}
  {operation.data?.status === 'UNKNOWN' && <div className="alert">文件操作结果尚未确认，请等待 Agent 对账后再继续。</div>}
  {listing && <div className="table-wrap"><table><thead><tr><th>名称</th><th>类型</th><th>大小</th><th /></tr></thead><tbody>{listing.entries.map(entry => <tr key={entry.name}><td className="mono">{entry.name}</td><td>{entry.kind}</td><td>{entry.sizeBytes == null ? '—' : `${entry.sizeBytes} B`}</td><td>{entry.kind === 'directory' ? <button type="button" className="link" disabled={busy || Boolean(operationRunning)} onClick={() => { const next = listing!.path === '/' ? `/${entry.name}` : `${listing!.path}/${entry.name}`; setDirectoryPath(next); void run('file.list', next); }}>打开目录 →</button> : entry.kind === 'file' ? <button type="button" className="link" disabled={busy || Boolean(operationRunning) || !capabilities.includes('file.read')} onClick={() => void run('file.read', listing!.path === '/' ? `/${entry.name}` : `${listing!.path}/${entry.name}`)}>读取</button> : <span className="muted">不跟随</span>}</td></tr>)}</tbody></table>{listing.entries.length === 0 && <Empty>此目录为空</Empty>}{listing.truncated && <p className="muted">目录超过 200 项，列表已截断。</p>}</div>}
  <div className="restart-form"><h3>读取、创建或修改文本文件</h3><Field label="绝对文件路径"><input required maxLength={4096} value={filePath} onChange={event => setFilePath(event.target.value)} placeholder="例如 /etc/example.conf" /></Field><Field label="UTF-8 文件内容"><textarea value={content} maxLength={65536} onChange={event => setContent(event.target.value)} rows={12} placeholder="读取现有文件，或输入内容创建一个新文件" /></Field><small className="muted">当前 {new TextEncoder().encode(content).length} 字节 · 保存上限 32 KiB</small><div className="form-actions"><button type="button" className="secondary" disabled={busy || Boolean(operationRunning) || !filePath.startsWith('/') || !capabilities.includes('file.write') || new TextEncoder().encode(content).length > 32768} onClick={() => void run('file.write', filePath, { content })}>保存文件</button><button type="button" className="secondary" disabled={busy || Boolean(operationRunning) || !filePath.startsWith('/') || !capabilities.includes('file.delete')} onClick={() => { if (window.confirm(`确认删除主机文件 ${filePath}？`)) void run('file.delete', filePath); }}>删除文件</button><button type="button" className="secondary" onClick={operation.refresh} disabled={!operationId || operation.loading}>↻ 刷新任务</button></div></div>
  {error && <div className="alert error" role="alert">{error}</div>}
 </SecondaryDrawer>;
}

function SystemdServicesDialog({ host, helperStatus, close, notify }: {
 host: Host; helperStatus?: string; close: () => void; notify: (message: string) => void;
}) {
 const pageSize = 25;
 const [cursors, setCursors] = useState<(string | null)[]>([null]);
 const [action, setAction] = useState<{ service: SystemService; kind: 'RESTART' | 'READ_JOURNAL'; etag: string; key: string }>();
 const [reason, setReason] = useState('');
 const [busy, setBusy] = useState(false);
 const [error, setError] = useState('');
 const [operationId, setOperationId] = useState('');
 const cursor = cursors[cursors.length - 1];
 const params = new URLSearchParams({ limit: String(pageSize) });
 if (cursor) params.set('cursor', cursor);
 const services = useResource<SystemServicePage>(`/hosts/${host.id}/services?${params}`, 0, true);
 const operation = useResource<Operation>(operationId ? `/operations/${operationId}` : '', operationId ? 1500 : 0, true);
 const running = operation.data && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(operation.data.status);
 const journal = operation.data?.result && 'journal' in operation.data.result ? operation.data.result.journal : undefined;
 useEffect(() => {
  if (operation.data?.kind === 'SYSTEM_SERVICE_RESTART' && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(operation.data.status)) services.refresh();
 }, [operation.data?.id, operation.data?.status, operation.data?.updatedAt]);
 async function prepare(service: SystemService, kind: 'RESTART' | 'READ_JOURNAL') {
  try {
   const result = await api<SystemService>(`/hosts/${host.id}/services/${service.id}`, 'GET', undefined, {}, true);
   setAction({ service: result.data, kind, etag: result.etag ?? '', key: createUuid() });
   setReason(''); setError('');
  } catch (cause) { setError((cause as Error).message); }
 }
 async function submit(event: FormEvent) {
  event.preventDefault();
  if (!action) return;
  setBusy(true); setError('');
  const journalAction = action.kind === 'READ_JOURNAL';
  try {
   const result = await api<Operation>(
    `/hosts/${host.id}/services/${action.service.id}/${journalAction ? 'journal' : 'restart'}`,
    'POST',
    journalAction ? { reason } : { reason, timeoutSeconds: 60 },
    { 'If-Match': action.etag, 'Idempotency-Key': action.key }, true,
   );
   setOperationId(result.data.id); setAction(undefined); setReason('');
   notify(`${isMock ? '模拟' : ''}${journalAction ? '日志查询' : '重启'}任务已接受，结果会显示在此弹窗`);
  } catch (cause) { setError((cause as Error).message); }
  finally { setBusy(false); }
 }
 const unavailableMessage = helperStatus !== 'AVAILABLE'
  ? 'Agent 或 root Helper 当前不可用，暂时不能执行服务操作。'
  : 'Helper 尚未报告所需的 systemd 操作能力。请确认 Agent 和 Helper 在线，并刷新主机观测。';
 return <div className="modal-backdrop systemd-services-backdrop"><section className="modal systemd-services-modal" role="dialog" aria-modal="true" aria-label={`${host.hostname} systemd 服务`}>
  <div className="card-title"><div><span className="eyebrow">SYSTEMD SERVICES</span><h2>{host.hostname} · systemd 服务</h2><p className="muted">每页最多 25 个服务；操作结果与日志会直接显示在此弹窗。</p></div><button className="icon-button" aria-label="关闭 systemd 服务详情" onClick={close}>×</button></div>
  <div className="systemd-services-content">
   <ResourceState {...services} />
   {helperStatus !== 'AVAILABLE' || services.data?.items.some(service => service.supportedActions.length < 2) ? <div className="alert systemd-services-hint">{unavailableMessage}</div> : null}
   <div className="table-wrap"><table className="systemd-services-table"><thead><tr><th>Unit</th><th>服务名称</th><th>状态</th><th>最近观测</th><th>操作</th></tr></thead><tbody>{services.data?.items.map(service => {
    const canRestart = service.supportedActions.includes('RESTART') && helperStatus === 'AVAILABLE' && !service.observationStale && !running;
    const canReadJournal = service.supportedActions.includes('READ_JOURNAL') && helperStatus === 'AVAILABLE' && !service.observationStale && !running;
    return <tr key={service.id}><td className="mono">{service.unitName}</td><td>{service.displayName}</td><td><Badge value={service.activeState} />{service.observationStale && <small className="warning">观测已过期</small>}</td><td className="muted">{time(service.observedAt)}</td><td><div className="systemd-service-actions"><button type="button" className="secondary" title={canReadJournal ? '读取此服务日志' : unavailableMessage} disabled={!canReadJournal} onClick={() => void prepare(service, 'READ_JOURNAL')}>查看日志</button><button type="button" className="secondary" title={canRestart ? '重启此服务' : unavailableMessage} disabled={!canRestart} onClick={() => void prepare(service, 'RESTART')}>↻ 重启</button></div></td></tr>;
   })}</tbody></table>{services.data?.items.length === 0 && !services.loading && <Empty>尚无服务观测</Empty>}</div>
   <div className="form-actions systemd-pagination"><button className="secondary" disabled={cursors.length < 2 || services.loading} onClick={() => setCursors(value => value.slice(0, -1))}>上一页</button><span className="muted">第 {cursors.length} 页 · 每页 25 个</span><button className="secondary" disabled={!services.data?.nextCursor || services.loading} onClick={() => { if (services.data?.nextCursor) setCursors(value => [...value, services.data!.nextCursor]); }}>下一页</button><button type="button" className="secondary" onClick={services.refresh} disabled={services.loading}>↻ 刷新</button></div>
   {action && <form className="restart-form systemd-action-form" onSubmit={submit}><h3>{action.kind === 'RESTART' ? `确认重启 ${action.service.unitName}` : `查看 ${action.service.unitName} 的日志`}</h3><p className="muted">{action.kind === 'RESTART' ? '重启由主机 root Helper 执行；成功只证明 systemd 服务状态，不代表业务健康。' : '读取最近 12 行，日志会受管理员权限保护，并保存在操作任务中。'}</p><Field label={action.kind === 'RESTART' ? '操作原因' : '查询原因'}><textarea required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} placeholder={action.kind === 'RESTART' ? '请填写本次重启的原因' : '请填写本次查询的原因'} /></Field>{error && <div className="alert error" role="alert">{error}</div>}<div className="form-actions"><button type="button" className="secondary" onClick={() => { setAction(undefined); setError(''); }}>取消</button><button className="primary" disabled={busy || !reason.trim() || !action.etag}>{busy ? '正在提交…' : action.kind === 'RESTART' ? '确认重启' : '查询日志'}</button></div></form>}
   {operationId && <section className="systemd-operation-result"><div className="section-heading"><h3>最近一次 systemd 操作</h3>{operation.data && <Badge value={operation.data.status} />}</div><ResourceState {...operation} />{operation.data?.error && <div className="alert error">{operation.data.error.message}</div>}{operation.data?.status === 'UNKNOWN' && <div className="alert">执行结果尚未确认，请等待 Agent 对账，不要重复提交。</div>}{operation.data?.result && 'evidence' in operation.data.result && <div className="alert">{operation.data.result.evidence}</div>}{journal !== undefined && <><p className="muted">{operation.data?.result && 'unitName' in operation.data.result ? operation.data.result.unitName : ''} · 查询时间 {time(operation.data?.result && 'retrievedAt' in operation.data.result ? operation.data.result.retrievedAt : null)}</p><pre className="journal-output">{journal || '（没有日志内容）'}</pre></>}</section>}
  </div>
 </section></div>;
}
function SecondaryDrawer({ title, description, close, children }: {
 title: string; description: string; close: () => void; children: ReactNode;
}) {
 return <div className="modal-backdrop host-secondary-backdrop"><aside className="drawer host-secondary-drawer" role="dialog" aria-modal="true" aria-label={title}>
  <div className="card-title"><div><span className="eyebrow">HOST ACCESS</span><h2>{title}</h2><p className="muted">{description}</p></div><button className="icon-button" aria-label={`关闭${title}`} onClick={close}>×</button></div>
  <div className="host-secondary-body">{children}</div>
 </aside></div>;
}
function DrawerTabs({ label, value, onChange, tabs }: {
 label: string; value: string; onChange: (value: string) => void; tabs: [string, string][];
}) {
 return <div className="drawer-tabs" role="tablist" aria-label={label}>{tabs.map(([id, title]) => <button key={id} type="button" role="tab" aria-selected={value === id} className={value === id ? 'active' : ''} onClick={() => onChange(id)}>{title}</button>)}</div>;
}
function SshAccessPanel({ host, notify }: { host: Host; notify: (s: string) => void }) {
 const [reason, setReason] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
 const [unitName, setUnitName] = useState(''); const [restartReason, setRestartReason] = useState(''); const [restartBusy, setRestartBusy] = useState(false); const [restartError, setRestartError] = useState(''); const [restartKey, setRestartKey] = useState(createUuid());
 const [diagnosticReason, setDiagnosticReason] = useState(''); const [journalLines, setJournalLines] = useState('50'); const [diagnosticBusy, setDiagnosticBusy] = useState(false); const [diagnosticError, setDiagnosticError] = useState(''); const [status, setStatus] = useState<SshSystemdStatusResult>(); const [journal, setJournal] = useState<SshSystemdJournalResult>();
 const [drawerOpen, setDrawerOpen] = useState(false); const [rescueTab, setRescueTab] = useState('probe');
 async function probe(event: FormEvent) {
  event.preventDefault(); setBusy(true); setError('');
  try {
   const result = await api<SshProbeResult>(`/hosts/${host.id}/ssh/probe`, 'POST', { reason }, {}, true);
   notify(`${isMock ? '模拟' : ''}SSH host key 验证成功：${result.data.address}:${result.data.port}`); setReason('');
  } catch (e) { setError((e as Error).message); }
  finally { setBusy(false); }
 }
 async function restart(event: FormEvent) {
  event.preventDefault(); setRestartBusy(true); setRestartError('');
  try {
   const result = await api<Operation>(`/hosts/${host.id}/ssh/systemd/restart`, 'POST', { unitName: unitName.trim(), reason: restartReason }, { 'Idempotency-Key': restartKey }, true);
   notify(`${isMock ? '模拟' : ''}SSH 救援任务 ${result.data.status}：${unitName.trim()}`);
   setRestartReason('');
   if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(result.data.status)) setRestartKey(createUuid());
  } catch (e) { setRestartError((e as Error).message); }
  finally { setRestartBusy(false); }
 }
 async function readStatus() {
  setDiagnosticBusy(true); setDiagnosticError('');
  try { const result = await api<SshSystemdStatusResult>(`/hosts/${host.id}/ssh/systemd/status`, 'POST', { unitName: unitName.trim(), reason: diagnosticReason }, {}, true); setStatus(result.data); setJournal(undefined); notify(`SSH 状态：${unitName.trim()} ${result.data.activeState}/${result.data.subState}`); }
  catch (e) { setDiagnosticError((e as Error).message); }
  finally { setDiagnosticBusy(false); }
 }
 async function readJournal() {
  setDiagnosticBusy(true); setDiagnosticError('');
  try { const result = await api<SshSystemdJournalResult>(`/hosts/${host.id}/ssh/systemd/journal`, 'POST', { unitName: unitName.trim(), lines: Number(journalLines), reason: diagnosticReason }, {}, true); setJournal(result.data); setStatus(undefined); notify(`SSH 日志已读取：${unitName.trim()}`); }
  catch (e) { setDiagnosticError((e as Error).message); }
  finally { setDiagnosticBusy(false); }
 }
 return <>
  <section className="ssh-entry-card">
   <div><span className="eyebrow">SSH ACCESS</span><h3>SSH 救援</h3><p>使用主机资产中已确认 host key 的 SSH 配置执行固定服务操作和只读诊断。</p></div>
   <button type="button" className="secondary" onClick={() => setDrawerOpen(true)}>打开救援工具</button>
  </section>
  {drawerOpen && <SecondaryDrawer title={`${host.hostname} · SSH 救援`} description="这些固定操作使用主机资产中加密保存的 SSH 凭据，并校验已确认的 host key。" close={() => setDrawerOpen(false)}>
   <DrawerTabs label="SSH 救援操作" value={rescueTab} onChange={setRescueTab} tabs={[["probe", "连通性探测"], ["restart", "服务重启"], ["diagnostics", "只读诊断"]]} />
   {rescueTab === 'probe' && <form className="restart-form" onSubmit={probe}>
    <p className="muted">只使用资产中配置的 SSH 地址、账号和认证信息，校验已确认的 host key，并运行无副作用的 true 命令。</p>
    <Field label="SSH 探测原因"><textarea required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} placeholder="记录本次 SSH 连通性检查的原因" /></Field>
    {error && <div className="alert error" role="alert">{error}</div>}
    <div className="form-actions"><button className="primary" disabled={busy || !reason.trim()}>{busy ? '正在验证…' : '验证 SSH 通道'}</button></div>
   </form>}
   {rescueTab === 'restart' && <form className="restart-form" onSubmit={restart}>
    <p className="muted">仅执行固定的 systemctl restart 和 active 状态校验，操作结果与原因会写入审计。</p>
    <Field label="systemd unit"><input required maxLength={256} value={unitName} onChange={event => setUnitName(event.target.value)} placeholder="例如 nanpanel-agent.service" /></Field>
    <Field label="SSH 救援原因"><textarea required maxLength={500} value={restartReason} onChange={event => setRestartReason(event.target.value)} placeholder="记录本次 SSH 救援原因" /></Field>
    {restartError && <div className="alert error" role="alert">{restartError}</div>}
    <div className="form-actions"><button className="primary" disabled={restartBusy || !unitName.trim() || !restartReason.trim()}>{restartBusy ? '正在执行…' : '重启服务'}</button></div>
   </form>}
   {rescueTab === 'diagnostics' && <section className="restart-form"><h4>SSH 只读诊断</h4><p className="muted">读取固定 systemctl 状态或最多 100 行 journal。命令输出有字节和时间上限，内容不会写入审计摘要。</p>
    <Field label="SSH 诊断 unit"><input required maxLength={256} value={unitName} onChange={event => setUnitName(event.target.value)} placeholder="例如 nanpanel-agent.service" /></Field>
    <Field label="日志行数"><input type="number" min="1" max="100" value={journalLines} onChange={event => setJournalLines(event.target.value)} /></Field>
    <Field label="SSH 诊断原因"><textarea required maxLength={500} value={diagnosticReason} onChange={event => setDiagnosticReason(event.target.value)} placeholder="说明读取服务状态或日志的目的" /></Field>
    {diagnosticError && <div className="alert error" role="alert">{diagnosticError}</div>}
    <div className="form-actions"><button type="button" className="secondary" disabled={diagnosticBusy || !unitName.trim() || !diagnosticReason.trim()} onClick={() => void readStatus()}>{diagnosticBusy ? '读取中…' : '读取服务状态'}</button><button type="button" className="secondary" disabled={diagnosticBusy || !unitName.trim() || !diagnosticReason.trim() || Number(journalLines) < 1 || Number(journalLines) > 100} onClick={() => void readJournal()}>{diagnosticBusy ? '读取中…' : '读取服务日志'}</button></div>
    {status && <div className="alert" role="status">{status.unitName} · {status.loadState} / {status.activeState} / {status.subState}</div>}
    {journal && <div className="ssh-journal"><p className="muted">最多 {journal.requestedLines} 行{journal.truncated ? ' · 输出已截断' : ''}</p><pre>{journal.output || '（没有日志内容）'}</pre></div>}
   </section>}
  </SecondaryDrawer>}
 </>;
}
function HostSshBastionPanel({ hostId, hostName }: { hostId: string; hostName: string }) {
 const [config, setConfig] = useState<HostSshConfig>();
 const [configLoaded, setConfigLoaded] = useState(isMock);
 const [configError, setConfigError] = useState('');
 const [address, setAddress] = useState(''); const [port, setPort] = useState('22'); const [username, setUsername] = useState('root');
 const [authMethod, setAuthMethod] = useState<'PASSWORD' | 'PRIVATE_KEY'>('PASSWORD');
 const [password, setPassword] = useState(''); const [privateKey, setPrivateKey] = useState(''); const [privateKeyPassphrase, setPrivateKeyPassphrase] = useState('');
 const [saveBusy, setSaveBusy] = useState(false); const [probeBusy, setProbeBusy] = useState(false); const [trustBusy, setTrustBusy] = useState(false);
 const [keyProbe, setKeyProbe] = useState<SshHostKeyProbe>(); const [probeReason, setProbeReason] = useState('');
 const [sessionReason, setSessionReason] = useState(''); const [sessionBusy, setSessionBusy] = useState(false);
 const [terminatingSession, setTerminatingSession] = useState(''); const [historyMessage, setHistoryMessage] = useState('');
 const [ticket, setTicket] = useState<BastionSessionTicket>(); const [sessions, setSessions] = useState<BastionSession[]>([]);
 const [historyError, setHistoryError] = useState(''); const [selectedCommands, setSelectedCommands] = useState<{ session: BastionSession; items: BastionCommand[] }>();
 const [replay, setReplay] = useState<{ session: BastionSession; items: BastionRecordingChunk[] }>();
 const [drawer, setDrawer] = useState<'config' | 'bastion'>(); const [settingsTab, setSettingsTab] = useState('connection'); const [bastionTab, setBastionTab] = useState('shell');
 async function loadConfig() {
  if (isMock) { setConfigLoaded(true); return; }
  try { const result = await api<HostSshConfig>(`/hosts/${hostId}/ssh/config`, 'GET', undefined, {}, true); setConfig(result.data); setAddress(result.data.address); setPort(String(result.data.port)); setUsername(result.data.username); setAuthMethod(result.data.authMethod); setConfigError(''); }
  catch (error) { if ((error as ApiError).status === 404) { setConfig(undefined); setConfigError(''); } else setConfigError((error as Error).message); }
  finally { setConfigLoaded(true); }
 }
 async function loadSessions() {
  if (isMock) return;
  try { const result = await api<{ items: BastionSession[] }>(`/bastion-sessions?hostId=${encodeURIComponent(hostId)}&limit=30`, 'GET', undefined, {}, true); setSessions(result.data.items); setHistoryError(''); }
  catch (error) { setHistoryError((error as Error).message); }
 }
 useEffect(() => { void loadConfig(); void loadSessions(); }, [hostId]);
 async function saveConfig(event: FormEvent) {
  event.preventDefault(); setSaveBusy(true); setConfigError('');
  try {
   await api('/auth/csrf');
   await api(`/hosts/${hostId}/ssh/config`, 'PUT', {
    address: address.trim(), port: Number(port), username: username.trim(), authMethod,
    password: authMethod === 'PASSWORD' ? password : null,
    privateKey: authMethod === 'PRIVATE_KEY' ? privateKey : null,
    privateKeyPassphrase: authMethod === 'PRIVATE_KEY' && privateKeyPassphrase ? privateKeyPassphrase : null,
   }, {}, true);
   setPassword(''); setPrivateKey(''); setPrivateKeyPassphrase(''); setKeyProbe(undefined);
   await loadConfig();
  } catch (error) { setConfigError((error as Error).message); }
  finally { setSaveBusy(false); }
 }
 async function probeHostKey() {
  setProbeBusy(true); setConfigError('');
  try {
   await api('/auth/csrf');
   const result = await api<SshHostKeyProbe>(`/hosts/${hostId}/ssh/host-key/probe`, 'POST', { reason: probeReason.trim() }, {}, true);
   setKeyProbe(result.data);
   if (result.data.matchesTrustedFingerprint) await loadConfig();
  } catch (error) { setConfigError((error as Error).message); }
  finally { setProbeBusy(false); }
 }
 async function trustHostKey() {
  if (!keyProbe) return;
  setTrustBusy(true); setConfigError('');
  try {
   await api(`/hosts/${hostId}/ssh/host-key/trust`, 'POST', { fingerprint: keyProbe.fingerprint, reason: probeReason.trim() }, {}, true);
   setKeyProbe(undefined); await loadConfig();
  } catch (error) { setConfigError((error as Error).message); }
  finally { setTrustBusy(false); }
 }
 async function openShell(event: FormEvent) {
  event.preventDefault(); setSessionBusy(true); setHistoryError('');
  try {
   await api('/auth/csrf');
   const result = await api<BastionSessionTicket>(`/hosts/${hostId}/ssh/sessions`, 'POST', { reason: sessionReason.trim(), columns: 120, rows: 36 }, {}, true);
   setTicket(result.data); setSessionReason(''); await loadSessions();
  } catch (error) { setHistoryError((error as Error).message); }
  finally { setSessionBusy(false); }
 }
 async function forceTerminate(session: BastionSession) {
  const reason = window.prompt(`强制阻断 ${session.targetUsername}@${session.targetAddress}:${session.targetPort}？该操作会立即断开终端并写入审计。请填写原因：`, '管理员执行强制阻断');
  if (reason === null) return;
  if (!reason.trim()) { setHistoryError('强制阻断原因不能为空'); return; }
  setTerminatingSession(session.id); setHistoryError(''); setHistoryMessage('');
  try {
   await api('/auth/csrf');
   await api(`/bastion-sessions/${session.id}/terminate`, 'POST', { reason: reason.trim() }, {}, true);
   setHistoryMessage('会话已强制阻断，操作已写入审计记录');
   await loadSessions();
  } catch (error) { setHistoryError((error as Error).message); }
  finally { setTerminatingSession(''); }
 }
 async function viewCommands(session: BastionSession) {
  try { const result = await api<{ items: BastionCommand[] }>(`/bastion-sessions/${session.id}/commands`, 'GET', undefined, {}, true); setSelectedCommands({ session, items: result.data.items }); }
  catch (error) { setHistoryError((error as Error).message); }
 }
 async function playRecording(session: BastionSession) {
  try { const result = await api<{ items: BastionRecordingChunk[] }>(`/bastion-sessions/${session.id}/recording`, 'GET', undefined, {}, true); setReplay({ session, items: result.data.items }); }
  catch (error) { setHistoryError((error as Error).message); }
 }
 const openBastion = (tab: string) => { setBastionTab(tab); setDrawer('bastion'); void loadSessions(); };
 return <>
  <section className="ssh-entry-card">
   <div><span className="eyebrow">DIRECT SSH · BASTION</span><h3>资产 SSH 访问</h3>
    <p>{isMock ? 'Mock 演示只展示入口，不连接真实主机。Live API 会加载已保存连接信息。' : !configLoaded ? '正在读取连接配置…' : configError ? 'SSH 配置读取失败，请打开配置查看错误。' : config ? `${config.username}@${config.address}:${config.port} · ${config.authMethod} · 凭据已加密保存` : '尚未配置目标 SSH 地址和认证凭据。'}</p>
    {config && <span className={`chip ${config.hostKeyFingerprint ? 'status-ok' : 'status-warn'}`}>{config.hostKeyFingerprint ? 'Host key 已确认' : 'Host key 待确认'}</span>}
   </div>
   <div className="ssh-entry-actions"><button type="button" className="secondary" disabled={!configLoaded} onClick={() => { setSettingsTab('connection'); setDrawer('config'); }}>{configLoaded ? 'SSH 配置' : '读取配置…'}</button><button type="button" className="primary" onClick={() => openBastion('shell')}>开启堡垒 Shell</button><button type="button" className="secondary" onClick={() => openBastion('audit')}>会话审计</button></div>
  </section>
  {drawer === 'config' && <SecondaryDrawer title={`${hostName} · SSH 配置`} description="连接参数与 host key 信任分开管理；凭据只写入，不会从服务器读取回显。" close={() => setDrawer(undefined)}>
   <DrawerTabs label="SSH 配置" value={settingsTab} onChange={setSettingsTab} tabs={[["connection", "连接参数"], ["host-key", "Host key 信任"]]} />
   {isMock ? <div className="alert">{settingsTab === 'connection' ? 'Mock 演示模式不保存 SSH 凭据。Live API 中可配置地址、端口、用户名、密码或私钥。' : 'Mock 演示模式不探测或信任真实 Host key。Live API 中此处会先显示 SHA-256 指纹，核对后再确认。'}</div> : <>
   {configError && <div className="alert error" role="alert">{configError}</div>}
   {settingsTab === 'connection' && <section className="drawer-section">
    <div className="alert">支持 IP/域名直连。密码或私钥以 AES-GCM 加密保存，读取接口不返回凭据。</div>
    {config && <div className="ssh-config-summary"><b>当前连接</b><span>{config.username}@{config.address}:{config.port}</span><small>{config.authMethod} · 凭据已保存 · {config.hostKeyFingerprint ? 'Host key 已确认' : 'Host key 尚未确认'}</small></div>}
    <form onSubmit={saveConfig}>
     <Field label="SSH 地址"><input required maxLength={253} value={address} onChange={event => setAddress(event.target.value)} placeholder="例如 192.0.2.10 或 host.example.net" /></Field>
     <div className="ssh-form-grid"><Field label="端口"><input type="number" min="1" max="65535" required value={port} onChange={event => setPort(event.target.value)} /></Field><Field label="用户名"><input required maxLength={128} value={username} onChange={event => setUsername(event.target.value)} placeholder="root" /></Field></div>
     <Field label="认证方式"><select value={authMethod} onChange={event => setAuthMethod(event.target.value as 'PASSWORD' | 'PRIVATE_KEY')}><option value="PASSWORD">密码</option><option value="PRIVATE_KEY">SSH 私钥</option></select></Field>
     {authMethod === 'PASSWORD' ? <Field label="SSH 密码"><input type="password" autoComplete="new-password" required value={password} onChange={event => setPassword(event.target.value)} placeholder={config ? '更新时重新输入密码' : '不会回显或再次读取'} /></Field> : <><Field label="私钥内容"><textarea required rows={5} autoComplete="off" value={privateKey} onChange={event => setPrivateKey(event.target.value)} placeholder="-----BEGIN OPENSSH PRIVATE KEY-----" /></Field><Field label="私钥口令（可选）"><input type="password" autoComplete="new-password" value={privateKeyPassphrase} onChange={event => setPrivateKeyPassphrase(event.target.value)} /></Field></>}
     <div className="form-actions"><button className="primary" disabled={saveBusy || !address.trim() || !username.trim() || (authMethod === 'PASSWORD' ? !password : !privateKey.trim())}>{saveBusy ? '正在加密保存…' : config ? '更新连接配置' : '保存连接配置'}</button></div>
    </form>
   </section>}
   {settingsTab === 'host-key' && <section className="drawer-section">
    <div className="alert">指纹探测只验证 SSH 握手和 host key，不会验证密码或私钥。只有完成指纹确认后，堡垒 Shell 才能尝试登录。</div>
    {config ? <>
     <div className="ssh-config-summary"><b>已信任的 Host key</b><span className="mono">{config.hostKeyFingerprint ?? '尚未确认'}</span></div>
     <Field label="探测或确认原因"><input required maxLength={500} value={probeReason} onChange={event => setProbeReason(event.target.value)} placeholder="说明为什么要验证目标主机" /></Field>
     <div className="form-actions"><button type="button" className="secondary" disabled={probeBusy || !probeReason.trim()} onClick={() => void probeHostKey()}>{probeBusy ? '探测中…' : '探测 Host key 指纹'}</button></div>
     {keyProbe && <div className="alert" role="status">目标报告的 SHA-256 指纹：<code className="mono">{keyProbe.fingerprint}</code>{keyProbe.matchesTrustedFingerprint ? ' · 与已信任指纹一致' : ' · 尚未信任，请先通过其他可信渠道核对后确认'}</div>}
     {keyProbe?.requiresConfirmation && <button className="secondary" disabled={trustBusy || !probeReason.trim()} onClick={() => void trustHostKey()}>{trustBusy ? '确认中…' : '我已核对并信任此 Host key'}</button>}
    </> : <p className="muted">请先在“连接参数”标签保存地址和凭据，再探测并确认 Host key。</p>}
   </section>}
   </>}
  </SecondaryDrawer>}
  {drawer === 'bastion' && <SecondaryDrawer title={`${hostName} · 堡垒 Shell`} description="通过 Core 代理目标 SSH；命令历史和有界终端录像会进入独立审计记录。" close={() => setDrawer(undefined)}>
   <DrawerTabs label="堡垒 Shell" value={bastionTab} onChange={setBastionTab} tabs={[["shell", "开启 Shell"], ["audit", "会话审计"]]} />
   {isMock ? bastionTab === 'shell' ? <div className="alert">Mock 演示模式不连接真实主机。Live API 中只有保存凭据并确认 Host key 后，才可以填写操作原因并建立审计 Shell。</div> : <Empty>Mock 演示模式没有真实堡垒会话或审计记录</Empty> : <>
   {bastionTab === 'shell' && <section className="drawer-section">
    {!config && <div className="alert error">尚未配置 SSH 地址和凭据。<button type="button" className="link" onClick={() => { setSettingsTab('connection'); setDrawer('config'); }}>前往 SSH 配置</button></div>}
    {config && !config.hostKeyFingerprint && <div className="alert error">尚未确认目标 Host key。<button type="button" className="link" onClick={() => { setSettingsTab('host-key'); setDrawer('config'); }}>前往指纹确认</button></div>}
    {config && <div className="ssh-config-summary"><b>目标主机</b><span>{config.username}@{config.address}:{config.port}</span><small>{config.authMethod} · Host key {config.hostKeyFingerprint ? '已确认' : '待确认'}</small></div>}
    <div className="alert">所有输入和输出都会保存为可回放终端录像，按回车提交的命令行会单独记录。请勿在终端输入密码、密钥或其他秘密值。</div>
    <form onSubmit={openShell}><Field label="开启 Shell 的原因"><textarea required maxLength={500} value={sessionReason} onChange={event => setSessionReason(event.target.value)} placeholder="记录本次主机 Shell 的操作目的" /></Field><div className="form-actions"><button className="primary" disabled={sessionBusy || !config?.hostKeyFingerprint || !sessionReason.trim()}>{sessionBusy ? '正在创建审计会话…' : '连接并开启 Shell'}</button></div></form>
    {historyError && <div className="alert error" role="alert">{historyError}</div>}
   </section>}
   {bastionTab === 'audit' && <section className="drawer-section">
    <div className="section-heading"><div><h3>堡垒会话历史</h3><p className="muted">最近 30 条；查看命令记录、回放终端或强制阻断活动会话。</p></div><button type="button" className="secondary" onClick={() => void loadSessions()}>↻ 刷新</button></div>
    {historyMessage && <div className="alert" role="status">{historyMessage}</div>}{historyError && <div className="alert error" role="alert">{historyError}</div>}
    {sessions.length === 0 ? <Empty>还没有 SSH 堡垒 Shell 会话。</Empty> : sessions.map(session => <div className="bastion-session-row" key={session.id}><div><b>{time(session.startedAt)} · <Badge value={session.failureCode === 'FORCE_TERMINATED' ? 'FORCE_TERMINATED' : session.status} /></b><small>{session.targetUsername}@{session.targetAddress}:{session.targetPort} · {session.reason}</small><small>{session.bytesIn + session.bytesOut} 字节 · {session.durationMs == null ? '进行中' : `${Math.round(session.durationMs / 1000)} 秒`}{session.failureCode && session.failureCode !== 'FORCE_TERMINATED' ? ` · ${session.failureCode}` : ''}</small></div><div className="form-actions">{['PENDING', 'RUNNING'].includes(session.status) && <button type="button" className="secondary" disabled={terminatingSession === session.id} onClick={() => void forceTerminate(session)}>{terminatingSession === session.id ? '正在阻断…' : '强制阻断'}</button>}<button type="button" className="secondary" onClick={() => void viewCommands(session)}>命令记录</button><button type="button" className="secondary" onClick={() => void playRecording(session)}>回放录像</button></div></div>)}
   </section>}
   </>}
  </SecondaryDrawer>}
  {ticket && <BastionTerminal ticket={ticket} refresh={() => void loadSessions()} close={() => { setTicket(undefined); void loadSessions(); }} />}
  {replay && <BastionReplay session={replay.session} items={replay.items} commands={selectedCommands?.session.id === replay.session.id ? selectedCommands.items : undefined} close={() => setReplay(undefined)} />}
  {selectedCommands && !replay && <div className="modal-backdrop bastion-audit-detail-backdrop"><section className="modal" role="dialog" aria-modal="true" aria-label="命令历史"><div className="card-title"><h2>命令历史 · {selectedCommands.session.targetAddress}</h2><button className="icon-button" aria-label="关闭命令历史" onClick={() => setSelectedCommands(undefined)}>×</button></div>{selectedCommands.items.length ? <ol className="bastion-command-list">{selectedCommands.items.map(command => <li key={command.sequence}><time>{time(command.occurredAt)}</time><code>{command.command}</code></li>)}</ol> : <p className="muted">没有记录到提交的命令行。</p>}</section></div>}
 </>;
}
function BastionTerminal({ ticket, close, refresh }: { ticket: BastionSessionTicket; close: () => void; refresh: () => void }) {
 const terminalElement = useRef<HTMLDivElement>(null); const socketRef = useRef<WebSocket | undefined>(undefined);
 const closeRef = useRef(close); const refreshRef = useRef(refresh); const readyRef = useRef(false); const closeRequestedRef = useRef(false);
 const [status, setStatus] = useState('正在建立受审计的 SSH 连接…');
 useEffect(() => { closeRef.current = close; }, [close]);
 useEffect(() => { refreshRef.current = refresh; }, [refresh]);
 useEffect(() => {
  if (!terminalElement.current) return;
  const terminal = new Terminal({ cursorBlink: true, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13, theme: { background: '#07111f', foreground: '#dbeafe', cursor: '#83b8ff' } });
  const fit = new FitAddon(); terminal.loadAddon(fit); terminal.open(terminalElement.current); fit.fit(); terminal.focus();
  const url = new URL(ticket.websocketPath, window.location.href); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(url);
  socketRef.current = socket;
  socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', ticket: ticket.ticket, columns: terminal.cols, rows: terminal.rows }));
  socket.onmessage = event => {
   try { const message = JSON.parse(String(event.data)) as { type: string; data?: string; message?: string };
    if (message.type === 'ready') { readyRef.current = true; setStatus('已连接 · 终端输入和输出正在录像'); refreshRef.current(); return; }
    if (message.type === 'output' && message.data) { const bytes = Uint8Array.from(atob(message.data), value => value.charCodeAt(0)); terminal.write(bytes); return; }
    if (message.type === 'error') { terminal.write(`\r\n\x1b[31m${message.message ?? 'SSH 连接失败'}\x1b[0m\r\n`); setStatus(message.message ?? 'SSH 连接失败'); }
   } catch { terminal.write(String(event.data)); }
  };
  socket.onerror = () => setStatus('WebSocket 握手或连接失败。终端窗口仍保持打开，可查看错误并手动关闭。');
  socket.onclose = event => {
   readyRef.current = false;
   setStatus(event.code === 1000 ? '连接已关闭；录像已归档。' : `连接已关闭（${event.code}${event.reason ? ` · ${event.reason}` : ''}）。请检查 SSH 配置或服务端日志。`);
   refreshRef.current();
   if (closeRequestedRef.current) closeRef.current();
  };
  const input = terminal.onData(data => { if (readyRef.current && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'input', data })); });
  const resize = () => { fit.fit(); if (readyRef.current && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'resize', columns: terminal.cols, rows: terminal.rows })); };
  window.addEventListener('resize', resize);
  const keepalive = window.setInterval(() => { if (readyRef.current && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'keepalive' })); }, 30000);
  return () => { window.clearInterval(keepalive); window.removeEventListener('resize', resize); input.dispose(); if (socketRef.current === socket) socketRef.current = undefined; socket.close(); readyRef.current = false; terminal.dispose(); };
 }, [ticket.sessionId, ticket.websocketPath, ticket.ticket]);
 async function end() {
  closeRequestedRef.current = true;
  const socket = socketRef.current;
  if (socket && socket.readyState === WebSocket.OPEN && readyRef.current) {
   setStatus('正在结束会话并保存录像…');
   socket.send(JSON.stringify({ type: 'close' }));
   window.setTimeout(() => { if (socket.readyState !== WebSocket.CLOSED) socket.close(); closeRef.current(); }, 2000);
   return;
  }
  setStatus('正在结束未建立或已断开的会话…');
  try {
   await api('/auth/csrf');
   await api(`/bastion-sessions/${ticket.sessionId}/terminate`, 'POST', { reason: '操作者关闭未连接的堡垒终端' }, {}, true);
  } catch (error) {
   if ((error as ApiError).status !== 409) setStatus(`结束会话失败：${(error as Error).message}`);
  }
  closeRef.current();
 }
 return <div className="modal-backdrop bastion-terminal-backdrop"><section className="modal bastion-terminal-modal"><div className="card-title"><div><span className="eyebrow">RECORDED SSH SESSION</span><h2>堡垒 Shell · {ticket.sessionId.slice(0, 8)}</h2></div><button className="icon-button" aria-label="结束 SSH 会话" onClick={end}>×</button></div><p className="muted">{status}</p><div className="bastion-terminal" ref={terminalElement} /><div className="form-actions"><button className="secondary" onClick={end}>结束会话并归档</button></div></section></div>;
}
function BastionReplay({ session, items, commands, close }: { session: BastionSession; items: BastionRecordingChunk[]; commands?: BastionCommand[]; close: () => void }) {
 const terminalElement = useRef<HTMLDivElement>(null);
 useEffect(() => {
  if (!terminalElement.current) return;
  const terminal = new Terminal({ convertEol: true, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12, theme: { background: '#07111f', foreground: '#dbeafe' } });
  terminal.open(terminalElement.current); terminal.write('\x1b[90m回放从 0.25× 加速；只显示远端终端输出。\x1b[0m\r\n');
  let cancelled = false;
  void (async () => { let previous = 0; for (const chunk of items.filter(item => item.direction === 'OUTPUT')) { const delay = Math.min(200, Math.max(0, (chunk.offsetMs - previous) / 4)); await new Promise(resolve => window.setTimeout(resolve, delay)); if (cancelled) return; const bytes = Uint8Array.from(atob(chunk.data), value => value.charCodeAt(0)); terminal.write(bytes); previous = chunk.offsetMs; } })();
  return () => { cancelled = true; terminal.dispose(); };
 }, [session.id, items]);
 return <div className="modal-backdrop bastion-terminal-backdrop"><section className="modal bastion-terminal-modal"><div className="card-title"><div><span className="eyebrow">BASTION RECORDING</span><h2>{session.targetUsername}@{session.targetAddress}:{session.targetPort}</h2></div><button className="icon-button" onClick={close}>×</button></div><div className="bastion-terminal bastion-replay-terminal" ref={terminalElement} />{commands && <ol className="bastion-command-list">{commands.map(command => <li key={command.sequence}><time>{time(command.occurredAt)}</time><code>{command.command}</code></li>)}</ol>}<div className="form-actions"><button className="secondary" onClick={close}>关闭回放</button></div></section></div>;
}
function HelperOperationPanel({ host, agent, notify, refresh }: { host: Host; agent: Agent; notify: (s: string) => void; refresh: () => void }) {
 type StandardCapability = Exclude<HostHelperOperationRequest['capability'], 'file.list' | 'file.read' | 'file.write' | 'file.delete'>;
 const actionLabels: Record<StandardCapability, string> = {
  'package.install': '安装软件包', 'file.write.protected': '写入受保护目录文件',
  'container.list': '列出容器', 'container.logs': '读取容器日志',
  'container.start': '启动容器', 'container.stop': '停止容器',
  'container.deploy': '部署容器', 'network.firewall.expose': '临时开放防火墙端口',
  'k3s.preflight': 'K3s 安装前置校验',
  'k3s.install': 'K3s 安装节点', 'k3s.upgrade': 'K3s 升级节点'
 };
 const supported = (['package.install', 'file.write.protected', 'container.list', 'container.logs', 'container.start', 'container.stop', 'container.deploy', 'network.firewall.expose', 'k3s.preflight', 'k3s.install', 'k3s.upgrade'] as const satisfies readonly StandardCapability[])
  .filter(capability => agent.capabilities.includes(capability));
 const helperReady = agent.status === 'ONLINE' && agent.helperStatus === 'AVAILABLE';
 const [capability, setCapability] = useState<StandardCapability>(supported[0] ?? 'container.list');
 const [target, setTarget] = useState(''); const [packages, setPackages] = useState(''); const [content, setContent] = useState(''); const [rollbackSeconds, setRollbackSeconds] = useState('90');
 const [image, setImage] = useState('nginx:1.27'); const [hostPort, setHostPort] = useState(''); const [containerPort, setContainerPort] = useState('');
 const [k3sRole, setK3sRole] = useState<'server' | 'agent'>('server');
 const [k3sVersion, setK3sVersion] = useState('v1.37.1+k3s1'); const [k3sServerUrl, setK3sServerUrl] = useState(''); const [k3sToken, setK3sToken] = useState('');
 const [k3sUpgradeConfirmed, setK3sUpgradeConfirmed] = useState(false);
 const [firewallInterface, setFirewallInterface] = useState(''); const [firewallProtocol, setFirewallProtocol] = useState<'tcp' | 'udp'>('tcp'); const [firewallPort, setFirewallPort] = useState('');
 const [reason, setReason] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [key, setKey] = useState(createUuid());
 useEffect(() => { if (!supported.includes(capability)) setCapability(supported[0] ?? 'container.list'); }, [agent.capabilities.join(','), capability]);
 const needsTarget = capability === 'file.write.protected' || capability === 'network.firewall.expose' || ['container.logs', 'container.start', 'container.stop', 'container.deploy'].includes(capability);
 async function submit(event: FormEvent) {
  event.preventDefault(); if (!supported.includes(capability) || !helperReady) return;
  const payload = capability === 'k3s.preflight' ? { role: k3sRole }
   : capability === 'k3s.install' ? { role: k3sRole, version: k3sVersion.trim(), serverUrl: k3sRole === 'agent' ? k3sServerUrl.trim() : null, token: k3sToken }
   : capability === 'k3s.upgrade' ? { role: k3sRole, version: k3sVersion.trim() }
   : capability === 'package.install' ? { packages: packages.split(',').map(name => name.trim()).filter(Boolean) }
   : capability === 'file.write.protected' ? { content }
   : capability === 'container.deploy' ? { image: image.trim(), hostPort: hostPort.trim() ? Number(hostPort) : null, containerPort: containerPort.trim() ? Number(containerPort) : null }
   : capability === 'network.firewall.expose' ? { interfaceName: firewallInterface.trim(), protocol: firewallProtocol, port: Number(firewallPort), rollbackSeconds: Number(rollbackSeconds) } : undefined;
  const body: HostHelperOperationRequest = { capability, target: needsTarget ? target.trim() : '', reason, timeoutSeconds: ['k3s.install', 'k3s.upgrade'].includes(capability) ? 600 : capability === 'package.install' ? 300 : 60, expectedGeneration: host.generation, ...(payload ? { payload } : {}) };
  setBusy(true); setError('');
  try {
   await api<Operation>(`/hosts/${host.id}/operations`, 'POST', body, { 'Idempotency-Key': key }, true);
   notify(`${isMock ? '模拟' : ''}Helper 任务已接受，可在操作任务中查看结果`);
   setReason(''); setTarget(''); setPackages(''); setContent(''); setRollbackSeconds('90'); setImage('nginx:1.27'); setHostPort(''); setContainerPort(''); setFirewallInterface(''); setFirewallProtocol('tcp'); setFirewallPort(''); setK3sToken(''); setKey(createUuid()); refresh();
  } catch (e) { setError((e as Error).message); }
  finally { setBusy(false); }
 }
 if (supported.length === 0) return <><h3>Helper 主机操作</h3><p className="muted">主机尚未上报可用操作。请确认 Agent 与 Helper 在线；容器和防火墙操作还需要对应运行时及系统工具。</p></>;
 const deploymentPortsMismatch = Boolean(hostPort.trim()) !== Boolean(containerPort.trim());
 return <><h3>Helper 主机操作</h3><form className="restart-form" onSubmit={submit}>
  <p className="muted">操作通过固定 Helper 能力执行，不开放任意命令。Helper 校验操作类型、参数和文件路径，并保留容器资源限制与防火墙自动回滚。</p>
  <Field label="操作类型"><select value={capability} onChange={event => { setCapability(event.target.value as StandardCapability); setKey(createUuid()); setError(''); }}>
   {supported.map(item => <option key={item} value={item}>{actionLabels[item]}</option>)}
  </select></Field>
  {needsTarget && <Field label={capability === 'file.write.protected' ? '文件名' : capability === 'network.firewall.expose' ? '规则名' : '容器名'}><input required maxLength={128} value={target} onChange={event => setTarget(event.target.value)} placeholder={capability === 'file.write.protected' ? '例如 app.conf' : capability === 'network.firewall.expose' ? '例如 ssh-test' : '例如 web-gateway'} /></Field>}
  {capability === 'package.install' && <Field label="软件包名（逗号分隔）"><input required maxLength={512} value={packages} onChange={event => setPackages(event.target.value)} placeholder="例如 curl, jq" /></Field>}
  {capability === 'k3s.preflight' && <><Field label="K3s 节点角色"><select value={k3sRole} onChange={event => setK3sRole(event.target.value as 'server' | 'agent')}><option value="server">Server（控制面）</option><option value="agent">Agent（工作节点）</option></select></Field><small className="muted">只读检查主机资源、系统管理器、cgroup 和已有 K3s 安装；不会安装或修改主机。</small></>}
  {capability === 'k3s.install' && <><Field label="K3s 节点角色"><select value={k3sRole} onChange={event => setK3sRole(event.target.value as 'server' | 'agent')}><option value="server">Server（初始化控制面）</option><option value="agent">Agent（加入现有集群）</option></select></Field><Field label="固定 K3s 版本"><input required maxLength={32} pattern="v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+" value={k3sVersion} onChange={event => setK3sVersion(event.target.value)} placeholder="v1.37.1+k3s1" /></Field>{k3sRole === 'agent' && <Field label="K3s API 地址"><input required maxLength={512} type="url" value={k3sServerUrl} onChange={event => setK3sServerUrl(event.target.value)} placeholder="https://k3s.example.net:6443" /></Field>}<Field label="集群令牌"><input required type="password" autoComplete="new-password" minLength={16} maxLength={512} pattern="[A-Za-z0-9_.:-]+" value={k3sToken} onChange={event => setK3sToken(event.target.value)} /><small className="muted">Server 首次初始化使用短令牌；加入集群的 Agent 可使用 K3s 短令牌或 K10 安全令牌。令牌仅写入 root 可读文件，不会回显到结果。</small></Field><small className="muted">仅支持空白主机上的固定版本安装；不会覆盖已有 K3s 文件或数据。Agent 安装后仍须通过集群视图确认 Node Ready。</small></>}
  {capability === 'k3s.upgrade' && <><Field label="现有 K3s 节点角色"><select value={k3sRole} onChange={event => setK3sRole(event.target.value as 'server' | 'agent')}><option value="server">Server</option><option value="agent">Agent</option></select></Field><Field label="目标 K3s 版本"><input required maxLength={32} pattern="v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+" value={k3sVersion} onChange={event => setK3sVersion(event.target.value)} placeholder="v1.37.1+k3s1" /></Field><small className="muted">仅升级由 NaNPanel 固定 unit 管理的 K3s；只允许同一 Kubernetes minor 或向前一个 minor。健康检查失败时会恢复旧二进制。Agent 节点的 Kubernetes Ready 状态仍需在集群页复核。</small><label className="check-field"><input type="checkbox" checked={k3sUpgradeConfirmed} onChange={event => setK3sUpgradeConfirmed(event.target.checked)} /><span>我已确认当前节点处于维护窗口，并会按集群顺序逐台升级。</span></label></>}
  {capability === 'file.write.protected' && <Field label="文件内容"><textarea required maxLength={32768} value={content} onChange={event => setContent(event.target.value)} placeholder="最多 32 KiB；写入主机 root 管理的固定目录" /></Field>}
  {capability === 'container.deploy' && <><Field label="容器镜像"><input required maxLength={255} pattern="[A-Za-z0-9][A-Za-z0-9._:/@-]{0,254}" value={image} onChange={event => setImage(event.target.value)} placeholder="例如 nginx:1.27" /></Field><div className="ssh-form-grid"><Field label="Loopback 主机端口（可选）"><input type="number" min={1} max={65535} value={hostPort} onChange={event => setHostPort(event.target.value)} /></Field><Field label="容器端口（可选）"><input type="number" min={1} max={65535} value={containerPort} onChange={event => setContainerPort(event.target.value)} /></Field></div><small className="muted">两个端口必须同时填写或留空；端口发布仍仅绑定 127.0.0.1。镜像和端口受固定参数校验与容器资源限制约束。</small></>}
  {capability === 'network.firewall.expose' && <><div className="ssh-form-grid"><Field label="网络接口"><input required maxLength={15} pattern="[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}" value={firewallInterface} onChange={event => setFirewallInterface(event.target.value)} placeholder="例如 eth0" /></Field><Field label="协议"><select value={firewallProtocol} onChange={event => setFirewallProtocol(event.target.value as 'tcp' | 'udp')}><option value="tcp">TCP</option><option value="udp">UDP</option></select></Field></div><Field label="端口"><input required type="number" min={1} max={65535} step={1} value={firewallPort} onChange={event => setFirewallPort(event.target.value)} /></Field><Field label="自动回滚秒数"><input required type="number" min={30} max={300} step={1} value={rollbackSeconds} onChange={event => setRollbackSeconds(event.target.value)} /><small className="muted">临时添加接口/协议/端口规则，执行前先挂载 systemd 定时回滚；系统会在执行前校验参数，并自动回滚临时规则。</small></Field></>}
  <Field label="操作原因"><textarea required maxLength={500} value={reason} onChange={event => setReason(event.target.value)} placeholder="记录本次运维操作的原因" /></Field>
  {error && <div className="alert error" role="alert">{error}</div>}
  <div className="form-actions"><span className="muted">主机版本 {host.generation} · K3s 操作最长 600 秒</span><button className="primary" disabled={busy || !helperReady || !reason.trim() || (needsTarget && !target.trim()) || (capability === 'container.deploy' && (!image.trim() || deploymentPortsMismatch)) || (capability === 'k3s.install' && k3sRole === 'server' && k3sToken.startsWith('K10')) || (capability === 'k3s.upgrade' && !k3sUpgradeConfirmed)}>{busy ? '正在下发…' : '提交 Helper 操作'}</button></div>
 </form></>;
}
function AgentMaintenanceDialog({ agentId, mode, installation, installationError, selfUpdateSupported, selfUpdateReported, agentStatus, helperStatus, close }: { agentId: string; mode: AgentMaintenanceMode; installation?: AgentInstallation; installationError: string; selfUpdateSupported: boolean; selfUpdateReported: boolean; agentStatus?: Agent['status']; helperStatus?: Agent['helperStatus']; close: () => void }) {
 const [copied, setCopied] = useState(false);
 const [updating, setUpdating] = useState(false);
 const [updateError, setUpdateError] = useState('');
 const [operation, setOperation] = useState<AgentUpdateOperation>();
 let command = '';
 let commandError = '';
 if (installation) {
  try { command = agentMaintenanceCommand(mode, window.location.origin, installation.controllerFingerprint, installation.agentDownloadPath, installation.helperDownloadPath); }
  catch (e) { commandError = (e as Error).message; }
 }
 useEffect(() => {
  if (!operation || operation.status === 'SUCCEEDED' || operation.status === 'FAILED') return;
  let active = true;
  const refresh = () => api<AgentUpdateOperation>(`/agents/${agentId}/updates/${operation.id}`, 'GET', undefined, {}, true)
    .then(result => { if (active) setOperation(result.data); })
    .catch(e => { if (active) setUpdateError((e as Error).message); });
  const timer = window.setInterval(() => { void refresh(); }, 1500);
  return () => { active = false; window.clearInterval(timer); };
 }, [agentId, operation?.id, operation?.status]);
 async function copyCommand() {
  if (!command) return;
  try { await navigator.clipboard.writeText(command); setCopied(true); }
  catch { setCopied(false); }
 }
 async function startUpdate() {
  if (!agentId || isMock) return;
  setUpdating(true); setUpdateError('');
  try {
   const result = await api<AgentUpdateOperation>(`/agents/${agentId}/updates`, 'POST', {}, {}, true);
   setOperation(result.data);
  } catch (e) { setUpdateError((e as Error).message); }
  finally { setUpdating(false); }
 }
 const title = mode === 'reinstall' ? '重新安装 Agent' : '更新 Agent';
 const automaticUpdate = mode === 'update' && selfUpdateSupported && !isMock;
 return <div className="modal-backdrop agent-maintenance-backdrop"><section className="modal host-enrollment-modal agent-maintenance-modal" role="dialog" aria-modal="true" aria-labelledby="agent-maintenance-title"><div className="card-title"><div><span className="eyebrow">AGENT MAINTENANCE</span><h2 id="agent-maintenance-title">{title}</h2></div><button className="icon-button" aria-label={`关闭${title}`} onClick={close}>×</button></div><p className="muted">{automaticUpdate ? '更新任务会异步发送到在线 Agent，由 root Helper 从固定的 HTTPS 控制器下载并替换 Agent，然后重启服务。' : '在目标 Linux x86_64 主机上以 root 身份运行命令。命令会核对控制面板证书指纹，再安装当前版本。'}</p>{mode === 'reinstall' ? <div className="alert">重新安装会修复 Agent 与 Helper 的 systemd 服务定义并替换程序，保留现有主机身份、证书和配置，不会创建重复主机资产。</div> : selfUpdateSupported ? <div className="alert">当前 Agent 在线，Helper 可用，支持在后台自行更新。</div> : selfUpdateReported ? <div className="alert">最近一次心跳报告了自更新能力，但当前 Agent 状态为 {agentStatus ?? '未知'}、Helper 状态为 {helperStatus ?? '未知'}。后台更新要求 Agent 在线且 Helper 可用；下面的命令可用于手动修复或更新。</div> : <div className="alert">最近一次心跳未报告可用的自更新能力，需要在目标主机手动执行更新命令。</div>}{isMock ? <div className="alert">Mock 演示不提供可执行命令或远程更新任务。</div> : automaticUpdate ? <>{operation ? <><div className="form-actions"><span>任务状态</span><Badge value={operation.status} /></div><p className="muted">{operation.detail ?? (operation.status === 'RUNNING' ? '等待 Agent 下载并安装更新…' : '任务已创建')}</p><p className="muted">任务 ID：<span className="mono">{operation.id}</span></p>{operation.status === 'SUCCEEDED' && <div className="alert">Agent 已报告程序替换成功，正在重启；关闭后可刷新主机详情查看新心跳。</div>}{operation.status === 'FAILED' && <div className="alert error">Agent 更新失败：{operation.detail}</div>}</> : <>{updateError && <div className="alert error" role="alert">{updateError}</div>}<div className="form-actions"><button className="secondary" onClick={close}>取消</button><button className="primary" disabled={updating} onClick={() => void startUpdate()}>{updating ? '正在下发…' : '开始异步更新'}</button></div></>}</> : installationError ? <div className="alert error" role="alert">无法读取安装信息：{installationError}</div> : commandError ? <div className="alert error" role="alert">{commandError}</div> : !command ? <p className="muted">正在读取控制面板安装信息…</p> : <><textarea className="install-command" aria-label={`${title}命令`} readOnly value={command} onFocus={e => e.currentTarget.select()} /><div className="form-actions"><button className="secondary" onClick={copyCommand}>{copied ? '已复制' : '复制命令'}</button><button className="primary" onClick={close}>完成</button></div>{copied && <small className="muted">命令已复制；请在目标主机终端粘贴执行。</small>}</>}</section></div>;
}
function operationTitle(operation: Operation) {
 if (operation.kind === 'SYSTEMD_JOURNAL_READ') return 'systemd 日志查询';
 if (operation.kind === 'SYSTEM_SERVICE_RESTART') return 'systemd 服务重启';
 if (operation.kind === 'SSH_SYSTEMD_RESTART') return 'SSH systemd 救援';
 if (operation.kind === 'SSH_AGENT_INSTALL') return 'SSH Agent 初始化';
 const titles: Record<string, string> = { 'package.install': '安装软件包', 'file.write.protected': '写入受保护文件', 'container.list': '列出容器', 'container.logs': '读取容器日志', 'container.start': '启动容器', 'container.stop': '停止容器', 'container.deploy': '部署容器', 'network.firewall.expose': '临时开放防火墙端口' };
 return titles[operation.capability ?? ''] ?? operation.capability ?? '受限 Helper 操作';
}
function Operations() {
 const [status, setStatus] = useState(''); const r = useResource<OperationPage>(`/operations${status ? `?status=${status}` : ''}`, 1500, true); const [selected, setSelected] = useState<string>(); const op = r.data?.items.find(x => x.id === selected);
 const evidence = op?.result && 'evidence' in op.result ? op.result.evidence : undefined;
 const journal = op?.result && 'journal' in op.result ? op.result.journal : undefined;
 const helperOutput = op?.result && 'output' in op.result ? op.result.output : undefined;
 return <><PageHeading title="操作任务" description="跟踪已持久化任务的执行、验证与最终结果。" /><div className="alert">UNKNOWN 表示执行结果不确定，需要对账；不能视作失败或自动重新执行。</div><section className="card"><div className="toolbar"><select aria-label="任务状态" value={status} onChange={e => setStatus(e.target.value)}><option value="">全部状态</option>{['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED'].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select><span className="muted">每 1.5 秒自动更新</span></div><ResourceState {...r} /><div className="table-wrap"><table><thead><tr><th>任务 / 原因</th><th>主机</th><th>状态</th><th>阶段</th><th>创建时间</th><th /></tr></thead><tbody>{r.data?.items.map(o => <tr key={o.id}><td><b>{operationTitle(o)}</b><small>{o.target || o.reason}</small></td><td className="mono">{o.hostId.slice(0, 8)}</td><td><Badge value={o.status} /></td><td className="mono">{o.phase}</td><td>{time(o.createdAt)}</td><td><button className="link" onClick={() => setSelected(o.id)}>查看详情</button></td></tr>)}</tbody></table>{!r.data?.items.length && !r.loading && <Empty>暂无任务。前往主机资产查看服务操作。</Empty>}</div></section>{op && <div className="modal-backdrop"><div className="modal"><div className="card-title"><h2>任务详情</h2><button className="icon-button" aria-label="关闭任务详情" onClick={() => setSelected(undefined)}>×</button></div><Badge value={op.status} /><dl className="info-list">{[['任务 ID', op.id], ['执行阶段', op.phase], ['创建时间', time(op.createdAt)], ['结束时间', time(op.finishedAt)], ['执行依据', evidence ?? op.error?.message ?? '等待执行与验证结果']].map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>{journal && <pre className="journal-output">{journal}</pre>}{helperOutput && <pre className="journal-output">{helperOutput}</pre>}<div className="alert">{op.status === 'UNKNOWN' ? '结果未知，保持互斥锁，等待 Agent 执行记录对账。' : op.kind === 'SYSTEMD_JOURNAL_READ' || op.kind === 'HOST_HELPER_OPERATION' ? '输出可能包含敏感信息，请按管理员权限保护操作记录。' : '任务成功仅确认服务状态，不代表业务端到端健康。'}</div></div></div>}</>;
}
function KubernetesClusters({ notify }: { notify: (message: string) => void }) {
 const [clusters, setClusters] = useState<KubernetesClusterSummary[]>([]); const [hosts, setHosts] = useState<Host[]>([]); const [discoveries, setDiscoveries] = useState<KubernetesRuntimeDiscovery[]>([]); const [nodes, setNodes] = useState<Record<string, KubernetesClusterNode[]>>({});
 const [workspace, setWorkspace] = useState<KubernetesWorkspace>('overview'); const [returnWorkspace, setReturnWorkspace] = useState<KubernetesWorkspace>('overview'); const [selectedNodeClusterId, setSelectedNodeClusterId] = useState(''); const [showImportForm, setShowImportForm] = useState(false);
 const [rollouts, setRollouts] = useState<K3sRolloutPlan[]>([]); const [rolloutClusterId, setRolloutClusterId] = useState(''); const [rolloutVersion, setRolloutVersion] = useState(''); const [rolloutReason, setRolloutReason] = useState(''); const [rolloutConfirmed, setRolloutConfirmed] = useState(false); const [rolloutBusy, setRolloutBusy] = useState(false);
 const [installations, setInstallations] = useState<K3sInstallationPlan[]>([]); const [installServerHostId, setInstallServerHostId] = useState(''); const [installAgentHostIds, setInstallAgentHostIds] = useState<string[]>([]); const [installVersion, setInstallVersion] = useState(''); const [installServerUrl, setInstallServerUrl] = useState(''); const [installToken, setInstallToken] = useState(''); const [installReason, setInstallReason] = useState(''); const [installConfirmed, setInstallConfirmed] = useState(false); const [installClusterIds, setInstallClusterIds] = useState<Record<string, string>>({}); const [installBusy, setInstallBusy] = useState(false);
 const [name, setName] = useState(''); const [kind, setKind] = useState<KubernetesClusterImportRequest['kind']>('K3S'); const [contextName, setContextName] = useState(''); const [kubeconfig, setKubeconfig] = useState('');
 const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [nodeLoading, setNodeLoading] = useState<Record<string, boolean>>({}); const [error, setError] = useState('');
 const [namespaceSnapshots, setNamespaceSnapshots] = useState<Record<string, KubernetesTopologyNamespaceSnapshot>>({}); const [selectedNamespaces, setSelectedNamespaces] = useState<Record<string, string>>({});
 const [managedDeployments, setManagedDeployments] = useState<Record<string, KubernetesManagedDeployment[]>>({}); const [deploymentLoading, setDeploymentLoading] = useState<Record<string, boolean>>({}); const [deploymentBusy, setDeploymentBusy] = useState<Record<string, boolean>>({}); const [scaleValues, setScaleValues] = useState<Record<string, string>>({});
 async function load() {
  if (isMock) { setLoading(false); return; }
  setLoading(true); setError('');
  try {
   const [clusterResult, hostResult, discoveryResult, rolloutResult, installationResult] = await Promise.all([
    api<KubernetesClusterList>('/kubernetes/clusters', 'GET', undefined, {}, true),
    api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true),
    api<KubernetesRuntimeDiscoveryList>('/kubernetes/discoveries', 'GET', undefined, {}, true),
    api<K3sRolloutList>('/kubernetes/rollouts', 'GET', undefined, {}, true),
    api<K3sInstallationList>('/kubernetes/installations', 'GET', undefined, {}, true),
   ]);
   setClusters(clusterResult.data.items); setHosts(hostResult.data.items); setDiscoveries(discoveryResult.data.items);
   setRollouts(rolloutResult.data.items);
   setInstallations(installationResult.data.items);
   setSelectedNodeClusterId(current => current || clusterResult.data.items[0]?.id || '');
   setInstallServerHostId(current => current || hostResult.data.items.find(host => host.capabilities.includes('k3s.install'))?.id || '');
   setRolloutClusterId(current => current || clusterResult.data.items.find(cluster => cluster.kind === 'K3S')?.id || '');
  } catch (e) { setError((e as Error).message); }
  finally { setLoading(false); }
 }
 useEffect(() => { void load(); }, []);
 useEffect(() => {
  if (isMock) return;
  const timer = window.setInterval(() => {
   void api<K3sRolloutList>('/kubernetes/rollouts', 'GET', undefined, {}, true)
    .then(result => setRollouts(result.data.items)).catch(() => undefined);
   void api<K3sInstallationList>('/kubernetes/installations', 'GET', undefined, {}, true)
    .then(result => setInstallations(result.data.items)).catch(() => undefined);
  }, 5000);
  return () => window.clearInterval(timer);
 }, []);
 async function importCluster(event: FormEvent<HTMLFormElement>) {
  event.preventDefault(); setBusy(true); setError('');
  const request: KubernetesClusterImportRequest = { name: name.trim(), kind, contextName: contextName.trim(), kubeconfig };
  try {
   const result = await api<KubernetesClusterSummary>('/kubernetes/clusters', 'POST', request, {}, true);
   setClusters(current => [...current, result.data].sort((a, b) => a.name.localeCompare(b.name)));
   setSelectedNodeClusterId(current => current || result.data.id);
   setName(''); setContextName(''); setKubeconfig(''); setShowImportForm(false); notify(`已导入 Kubernetes 集群 ${result.data.name}`);
  } catch (e) { setError((e as Error).message); }
  finally { setBusy(false); }
 }
 async function createRollout(event: FormEvent<HTMLFormElement>) {
  event.preventDefault(); setRolloutBusy(true); setError('');
  try {
   const result = await api<K3sRolloutPlan>('/kubernetes/rollouts', 'POST', {
    clusterId: rolloutClusterId, targetVersion: rolloutVersion.trim(), reason: rolloutReason.trim(),
   }, {}, true);
   setRollouts(current => [result.data, ...current]); setRolloutVersion(''); setRolloutReason(''); setRolloutConfirmed(false);
   notify(`已创建 K3s 升级草稿 ${result.data.id.slice(0, 8)}；请核对节点顺序后显式启动`);
  } catch (e) { setError((e as Error).message); }
  finally { setRolloutBusy(false); }
 }
 async function startRollout(plan: K3sRolloutPlan) {
  if (!rolloutConfirmed || !window.confirm(`将按列出的顺序逐台升级 ${plan.clusterName} 至 ${plan.targetVersion}。确认启动？`)) return;
  setRolloutBusy(true); setError('');
  try {
   const result = await api<K3sRolloutPlan>(`/kubernetes/rollouts/${encodeURIComponent(plan.id)}/start`, 'POST', {}, {}, true);
   setRollouts(current => current.map(item => item.id === plan.id ? result.data : item));
   setRolloutConfirmed(false); notify(`已启动 K3s 升级计划 ${plan.id.slice(0, 8)}`);
  } catch (e) { setError((e as Error).message); }
  finally { setRolloutBusy(false); }
 }
 async function createInstallation(event: FormEvent<HTMLFormElement>) {
  event.preventDefault(); setInstallBusy(true); setError('');
  try {
   const result = await api<K3sInstallationPlan>('/kubernetes/installations', 'POST', {
    serverHostId: installServerHostId, agentHostIds: installAgentHostIds,
    targetVersion: installVersion.trim(), serverUrl: installServerUrl.trim(), token: installToken,
    reason: installReason.trim(),
   }, {}, true);
   setInstallations(current => [result.data, ...current]); setInstallToken(''); setInstallVersion(''); setInstallServerUrl(''); setInstallReason(''); setInstallAgentHostIds([]); setInstallConfirmed(false);
   notify(`已创建 K3s 安装草稿 ${result.data.id.slice(0, 8)}；请检查主机顺序后显式启动`);
  } catch (e) { setError((e as Error).message); }
  finally { setInstallBusy(false); }
 }
 async function startInstallation(plan: K3sInstallationPlan) {
  if (!installConfirmed || !window.confirm(`将先在所选主机安装 K3s Server，再等待集群登记和 Node 映射后逐台加入 ${plan.steps.length - 1} 个 Agent。确认启动？`)) return;
  setInstallBusy(true); setError('');
  try {
   const result = await api<K3sInstallationPlan>(`/kubernetes/installations/${encodeURIComponent(plan.id)}/start`, 'POST', {}, {}, true);
   setInstallations(current => current.map(item => item.id === plan.id ? result.data : item)); setInstallConfirmed(false);
   notify(`已启动 K3s 安装计划 ${plan.id.slice(0, 8)}`);
  } catch (e) { setError((e as Error).message); }
  finally { setInstallBusy(false); }
 }
 async function registerInstallationCluster(plan: K3sInstallationPlan) {
  const clusterId = installClusterIds[plan.id]; if (!clusterId) return;
  setInstallBusy(true); setError('');
  try {
   const result = await api<K3sInstallationPlan>(`/kubernetes/installations/${encodeURIComponent(plan.id)}/register-cluster`, 'POST', { clusterId }, {}, true);
   setInstallations(current => current.map(item => item.id === plan.id ? result.data : item));
   notify(`已关联集群；Agent 将在 Server Node Ready 并映射后逐台安装`);
  } catch (e) { setError((e as Error).message); }
  finally { setInstallBusy(false); }
 }
 function prepareDiscoveredImport(discovery: KubernetesRuntimeDiscovery) {
  setName(`${discovery.hostname}-${discovery.runtimeKind.toLowerCase()}`.slice(0, 80));
  setKind(discovery.runtimeKind === 'K3S' ? 'K3S' : 'KUBERNETES');
  setContextName(discovery.runtimeKind === 'K3S' ? 'default' : 'kubernetes-admin@kubernetes');
  setWorkspace('clusters'); setShowImportForm(true);
  notify(`已为 ${discovery.hostname} 填入导入信息；请粘贴 kubeconfig 完成凭据验证`);
 }
 async function refreshClusterStatus(clusterId: string) {
  try {
   const status = await api<KubernetesClusterSummary>(`/kubernetes/clusters/${encodeURIComponent(clusterId)}`, 'GET', undefined, {}, true);
   setClusters(current => current.map(cluster => cluster.id === clusterId ? { ...cluster, healthStatus: status.data.healthStatus, cloudCoreStatus: status.data.cloudCoreStatus, cloudCoreObservedAt: status.data.cloudCoreObservedAt, observedAt: status.data.observedAt, observationsStale: status.data.observationsStale, lastErrorCode: status.data.lastErrorCode } : cluster));
  } catch { /* Keep the last successful status visible when the registry is also unavailable. */ }
 }
 async function refreshNodes(clusterId: string) {
  setNodeLoading(current => ({ ...current, [clusterId]: true })); setError('');
  try {
   const result = await api<KubernetesClusterNodeList>(`/kubernetes/clusters/${encodeURIComponent(clusterId)}/nodes`, 'GET', undefined, {}, true);
   setNodes(current => ({ ...current, [clusterId]: result.data.items }));
   setClusters(current => current.map(cluster => cluster.id === clusterId ? { ...cluster, nodeCount: result.data.items.filter(node => node.nodePresent).length, healthStatus: 'HEALTHY', observedAt: result.data.observedAt, observationsStale: false, lastErrorCode: null } : cluster));
   await refreshClusterStatus(clusterId);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setNodeLoading(current => ({ ...current, [clusterId]: false })); }
 }
 async function mapHost(clusterId: string, node: KubernetesClusterNode, hostId: string) {
  const path = `/kubernetes/clusters/${encodeURIComponent(clusterId)}/nodes/${encodeURIComponent(node.nodeUid)}/host`;
  setError('');
  try {
   if (hostId) await api<KubernetesClusterNode>(path, 'PUT', { hostId }, {}, true);
   else await api(path, 'DELETE', undefined, {}, true);
   await refreshNodes(clusterId);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
 }
 async function loadNamespaces(clusterId: string) {
  setDeploymentLoading(current => ({ ...current, [`${clusterId}:namespaces`]: true })); setError('');
  try {
   const result = await api<KubernetesTopologyNamespaceSnapshot>(`/kubernetes/clusters/${encodeURIComponent(clusterId)}/topology/namespaces`, 'GET', undefined, {}, true);
   setNamespaceSnapshots(current => ({ ...current, [clusterId]: result.data }));
   const namespace = selectedNamespaces[clusterId] || result.data.namespaces[0]?.name || '';
   setSelectedNamespaces(current => ({ ...current, [clusterId]: namespace }));
   if (namespace) await loadManagedDeployments(clusterId, namespace);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setDeploymentLoading(current => ({ ...current, [`${clusterId}:namespaces`]: false })); }
 }
 function managedDeploymentPath(clusterId: string, namespace: string) {
  return `/kubernetes/clusters/${encodeURIComponent(clusterId)}/namespaces/${encodeURIComponent(namespace)}/deployments`;
 }
 async function loadManagedDeployments(clusterId: string, namespace = selectedNamespaces[clusterId] ?? '') {
  if (!namespace) return;
  const key = `${clusterId}:${namespace}`;
  setDeploymentLoading(current => ({ ...current, [key]: true })); setError('');
  try {
   const result = await api<KubernetesManagedDeploymentList>(managedDeploymentPath(clusterId, namespace), 'GET', undefined, {}, true);
   setManagedDeployments(current => ({ ...current, [key]: result.data.items }));
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setDeploymentLoading(current => ({ ...current, [key]: false })); }
 }
 async function createManagedDeployment(event: FormEvent<HTMLFormElement>, clusterId: string, namespace: string) {
  event.preventDefault(); const form = event.currentTarget; const values = new FormData(form);
  const payload: KubernetesManagedDeploymentCreateRequest = { name: String(values.get('name') ?? ''), image: String(values.get('image') ?? ''), replicas: Number(values.get('replicas')), containerPort: Number(values.get('containerPort')) };
  const key = `${clusterId}:${namespace}`; setDeploymentBusy(current => ({ ...current, [key]: true })); setError('');
  try {
   await api<KubernetesManagedDeployment>(managedDeploymentPath(clusterId, namespace), 'POST', payload, {}, true);
   form.reset(); await loadManagedDeployments(clusterId, namespace); notify(`已创建 ${namespace}/${payload.name} Deployment`);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setDeploymentBusy(current => ({ ...current, [key]: false })); }
 }
 async function scaleManagedDeployment(clusterId: string, namespace: string, deployment: KubernetesManagedDeployment) {
  const key = `${clusterId}:${namespace}`; const scaleKey = `${key}:${deployment.name}`; const replicas = Number(scaleValues[scaleKey] ?? deployment.replicas ?? 1);
  setDeploymentBusy(current => ({ ...current, [key]: true })); setError('');
  try {
   await api<KubernetesManagedDeployment>(`${managedDeploymentPath(clusterId, namespace)}/${encodeURIComponent(deployment.name)}/scale`, 'PUT', { replicas } satisfies KubernetesManagedDeploymentScaleRequest, {}, true);
   await loadManagedDeployments(clusterId, namespace); notify(`已调整 ${namespace}/${deployment.name} 的副本数`);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setDeploymentBusy(current => ({ ...current, [key]: false })); }
 }
 async function deleteManagedDeployment(clusterId: string, namespace: string, deployment: KubernetesManagedDeployment) {
  if (!window.confirm(`删除由 NaNPanel 管理的 ${namespace}/${deployment.name}？`)) return;
  const key = `${clusterId}:${namespace}`; setDeploymentBusy(current => ({ ...current, [key]: true })); setError('');
  try {
   await api(`${managedDeploymentPath(clusterId, namespace)}/${encodeURIComponent(deployment.name)}`, 'DELETE', undefined, {}, true);
   await loadManagedDeployments(clusterId, namespace); notify(`已删除 ${namespace}/${deployment.name}`);
  } catch (e) { setError((e as Error).message); await refreshClusterStatus(clusterId); }
  finally { setDeploymentBusy(current => ({ ...current, [key]: false })); }
 }
 const selectedNodeCluster = clusters.find(cluster => cluster.id === selectedNodeClusterId);
 const selectedNodes = selectedNodeCluster ? nodes[selectedNodeCluster.id] : undefined;
 const totalNodes = clusters.reduce((total, cluster) => total + cluster.nodeCount, 0);
 const healthyClusters = clusters.filter(cluster => cluster.healthStatus === 'HEALTHY' && !cluster.observationsStale).length;
 const unmappedDiscoveries = discoveries.filter(discovery => !clusters.some(cluster => cluster.discoveredHostId === discovery.hostId)).length;
 const openInstallations = installations.filter(plan => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(plan.state)).length;
 const openRollouts = rollouts.filter(plan => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(plan.state)).length;
 const presentNodes = selectedNodes?.filter(node => node.nodePresent) ?? [];
 const readyNodes = presentNodes.filter(node => node.ready).length;
 const mappedNodes = presentNodes.filter(node => node.hostId).length;
 const workspaceHeadings: Record<KubernetesWorkspace, { title: string; description: string }> = {
  overview: { title: 'Kubernetes 集群管理', description: '统一查看集群健康、节点状态与生命周期任务。' },
  clusters: { title: '集群管理', description: '登记 Kubernetes 集群、查看连接状态并管理集群资源。' },
  nodes: { title: '节点管理', description: '查看 Node 健康状态，并维护主机资产映射。' },
  upgrades: { title: '集群升级', description: '创建并跟踪逐节点升级计划。' },
  installation: { title: '安装 K3s 集群', description: '通过持久化计划逐台安装 Server 与 Agent；每一步都会等待集群和节点状态确认。' },
 };
 const heading = workspaceHeadings[workspace];
 return <><PageHeading title={heading.title} description={heading.description} action={workspace === 'installation' ? <button className="secondary" onClick={() => setWorkspace(returnWorkspace === 'installation' ? 'overview' : returnWorkspace)}>← 返回集群管理</button> : <div className="k8s-heading-actions"><button className="secondary" onClick={() => void load()} disabled={loading || isMock}>↻ 刷新</button><button className="primary" onClick={() => { setReturnWorkspace(workspace); setWorkspace('installation'); }} disabled={isMock}>＋ 安装集群</button></div>} />
  {isMock && <div className="alert">当前是纯 Mock 演示模式，集群管理 API 已停用。切换到 hybrid 或 live 模式后可导入真实集群。</div>}
  {error && <div className="alert error" role="alert">{error}</div>}
  <div className={`k8s-workspace${workspace === 'installation' ? ' is-installation' : ''}`}>
   {workspace !== 'installation' && <aside className="k8s-subnav" aria-label="Kubernetes 管理菜单"><div className="k8s-subnav-heading"><span className="eyebrow">CLUSTER MANAGEMENT</span><strong>集群工作区</strong><small>资源与生命周期管理</small></div><div role="tablist" aria-label="Kubernetes 工作区">{kubernetesTabs.map(tab => <button id={`k8s-tab-${tab.id}`} role="tab" aria-selected={workspace === tab.id} aria-controls="k8s-workspace-panel" className={workspace === tab.id ? 'active' : ''} key={tab.id} onClick={() => setWorkspace(tab.id)}><span className="k8s-tab-icon">{tab.icon}</span><span className="k8s-tab-copy"><strong>{tab.label}</strong><small>{tab.description}</small></span>{tab.id === 'clusters' ? <span className="k8s-tab-count">{clusters.length}</span> : tab.id === 'nodes' ? <span className="k8s-tab-count">{totalNodes}</span> : tab.id === 'upgrades' ? <span className="k8s-tab-count">{openRollouts}</span> : null}</button>)}</div><div className="k8s-subnav-footer"><span className={`k8s-status-dot ${loading ? 'is-loading' : clusters.length && healthyClusters === clusters.length ? 'is-ready' : 'is-muted'}`} />{loading ? '正在同步集群' : clusters.length ? `${healthyClusters}/${clusters.length} 个集群健康` : '尚未登记集群'}</div></aside>}
   <div className="k8s-workspace-panel" id="k8s-workspace-panel" role="tabpanel" aria-labelledby={workspace === 'installation' ? undefined : `k8s-tab-${workspace}`}>
  {workspace === 'overview' && <div className="k8s-overview-view"><div className="k8s-view-heading"><div><span className="eyebrow">OVERVIEW</span><h2>集群概览</h2><p>从集群健康、节点数量和发现状态快速进入管理工作区。</p></div><span className="k8s-last-sync">{loading ? '正在更新…' : `登记 ${clusters.length} 个集群`}</span></div><div className="k8s-metric-grid"><article className="k8s-metric-card"><span>已纳管集群</span><strong>{loading ? '—' : clusters.length}</strong><small>连接到控制面的集群</small><i className="k8s-metric-symbol blue">▦</i></article><article className="k8s-metric-card"><span>健康集群</span><strong>{loading ? '—' : healthyClusters}</strong><small>{clusters.length ? `共 ${clusters.length} 个集群` : '等待导入集群'}</small><i className="k8s-metric-symbol green">✓</i></article><article className="k8s-metric-card"><span>集群节点</span><strong>{loading ? '—' : totalNodes}</strong><small>来自最近一次 Node 清单</small><i className="k8s-metric-symbol purple">▤</i></article><article className="k8s-metric-card"><span>待处理任务</span><strong>{loading ? '—' : openInstallations + openRollouts}</strong><small>{openInstallations} 个安装 · {openRollouts} 个升级</small><i className="k8s-metric-symbol amber">◷</i></article></div><div className="k8s-overview-grid"><section className="card k8s-overview-card"><div className="card-title"><div><h2>集群列表</h2><p>健康状态与最近观测</p></div><button className="link" onClick={() => setWorkspace('clusters')}>管理集群 →</button></div>{clusters.length ? <div className="k8s-overview-clusters">{clusters.slice(0, 5).map(cluster => <div className="k8s-overview-cluster" key={cluster.id}><span className="k8s-cluster-kind">{cluster.kind === 'K3S' ? 'K3s' : cluster.kind}</span><div className="k8s-overview-cluster-main"><strong>{cluster.name}</strong><small>{cluster.endpoint} · {cluster.nodeCount} 个 Node · {cluster.observationsStale ? '观测已过期' : `更新于 ${time(cluster.observedAt)}`}</small></div><span className={`k8s-health-mark ${cluster.healthStatus === 'HEALTHY' && !cluster.observationsStale ? 'healthy' : 'unhealthy'}`}><i />{cluster.healthStatus === 'HEALTHY' && !cluster.observationsStale ? '健康' : '需检查'}</span></div>)}</div> : <Empty>{loading ? '正在读取集群信息…' : '还没有纳管集群。可以导入已有集群，或安装一套新的 K3s 集群。'}</Empty>}</section><section className="card k8s-overview-card"><div className="card-title"><div><h2>运行状态</h2><p>只展示控制面已观测到的信息</p></div></div><div className="k8s-status-list"><div><span className="k8s-status-symbol blue">◉</span><div><strong>运行中安装计划</strong><small>Server 安装、集群登记与 Node 映射</small></div><b>{loading ? '—' : openInstallations}</b></div><div><span className="k8s-status-symbol amber">↻</span><div><strong>运行中升级计划</strong><small>逐节点处理并等待 Ready 确认</small></div><b>{loading ? '—' : openRollouts}</b></div><div><span className="k8s-status-symbol purple">⌕</span><div><strong>待确认的运行时发现</strong><small>Agent 报告的 K3s/Kubernetes 主机</small></div><b>{loading ? '—' : unmappedDiscoveries}</b></div></div><div className="k8s-quick-links"><button className="secondary" onClick={() => setWorkspace('nodes')}>查看节点 →</button><button className="secondary" onClick={() => setWorkspace('upgrades')}>升级计划 →</button></div></section></div></div>}
  {workspace === 'nodes' && <section className="card k8s-node-workspace"><div className="card-title"><div><span className="eyebrow">NODE MANAGEMENT</span><h2>节点管理</h2><p>查看所选集群的 Node 就绪状态，并维护 Node 与主机资产的映射关系。</p></div><div className="k8s-node-toolbar"><label>集群<select aria-label="选择要管理节点的集群" value={selectedNodeClusterId} onChange={event => { const clusterId = event.target.value; setSelectedNodeClusterId(clusterId); if (clusterId) void refreshNodes(clusterId); }}><option value="">选择集群</option>{clusters.map(cluster => <option value={cluster.id} key={cluster.id}>{cluster.name} · {cluster.kind}</option>)}</select></label><button className="secondary" disabled={!selectedNodeCluster || !!nodeLoading[selectedNodeCluster.id] || isMock} onClick={() => selectedNodeCluster && void refreshNodes(selectedNodeCluster.id)}>{selectedNodeCluster && nodeLoading[selectedNodeCluster.id] ? '读取中…' : '↻ 刷新 Node'}</button></div></div>
   {!clusters.length ? <Empty>{loading ? '正在读取集群信息…' : '尚无已纳管集群。先在“集群管理”中导入集群，或使用“安装集群”创建 K3s。'}</Empty> : selectedNodeCluster ? <><div className="k8s-node-metrics"><div><span>节点总数</span><strong>{selectedNodes ? presentNodes.length : selectedNodeCluster.nodeCount}</strong></div><div><span>Ready</span><strong>{selectedNodes ? readyNodes : '—'}</strong></div><div><span>已映射主机</span><strong>{selectedNodes ? mappedNodes : '—'}</strong></div><div><span>观测状态</span><strong className={selectedNodeCluster.observationsStale ? 'is-stale' : 'is-fresh'}>{selectedNodeCluster.observationsStale ? '已过期' : '最新'}</strong></div></div>{selectedNodes ? selectedNodes.length ? <div className="table-wrap k8s-node-table"><table><thead><tr><th>Node</th><th>角色</th><th>就绪状态</th><th>容器所有者</th><th>主机资产映射</th></tr></thead><tbody>{selectedNodes.map(node => {
    const edgeStatus = { NOT_EDGE_NODE: '非 Edge 节点', NOT_PRESENT: 'Node 已移除', REGISTERED_NOT_READY: '已注册 · NotReady', REGISTERED_READY: '已注册 · Ready' }[node.edgeCoreRegistrationStatus];
    const edgeProcessStatus = { NOT_APPLICABLE: '不适用', NOT_MAPPED: '未映射主机', NOT_OBSERVED: '尚无服务观测', STALE: '观测过期', ACTIVE: '运行中', INACTIVE: '未运行', UNKNOWN: '状态未知' }[node.edgeCoreProcessStatus];
    return <tr key={node.nodeUid}><td><strong>{node.nodeName}</strong><small className="mono">UID {node.nodeUid}</small></td><td>{node.roles.join(', ') || 'worker'}{selectedNodeCluster.kind === 'KUBEEDGE' && <small>EdgeCore：{edgeStatus} · 进程 {edgeProcessStatus}{node.edgeCoreProcessObservedAt ? ` · ${time(node.edgeCoreProcessObservedAt)}` : ''}</small>}</td><td><span className={`k8s-node-ready ${node.nodePresent && node.ready ? 'ready' : node.nodePresent ? 'not-ready' : 'removed'}`}><i />{node.nodePresent ? node.ready ? 'Ready' : 'NotReady' : '已移除'}</span></td><td>{node.containerOwner}</td><td><select aria-label={`映射 ${node.nodeName} 到主机资产`} value={node.hostId ?? ''} onChange={event => void mapHost(selectedNodeCluster.id, node, event.target.value)} disabled={!!nodeLoading[selectedNodeCluster.id] || isMock || (!node.nodePresent && !node.hostId)}><option value="">未映射</option>{hosts.map(host => <option value={host.id} key={host.id}>{host.hostname}</option>)}</select></td></tr>;
   })}</tbody></table></div> : <Empty>该集群当前没有 Node 记录。</Empty> : <Empty>点击“刷新 Node”读取集群节点清单。</Empty>}</> : null}
  </section>}
  {workspace === 'installation' && !isMock && <section className="card k8s-installation-card"><div className="card-title"><div><span className="eyebrow">NEW CLUSTER</span><h2>K3s 多节点首次安装</h2><p>先在空白主机安装 Server，再等待集群登记和 Node 映射后逐台加入 Agent。安装过程由持久化计划推进。</p></div></div>
   <form className="managed-deployment-form" onSubmit={event => void createInstallation(event)}>
    <Field label="K3s Server 主机"><select required value={installServerHostId} onChange={event => setInstallServerHostId(event.target.value)}><option value="">选择 Server 主机资产</option>{hosts.filter(host => host.capabilities.includes('k3s.install')).map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}</select></Field>
    <Field label="K3s Agent 主机（可多选）"><select multiple size={Math.min(5, Math.max(2, hosts.filter(host => host.capabilities.includes('k3s.install') && host.id !== installServerHostId).length))} value={installAgentHostIds} onChange={event => setInstallAgentHostIds(Array.from(event.currentTarget.selectedOptions, option => option.value))}>{hosts.filter(host => host.capabilities.includes('k3s.install') && host.id !== installServerHostId).map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}</select><small className="muted">仅显示已上报 k3s.install 能力的主机。实际安装前 Helper 仍会执行空白主机与系统条件校验。</small></Field>
    <Field label="固定 K3s 版本"><input required maxLength={32} pattern="v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+" value={installVersion} onChange={event => setInstallVersion(event.target.value)} placeholder="v1.33.5+k3s1" /></Field>
    <Field label="Agent 可访问的 Server HTTPS API 地址"><input required maxLength={512} type="url" pattern="https://.*" value={installServerUrl} onChange={event => setInstallServerUrl(event.target.value)} placeholder="https://k3s.example.net:6443" /></Field>
    <Field label="K3s 短令牌"><input required type="password" autoComplete="new-password" minLength={16} maxLength={512} pattern="[A-Za-z0-9_.:-]+" value={installToken} onChange={event => setInstallToken(event.target.value)} /><small className="muted">Server 初始化与 Agent 加入共用短令牌；令牌只加密存储并在调度时传给 Helper。</small></Field>
    <Field label="安装原因"><input required maxLength={500} value={installReason} onChange={event => setInstallReason(event.target.value)} placeholder="例如：建立测试 K3s 集群" /></Field>
    <button className="primary" disabled={installBusy || !installServerHostId || !installVersion.trim() || !installServerUrl.trim() || !installToken || !installReason.trim()}>创建安装草稿</button>
   </form>
   {installations.map(plan => <article className="managed-deployment-row" key={plan.id}>
    <div><strong>K3s 安装 → {plan.targetVersion}</strong><span className={`badge ${plan.state.toLowerCase()}`}>{plan.state}</span><small>{plan.reason} · {plan.steps.length} 台主机 · 创建于 {time(plan.createdAt)}</small>{plan.errorMessage && <small className="alert">{plan.errorCode}: {plan.errorMessage}</small>}
     {plan.state === 'AWAITING_CLUSTER_REGISTRATION' && <div className="form-actions"><select aria-label="已导入的 K3s 集群" value={installClusterIds[plan.id] ?? ''} onChange={event => setInstallClusterIds(current => ({ ...current, [plan.id]: event.target.value }))}><option value="">先导入并选择 K3s 集群</option>{clusters.filter(cluster => cluster.kind === 'K3S').map(cluster => <option key={cluster.id} value={cluster.id}>{cluster.name} · {cluster.healthStatus}</option>)}</select><button className="secondary" disabled={installBusy || !installClusterIds[plan.id]} onClick={() => void registerInstallationCluster(plan)}>校验 Server 映射并继续</button><small className="muted">集群登记后，前往“节点管理”将 Server Node 映射到对应主机资产。</small></div>}
     {plan.state === 'AWAITING_NODE_MAPPING' && <small className="alert">Agent 已安装。请前往“节点管理”刷新关联集群的 Node 清单，并把新 Ready Node 映射到该步骤主机；映射成功后计划会自动继续。</small>}
     <div className="table-wrap"><table><thead><tr><th>顺序</th><th>主机</th><th>角色</th><th>步骤状态</th></tr></thead><tbody>{plan.steps.map(step => <tr key={`${plan.id}:${step.ordinal}`}><td>{step.ordinal + 1}</td><td>{hosts.find(host => host.id === step.hostId)?.hostname ?? step.hostId}<small className="mono">{step.hostId}</small></td><td>{step.role === 'server' ? 'Server' : 'Agent'}</td><td>{step.state}{step.operationId && <small className="mono">任务 {step.operationId}</small>}</td></tr>)}</tbody></table></div>
    </div>
    <div className="managed-deployment-actions">{plan.state === 'DRAFT' && <button className="primary" disabled={installBusy || !installConfirmed} onClick={() => void startInstallation(plan)}>启动计划</button>}</div>
   </article>)}
   {installations.some(plan => plan.state === 'DRAFT') && <label className="check-field"><input type="checkbox" checked={installConfirmed} onChange={event => setInstallConfirmed(event.target.checked)} /><span>我已确认所选主机是空白安装目标，API 地址可由 Agent 访问，且理解计划会等待集群导入与 Node 映射。</span></label>}
   {!installations.length && <p className="muted">尚无 K3s 安装计划。</p>}
  </section>}
  {workspace === 'upgrades' && !isMock && <section className="card k8s-upgrade-card"><div className="card-title"><div><span className="eyebrow">LIFECYCLE</span><h2>K3s 逐节点升级计划</h2><p>创建草稿不会执行升级。启动后按 Server 优先、Agent 随后的顺序逐台处理；每步都要刷新并确认所有 Node Ready，未知结果会停止计划。</p></div></div>
   <form className="managed-deployment-form" onSubmit={event => void createRollout(event)}>
    <Field label="K3s 集群"><select required value={rolloutClusterId} onChange={event => setRolloutClusterId(event.target.value)}><option value="">选择已登记的 K3s 集群</option>{clusters.filter(cluster => cluster.kind === 'K3S').map(cluster => <option key={cluster.id} value={cluster.id}>{cluster.name} · {cluster.healthStatus}</option>)}</select></Field>
    <Field label="目标版本"><input required maxLength={32} pattern="v[0-9]+\.[0-9]+\.[0-9]+\+k3s[0-9]+" value={rolloutVersion} onChange={event => setRolloutVersion(event.target.value)} placeholder="v1.34.0+k3s1" /></Field>
    <Field label="升级原因"><input required maxLength={500} value={rolloutReason} onChange={event => setRolloutReason(event.target.value)} placeholder="例如：维护窗口升级" /></Field>
    <button className="primary" disabled={rolloutBusy || !rolloutClusterId || !rolloutReason.trim()}>{rolloutBusy ? '处理中…' : '创建升级草稿'}</button>
   </form>
   {rollouts.map(plan => <article className="managed-deployment-row" key={plan.id}>
    <div><strong>{plan.clusterName} → {plan.targetVersion}</strong><span className={`badge ${plan.state.toLowerCase()}`}>{plan.state}</span><small>{plan.reason} · {plan.steps.length} 个节点 · 创建于 {time(plan.createdAt)}</small>
     {plan.errorMessage && <small className="alert error">{plan.errorCode}: {plan.errorMessage}</small>}
     <div className="table-wrap"><table><thead><tr><th>顺序</th><th>Node / Host</th><th>角色</th><th>步骤状态</th></tr></thead><tbody>{plan.steps.map(step => <tr key={`${plan.id}:${step.ordinal}`}><td>{step.ordinal + 1}</td><td>{step.nodeName}<small className="mono">{step.hostId}</small></td><td>{step.role === 'server' ? 'Server' : 'Agent'}</td><td>{step.state}{step.operationId && <small className="mono">任务 {step.operationId}</small>}</td></tr>)}</tbody></table></div>
    </div>
    <div className="managed-deployment-actions">{plan.state === 'DRAFT' && <button className="primary" disabled={rolloutBusy || !rolloutConfirmed} onClick={() => void startRollout(plan)}>启动计划</button>}</div>
   </article>)}
   {rollouts.some(plan => plan.state === 'DRAFT') && <label className="check-field"><input type="checkbox" checked={rolloutConfirmed} onChange={event => setRolloutConfirmed(event.target.checked)} /><span>我已核对草稿中的节点顺序、目标版本和维护窗口，确认启动后将按计划逐台升级。</span></label>}
   {!rollouts.length && <p className="muted">尚无 K3s 升级计划。</p>}
  </section>}
  {workspace === 'clusters' && discoveries.length > 0 && <section className="card k8s-discovery-card"><div className="card-title"><div><h2>Agent 自动发现</h2><p>Agent 持续报告 kubelet/K3s 状态。Core 与本机 K3s 属于同一集群且节点 Ready 时会使用 Core 的集群内只读身份自动纳管；其他主机仍可验证后导入 kubeconfig。</p></div></div><div className="table-wrap"><table><thead><tr><th>主机</th><th>运行时</th><th>本机 API</th><th>最近心跳</th><th /></tr></thead><tbody>{discoveries.map(discovery => <tr key={discovery.hostId}><td>{discovery.hostname}<small className="mono">{discovery.hostId}</small></td><td>{discovery.runtimeKind} · kubelet 运行中</td><td>{discovery.apiServerLocal ? '127.0.0.1:6443 可连接' : '本机 6443 不可连接'}</td><td>{time(discovery.agentLastSeen)}</td><td>{clusters.some(cluster => cluster.discoveredHostId === discovery.hostId) ? <span className="badge online">已自动纳管</span> : <button className="link" onClick={() => prepareDiscoveredImport(discovery)}>准备导入</button>}</td></tr>)}</tbody></table></div></section>}
  {workspace === 'clusters' && <section className="card k8s-import-card"><div className="card-title"><div><span className="eyebrow">CLUSTER REGISTRY</span><h2>导入已有集群</h2><p>选择 kubeconfig 中的单一 context。凭据只发送到 Core 并加密保存，页面不会持久化副本。</p></div><button className="primary" disabled={isMock} onClick={() => setShowImportForm(current => !current)}>{showImportForm ? '收起导入表单' : '＋ 导入集群'}</button></div>
   {showImportForm && <form className="cluster-import-form" onSubmit={event => void importCluster(event)}>
    <Field label="集群名称"><input required maxLength={80} value={name} onChange={event => setName(event.target.value)} placeholder="例如 production-k3s" /></Field>
    <Field label="集群类型"><select value={kind} onChange={event => setKind(event.target.value as KubernetesClusterImportRequest['kind'])}><option value="K3S">K3s</option><option value="KUBERNETES">Kubernetes</option><option value="KUBEEDGE">KubeEdge</option></select></Field>
    <Field label="kubeconfig context"><input required maxLength={128} value={contextName} onChange={event => setContextName(event.target.value)} placeholder="明确填写 context 名称" /></Field>
    <Field label="kubeconfig"><textarea required maxLength={1048576} rows={10} value={kubeconfig} onChange={event => setKubeconfig(event.target.value)} autoComplete="off" spellCheck={false} placeholder="粘贴 kubeconfig YAML" /></Field>
    <div className="form-actions"><button className="primary" disabled={busy || loading || isMock || !kubeconfig.trim()}>{busy ? '验证并导入…' : '验证并导入集群'}</button></div>
   </form>}
  </section>}
  {workspace === 'clusters' && <><ResourceState loading={loading} error="" />
  <div className="cluster-list">{clusters.map(cluster => {
   return <section className="card cluster-card" key={cluster.id}>
    <div className="card-title"><div><span className="eyebrow">{cluster.kind}</span><h2>{cluster.name}</h2><p className="mono">{cluster.endpoint} · context {cluster.contextName}</p><p className="muted">凭据来源：{cluster.credentialSource === 'IN_CLUSTER' ? `集群内只读身份 · Agent 主机 ${cluster.discoveredHostId ?? '自动发现'}` : '加密保存的 kubeconfig'} · 最近观测 {time(cluster.observedAt)}{cluster.observationsStale ? ' · 观测已过期' : ' · 观测新鲜'}{cluster.lastErrorCode ? ` · ${cluster.lastErrorCode}` : ''}</p>{cluster.kind === 'KUBEEDGE' && <p className="muted">CloudCore：{cluster.cloudCoreStatus} · 观测于 {time(cluster.cloudCoreObservedAt)}</p>}</div><div className="cluster-actions"><span className={`badge ${cluster.healthStatus === 'HEALTHY' && !cluster.observationsStale ? 'online' : 'offline'}`}>{cluster.healthStatus === 'HEALTHY' && !cluster.observationsStale ? '健康' : '需检查'}</span><button className="secondary" onClick={() => { setSelectedNodeClusterId(cluster.id); setWorkspace('nodes'); void refreshNodes(cluster.id); }}>管理节点</button><button className="secondary" disabled={loading || isMock} onClick={() => void refreshClusterStatus(cluster.id)}>刷新状态</button></div></div>
    <div className="cluster-resource-actions"><button className="secondary" disabled={!!deploymentLoading[`${cluster.id}:namespaces`] || isMock} onClick={() => void loadNamespaces(cluster.id)}>{deploymentLoading[`${cluster.id}:namespaces`] ? '读取中…' : namespaceSnapshots[cluster.id] ? '↻ 刷新 Namespace' : '读取 Namespace 与资源管理'}</button></div>
    {namespaceSnapshots[cluster.id] && (() => {
      const snapshot = namespaceSnapshots[cluster.id];
      const selectedNamespace = selectedNamespaces[cluster.id] ?? '';
      const resourceKey = `${cluster.id}:${selectedNamespace}`;
      const resources = managedDeployments[resourceKey] ?? [];
      return <section className="managed-resource-panel">
       <div className="resource-panel-heading"><div><h3>Namespace 资源</h3><p className="muted">Namespace 清单只读；只允许管理由 NaNPanel 创建并登记所有权的 Deployment。</p></div><span className="muted">观测于 {time(snapshot.observedAt)}</span></div>
       <div className="namespace-toolbar"><label>Namespace<select aria-label={`${cluster.name} Namespace`} value={selectedNamespace} onChange={event => { const value = event.target.value; setSelectedNamespaces(current => ({ ...current, [cluster.id]: value })); if (value) void loadManagedDeployments(cluster.id, value); }}><option value="">选择 Namespace</option>{snapshot.namespaces.map(namespace => <option key={namespace.name} value={namespace.name}>{namespace.name}</option>)}</select></label><button className="secondary" disabled={!selectedNamespace || !!deploymentLoading[resourceKey] || isMock} onClick={() => void loadManagedDeployments(cluster.id)}>{deploymentLoading[resourceKey] ? '读取中…' : '刷新托管 Deployment'}</button></div>
       {selectedNamespace && <>
        <form className="managed-deployment-form" onSubmit={event => void createManagedDeployment(event, cluster.id, selectedNamespace)}>
         <Field label="Deployment 名称"><input name="name" required maxLength={63} pattern="[a-z0-9]([a-z0-9-]*[a-z0-9])?" placeholder="例如 web" /></Field>
         <Field label="镜像"><input name="image" required maxLength={512} placeholder="例如 nginx:1.27" /></Field>
         <Field label="副本数"><input name="replicas" type="number" min={1} max={20} defaultValue={1} required /></Field>
         <Field label="容器端口"><input name="containerPort" type="number" min={1} max={65535} defaultValue={8080} required /></Field>
         <button className="primary" disabled={!!deploymentBusy[resourceKey] || isMock}>{deploymentBusy[resourceKey] ? '正在提交…' : '创建托管 Deployment'}</button>
        </form>
        <div className="managed-deployment-list">
         {resources.map(deployment => {
          const scaleKey = `${resourceKey}:${deployment.name}`;
          return <article className="managed-deployment-row" key={deployment.name}>
           <div><strong>{deployment.name}</strong><span className={`badge ${deployment.state === 'READY' ? 'online' : 'offline'}`}>{deployment.state}</span><small>{deployment.image ?? '镜像不可读'} · {deployment.readyReplicas ?? 0}/{deployment.replicas ?? 0} Ready · 端口 {deployment.containerPort ?? '—'}</small></div>
           <div className="managed-deployment-actions"><label>副本数<input aria-label={`${deployment.name} 副本数`} type="number" min={0} max={20} value={scaleValues[scaleKey] ?? String(deployment.replicas ?? 1)} onChange={event => setScaleValues(current => ({ ...current, [scaleKey]: event.target.value }))} /></label><button className="secondary" disabled={!!deploymentBusy[resourceKey] || isMock} onClick={() => void scaleManagedDeployment(cluster.id, selectedNamespace, deployment)}>调整</button><button className="danger" disabled={!!deploymentBusy[resourceKey] || isMock || deployment.state === 'OWNERSHIP_CONFLICT'} onClick={() => void deleteManagedDeployment(cluster.id, selectedNamespace, deployment)}>删除</button></div>
          </article>;
         })}
         {!resources.length && !deploymentLoading[resourceKey] && <p className="muted">暂无已登记的托管 Deployment。先刷新清单，或创建一个新的 Deployment。</p>}
         {deploymentLoading[resourceKey] && <p className="muted">正在读取 Namespace Deployment…</p>}
        </div>
        <KubernetesApplicationReleases clusterId={cluster.id} namespace={selectedNamespace} notify={notify} />
       </>}
      </section>;
    })()}
   </section>;
  })}{!loading && !clusters.length && !isMock && <Empty>尚未导入 Kubernetes 集群。</Empty>}</div></>}
   </div>
  </div>
 </>;
}
function KubernetesApplicationReleases({ clusterId, namespace, notify }: {
 clusterId: string; namespace: string; notify: (message: string) => void;
}) {
 const [items, setItems] = useState<KubernetesApplicationRelease[]>([]);
 const [loading, setLoading] = useState(false);
 const [busy, setBusy] = useState('');
 const [error, setError] = useState('');
 const path = `/kubernetes/clusters/${encodeURIComponent(clusterId)}/namespaces/${encodeURIComponent(namespace)}/application-releases`;
 async function load() {
  if (isMock || !namespace) return;
  setLoading(true);
  try {
   const result = await api<KubernetesApplicationReleaseList>(path, 'GET', undefined, {}, true);
   setItems(result.data.items);
   setError('');
  } catch (e) { setError((e as Error).message); }
  finally { setLoading(false); }
 }
 useEffect(() => {
  void load();
  const timer = window.setInterval(() => void load(), 5000);
  return () => window.clearInterval(timer);
 }, [clusterId, namespace]);
 async function create(event: FormEvent<HTMLFormElement>) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const values = new FormData(formElement);
  const payload: KubernetesApplicationReleaseCreateRequest = {
   name: String(values.get('name') ?? ''), stableImage: String(values.get('stableImage') ?? ''),
   image: String(values.get('image') ?? ''),
   replicas: Number(values.get('replicas')), containerPort: Number(values.get('containerPort')),
   gatewayName: String(values.get('gatewayName') ?? ''), hostname: String(values.get('hostname') ?? ''),
   prometheusService: String(values.get('prometheusService') ?? ''),
   maxErrorRate: Number(values.get('maxErrorRate')), reason: String(values.get('reason') ?? '')
  };
  setBusy('create'); setError('');
  try {
   await api<KubernetesApplicationRelease>(path, 'POST', payload, {}, true);
   formElement.reset(); await load(); notify(`已创建 ${namespace}/${payload.name} 渐进发布草稿`);
  } catch (e) { setError((e as Error).message); }
  finally { setBusy(''); }
 }
 async function act(plan: KubernetesApplicationRelease, action: 'start' | 'pause' | 'resume' | 'promote' | 'rollback') {
  setBusy(`${plan.id}:${action}`); setError('');
  try {
   await api<KubernetesApplicationRelease>(`${path}/${encodeURIComponent(plan.id)}/${action}`, 'POST', {}, {}, true);
   await load(); notify(`${plan.name}：${({ start: '已启动', pause: '已请求暂停', resume: '已请求恢复', promote: '已推进阶段', rollback: '已请求回滚' })[action]}`);
  } catch (e) { setError((e as Error).message); await load(); }
  finally { setBusy(''); }
 }
 return <section className="managed-resource-panel application-release-panel">
  <div className="resource-panel-heading"><div><h3>渐进发布（Gateway API + Argo Rollouts）</h3><p className="muted">创建草稿后需显式启动。集群须预装 Gateway API、Argo Rollouts Gateway API 插件和可用的 Prometheus；流量由 Argo 按 10%、50%、100% 推进并在每阶段暂停。</p></div><button className="secondary" disabled={loading || isMock} onClick={() => void load()}>{loading ? '读取中…' : '刷新发布'}</button></div>
  {isMock && <div className="alert">Mock 环境不连接真实 Kubernetes 发布控制器。</div>}
  {error && <div className="alert error" role="alert">{error}</div>}
  {!isMock && <form className="managed-deployment-form" onSubmit={event => void create(event)}>
   <Field label="应用名称"><input name="name" required maxLength={63} pattern="[a-z0-9]([a-z0-9-]*[a-z0-9])?" placeholder="例如 web" /></Field>
   <Field label="当前稳定镜像"><input name="stableImage" required maxLength={512} placeholder="例如 registry.example/web:1.9.0" /></Field>
   <Field label="目标镜像"><input name="image" required maxLength={512} placeholder="例如 registry.example/web:2.0.0" /></Field>
   <Field label="副本数"><input name="replicas" type="number" min={1} max={20} defaultValue={2} required /></Field>
   <Field label="容器端口"><input name="containerPort" type="number" min={1} max={65535} defaultValue={8080} required /></Field>
   <Field label="Gateway 名称"><input name="gatewayName" required maxLength={63} pattern="[a-z0-9]([a-z0-9-]*[a-z0-9])?" placeholder="例如 public-gateway" /></Field>
   <Field label="HTTP 主机名"><input name="hostname" required maxLength={253} pattern="[a-z0-9.-]+" placeholder="app.example.com" /></Field>
   <Field label="Prometheus Service"><input name="prometheusService" required maxLength={63} defaultValue="prometheus" /></Field>
   <Field label="最大 HTTP 错误率（0.01–0.5）"><input name="maxErrorRate" type="number" min={0.01} max={0.5} step={0.01} defaultValue={0.05} required /></Field>
   <Field label="发布原因"><input name="reason" required maxLength={500} placeholder="例如：发布 web 2.0.0" /></Field>
   <button className="primary" disabled={!!busy || isMock}>{busy === 'create' ? '正在保存…' : '创建发布草稿'}</button>
  </form>}
  <div className="managed-deployment-list">
   {items.map(plan => <article className="managed-deployment-row" key={plan.id}>
    <div><strong>{plan.name} · {plan.stableImage} → {plan.image}</strong><Badge value={plan.state} /><small>{plan.hostname} · stable {plan.stableWeight ?? '—'}% / canary {plan.canaryWeight ?? '—'}% · Argo {plan.rolloutPhase ?? '尚未启动'} · 创建于 {time(plan.createdAt)}</small>
     {plan.errorMessage && <small className="alert error">{plan.errorCode}: {plan.errorMessage}</small>}
     <small className="mono">发布 {plan.id} · 原因：{plan.reason}</small>
     {plan.stages.length > 0 && <div className="table-wrap"><table><thead><tr><th>阶段</th><th>状态</th><th>目标 canary</th><th>观测 stable / canary</th><th>AnalysisRun</th></tr></thead><tbody>{plan.stages.map(stage => <tr key={`${plan.id}:${stage.ordinal}`}><td>{stage.stage}</td><td>{stage.state}</td><td>{stage.desiredCanaryWeight ?? '—'}%</td><td>{stage.observedStableWeight ?? '—'}% / {stage.observedCanaryWeight ?? '—'}%</td><td className="mono">{stage.analysisRunName ?? '—'}</td></tr>)}</tbody></table></div>}
     {plan.measurements.length > 0 && <small>最近测量：{plan.measurements.slice(0, 4).map(measurement => `${measurement.metricName}=${measurement.value ?? '—'} (${measurement.phase})`).join(' · ')}</small>}
    </div>
    <div className="managed-deployment-actions">
     {plan.state === 'DRAFT' && <button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'start')}>启动</button>}
     {plan.state === 'UNKNOWN' && <button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'start')}>对账并恢复</button>}
     {plan.state === 'RUNNING' && <button className="secondary" disabled={!!busy || isMock} onClick={() => void act(plan, 'pause')}>暂停</button>}
     {plan.state === 'PAUSED' && <><button className="secondary" disabled={!!busy || isMock} onClick={() => void act(plan, 'resume')}>恢复自动推进</button><button className="primary" disabled={!!busy || isMock} onClick={() => void act(plan, 'promote')}>批准下一阶段</button></>}
     {['RUNNING', 'PAUSED', 'UNKNOWN'].includes(plan.state) && <button className="danger" disabled={!!busy || isMock} onClick={() => { if (window.confirm(`请求 ${plan.name} 回滚到稳定版本？`)) void act(plan, 'rollback'); }}>回滚</button>}
    </div>
   </article>)}
   {!items.length && !loading && !isMock && <p className="muted">此 Namespace 暂无渐进发布计划。</p>}
  </div>
 </section>;
}
function Users({ self, notify }: { self: string; notify: (s: string) => void }) {
 const r = useResource<AdminUserPage>('/admin/users'); const roles = useResource<BuiltinRoleList>('/admin/roles'); const [create, setCreate] = useState(false); const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [editing, setEditing] = useState<{ user: AdminUser; etag: string }>(); const [editMode, setEditMode] = useState<'profile' | 'password'>('profile');
 async function add(e: FormEvent<HTMLFormElement>) { e.preventDefault(); setBusy(true); setError(''); try { await api('/admin/users', 'POST', Object.fromEntries(new FormData(e.currentTarget))); setCreate(false); r.refresh(); notify('管理员已创建，首次登录需要修改初始密码'); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
 async function edit(u: AdminUser) { try { const fresh = await api<AdminUser>(`/admin/users/${u.id}`); setEditing({ user: fresh.data, etag: fresh.etag ?? '' }); setEditMode('profile'); setError(''); } catch (e) { notify((e as Error).message); } }
 async function update(e: FormEvent<HTMLFormElement>) { e.preventDefault(); if (!editing) return; const values = Object.fromEntries(new FormData(e.currentTarget)); setBusy(true); try { await api(`/admin/users/${editing.user.id}${editMode === 'password' ? '/password' : ''}`, editMode === 'password' ? 'PUT' : 'PATCH', values, { 'If-Match': editing.etag }); setEditing(undefined); r.refresh(); notify(editMode === 'password' ? '密码已重置，目标账号下次登录必须改密' : '显示名称已更新'); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
 async function toggle(u: AdminUser) { try { const fresh = await api<AdminUser>(`/admin/users/${u.id}`); await api(`/admin/users/${u.id}`, 'PATCH', { status: fresh.data.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' }, { 'If-Match': fresh.etag ?? '' }); r.refresh(); notify('账号状态已更新'); } catch (e) { notify((e as Error).message); } }
 return <><PageHeading title="管理员账号" description="平台账号独立于 Linux 用户，首期使用内置 ADMIN 角色。" action={<button className="primary" onClick={() => { setCreate(true); setError(''); }}>＋ 创建管理员</button>} /><section className="card"><ResourceState {...r} /><div className="table-wrap"><table><thead><tr><th>账号</th><th>显示名称</th><th>角色</th><th>状态</th><th>首次改密</th><th /></tr></thead><tbody>{r.data?.items.map(u => <tr key={u.id}><td><b>{u.username}</b>{u.id === self && <span className="chip">本人</span>}</td><td>{u.displayName}</td><td><span className="chip">ADMIN</span></td><td><Badge value={u.status} /></td><td>{u.mustChangePassword ? '待修改' : '已完成'}</td><td><button className="link" onClick={() => edit(u)}>管理</button><button className="link" disabled={u.id === self} onClick={() => toggle(u)}>{u.status === 'ACTIVE' ? '禁用账号' : '启用账号'}</button></td></tr>)}</tbody></table></div></section><section className="card role-card"><h2>内置角色与权限</h2><ResourceState {...roles} />{roles.data?.items.map(role => <div key={role.name}><h3>{role.name} <span className="chip">{role.id} · 只读</span></h3><p className="muted">平台内置管理员权限，暂不支持自定义角色。</p>{role.permissions.map(p => <span className="chip" key={p}>{p}</span>)}</div>)}</section>{editing && <div className="modal-backdrop"><div className="modal"><div className="card-title"><h2>管理 {editing.user.username}</h2><button className="icon-button" aria-label="关闭账号管理" onClick={() => setEditing(undefined)}>×</button></div><div className="form-actions"><button className="secondary" onClick={() => { setEditMode('profile'); setError(''); }}>修改显示名称</button><button className="secondary" disabled={editing.user.id === self} onClick={() => { setEditMode('password'); setError(''); }}>重置密码</button></div><form key={editMode} onSubmit={update}>{editMode === 'profile' ? <Field label="显示名称"><input name="displayName" defaultValue={editing.user.displayName} required maxLength={100} /></Field> : <><div className="alert">将撤销目标账号的全部会话，要求下次登录修改密码。</div><Field label="新初始密码（15–128 字符）"><input type="password" name="newPassword" required minLength={15} maxLength={128} autoComplete="new-password" /></Field></>}{error && <div className="alert error">{error}</div>}<button className="primary full" disabled={busy}>{busy ? '正在提交…' : '确认保存'}</button></form></div></div>}{create && <div className="modal-backdrop"><div className="modal"><div className="card-title"><h2>创建管理员</h2><button className="icon-button" aria-label="关闭创建管理员" onClick={() => setCreate(false)}>×</button></div><form onSubmit={add}><Field label="用户名（创建后不可更改）"><input name="username" required pattern="[a-z][a-z0-9_.-]{2,63}" placeholder="例如 operator02" /></Field><Field label="显示名称"><input name="displayName" required maxLength={100} /></Field><Field label="初始密码（15–128 字符）"><input name="initialPassword" required type="password" minLength={15} maxLength={128} autoComplete="new-password" /></Field><p className="muted">新账号角色固定为 ADMIN，首次登录必须改密。</p>{error && <div className="alert error">{error}</div>}<button className="primary full" disabled={busy}>{busy ? '正在创建…' : '创建管理员'}</button></form></div></div>}</>;
}
function Settings({ notify }: { notify: (s: string) => void }) {
 const [value, setValue] = useState<PlatformSettings>(); const [tag, setTag] = useState(''); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
 const load = () => api<PlatformSettings>('/admin/settings').then(r => { setValue(r.data); setTag(r.etag ?? ''); setError(''); }).catch(e => setError(e.message)); useEffect(() => { void load(); }, []);
 async function save(e: FormEvent) { e.preventDefault(); if (!value) return; setBusy(true); try { const { siteName, defaultTimezone, hostOfflineAfterSeconds, operationRetentionDays } = value; const r = await api<PlatformSettings>('/admin/settings', 'PATCH', { siteName, defaultTimezone, hostOfflineAfterSeconds, operationRetentionDays }, { 'If-Match': tag }); setTag(r.etag ?? ''); setValue(r.data); setError(''); notify('平台设置已保存'); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
 return <><PageHeading title="平台设置" description="管理非机密平台配置；修改使用版本校验，避免覆盖并发变更。" action={<button className="secondary" onClick={load}>↻ 重新读取</button>} /><section className="card settings-card">{error && <div className="alert error">{error}</div>}{value ? <form onSubmit={save}><h2>基本设置</h2><p className="muted">仅影响平台展示与控制面策略。</p><Field label="站点名称"><input required maxLength={100} value={value.siteName} onChange={e => setValue({ ...value, siteName: e.target.value })} /></Field><Field label="默认展示时区"><select value={value.defaultTimezone} onChange={e => setValue({ ...value, defaultTimezone: e.target.value })}>{[...new Set([value.defaultTimezone, 'Asia/Shanghai', 'UTC', 'Asia/Singapore', 'America/New_York'])].map(t => <option key={t}>{t}</option>)}</select></Field><Field label="主机离线阈值（30–600 秒）"><input type="number" required min={30} max={600} value={value.hostOfflineAfterSeconds} onChange={e => setValue({ ...value, hostOfflineAfterSeconds: Number(e.target.value) })} /></Field><Field label="已结束任务保留天数（7–365 天）"><input type="number" required min={7} max={365} value={value.operationRetentionDays} onChange={e => setValue({ ...value, operationRetentionDays: Number(e.target.value) })} /></Field><div className="alert">非终态与 UNKNOWN 任务不因保留期被清理。审计保留独立于任务保留策略。</div><button className="primary" disabled={busy}>{busy ? '正在保存…' : '保存设置'}</button><p className="muted">最近修改：{time(value.updatedAt)}</p></form> : !error && <p>正在读取设置…</p>}</section></>;
}
function Audit() {
 const [outcome, setOutcome] = useState(''); const [query, setQuery] = useState(''); const [actorDraft, setActorDraft] = useState(''); const [actionDraft, setActionDraft] = useState(''); const [fromDraft, setFromDraft] = useState(''); const [toDraft, setToDraft] = useState(''); const [filters, setFilters] = useState({ outcome: '', actorId: '', action: '', from: '', to: '' }); const [cursors, setCursors] = useState<(string | null)[]>([null]); const [selected, setSelected] = useState<string>();
 const params = new URLSearchParams(); if (filters.outcome) params.set('outcome', filters.outcome); if (filters.actorId) params.set('actorId', filters.actorId); if (filters.action) params.set('action', filters.action); if (filters.from) params.set('from', new Date(filters.from).toISOString()); if (filters.to) params.set('to', new Date(filters.to).toISOString()); const cursor = cursors[cursors.length - 1]; if (cursor) params.set('cursor', cursor);
 const serialized = params.toString(); const r = useResource<AuditEventPage>('/admin/audit-events' + (serialized ? '?' + serialized : ''));
 function apply(e: FormEvent) { e.preventDefault(); setFilters({ outcome, actorId: actorDraft.trim(), action: actionDraft.trim(), from: fromDraft, to: toDraft }); setCursors([null]); }
 return <><PageHeading title="审计日志" description="查看认证、管理员管理和安全拒绝事件；包含认证、管理员变更和主机运维结果；UNKNOWN 表示任务执行结果尚未确认。" action={<button className="secondary" onClick={r.refresh}>↻ 刷新</button>} /><section className="card"><form className="toolbar" onSubmit={apply}><input aria-label="搜索审计" placeholder="搜索当前页动作或摘要…" value={query} onChange={e => setQuery(e.target.value)} /><Field label="主体 ID"><input aria-label="审计主体 ID" value={actorDraft} onChange={e => setActorDraft(e.target.value)} placeholder="UUID" /></Field><Field label="动作"><input aria-label="审计动作" value={actionDraft} onChange={e => setActionDraft(e.target.value)} placeholder="auth.login" /></Field><Field label="开始时间"><input aria-label="审计开始时间" type="datetime-local" value={fromDraft} onChange={e => setFromDraft(e.target.value)} /></Field><Field label="结束时间"><input aria-label="审计结束时间" type="datetime-local" value={toDraft} onChange={e => setToDraft(e.target.value)} /></Field><select aria-label="审计结果" value={outcome} onChange={e => setOutcome(e.target.value)}><option value="">全部结果</option>{['SUCCEEDED', 'DENIED', 'FAILED', 'ACCEPTED', 'UNKNOWN'].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select><button className="secondary">应用筛选</button></form><ResourceState {...r} /><div className="table-wrap"><table><thead><tr><th>时间 / Trace ID</th><th>动作</th><th>摘要</th><th>来源</th><th>结果</th><th /></tr></thead><tbody>{r.data?.items.filter(e => (e.action + ' ' + e.summary).includes(query)).map(e => <tr key={e.id}><td>{time(e.occurredAt)}<small className="mono">{e.traceId}</small></td><td className="mono">{e.action}</td><td>{e.summary}</td><td>{e.clientIp ?? '—'}</td><td><Badge value={e.outcome} /></td><td><button className="link" onClick={() => setSelected(e.id)}>详情</button></td></tr>)}</tbody></table>{!r.data?.items.filter(e => (e.action + ' ' + e.summary).includes(query)).length && !r.loading && <Empty>没有符合条件的审计事件</Empty>}</div><div className="form-actions"><button className="secondary" disabled={cursors.length < 2} onClick={() => setCursors(value => value.slice(0, -1))}>上一页</button><span className="muted">第 {cursors.length} 页</span><button className="secondary" disabled={!r.data?.nextCursor} onClick={() => { if (r.data?.nextCursor) setCursors([...cursors, r.data.nextCursor]); }}>下一页</button></div></section>{selected && <AuditDetail id={selected} onClose={() => setSelected(undefined)} />}</>;
}
function AuditDetail({ id, onClose }: { id: string; onClose: () => void }) {
 const r = useResource<import('./lib/types').AuditEvent>('/admin/audit-events/' + id);
 return <div className="modal-backdrop"><div className="modal"><div className="card-title"><h2>审计事件详情</h2><button className="icon-button" aria-label="关闭审计详情" onClick={onClose}>×</button></div><ResourceState {...r} />{r.data && <dl className="info-list"><div><dt>事件 ID</dt><dd className="mono">{r.data.id}</dd></div><div><dt>主体 ID</dt><dd className="mono">{r.data.actorId ?? '—'}</dd></div><div><dt>资源</dt><dd>{r.data.resourceType} · {r.data.resourceId ?? '—'}</dd></div><div><dt>动作 / 结果</dt><dd>{r.data.action} · {labels[r.data.outcome] ?? r.data.outcome}</dd></div><div><dt>发生时间</dt><dd>{time(r.data.occurredAt)}</dd></div><div><dt>Trace ID</dt><dd className="mono">{r.data.traceId}</dd></div><div><dt>来源</dt><dd>{r.data.clientIp ?? '—'}</dd></div><div><dt>摘要</dt><dd>{r.data.summary}</dd></div></dl>}</div></div>;
}
function Account({ onLogout, allowApiKeys = true }: { onLogout: () => void; allowApiKeys?: boolean }) {
 const sessions = useResource<WebSessionList>('/auth/sessions'); const apiKeys = useResource<ApiKeyList>(allowApiKeys && !isMock ? '/auth/api-keys' : '', 0, true); const [error, setError] = useState(''); const [busy, setBusy] = useState(false); const [keyError, setKeyError] = useState(''); const [keyBusy, setKeyBusy] = useState(false); const [createdKey, setCreatedKey] = useState('');
 async function change(e: FormEvent<HTMLFormElement>) { e.preventDefault(); const form = new FormData(e.currentTarget); if (form.get('newPassword') !== form.get('confirm')) { setError('两次新密码不一致'); return; } setBusy(true); try { await api('/auth/password', 'PUT', { currentPassword: form.get('currentPassword'), newPassword: form.get('newPassword') }); onLogout(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }
 async function revoke(id: string, current: boolean) { try { await api(`/auth/sessions/${id}`, 'DELETE'); if (current) onLogout(); else sessions.refresh(); } catch (e) { setError((e as Error).message); } }
 async function createApiKey(e: FormEvent<HTMLFormElement>) { e.preventDefault(); const formElement = e.currentTarget; const form = new FormData(formElement); const scopes = [...formElement.querySelectorAll<HTMLInputElement>('input[name="apiScope"]:checked')].map(input => input.value); if (!scopes.length) { setKeyError('至少选择一个密钥范围'); return; } if (scopes.includes('kubernetes:inventory:refresh') && !scopes.includes('kubernetes:inventory:read')) scopes.push('kubernetes:inventory:read'); setKeyBusy(true); setKeyError(''); setCreatedKey(''); try { const result = await api<CreatedApiKey>('/auth/api-keys', 'POST', { name: String(form.get('name')), scopes, expiresInDays: Number(form.get('expiresInDays')) }); setCreatedKey(result.data.token); apiKeys.refresh(); formElement.reset(); } catch (e) { setKeyError((e as Error).message); } finally { setKeyBusy(false); } }
 async function revokeApiKey(id: string) { setKeyError(''); try { await api(`/auth/api-keys/${id}`, 'DELETE'); apiKeys.refresh(); if (createdKey) setCreatedKey(''); } catch (e) { setKeyError((e as Error).message); } }
 return <><PageHeading title="账号与会话" description="修改本人密码，查看并撤销有效浏览器会话及受限 API 密钥。" /><div className="overview-grid"><section className="card settings-card"><h2>修改密码</h2><p className="muted">成功后撤销本人全部会话，需要重新登录。</p><form onSubmit={change}><Field label="当前密码"><input type="password" name="currentPassword" required autoComplete="current-password" /></Field><Field label="新密码（15–128 字符）"><input type="password" name="newPassword" minLength={15} maxLength={128} required autoComplete="new-password" /></Field><Field label="确认新密码"><input type="password" name="confirm" required autoComplete="new-password" /></Field>{error && <div className="alert error">{error}</div>}<button className="primary" disabled={busy}>{busy ? '正在修改…' : '修改密码并退出'}</button></form></section><section className="card settings-card"><h2>有效会话</h2><ResourceState {...sessions} />{sessions.data?.items.map(s => <div className="session" key={s.id}><span className="chip">{s.current ? '当前浏览器' : '其他浏览器'}</span><p className="muted session-agent">{s.userAgent}</p><small>{s.clientIp} · 创建于 {time(s.createdAt)}</small><p><button className="secondary" onClick={() => revoke(s.id, s.current)}>撤销{ s.current ? '当前会话并退出' : '会话'}</button></p></div>)}</section>{allowApiKeys && <section className="card settings-card"><h2>API 密钥</h2>{isMock ? <p className="muted">Mock 演示不保存 API 密钥。真实 API 模式下可创建只允许读取 Kubernetes 服务网络数据的密钥。</p> : <><p className="muted">密钥只在创建时显示一次，最多有效 90 天。只授予需要的范围。</p><form onSubmit={createApiKey}><Field label="名称"><input name="name" maxLength={80} required placeholder="例如：P2 inventory read" /></Field><label className="check-row"><input type="checkbox" name="apiScope" value="kubernetes:inventory:read" defaultChecked />读取 Kubernetes 服务清单和探测记录</label><label className="check-row"><input type="checkbox" name="apiScope" value="kubernetes:inventory:refresh" />刷新 Kubernetes 服务清单（需同时选中读取范围）</label><Field label="有效天数（1–90）"><input type="number" name="expiresInDays" min={1} max={90} defaultValue={30} required /></Field>{keyError && <div className="alert error" role="alert">{keyError}</div>}<button className="primary" disabled={keyBusy}>{keyBusy ? '正在创建…' : '创建 API 密钥'}</button></form>{createdKey && <div className="alert" role="status"><b>请立即复制密钥，此后不会再次显示。</b><code className="api-key-value">{createdKey}</code><button className="secondary" onClick={() => setCreatedKey('')}>已保存，隐藏密钥</button></div>}<ResourceState {...apiKeys} />{apiKeys.data?.items.map(key => <div className="session" key={key.id}><b>{key.name}</b><small>范围：{key.scopes.join('、')}</small><small>创建于 {time(key.createdAt)} · 到期 {time(key.expiresAt)}</small><small>最近使用：{time(key.lastUsedAt)}{key.revokedAt ? ` · 已撤销 ${time(key.revokedAt)}` : ''}</small>{!key.revokedAt && <p><button className="secondary" onClick={() => revokeApiKey(key.id)}>撤销密钥</button></p>}</div>)}</>}</section>}</div></>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
