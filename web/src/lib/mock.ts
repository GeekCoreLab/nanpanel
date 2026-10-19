import { initialTopology, draftError } from './topology-model.ts';
import type { TopologyDraft } from './types';
import type { Host, SystemService, AdminUser, PlatformSettings, Operation, AuditEvent, WebSession } from './types';
const now = () => new Date().toISOString();
const id = () => crypto.randomUUID();
const uuid = (n: number) => `8b395d24-5d5d-4f27-b70b-${String(n).padStart(12, '0')}`;
export const DEMO_PASSWORD = 'nanpanel-demo-2026';
const hosts: Host[] = ['gateway-sg-01', 'homelab-node-01', 'k3s-worker-01', 'edge-shanghai-02', 'backup-node-01', 'lab-arm-01'].map((hostname, i) => ({ id: uuid(i + 1), hostname, os: 'linux', arch: i === 5 ? 'aarch64' : 'x86_64', status: i === 4 ? 'OFFLINE' : i === 5 ? 'UNKNOWN' : 'ONLINE', capabilities: ['systemd.read', 'systemd.restart'], agentId: uuid(i + 21), lastSeenAt: i >= 4 ? new Date(Date.now() - 3600000).toISOString() : now(), createdAt: now() }));
const services: SystemService[] = hosts.flatMap((h, i) => ['nginx', 'nanpanel-agent', 'frpc'].map((unit, j) => ({ id: uuid(100 + i * 3 + j), hostId: h.id, unitName: `${unit}.service`, displayName: ['Nginx Web Server', 'NaNPanel Rust Agent', 'FRP Tunnel Client'][j], activeState: 'ACTIVE', subState: 'running', generation: 1, observedAt: h.lastSeenAt, allowedActions: h.status === 'ONLINE' && i !== 3 ? ['RESTART'] : [], observationStale: h.status !== 'ONLINE' })));
const users: AdminUser[] = [{ id: uuid(50), username: 'admin', displayName: '实验室管理员', roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false, createdAt: now(), updatedAt: now(), lastLoginAt: now() }, { id: uuid(51), username: 'operator', displayName: '运维管理员', roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false, createdAt: now(), updatedAt: now(), lastLoginAt: null }];
const passwords = new Map(users.map(u => [u.id, DEMO_PASSWORD]));
let settings: PlatformSettings = { siteName: 'NaNPanel', defaultTimezone: 'Asia/Shanghai', hostOfflineAfterSeconds: 90, operationRetentionDays: 30, updatedAt: now(), updatedBy: users[0].id };
const versions = new Map<string, number>();
const etag = (key: string) => `"mock-${key}-${versions.get(key) ?? 1}"`;
const bump = (key: string) => versions.set(key, (versions.get(key) ?? 1) + 1);
let topology: TopologyDraft = { ...initialTopology(), updatedAt: now(), updatedBy: null };
const operations: Operation[] = [];
const audit: AuditEvent[] = [];
let sessions: WebSession[] = [];
let currentUserId: string | null = null;
let csrf = id();
function log(action: string, resourceType: string, resourceId: string | null, summary: string) { audit.unshift({ id: id(), occurredAt: now(), actorId: currentUserId, action, resourceType, resourceId, outcome: 'SUCCEEDED', traceId: id(), clientIp: '127.0.0.1', summary }); }
log('MOCK_INITIALIZED', 'system', null, '演示环境已初始化，所有操作仅在当前页面内模拟');
function sessionContext() { return { user: users.find(u => u.id === currentUserId), session: sessions.find(s => s.current), permissions: users.find(u => u.id === currentUserId)?.mustChangePassword ? [] : ['hosts:read', 'services:restart', 'admin:manage'], csrfToken: csrf }; }
function createSession(user: AdminUser) { const stamp = now(); currentUserId = user.id; csrf = id(); sessions = [{ id: id(), userId: user.id, current: true, createdAt: stamp, authenticatedAt: stamp, lastSeenAt: stamp, idleExpiresAt: new Date(Date.now() + 1800000).toISOString(), absoluteExpiresAt: new Date(Date.now() + 43200000).toISOString(), clientIp: '127.0.0.1', userAgent: navigator.userAgent }, ...sessions.filter(s => !s.current && s.userId === user.id)]; }
const replay = new Map<string, { fingerprint: string; operation: Operation }>();
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
  topology = { ...structuredClone(b), updatedAt: now(), updatedBy: current.id }; bump('topology');
  log('TOPOLOGY_DRAFT_UPDATED', 'topology', null, `保存期望拓扑草稿：${topology.nodes.length} 个节点、${topology.links.length} 条未验证连接`);
  return ok(topology, 200, { ETag: etag('topology'), 'Cache-Control': 'no-store' });
 }
 if (p === '/system') return ok({ name: 'NaNPanel', version: '0.1.0-mock', stage: 'mock' });
 if (p === '/hosts') return ok(page(hosts.filter(h => !url.searchParams.get('status') || h.status === url.searchParams.get('status'))));
 const host = hosts.find(h => h.id === p.split('/')[2]);
 if (p.startsWith('/hosts/') && !host) return fail(404, 'NOT_FOUND', '主机不存在');
 if (/^\/hosts\/[^/]+$/.test(p)) return ok(host);
 if (p.startsWith('/agents/')) { const h = hosts.find(x => x.agentId === p.split('/')[2]); return h ? ok({ id: h.agentId, hostId: h.id, version: '0.1.0', implementation: 'rust', status: h.status, lastSeenAt: h.lastSeenAt, capabilities: h.capabilities, helperStatus: h === hosts[3] ? 'UNAVAILABLE' : h.status === 'ONLINE' ? 'AVAILABLE' : 'UNKNOWN' }) : fail(404, 'NOT_FOUND', 'Agent 不存在'); }
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
   if (!service.allowedActions.includes('RESTART') || host!.status !== 'ONLINE') return fail(503, 'AGENT_UNAVAILABLE', 'Agent 或 Helper 不可用');
   if (operations.some(o => o.serviceId === service.id && ['PENDING', 'RUNNING', 'UNKNOWN'].includes(o.status))) return fail(409, 'OPERATION_IN_PROGRESS', '该服务有在途或结果未知的任务');
   const stamp = now(); const op: Operation = { id: id(), kind: 'SYSTEM_SERVICE_RESTART', hostId: host!.id, agentId: host!.agentId!, serviceId: service.id, idempotencyKey: key, requestedBy: current.id, reason: b.reason, expectedGeneration: service.generation, phase: 'VALIDATE', status: 'PENDING', createdAt: stamp, startedAt: null, updatedAt: stamp, deadlineAt: new Date(Date.now() + (b.timeoutSeconds ?? 60) * 1000).toISOString(), finishedAt: null, result: null, error: null };
   operations.unshift(op); replay.set(key, { fingerprint, operation: op }); log('SYSTEM_SERVICE_RESTART', 'service', service.id, b.reason);
   setTimeout(() => { op.status = 'RUNNING'; op.phase = 'APPLY'; op.startedAt = now(); op.updatedAt = now(); }, 900);
   setTimeout(() => { op.status = 'SUCCEEDED'; op.phase = 'COMMIT'; op.finishedAt = now(); op.updatedAt = now(); op.result = { serviceId: service.id, unitName: service.unitName, activeState: 'ACTIVE', verifiedAt: now(), evidence: 'Mock：模拟执行与 systemd ACTIVE 观测；不证明业务健康' }; service.observedAt = now(); bump(service.id); }, 3500);
   return ok(op, 202, { Location: `/api/v1/operations/${op.id}`, 'Retry-After': '1', 'Idempotency-Replayed': 'false' });
  }
 }
 if (p === '/operations') return ok(page(operations.filter(o => (!url.searchParams.get('status') || o.status === url.searchParams.get('status')) && (!url.searchParams.get('hostId') || o.hostId === url.searchParams.get('hostId')))));
 if (p.startsWith('/operations/')) { const op = operations.find(o => o.id === p.split('/')[2]); return op ? ok(op) : fail(404, 'NOT_FOUND', '任务不存在'); }
 if (p === '/admin/overview') return ok({ observedAt: now(), hosts: { total: hosts.length, online: 4, offline: 1, unknown: 1 }, operations: { pending: operations.filter(o => o.status === 'PENDING').length, running: operations.filter(o => o.status === 'RUNNING').length, unknown: operations.filter(o => o.status === 'UNKNOWN').length, failedLast24Hours: 0 }, activeAdminUsers: users.filter(u => u.status === 'ACTIVE').length });
 if (p === '/admin/users' && method === 'GET') return ok(page(users));
 if (p === '/admin/users' && method === 'POST') { const username = String(b.username).toLowerCase(); if (!/^[a-z][a-z0-9_.-]{2,63}$/.test(username) || !b.displayName?.trim() || [...String(b.initialPassword)].length < 15 || [...String(b.initialPassword)].length > 128) return fail(422, 'VALIDATION_FAILED', '用户名、显示名或初始密码无效'); if (users.some(u => u.username === username)) return fail(409, 'USERNAME_EXISTS', '用户名已存在'); const u: AdminUser = { id: id(), username, displayName: b.displayName, roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: true, createdAt: now(), updatedAt: now(), lastLoginAt: null }; users.push(u); passwords.set(u.id, b.initialPassword); log('ADMIN_CREATED', 'user', u.id, '创建管理员'); return ok(u, 201, { ETag: etag(u.id) }); }
 if (p.startsWith('/admin/users/')) { const u = users.find(u => u.id === p.split('/')[3]); if (!u) return fail(404, 'NOT_FOUND', '账号不存在'); if (method === 'GET') return ok(u, 200, { ETag: etag(u.id) }); if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '缺少版本'); if (!match(u.id)) return fail(412, 'PRECONDITION_FAILED', '账号已变化，请刷新'); if (p.endsWith('/password')) { if (u.id === current.id) return fail(409, 'SELF_RESET_FORBIDDEN', '请使用本人改密'); if ([...String(b.newPassword)].length < 15 || [...String(b.newPassword)].length > 128) return fail(422, 'PASSWORD_REJECTED', '密码须为 15–128 字符'); passwords.set(u.id, b.newPassword); u.mustChangePassword = true; } else { if (b.status === 'DISABLED' && (u.id === current.id || users.filter(x => x.status === 'ACTIVE').length <= 1)) return fail(409, 'ADMIN_CONFLICT', '不能禁用自己或最后一位管理员'); if (b.displayName !== undefined) { if (!b.displayName.trim()) return fail(422, 'VALIDATION_FAILED', '显示名不能为空'); u.displayName = b.displayName; } if (b.status) u.status = b.status; } u.updatedAt = now(); bump(u.id); log('ADMIN_UPDATED', 'user', u.id, '更新管理员'); return ok(u, 200, { ETag: etag(u.id) }); }
 if (p === '/admin/roles') return ok({ items: [{ id: 'ADMIN', name: '管理员', permissions: ['hosts:read', 'services:restart', 'admin:manage'] }] });
 if (p === '/admin/settings') { if (method === 'GET') return ok(settings, 200, { ETag: etag('settings') }); if (!headers.has('If-Match')) return fail(428, 'PRECONDITION_REQUIRED', '缺少版本'); if (!match('settings')) return fail(412, 'PRECONDITION_FAILED', '设置已变化，请重新读取'); if (!b.siteName?.trim() || b.siteName.length > 100 || b.hostOfflineAfterSeconds < 30 || b.hostOfflineAfterSeconds > 600 || b.operationRetentionDays < 7 || b.operationRetentionDays > 365) return fail(422, 'VALIDATION_FAILED', '设置不符合约束'); try { new Intl.DateTimeFormat('zh', { timeZone: b.defaultTimezone }); } catch { return fail(422, 'VALIDATION_FAILED', '无效 IANA 时区'); } settings = { ...settings, ...b, updatedAt: now(), updatedBy: current.id }; bump('settings'); log('SETTINGS_UPDATED', 'settings', null, '更新非机密平台设置'); return ok(settings, 200, { ETag: etag('settings') }); }
 if (p === '/admin/audit-events') return ok(page(audit.filter(e => !url.searchParams.get('outcome') || e.outcome === url.searchParams.get('outcome'))));
 if (p.startsWith('/admin/audit-events/')) { const event = audit.find(e => e.id === p.split('/')[3]); return event ? ok(event) : fail(404, 'NOT_FOUND', '审计事件不存在'); }
 return fail(404, 'NOT_FOUND', '未定义的 Mock 接口');
}
