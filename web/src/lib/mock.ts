import { createUuid } from './uuid.ts';
import { initialTopology, draftError } from './topology-model.ts';
import type { TopologyDraft, TopologyLink } from './types';
import type { Host, SystemService, AdminUser, PlatformSettings, Operation, AuditEvent, WebSession, NetworkProviderOperation } from './types';
const now = () => new Date().toISOString();
const id = () => createUuid();
const uuid = (n: number) => `8b395d24-5d5d-4f27-b70b-${String(n).padStart(12, '0')}`;
export const DEMO_PASSWORD = 'nanpanel-demo-2026';
const hosts: Host[] = ['gateway-sg-01', 'homelab-node-01', 'k3s-worker-01', 'edge-shanghai-02', 'backup-node-01', 'lab-arm-01'].map((hostname, i) => ({ id: uuid(i + 1), hostname, os: 'linux', arch: i === 5 ? 'aarch64' : 'x86_64', generation: 1, status: i === 4 ? 'OFFLINE' : i === 5 ? 'UNKNOWN' : 'ONLINE', capabilities: ['systemd.read', 'systemd.restart', 'systemd.journal.read', 'network.inventory', ...(i < 2 ? ['package.install', 'file.write.protected', 'file.list', 'file.read', 'file.write', 'file.delete', 'container.list', 'container.logs', 'container.start', 'container.stop', 'container.deploy', 'network.firewall.expose'] : [])], agentId: uuid(i + 21), lastSeenAt: i >= 4 ? new Date(Date.now() - 3600000).toISOString() : now(), createdAt: now() }));
const services: SystemService[] = hosts.flatMap((h, i) => Array.from({ length: i === 0 ? 30 : 3 }, (_, j) => {
 const unit = ['nginx', 'nanpanel-agent', 'frpc'][j] ?? `demo-systemd-${j - 2}`;
 const displayName = ['Nginx Web Server', 'NaNPanel Rust Agent', 'FRP Tunnel Client'][j] ?? `Mock systemd service ${j + 1}`;
 return { id: uuid(100 + i * 30 + j), hostId: h.id, unitName: `${unit}.service`, displayName, activeState: 'ACTIVE', subState: 'running', generation: 1, observedAt: h.lastSeenAt, supportedActions: h.status === 'ONLINE' && i !== 3 ? ['RESTART', 'READ_JOURNAL'] : [], observationStale: h.status !== 'ONLINE' };
}));
const users: AdminUser[] = [{ id: uuid(50), username: 'admin', displayName: '实验室管理员', roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false, createdAt: now(), updatedAt: now(), lastLoginAt: now() }, { id: uuid(51), username: 'operator', displayName: '运维管理员', roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false, createdAt: now(), updatedAt: now(), lastLoginAt: null }];
const passwords = new Map(users.map(u => [u.id, DEMO_PASSWORD]));
let settings: PlatformSettings = { siteName: 'NaNPanel', defaultTimezone: 'Asia/Shanghai', hostOfflineAfterSeconds: 90, operationRetentionDays: 30, updatedAt: now(), updatedBy: users[0].id };
const versions = new Map<string, number>();
const etag = (key: string) => `"mock-${key}-${versions.get(key) ?? 1}"`;
const bump = (key: string) => versions.set(key, (versions.get(key) ?? 1) + 1);
let topology: TopologyDraft = { ...initialTopology(), revisionId: id(), generation: 0, updatedAt: now(), updatedBy: null };
const operations: Operation[] = [];
const audit: AuditEvent[] = [];
const mockFileContents = new Map<string, Map<string, string>>();
const mockDirectories = new Set(['/etc', '/etc/nanpanel']);
for (const host of hosts.slice(0, 2)) {
 mockFileContents.set(host.id, new Map([
  ['/etc/hosts', '127.0.0.1 localhost\n'],
  ['/etc/nanpanel/agent.conf', 'controller=https://panel.example\n'],
 ]));
}
function mockFileOperation(hostId: string, capability: string, target: string, payload: Record<string, unknown>) {
 const files = mockFileContents.get(hostId) ?? new Map<string, string>();
 mockFileContents.set(hostId, files);
 if (capability === 'file.list') {
  const prefix = target === '/' ? '/' : `${target}/`;
  const entries = new Map<string, { name: string; kind: 'directory' | 'file'; sizeBytes: number | null }>();
  for (const directory of mockDirectories) {
   if (!directory.startsWith(prefix) || directory === target) continue;
   const child = directory.slice(prefix.length).split('/')[0];
   if (child) entries.set(child, { name: child, kind: 'directory', sizeBytes: null });
  }
  for (const [path, content] of files) {
   if (!path.startsWith(prefix) || path === target) continue;
   const child = path.slice(prefix.length).split('/')[0];
   if (child && !path.slice(prefix.length).includes('/')) entries.set(child, { name: child, kind: 'file', sizeBytes: new TextEncoder().encode(content).length });
  }
  return JSON.stringify({ path: target, entries: [...entries.values()].sort((a, b) => a.name.localeCompare(b.name)), truncated: false });
 }
 if (capability === 'file.read') {
  const content = files.get(target);
  if (content === undefined) throw new Error('模拟文件不存在');
  return content;
 }
 if (capability === 'file.write') {
  const content = String(payload.content ?? '');
  files.set(target, content);
  return JSON.stringify({ path: target, bytesWritten: new TextEncoder().encode(content).length });
 }
 if (capability === 'file.delete') {
  files.delete(target);
  return JSON.stringify({ path: target, deleted: true });
 }
 return `Mock ${capability} completed`;
}
let sessions: WebSession[] = [];
let currentUserId: string | null = null;
let csrf = id();
function log(action: string, resourceType: string, resourceId: string | null, summary: string) { audit.unshift({ id: id(), occurredAt: now(), actorId: currentUserId, action, resourceType, resourceId, outcome: 'SUCCEEDED', traceId: id(), clientIp: '127.0.0.1', summary }); }
log('MOCK_INITIALIZED', 'system', null, '演示环境已初始化，所有操作仅在当前页面内模拟');
function sessionContext() { return { user: users.find(u => u.id === currentUserId), session: sessions.find(s => s.current), permissions: users.find(u => u.id === currentUserId)?.mustChangePassword ? [] : ['hosts:read', 'services:restart', 'admin:manage'], csrfToken: csrf }; }
function createSession(user: AdminUser) { const stamp = now(); currentUserId = user.id; csrf = id(); sessions = [{ id: id(), userId: user.id, current: true, createdAt: stamp, authenticatedAt: stamp, lastSeenAt: stamp, idleExpiresAt: new Date(Date.now() + 1800000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 43200000).toISOString(), clientIp: '127.0.0.1', userAgent: navigator.userAgent }, ...sessions.filter(s => !s.current && s.userId === user.id)]; }
const replay = new Map<string, { fingerprint: string; operation: Operation }>();
let netbirdGroup: { groupName: string; peerIds: string[]; groupId: string; resourceCount: number } | undefined;
const netbirdOperations = new Map<string, { operation: NetworkProviderOperation; desired: { groupName: string; generation: number; peerIds: string[] }; before?: typeof netbirdGroup; appliedFingerprint?: string }>();
const netbirdFingerprint = () => JSON.stringify(netbirdGroup ?? null);
export async function mockRequest(path: string, options: RequestInit, authenticatedBusiness = false): Promise<Response> {
 await new Promise(r => setTimeout(r, 120));
 const url = new URL(path, 'http://mock'); const p = url.pathname; const method = options.method ?? 'GET'; const headers = new Headers(options.headers); const b = options.body ? JSON.parse(String(options.body)) : {};
 const ok = (data: unknown, status = 200, extra: Record<string, string> = {}) => new Response(status === 204 ? null : JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json', ...extra } });
 const fail = (status: number, code: string, detail: string) => ok({ type: 'about:blank', title: code, status, code, detail, traceId: id() }, status);
 const page = <T,>(items: T[]) => { const limit = Math.min(Number(url.searchParams.get('limit') ?? 20), 100); const cursor = url.searchParams.get('cursor'); const start = cursor ? Number(atob(cursor)) : 0; return { items: items.slice(start, start + limit), nextCursor: start + limit < items.length ? btoa(String(start + limit)) : null }; };
 const match = (key: string) => headers.get('If-Match') === etag(key);
 if (p === '/auth/csrf') return ok({ csrfToken: csrf, expiresAt: new Date(Date.now() + 600000).toISOString() });
 if (!authenticatedBusiness && method !== 'GET' && headers.get('X-CSRF-Token') !== csrf) return fail(403, 'CSRF_REJECTED', 'CSRF 上下文已失效，请重新登录');
 if (p === '/auth/login') { const user = users.find(u => u.username === String(b.username).toLowerCase() && u.status === 'ACTIVE'); if (!user || passwords.get(user.id) !== b.password) return fail(401, 'INVALID_CREDENTIALS', '用户名或密码不正确'); if (currentUserId && currentUserId !== user.id) return fail(409, 'ACCOUNT_MISMATCH', '请先退出当前账号'); createSession(user); log('AUTH_LOGIN', 'user', user.id, '管理员登录演示环境'); return ok(sessionContext()); }
 const current = users.find(u => u.id === currentUserId) ?? (authenticatedBusiness ? users[0] : undefined);
 if (!current || current.status !== 'ACTIVE' || (!authenticatedBusiness && !sessions.some(s => s.current))) return fail(401, 'UNAUTHENTICATED', '请登录以继续');
 if (current.mustChangePassword && !p.startsWith('/auth/')) return fail(403, 'PASSWORD_CHANGE_REQUIRED', '请先修改初始密码');
 if (method !== 'GET' && p.startsWith('/admin/') && Date.now() - new Date(sessions[0]?.authenticatedAt ?? now()).getTime() > 300000) return fail(403, 'REAUTHENTICATION_REQUIRED', '需要重新验证密码；验证后请重新检查并提交');
 if (p === '/network-providers' && method === 'GET') return ok([{ id: 'netbird-groups', capabilities: ['overlay.group-membership.reconcile'] }]);
 if (p === '/network-providers/netbird-groups/observations' && method === 'POST') {
  if (!String(b.resourceKey ?? '').startsWith('nanpanel-managed-')) return fail(400, 'VALIDATION_FAILED', '组名不在 Mock 受管前缀内');
  const group = netbirdGroup?.groupName === b.resourceKey ? netbirdGroup : undefined;
  const snapshot = { groupName: b.resourceKey, fingerprint: JSON.stringify(group ?? null), exists: !!group, ...(group ? { groupId: group.groupId, peerIds: group.peerIds, resourceCount: group.resourceCount } : {}) };
  return ok({ providerId: 'netbird-groups', resourceKey: b.resourceKey, snapshot: JSON.stringify(snapshot) });
 }
 if (p === '/network-providers/netbird-groups/plans' && method === 'POST') {
  let desired: { groupName: string; generation: number; peerIds: string[] };
  try { desired = JSON.parse(String(b.desiredSnapshot)); } catch { return fail(400, 'VALIDATION_FAILED', 'Mock 期望状态无效'); }
  if (!desired.groupName?.startsWith('nanpanel-managed-') || !Array.isArray(desired.peerIds) || desired.peerIds.length > 512) return fail(400, 'VALIDATION_FAILED', 'Mock 期望状态不符合边界');
  if (b.observedSnapshot) {
   try { const observed = JSON.parse(b.observedSnapshot); const current = netbirdGroup?.groupName === desired.groupName ? netbirdGroup : undefined; if (observed.groupName !== desired.groupName || observed.fingerprint !== JSON.stringify(current ?? null)) return fail(409, 'PROVIDER_PLAN_REJECTED', '观察状态已经变化，请重新读取'); }
   catch { return fail(400, 'VALIDATION_FAILED', 'Mock 观察状态无效'); }
  }
  const operation: NetworkProviderOperation = { id: id(), providerId: 'netbird-groups', generation: desired.generation, state: 'PLANNED', detail: 'Mock 计划已持久化；尚未修改示例组', createdAt: now(), updatedAt: now() };
  netbirdOperations.set(operation.id, { operation, desired: { ...desired, peerIds: [...desired.peerIds] }, before: netbirdGroup?.groupName === desired.groupName ? structuredClone(netbirdGroup) : undefined });
  log('network.provider.plan', 'network-provider-operation', operation.id, 'Mock 创建 NetBird 受管组计划');
  return ok(operation, 201);
 }
 const netbirdOperationMatch = p.match(/^\/network-providers\/netbird-groups\/operations\/([^/]+)(?:\/(validate|apply|verify|compensate))?$/);
 if (netbirdOperationMatch) {
  const item = netbirdOperations.get(netbirdOperationMatch[1]); if (!item) return fail(404, 'NOT_FOUND', 'Mock Provider 操作不存在');
  const action = netbirdOperationMatch[2]; if (method === 'GET' && !action) return ok(item.operation);
  if (method !== 'POST' || !action) return fail(405, 'METHOD_NOT_ALLOWED', 'Mock Provider 阶段仅支持 POST');
  if (action === 'validate') { if (item.operation.state !== 'PLANNED') return fail(409, 'INVALID_OPERATION_STATE', 'Mock 操作阶段无效'); item.operation.state = 'VALIDATED'; item.operation.detail = 'Mock 受管组前提有效'; }
  if (action === 'apply') { if (item.operation.state !== 'VALIDATED') return fail(409, 'INVALID_OPERATION_STATE', 'Mock 操作阶段无效'); netbirdGroup = { groupName: item.desired.groupName, peerIds: [...item.desired.peerIds], groupId: item.before?.groupId ?? id(), resourceCount: item.before?.resourceCount ?? 0 }; item.appliedFingerprint = netbirdFingerprint(); item.operation.state = 'APPLIED'; item.operation.detail = 'Mock 已更新示例组；真实 NetBird 未修改'; }
  if (action === 'verify') { item.operation.state = item.appliedFingerprint === netbirdFingerprint() ? 'VERIFIED' : 'DRIFTED'; item.operation.detail = item.operation.state === 'VERIFIED' ? 'Mock 期望成员已验证' : 'Mock 发现组漂移'; }
  if (action === 'compensate') { if (item.appliedFingerprint !== netbirdFingerprint()) { item.operation.state = 'DRIFTED'; item.operation.detail = 'Mock 组已漂移，拒绝覆盖'; } else { netbirdGroup = item.before ? structuredClone(item.before) : undefined; item.operation.state = 'COMPENSATED'; item.operation.detail = 'Mock 已恢复示例组的计划前状态'; } }
  item.operation.updatedAt = now(); log(`network.provider.${action}`, 'network-provider-operation', item.operation.id, item.operation.detail); return ok(item.operation);
 }
 if (p === '/auth/me') return ok(sessionContext());
 if (p === '/auth/logout') { currentUserId = null; sessions = []; return ok(null, 204); }
 if (p === '/auth/password') { if (passwords.get(current.id) !== b.currentPassword) return fail(400, 'INVALID_PASSWORD', '旧密码不正确'); if ([...String(b.newPassword)].length < 15 || [...String(b.newPassword)].length > 128 || b.newPassword === b.currentPassword) return fail(422, 'PASSWORD_REJECTED', '新密码须为 15–128 字符，且与旧密码不同'); passwords.set(current.id, b.newPassword); current.mustChangePassword = false; log('PASSWORD_CHANGED', 'user', current.id, '修改本人密码'); currentUserId = null; sessions = []; return ok(null, 204); }
 if (p === '/auth/sessions') return ok({ items: sessions });
 if (p.startsWith('/auth/sessions/') && method === 'DELETE') { sessions = sessions.filter(s => s.id !== p.split('/').pop()); return ok(null, 204); }
 if (p === '/topology/draft') {
  if (method === 'GET') return ok(topology, 200, { ETag: etag('topology'), 'Cache-Control': 'no-store' });
  if (method !== 'PUT') return fail(405, 'METHOD_NOT_ALLOWED', '草稿仅支持 GET / PUT');
  if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '保存草稿需要 If-Match');
  if (!match('topology')) return fail(412, 'PRECONDITION_FAILED', '草稿已被修改，请重新读取后检查再保存');
  const error = draftError(b); if (error) return fail(422, 'INVALID_TOPOLOGY', error);
  topology = { ...structuredClone(b), links: b.links.map((link: TopologyLink) => ({ ...link, state: 'PENDING' })), revisionId: id(), generation: topology.generation + 1, updatedAt: now(), updatedBy: current.id }; bump('topology');
  log('TOPOLOGY_DRAFT_UPDATED', 'topology', null, `保存期望拓扑草稿：${topology.nodes.length} 个节点、${topology.links.length} 条未验证连接`);
  return ok(topology, 200, { ETag: etag('topology'), 'Cache-Control': 'no-store' });
 }
 if (p === '/system') return ok({ name: 'NaNPanel', version: '0.1.0-mock', stage: 'mock' });
 if (p === '/kubernetes/topology/namespaces') return ok({ sourceId: 'kubernetes:demo-cluster', observedAt: now(), namespaces: [
  { name: 'default', phase: 'Active' }, { name: 'kube-system', phase: 'Active' },
  { name: 'nanpanel-mesh', phase: 'Active' },
 ] });
 const topologyNamespaceMatch = p.match(/^\/kubernetes\/topology\/namespaces\/([^/]+)$/);
 if (topologyNamespaceMatch) {
  const namespace = decodeURIComponent(topologyNamespaceMatch[1]);
  if (!['default', 'kube-system', 'nanpanel-mesh'].includes(namespace)) return fail(404, 'KUBERNETES_NAMESPACE_NOT_FOUND', 'Namespace 不存在或名称无效');
  const services = namespace === 'nanpanel-mesh' ? [
   { name: 'catalog-api', type: 'ClusterIP', clusterIps: ['10.43.120.17'], externalName: null, ports: [{ name: 'http', port: 80, protocol: 'TCP', targetPort: '8080' }] },
  ] : [];
  const pods = namespace === 'nanpanel-mesh' ? [
   { name: 'catalog-api-6c847d7d4b-kd2sh', phase: 'Running', ready: true, podIp: '10.42.1.23', nodeName: 'k3s-worker-1', createdAt: now(), containers: [{ name: 'api', image: 'ghcr.io/example/catalog-api:1.4.2', ready: true, restartCount: 0, ports: [{ name: 'http', containerPort: 8080, protocol: 'TCP' }] }] },
   { name: 'catalog-api-6c847d7d4b-qv9mc', phase: 'Running', ready: true, podIp: '10.42.2.31', nodeName: 'k3s-worker-2', createdAt: now(), containers: [{ name: 'api', image: 'ghcr.io/example/catalog-api:1.4.2', ready: true, restartCount: 1, ports: [{ name: 'http', containerPort: 8080, protocol: 'TCP' }] }] },
  ] : namespace === 'kube-system' ? [
   { name: 'traefik-6f5d7c8b9d-4n2kq', phase: 'Running', ready: true, podIp: '10.42.0.8', nodeName: 'k3s-server-1', createdAt: now(), containers: [{ name: 'traefik', image: 'rancher/mirrored-library-traefik:3.3.6', ready: true, restartCount: 0, ports: [{ name: 'web', containerPort: 8000, protocol: 'TCP' }, { name: 'websecure', containerPort: 8443, protocol: 'TCP' }] }] },
  ] : [];
  return ok({ sourceId: 'kubernetes:demo-cluster', namespace, observedAt: now(), services, ingresses: namespace === 'nanpanel-mesh' ? [
   { name: 'catalog', ingressClassName: 'traefik', loadBalancerAddresses: ['192.0.2.50'], routes: [
    { host: 'catalog.example.test', path: '/', pathType: 'Prefix', serviceName: 'catalog-api', servicePort: 80, servicePortName: null },
   ] },
  ] : [], pods });
 }
 if (p === '/kubernetes/service-inventory' || p === '/kubernetes/service-inventory/refresh') return ok({
  sourceId: 'kubernetes:demo-cluster', namespace: 'nanpanel-mesh', observedAt: now(), services: [
   { serviceName: 'catalog-api', type: 'ClusterIP', clusterIps: ['10.43.120.17'], externalName: null, ports: [{ name: 'http', protocol: 'TCP', port: 80, targetPort: '8080', appProtocol: 'http' }], endpoints: [
    { addressType: 'IPv4', address: '10.42.1.23', ready: true, serving: true, terminating: false, targetKind: 'Pod', targetName: 'catalog-api-6c847d7d4b-kd2sh', targetNamespace: 'nanpanel-mesh', nodeName: 'k3s-worker-1', zone: null, ports: [{ name: 'http', protocol: 'TCP', port: 8080, appProtocol: 'http' }] },
    { addressType: 'IPv4', address: '10.42.2.31', ready: true, serving: true, terminating: false, targetKind: 'Pod', targetName: 'catalog-api-6c847d7d4b-qv9mc', targetNamespace: 'nanpanel-mesh', nodeName: 'k3s-worker-2', zone: null, ports: [{ name: 'http', protocol: 'TCP', port: 8080, appProtocol: 'http' }] },
   ] },
   { serviceName: 'catalog-canary', type: 'ClusterIP', clusterIps: ['10.43.121.8'], externalName: null, ports: [{ name: 'http', protocol: 'TCP', port: 80, targetPort: '8080', appProtocol: 'http' }], endpoints: [
    { addressType: 'IPv4', address: '10.42.1.46', ready: false, serving: true, terminating: false, targetKind: 'Pod', targetName: 'catalog-canary-7b89c56899-m88zg', targetNamespace: 'nanpanel-mesh', nodeName: 'k3s-worker-1', zone: null, ports: [{ name: 'http', protocol: 'TCP', port: 8080, appProtocol: 'http' }] },
   ] },
  ],
 });
 if (p === '/kubernetes/service-probes') return ok({
  sourceId: 'kubernetes:demo-cluster', enabled: true, observedAt: now(), probes: [
   { serviceName: 'catalog-api', address: '10.42.1.23', port: 8080, protocol: 'TCP', outcome: 'SUCCEEDED', observedAt: now() },
   { serviceName: 'catalog-api', address: '10.42.2.31', port: 8080, protocol: 'TCP', outcome: 'SUCCEEDED', observedAt: now() },
   { serviceName: 'catalog-canary', address: '10.42.1.46', port: 8080, protocol: 'TCP', outcome: 'TIMED_OUT', observedAt: now() },
  ],
 });
 if (p === '/hosts') {
  const status = url.searchParams.get('status'); const search = (url.searchParams.get('search') ?? '').trim().toLocaleLowerCase();
  return ok(page(hosts.filter(host => (!status || host.status === status) && (!search || host.hostname.toLocaleLowerCase().includes(search)))));
 }
 if (p === '/agent-enrollments' && method === 'POST') {
  const name = String(b.hostname ?? '').trim(); if (!name || name.length > 255) return fail(422, 'VALIDATION_FAILED', '主机名称无效');
  const hostId = id(); const agentId = id(); const expiresAt = new Date(Date.now() + 900000).toISOString();
  hosts.unshift({ id: hostId, hostname: name, os: null, arch: null, generation: 1, status: 'UNKNOWN', capabilities: [], agentId, lastSeenAt: null, createdAt: now() });
  log('AGENT_ENROLLMENT_CREATED', 'host', hostId, 'Mock 演示创建 Agent 接入码');
  return ok({ enrollmentId: id(), hostId, agentId, token: `${id().replaceAll('-', '')}${id().slice(0, 8)}`, expiresAt, controllerFingerprint: 'a'.repeat(64), gatewayPort: 30444, relayEndpoints: [], relayFallback: { endpoints: [], signature: '', publicKey: '' } }, 201, { 'Cache-Control': 'no-store' });
 }
 const host = hosts.find(h => h.id === p.split('/')[2]);
 if (p.startsWith('/hosts/') && !host) return fail(404, 'NOT_FOUND', '主机不存在');
 if (/^\/hosts\/[^/]+\/network-inventory$/.test(p)) {
  const hostIndex = Math.max(0, hosts.indexOf(host!));
  return ok({
  interfaces: [{ name: 'eth0', macAddress: '02:00:00:00:00:01', mtu: 1500, state: 'UP', addresses: [{ family: 'inet', address: `192.0.2.${20 + hostIndex}`, prefixLength: 24, scope: 'global' }, { family: 'inet6', address: `2001:db8::${20 + hostIndex}`, prefixLength: 64, scope: 'global' }] }],
  routes: [{ destination: 'default', gateway: hostIndex === 0 ? '192.0.2.21' : '192.0.2.1', interfaceName: 'eth0', protocol: 'static', scope: 'global', routeType: 'unicast', table: 'main', metric: 100 }],
  observedAt: host!.lastSeenAt,
  overlay: host!.status === 'ONLINE' ? { peerCount: 12, connectedPeerCount: 9, p2pPeerCount: 6, relayedPeerCount: 3, observedAt: now() } : null,
  overlayPeers: host!.status === 'ONLINE' ? [
   { peerName: 'api-a.example.test', status: 'CONNECTED', connectionType: 'P2P' },
   { peerName: 'db-b.example.test', status: 'CONNECTED', connectionType: 'RELAY' },
   { peerName: 'worker-c.example.test', status: 'DISCONNECTED', connectionType: 'UNKNOWN' },
  ] : [],
  overlayPeersObservedAt: host!.status === 'ONLINE' ? now() : null,
  });
 }
 if (/^\/hosts\/[^/]+$/.test(p)) return ok(host);
 if (/^\/hosts\/[^/]+\/ssh\/probe$/.test(p) && method === 'POST') { if (!b.reason?.trim() || b.reason.length > 500) return fail(422, 'VALIDATION_FAILED', 'SSH 操作原因无效'); log('SSH_ACCESS_PROBED', 'host', host!.id, `Mock SSH direct connection probe: ${b.reason}`); return ok({ hostId: host!.id, address: host!.hostname, port: 22, status: 'SUCCEEDED', verifiedAt: now(), evidence: 'Mock: confirmed host key and fixed true command succeeded' }); }
 if (/^\/hosts\/[^/]+\/ssh\/systemd\/restart$/.test(p) && method === 'POST') {
  const key = headers.get('Idempotency-Key'); if (!key) return fail(400, 'IDEMPOTENCY_KEY_REQUIRED', '缺少幂等键');
  const fingerprint = JSON.stringify([current.id, p, b]); const previous = replay.get(key);
  if (previous) return previous.fingerprint === fingerprint ? ok(previous.operation, 200, { 'Idempotency-Replayed': 'true' }) : fail(409, 'IDEMPOTENCY_KEY_REUSED', '幂等键已被其他请求使用');
  const unitName = String(b.unitName ?? '').trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9_.@:-]{0,247}\.service$/.test(unitName) || !b.reason?.trim() || b.reason.length > 500) return fail(422, 'VALIDATION_FAILED', 'SSH systemd unit 或操作原因无效');
  const stamp = now(); const op: Operation = { id: id(), kind: 'SSH_SYSTEMD_RESTART', hostId: host!.id, agentId: null, serviceId: null, capability: 'systemd.restart', target: unitName, idempotencyKey: key, requestedBy: current.id, reason: b.reason, expectedGeneration: null, phase: 'VERIFY', status: 'SUCCEEDED', createdAt: stamp, startedAt: stamp, updatedAt: stamp, deadlineAt: stamp, finishedAt: stamp, result: { unitName, active: true, verifiedAt: stamp }, error: null };
  operations.unshift(op); replay.set(key, { fingerprint, operation: op }); log('SSH_SYSTEMD_RESTART', 'host', host!.id, `Mock SSH restarted ${unitName}: ${b.reason}`);
  return ok(op, 200, { Location: `/api/v1/operations/${op.id}`, 'Idempotency-Replayed': 'false' });
 }
 if (/^\/hosts\/[^/]+\/ssh\/systemd\/(status|journal)$/.test(p) && method === 'POST') {
  const unitName = String(b.unitName ?? '').trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9_.@:-]{0,247}\.service$/.test(unitName) || !b.reason?.trim() || b.reason.length > 500) return fail(422, 'VALIDATION_FAILED', 'SSH systemd unit 或操作原因无效');
  if (p.endsWith('/status')) { log('SSH_SYSTEMD_STATUS_READ', 'host', host!.id, `Mock SSH status read for ${unitName}: ${b.reason}`); return ok({ hostId: host!.id, unitName, loadState: 'loaded', activeState: 'active', subState: 'running', observedAt: now() }); }
  const lines = Number(b.lines); if (!Number.isInteger(lines) || lines < 1 || lines > 100) return fail(422, 'VALIDATION_FAILED', 'SSH journal 行数须为 1–100');
  log('SSH_SYSTEMD_JOURNAL_READ', 'host', host!.id, `Mock SSH journal read for ${unitName}, at most ${lines} lines: ${b.reason}`);
  return ok({ hostId: host!.id, unitName, requestedLines: lines, output: `Mock journal output for ${unitName}`, truncated: false, observedAt: now() });
 }
 if (p.startsWith('/agents/')) { const h = hosts.find(x => x.agentId === p.split('/')[2]); return h ? ok({ id: h.agentId, hostId: h.id, version: '0.1.0', implementation: 'rust', status: h.status, lastSeenAt: h.lastSeenAt, capabilities: h.capabilities, helperStatus: h === hosts[3] ? 'UNAVAILABLE' : h.status === 'ONLINE' ? 'AVAILABLE' : 'UNKNOWN' }) : fail(404, 'NOT_FOUND', 'Agent 不存在'); }
 if (/^\/hosts\/[^/]+\/operations$/.test(p) && method === 'POST') {
  const key = headers.get('Idempotency-Key'); if (!key) return fail(400, 'IDEMPOTENCY_KEY_REQUIRED', '缺少幂等键');
  const fingerprint = JSON.stringify([current.id, p, b]); const previous = replay.get(key);
  if (previous) return previous.fingerprint === fingerprint ? ok(previous.operation, ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(previous.operation.status) ? 200 : 202, { 'Idempotency-Replayed': 'true' }) : fail(409, 'IDEMPOTENCY_KEY_REUSED', '幂等键已被其他请求使用');
  if (!b.reason?.trim() || b.reason.length > 500 || !Number.isInteger(b.expectedGeneration)) return fail(422, 'VALIDATION_FAILED', '操作原因或主机版本无效');
  if (b.expectedGeneration !== host!.generation) return fail(412, 'PRECONDITION_FAILED', '主机版本已变化，请刷新后重试');
  if (!host!.capabilities.includes(b.capability) || host!.status !== 'ONLINE') return fail(503, 'HELPER_UNAVAILABLE', 'Agent 或 Helper 不可用');
  if (operations.some(o => o.hostId === host!.id && o.kind === 'HOST_HELPER_OPERATION' && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(o.status))) return fail(409, 'OPERATION_IN_PROGRESS', '该主机有等待对账的 Helper 操作');
  const stamp = now(); const op: Operation = { id: id(), kind: 'HOST_HELPER_OPERATION', hostId: host!.id, agentId: host!.agentId!, serviceId: null, capability: String(b.capability), target: String(b.target ?? ''), idempotencyKey: key, requestedBy: current.id, reason: b.reason, expectedGeneration: host!.generation, phase: 'APPLY', status: 'RUNNING', createdAt: stamp, startedAt: stamp, updatedAt: stamp, deadlineAt: new Date(Date.now() + (b.timeoutSeconds ?? 60) * 1000).toISOString(), finishedAt: null, result: null, error: null };
  operations.unshift(op); replay.set(key, { fingerprint, operation: op }); log(String(b.capability), 'host', host!.id, b.reason);
  setTimeout(() => {
   const capability = String(b.capability); const target = String(b.target ?? '');
   try {
    const output = capability.startsWith('file.')
     ? mockFileOperation(host!.id, capability, target, (b.payload ?? {}) as Record<string, unknown>)
     : capability === 'container.list' ? '[{"name":"web-gateway","state":"running"}]'
     : capability === 'network.firewall.expose' ? JSON.stringify({ rule: target, interface: 'eth0', protocol: 'tcp', port: 2222, verified: true, automaticRollback: true, rollbackAfterSeconds: b.payload.rollbackSeconds })
     : `Mock ${capability} completed`;
    op.status = 'SUCCEEDED'; op.phase = 'COMMIT'; op.finishedAt = now(); op.updatedAt = now();
    op.result = { capability, target, output, completedAt: now() };
   } catch (cause) {
    op.status = 'FAILED'; op.phase = 'APPLY'; op.finishedAt = now(); op.updatedAt = now();
    op.error = { code: 'MOCK_OPERATION_FAILED', message: (cause as Error).message, retryable: false, executionOutcome: 'VERIFIED_FAILED' };
   }
  }, 1500);
  return ok(op, 202, { Location: `/api/v1/operations/${op.id}`, 'Retry-After': '1', 'Idempotency-Replayed': 'false' });
 }
 if (/\/services$/.test(p)) return ok(page(services.filter(s => s.hostId === host!.id)));
 if (p.includes('/services/')) {
  const service = services.find(s => s.id === p.split('/')[4] && s.hostId === host!.id); if (!service) return fail(404, 'NOT_FOUND', '服务不存在');
  if (method === 'GET') return ok(service, 200, { ETag: etag(service.id) });
  if (p.endsWith('/restart') && method === 'POST') {
   const key = headers.get('Idempotency-Key'); if (!key) return fail(400, 'IDEMPOTENCY_KEY_REQUIRED', '缺少幂等键');
   const fingerprint = JSON.stringify([current.id, p, b, headers.get('If-Match')]); const previous = replay.get(key);
   if (previous) return previous.fingerprint === fingerprint ? ok(previous.operation, ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(previous.operation.status) ? 200 : 202, { 'Idempotency-Replayed': 'true' }) : fail(409, 'IDEMPOTENCY_KEY_REUSED', '幂等键已被其他请求使用');
   if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '请先读取服务版本');
   if (!match(service.id)) return fail(412, 'PRECONDITION_FAILED', '服务版本变化，请刷新后确认');
   if (!b.reason?.trim() || b.reason.length > 500 || (b.timeoutSeconds && (b.timeoutSeconds < 5 || b.timeoutSeconds > 300))) return fail(422, 'VALIDATION_FAILED', '请提供操作原因和有效超时');
   if (!service.supportedActions.includes('RESTART') || host!.status !== 'ONLINE') return fail(503, 'AGENT_UNAVAILABLE', 'Agent 或 Helper 不可用');
   if (operations.some(o => o.serviceId === service.id && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(o.status))) return fail(409, 'OPERATION_IN_PROGRESS', '该服务有在途或结果未知的任务');
   const stamp = now(); const op: Operation = { id: id(), kind: 'SYSTEM_SERVICE_RESTART', hostId: host!.id, agentId: host!.agentId!, serviceId: service.id, capability: null, target: null, idempotencyKey: key, requestedBy: current.id, reason: b.reason, expectedGeneration: service.generation, phase: 'VALIDATE', status: 'PENDING', createdAt: stamp, startedAt: null, updatedAt: stamp, deadlineAt: new Date(Date.now() + (b.timeoutSeconds ?? 60) * 1000).toISOString(), finishedAt: null, result: null, error: null };
   operations.unshift(op); replay.set(key, { fingerprint, operation: op }); log('SYSTEM_SERVICE_RESTART', 'service', service.id, b.reason);
   setTimeout(() => { op.status = 'RUNNING'; op.phase = 'APPLY'; op.startedAt = now(); op.updatedAt = now(); }, 900);
   setTimeout(() => { op.status = 'SUCCEEDED'; op.phase = 'COMMIT'; op.finishedAt = now(); op.updatedAt = now(); op.result = { serviceId: service.id, unitName: service.unitName, activeState: 'ACTIVE', verifiedAt: now(), evidence: 'Mock：模拟执行与 systemd ACTIVE 观测；不证明业务健康' }; service.observedAt = now(); bump(service.id); }, 3500);
   return ok(op, 202, { Location: `/api/v1/operations/${op.id}`, 'Retry-After': '1', 'Idempotency-Replayed': 'false' });
  }
  if (p.endsWith('/journal') && method === 'POST') {
   const key = headers.get('Idempotency-Key'); if (!key) return fail(400, 'IDEMPOTENCY_KEY_REQUIRED', '缺少幂等键');
   const fingerprint = JSON.stringify([current.id, p, b, headers.get('If-Match')]); const previous = replay.get(key);
   if (previous) return previous.fingerprint === fingerprint ? ok(previous.operation, ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(previous.operation.status) ? 200 : 202, { 'Idempotency-Replayed': 'true' }) : fail(409, 'IDEMPOTENCY_KEY_REUSED', '幂等键已被其他请求使用');
   if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '请先读取服务版本');
   if (!match(service.id)) return fail(412, 'PRECONDITION_FAILED', '服务版本变化，请刷新后确认');
   if (!b.reason?.trim() || b.reason.length > 500) return fail(422, 'VALIDATION_FAILED', '请提供查询原因');
   if (!service.supportedActions.includes('READ_JOURNAL') || host!.status !== 'ONLINE') return fail(503, 'AGENT_UNAVAILABLE', 'Agent 或 Helper 不可用');
   if (operations.some(o => o.serviceId === service.id && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(o.status))) return fail(409, 'OPERATION_IN_PROGRESS', '该服务有在途或结果未知的任务');
   const stamp = now(); const op: Operation = { id: id(), kind: 'SYSTEMD_JOURNAL_READ', hostId: host!.id, agentId: host!.agentId!, serviceId: service.id, capability: 'systemd.journal.read', target: service.unitName, idempotencyKey: key, requestedBy: current.id, reason: b.reason, expectedGeneration: service.generation, phase: 'APPLY', status: 'RUNNING', createdAt: stamp, startedAt: stamp, updatedAt: stamp, deadlineAt: new Date(Date.now() + 30000).toISOString(), finishedAt: null, result: null, error: null };
   operations.unshift(op); replay.set(key, { fingerprint, operation: op }); log('SYSTEMD_JOURNAL_READ', 'service', service.id, b.reason);
   setTimeout(() => { op.status = 'SUCCEEDED'; op.phase = 'COMMIT'; op.finishedAt = now(); op.updatedAt = now(); op.result = { serviceId: service.id, unitName: service.unitName, journal: `Mock journal for ${service.unitName}\nOct 19 00:00:01 host service[123]: started\nOct 19 00:00:02 host service[123]: ready`, retrievedAt: now() }; }, 1600);
   return ok(op, 202, { Location: `/api/v1/operations/${op.id}`, 'Retry-After': '1', 'Idempotency-Replayed': 'false' });
  }
 }
 if (p === '/operations') return ok(page(operations.filter(o => (!url.searchParams.get('status') || o.status === url.searchParams.get('status')) && (!url.searchParams.get('hostId') || o.hostId === url.searchParams.get('hostId')))));
 if (p.startsWith('/operations/')) { const op = operations.find(o => o.id === p.split('/')[2]); return op ? ok(op) : fail(404, 'NOT_FOUND', '任务不存在'); }
 if (p === '/admin/overview') return ok({ observedAt: now(), hosts: { total: hosts.length, online: hosts.filter(h => h.status === 'ONLINE').length, offline: hosts.filter(h => h.status === 'OFFLINE').length, unknown: hosts.filter(h => h.status === 'UNKNOWN').length }, operations: { pending: operations.filter(o => o.status === 'PENDING').length, running: operations.filter(o => o.status === 'RUNNING').length, unknown: operations.filter(o => o.status === 'UNKNOWN').length, failedLast24Hours: 0 }, activeAdminUsers: users.filter(u => u.status === 'ACTIVE').length });
 if (p === '/admin/users' && method === 'GET') return ok(page(users));
 if (p === '/admin/users' && method === 'POST') { const username = String(b.username).toLowerCase(); if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(username) || !b.displayName?.trim() || [...String(b.initialPassword)].length < 15 || [...String(b.initialPassword)].length > 128) return fail(422, 'VALIDATION_FAILED', '用户名、显示名或初始密码无效'); if (users.some(u => u.username === username)) return fail(409, 'USERNAME_EXISTS', '用户名已存在'); const u: AdminUser = { id: id(), username, displayName: b.displayName, roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: true, createdAt: now(), updatedAt: now(), lastLoginAt: null }; users.push(u); passwords.set(u.id, b.initialPassword); log('ADMIN_CREATED', 'user', u.id, '创建管理员'); return ok(u, 201, { ETag: etag(u.id) }); }
 if (p.startsWith('/admin/users/')) { const u = users.find(u => u.id === p.split('/')[3]); if (!u) return fail(404, 'NOT_FOUND', '账号不存在'); if (method === 'GET') return ok(u, 200, { ETag: etag(u.id) }); if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '缺少版本'); if (!match(u.id)) return fail(412, 'PRECONDITION_FAILED', '账号已变化，请刷新'); if (p.endsWith('/password')) { if (u.id === current.id) return fail(409, 'SELF_RESET_FORBIDDEN', '请使用本人改密'); if ([...String(b.newPassword)].length < 15 || [...String(b.newPassword)].length > 128) return fail(422, 'PASSWORD_REJECTED', '密码须为 15–128 字符'); passwords.set(u.id, b.newPassword); u.mustChangePassword = true; } else { if (b.status === 'DISABLED' && (u.id === current.id || users.filter(x => x.status === 'ACTIVE').length <= 1)) return fail(409, 'ADMIN_CONFLICT', '不能禁用自己或最后一位管理员'); if (b.displayName !== undefined) { if (!b.displayName.trim()) return fail(422, 'VALIDATION_FAILED', '显示名不能为空'); u.displayName = b.displayName; } if (b.status) u.status = b.status; } u.updatedAt = now(); bump(u.id); log('ADMIN_UPDATED', 'user', u.id, '更新管理员'); return ok(u, 200, { ETag: etag(u.id) }); }
 if (p === '/admin/roles') return ok({ items: [{ id: 'ADMIN', name: '管理员', permissions: ['hosts:read', 'services:restart', 'admin:manage'] }] });
 if (p === '/admin/settings') { if (method === 'GET') return ok(settings, 200, { ETag: etag('settings') }); if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '缺少版本'); if (!match('settings')) return fail(412, 'PRECONDITION_FAILED', '设置已变化，请重新读取'); if (!b.siteName?.trim() || b.siteName.length > 100 || b.hostOfflineAfterSeconds < 30 || b.hostOfflineAfterSeconds > 600 || b.operationRetentionDays < 7 || b.operationRetentionDays > 365) return fail(422, 'VALIDATION_FAILED', '设置不符合约束'); try { new Intl.DateTimeFormat('zh', { timeZone: b.defaultTimezone }); } catch { return fail(422, 'VALIDATION_FAILED', '无效 IANA 时区'); } settings = { ...settings, ...b, updatedAt: now(), updatedBy: current.id }; bump('settings'); log('SETTINGS_UPDATED', 'settings', null, '更新非机密平台设置'); return ok(settings, 200, { ETag: etag('settings') }); }
 if (p === '/admin/audit-events') return ok(page(audit.filter(e => !url.searchParams.get('outcome') || e.outcome === url.searchParams.get('outcome'))));
 if (p.startsWith('/admin/audit-events/')) { const event = audit.find(e => e.id === p.split('/')[3]); return event ? ok(event) : fail(404, 'NOT_FOUND', '审计事件不存在'); }
 return fail(404, 'NOT_FOUND', '未定义的 Mock 接口');
}
