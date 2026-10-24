import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

const baseUrl = process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173';
await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
const pageErrors = [];
const hostActions = [];
const kubernetesActions = [];
const now = '2026-10-23T04:00:00Z';
const hostIdA = '11111111-1111-4111-8111-111111111111';
const hostIdB = '22222222-2222-4222-8222-222222222222';
const hostIdBlocked = '33333333-3333-4333-8333-333333333333';
const clusterId = '44444444-4444-4444-8444-444444444444';
const hosts = [
  { id: hostIdA, hostname: 'release-host-a', os: 'linux', arch: 'x86_64', generation: 4, status: 'ONLINE', capabilities: ['container.deploy', 'container.start', 'container.stop', 'nginx.route.set', 'k3s.install'], agentId: null, lastSeenAt: now, createdAt: now },
  { id: hostIdB, hostname: 'release-host-b', os: 'linux', arch: 'x86_64', generation: 7, status: 'ONLINE', capabilities: ['container.deploy', 'container.start', 'container.stop', 'nginx.route.set'], agentId: null, lastSeenAt: now, createdAt: now },
  { id: hostIdBlocked, hostname: 'release-host-incomplete', os: 'linux', arch: 'x86_64', generation: 2, status: 'ONLINE', capabilities: ['container.deploy', 'container.start', 'container.stop'], agentId: null, lastSeenAt: now, createdAt: now },
];
const cluster = {
  id: clusterId, name: 'p5-isolated-k3s', kind: 'K3S', endpoint: 'https://127.0.0.1:6443', contextName: 'p5',
  healthStatus: 'HEALTHY', cloudCoreStatus: 'NOT_OBSERVED', cloudCoreObservedAt: null, observedAt: now,
  observationsStale: false, lastErrorCode: null, nodeCount: 0, createdAt: now, credentialSource: 'KUBECONFIG', discoveredHostId: null,
};
let hostPlan;
let kubernetesPlan;

const session = {
  user: { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', username: 'p5-operator', displayName: 'P5 Operator', roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false, createdAt: now, updatedAt: now, lastLoginAt: now },
  session: { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', userId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', current: true, createdAt: now, authenticatedAt: now, lastSeenAt: now, idleExpiresAt: now, absoluteExpiresAt: now, clientIp: '127.0.0.1', userAgent: 'Playwright' },
  permissions: ['ADMIN'], csrfToken: 'p5-browser-token',
};

function response(body, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) };
}

function hostRelease(payload) {
  return {
    id: '55555555-5555-4555-8555-555555555555', name: payload.name, stableImage: payload.stableImage,
    image: payload.image, containerPort: payload.containerPort, serverName: payload.serverName,
    listenPort: payload.listenPort, healthPath: payload.healthPath, batchSize: payload.batchSize,
    activeOrdinal: 0, state: 'DRAFT', reason: payload.reason, errorCode: null, errorMessage: null,
    requestedBy: session.user.username, createdAt: now, startedAt: null, finishedAt: null,
    targets: payload.targets.map((target, ordinal) => ({ ordinal, hostId: target.hostId, listenAddress: target.listenAddress,
      routeName: `${payload.name}-${ordinal + 1}`, stablePort: 18080 + ordinal * 2,
      candidatePort: 18081 + ordinal * 2, state: 'PENDING', detail: null, operationId: null,
      expectedGeneration: target.expectedGeneration })),
  };
}

function kubernetesRelease(payload) {
  return {
    id: '66666666-6666-4666-8666-666666666666', clusterId, namespace: 'apps', name: payload.name,
    rolloutName: `${payload.name}-rollout`, routeName: `${payload.name}-route`, hostname: payload.hostname,
    stableImage: payload.stableImage, image: payload.image, containerPort: payload.containerPort,
    replicas: payload.replicas, maxErrorRate: payload.maxErrorRate, reason: payload.reason,
    state: 'DRAFT', stableWeight: null, canaryWeight: null, rolloutPhase: null,
    currentStepMessage: null, errorCode: null, errorMessage: null, requestedBy: session.user.username,
    createdAt: now, startedAt: null, observedAt: null, finishedAt: null, stages: [], measurements: [],
  };
}

function advanceKubernetesPlan(action) {
  kubernetesActions.push(action);
  if (action === 'start') {
    kubernetesPlan.state = 'RUNNING';
    kubernetesPlan.rolloutPhase = 'Progressing';
    kubernetesPlan.stableWeight = 90;
    kubernetesPlan.canaryWeight = 10;
    kubernetesPlan.stages = [{ ordinal: 0, stage: 'CANARY_10', state: 'ACTIVE', desiredCanaryWeight: 10,
      observedCanaryWeight: 10, observedStableWeight: 90, analysisRunName: 'web-analysis-10', detail: 'HTTP traffic observed', startedAt: now, finishedAt: null }];
    kubernetesPlan.measurements = [{ analysisRunName: 'web-analysis-10', metricName: 'http-error-rate', measurementOrdinal: 0,
      phase: 'SUCCESSFUL', value: 0.01, detail: 'within configured threshold', sampledAt: now }];
  } else if (action === 'pause') {
    kubernetesPlan.state = 'PAUSED';
    kubernetesPlan.rolloutPhase = 'Paused';
  } else if (action === 'resume') {
    kubernetesPlan.state = 'RUNNING';
    kubernetesPlan.rolloutPhase = 'Progressing';
  } else if (action === 'promote') {
    kubernetesPlan.state = 'PAUSED';
    kubernetesPlan.rolloutPhase = 'Paused';
    kubernetesPlan.stableWeight = 50;
    kubernetesPlan.canaryWeight = 50;
    kubernetesPlan.stages.push({ ordinal: 1, stage: 'CANARY_50', state: 'ACTIVE', desiredCanaryWeight: 50,
      observedCanaryWeight: 50, observedStableWeight: 50, analysisRunName: 'web-analysis-50', detail: 'operator approved next stage', startedAt: now, finishedAt: null });
  } else if (action === 'rollback') {
    kubernetesPlan.state = 'ROLLED_BACK';
    kubernetesPlan.rolloutPhase = 'Degraded';
    kubernetesPlan.stableWeight = 100;
    kubernetesPlan.canaryWeight = 0;
  }
}

await page.route('**/api/v1/**', async route => {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname.replace(/^\/api\/v1/, '');
  const method = request.method();
  let body = {};

  if (path === '/auth/me') return route.fulfill(response(session));
  if (path === '/auth/csrf') return route.fulfill(response({ csrfToken: session.csrfToken, expiresAt: now }));
  if (path === '/hosts') return route.fulfill(response({ items: hosts, nextCursor: null }));
  if (path === '/host-application-releases' && method === 'GET') {
    return route.fulfill(response({ items: hostPlan ? [hostPlan] : [] }));
  }
  if (path === '/host-application-releases' && method === 'POST') {
    hostPlan = hostRelease(request.postDataJSON());
    return route.fulfill(response(hostPlan, 201));
  }
  const hostAction = path.match(/^\/host-application-releases\/([^/]+)\/(start|pause|resume|reconcile|rollback)$/);
  if (hostAction && method === 'POST' && hostPlan) {
    const action = hostAction[2];
    hostActions.push(action);
    if (action === 'start') { hostPlan.state = 'RUNNING'; hostPlan.activeOrdinal = 1; }
    if (action === 'pause') hostPlan.state = 'PAUSED';
    if (action === 'resume') hostPlan.state = 'RUNNING';
    if (action === 'reconcile') hostPlan.state = 'RUNNING';
    if (action === 'rollback') hostPlan.state = 'ROLLED_BACK';
    return route.fulfill(response(hostPlan));
  }
  if (path === '/kubernetes/clusters' && method === 'GET') return route.fulfill(response({ items: [cluster] }));
  if (path === '/kubernetes/discoveries') return route.fulfill(response({ items: [] }));
  if (path === '/kubernetes/rollouts') return route.fulfill(response({ items: [] }));
  if (path === '/kubernetes/installations') return route.fulfill(response({ items: [] }));
  if (path === `/kubernetes/clusters/${clusterId}/topology/namespaces`) {
    return route.fulfill(response({ sourceId: clusterId, observedAt: now, namespaces: [{ name: 'apps', phase: 'Active' }] }));
  }
  if (path === `/kubernetes/clusters/${clusterId}/namespaces/apps/deployments`) {
    return route.fulfill(response({ clusterId, namespace: 'apps', observedAt: now, items: [] }));
  }
  const releasePath = `/kubernetes/clusters/${clusterId}/namespaces/apps/application-releases`;
  if (path === releasePath && method === 'GET') {
    return route.fulfill(response({ items: kubernetesPlan ? [kubernetesPlan] : [] }));
  }
  if (path === releasePath && method === 'POST') {
    kubernetesPlan = kubernetesRelease(request.postDataJSON());
    return route.fulfill(response(kubernetesPlan, 201));
  }
  const kubernetesAction = path.match(new RegExp(`^${releasePath}/([^/]+)/(start|pause|resume|promote|rollback)$`));
  if (kubernetesAction && method === 'POST' && kubernetesPlan) {
    advanceKubernetesPlan(kubernetesAction[2]);
    return route.fulfill(response(kubernetesPlan));
  }

  if (path === '/operations') return route.fulfill(response({ items: [], nextCursor: null }));
  return route.fulfill(response({ items: [], nextCursor: null }));
});

page.on('pageerror', error => pageErrors.push(error.message));
page.on('dialog', dialog => dialog.accept());

try {
  await page.goto(`${baseUrl}/#hosts`);
  await page.getByRole('heading', { name: '主机资产' }).waitFor();

  const hostPanel = page.locator('.host-release-panel');
  await hostPanel.getByRole('heading', { name: '普通主机蓝绿发布' }).waitFor();
  const targetRows = hostPanel.locator('.host-release-target');
  if (await targetRows.nth(2).locator('input[type="checkbox"]').isEnabled()) throw new Error('Host missing nginx.route.set must be disabled');
  await targetRows.nth(0).locator('input[type="checkbox"]').check();
  await targetRows.nth(1).locator('input[type="checkbox"]').check();
  await hostPanel.getByLabel('release-host-a Nginx 监听地址').fill('10.20.0.11');
  await hostPanel.getByLabel('release-host-b Nginx 监听地址').fill('10.20.0.12');
  await hostPanel.getByLabel('应用名称').fill('web');
  await hostPanel.getByLabel('当前稳定镜像').fill('registry.example/web:1.9.0');
  await hostPanel.getByLabel('候选镜像').fill('registry.example/web:2.0.0');
  await hostPanel.getByLabel('容器端口').fill('8080');
  await hostPanel.getByLabel('HTTP 主机名').fill('web.example.test');
  await hostPanel.getByLabel('Nginx 监听端口').fill('18080');
  await hostPanel.getByLabel('健康检查路径').fill('/health');
  await hostPanel.getByLabel('每批主机数').fill('1');
  await hostPanel.getByLabel('发布原因').fill('P5 browser acceptance');
  await hostPanel.getByRole('button', { name: '创建主机发布草稿' }).click();
  await hostPanel.getByText('web · registry.example/web:1.9.0 → registry.example/web:2.0.0').waitFor();
  await hostPanel.getByRole('button', { name: '启动', exact: true }).click();
  await hostPanel.getByRole('button', { name: '暂停', exact: true }).waitFor();
  await hostPanel.getByRole('button', { name: '暂停', exact: true }).click();
  await hostPanel.getByRole('button', { name: '批准下一批', exact: true }).waitFor();
  await hostPanel.getByRole('button', { name: '批准下一批', exact: true }).click();
  await hostPanel.getByRole('button', { name: '暂停', exact: true }).click();
  await hostPanel.getByRole('button', { name: '回滚已完成主机' }).click();
  await hostPanel.getByText('ROLLED_BACK', { exact: true }).waitFor();
  await page.screenshot({ path: new URL('../artifacts/p5-host-release-console.png', import.meta.url).pathname, fullPage: true });

  await page.getByRole('link', { name: /Kubernetes 集群/ }).click();
  await page.getByRole('heading', { name: 'Kubernetes 集群' }).waitFor();
  await page.getByRole('button', { name: '读取 Namespace 与资源管理' }).click();
  const kubernetesPanel = page.locator('.application-release-panel');
  await kubernetesPanel.getByRole('heading', { name: '渐进发布（Gateway API + Argo Rollouts）' }).waitFor();
  await kubernetesPanel.getByLabel('应用名称').fill('web');
  await kubernetesPanel.getByLabel('当前稳定镜像').fill('registry.example/web:1.9.0');
  await kubernetesPanel.getByLabel('目标镜像').fill('registry.example/web:2.0.0');
  await kubernetesPanel.getByLabel('副本数').fill('2');
  await kubernetesPanel.getByLabel('容器端口').fill('8080');
  await kubernetesPanel.getByLabel('Gateway 名称').fill('public-gateway');
  await kubernetesPanel.getByLabel('HTTP 主机名').fill('web.example.test');
  await kubernetesPanel.getByLabel('Prometheus Service').fill('prometheus');
  await kubernetesPanel.getByLabel('最大 HTTP 错误率（0.01–0.5）').fill('0.05');
  await kubernetesPanel.getByLabel('发布原因').fill('P5 browser acceptance');
  await kubernetesPanel.getByRole('button', { name: '创建发布草稿' }).click();
  await kubernetesPanel.getByText('web · registry.example/web:1.9.0 → registry.example/web:2.0.0').waitFor();
  await kubernetesPanel.getByRole('button', { name: '启动', exact: true }).click();
  await kubernetesPanel.getByText('stable 90% / canary 10%').waitFor();
  await kubernetesPanel.getByText(/http-error-rate=0.01 \(SUCCESSFUL\)/).waitFor();
  await kubernetesPanel.getByRole('button', { name: '暂停', exact: true }).click();
  await kubernetesPanel.getByRole('button', { name: '恢复自动推进' }).waitFor();
  await kubernetesPanel.getByRole('button', { name: '恢复自动推进' }).click();
  await kubernetesPanel.getByRole('button', { name: '暂停', exact: true }).click();
  await kubernetesPanel.getByRole('button', { name: '批准下一阶段' }).waitFor();
  await kubernetesPanel.getByRole('button', { name: '批准下一阶段' }).click();
  await kubernetesPanel.getByText('stable 50% / canary 50%').waitFor();
  await kubernetesPanel.getByRole('button', { name: '回滚', exact: true }).click();
  await kubernetesPanel.getByText('stable 100% / canary 0%').waitFor();
  await page.screenshot({ path: new URL('../artifacts/p5-kubernetes-release-console.png', import.meta.url).pathname, fullPage: true });

  if (hostActions.join(',') !== 'start,pause,resume,pause,rollback') throw new Error(`Unexpected host action flow: ${hostActions.join(',')}`);
  if (kubernetesActions.join(',') !== 'start,pause,resume,pause,promote,rollback') throw new Error(`Unexpected Kubernetes action flow: ${kubernetesActions.join(',')}`);
  if (pageErrors.length) throw new Error(pageErrors.join('\n'));
  console.log('PASS: host release create/order/capability filtering/start/pause/next-batch/rollback; Kubernetes release create/start/measurements/pause/resume/promote/rollback; no page errors');
} finally {
  await browser.close();
}
