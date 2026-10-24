import { chromium } from '@playwright/test';
import http from 'node:http';

const baseUrl = process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173';
const username = process.env.NANPANEL_BROWSER_USERNAME ?? 'admin';
let password = process.env.NANPANEL_BROWSER_PASSWORD;
const newPassword = process.env.NANPANEL_BROWSER_NEW_PASSWORD;
const clusterId = process.env.NANPANEL_BROWSER_CLUSTER_ID;
const clusterName = process.env.NANPANEL_BROWSER_CLUSTER_NAME;
const gatewayUrl = new URL(process.env.NANPANEL_BROWSER_GATEWAY_URL ?? 'http://127.0.0.1:18080/');

if (!password || !clusterId || !clusterName) {
  throw new Error('Set NANPANEL_BROWSER_PASSWORD, NANPANEL_BROWSER_CLUSTER_ID, and NANPANEL_BROWSER_CLUSTER_NAME');
}

// The disposable P5 Prometheus fixture scrapes p5-demo and p5-api-release*.
const releaseName = `p5-api-release-ui-${Date.now().toString(36)}`;
const hostname = `${releaseName}.example.test`;
const pageErrors = [];
const responseCounts = new Map();
let latestPlan;
let stopped = false;

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function routeRequest(record = true) {
  return new Promise(resolve => {
    const request = http.request(gatewayUrl, {
      method: 'GET',
      headers: { host: hostname },
      timeout: 3000,
    }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => {
        const body = Buffer.concat(chunks).toString().trim();
        const key = `${response.statusCode} ${body}`;
        if (record) responseCounts.set(key, (responseCounts.get(key) ?? 0) + 1);
        resolve(key);
      });
    });
    request.on('timeout', () => request.destroy(new Error('Gateway request timed out')));
    request.on('error', error => {
      const key = `transport ${error.code ?? error.message}`;
      if (record) responseCounts.set(key, (responseCounts.get(key) ?? 0) + 1);
      resolve(key);
    });
    request.end();
  });
}

async function trafficPump() {
  while (!stopped) {
    await routeRequest();
    await wait(30);
  }
}

function analysisPassed(plan, weight) {
  const stage = [...(plan.stages ?? [])].reverse().find(item =>
    item.observedCanaryWeight === weight && item.analysisRunName,
  );
  if (!stage) return false;
  const samples = (plan.measurements ?? []).filter(item =>
    item.analysisRunName === stage.analysisRunName && item.metricName === 'canary-http-error-rate',
  ).sort((a, b) => a.measurementOrdinal - b.measurementOrdinal).slice(-3);
  return samples.length === 3 && samples.every(item =>
    item.phase === 'SUCCESSFUL' && Number(item.value) <= 0.025,
  );
}

async function waitForPlan(predicate, label, timeoutMs = 360_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (latestPlan?.state === 'FAILED' || latestPlan?.state === 'ROLLED_BACK') {
      throw new Error(`${label}: release became ${latestPlan.state}: ${latestPlan.errorCode ?? ''}`);
    }
    if (latestPlan && predicate(latestPlan)) return latestPlan;
    await wait(1000);
  }
  throw new Error(`${label}: timed out; last plan=${JSON.stringify(latestPlan)}`);
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1080 } });
page.on('pageerror', error => pageErrors.push(error.message));
page.on('response', response => {
  const request = response.request();
  const url = new URL(response.url());
  if (!url.pathname.includes('/application-releases')) return;
  void response.json().then(body => {
    const plans = Array.isArray(body.items) ? body.items : [body];
    latestPlan = plans.find(plan => plan?.name === releaseName) ?? latestPlan;
  }).catch(() => undefined);
  if (response.status() >= 400) {
    console.error(`API ${request.method()} ${url.pathname} returned ${response.status()}`);
  }
});
page.on('dialog', dialog => dialog.accept());

let pump;
try {
  await page.goto(`${baseUrl}/#clusters`);
  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: /进入控制台/ }).click();

  if (await page.getByText('请先修改初始密码').count()) {
    if (!newPassword) throw new Error('The admin requires a password change; set NANPANEL_BROWSER_NEW_PASSWORD');
    await page.getByLabel('当前密码').fill(password);
    await page.getByLabel('新密码（15–128 字符）').fill(newPassword);
    await page.getByLabel('确认新密码').fill(newPassword);
    await page.getByRole('button', { name: '修改密码并退出' }).click();
    password = newPassword;
    await page.getByLabel('用户名').fill(username);
    await page.getByLabel('密码').fill(password);
    await page.getByRole('button', { name: /进入控制台/ }).click();
  }

  await page.getByRole('heading', { name: 'Kubernetes 集群' }).waitFor();
  if (await page.getByText('Live API').count() === 0) {
    throw new Error('The console did not enter Live API mode');
  }
  const clusterCard = page.locator('.cluster-card').filter({ hasText: clusterName });
  await clusterCard.getByRole('heading', { name: clusterName }).waitFor();
  await clusterCard.getByRole('button', { name: '读取 Namespace 与资源管理' }).click();
  const namespace = clusterCard.getByLabel(`${clusterName} Namespace`);
  await namespace.waitFor();
  await namespace.selectOption('default');

  const panel = clusterCard.locator('.application-release-panel');
  await panel.getByRole('heading', { name: '渐进发布（Gateway API + Argo Rollouts）' }).waitFor();
  await panel.getByLabel('应用名称').fill(releaseName);
  await panel.getByLabel('当前稳定镜像').fill('localhost/nanpanel-p5-app:v1');
  await panel.getByLabel('目标镜像').fill('localhost/nanpanel-p5-app:v2');
  await panel.getByLabel('副本数').fill('2');
  await panel.getByLabel('容器端口').fill('8080');
  await panel.getByLabel('Gateway 名称').fill('eg');
  await panel.getByLabel('HTTP 主机名').fill(hostname);
  await panel.getByLabel('Prometheus Service').fill('prometheus');
  await panel.getByLabel('最大 HTTP 错误率（0.01–0.5）').fill('0.05');
  await panel.getByLabel('发布原因').fill('P5 live browser-to-Core acceptance');
  await panel.getByRole('button', { name: '创建发布草稿' }).click();

  const releaseRow = panel.locator('.managed-deployment-row').filter({ hasText: releaseName });
  await releaseRow.waitFor();
  await releaseRow.locator('.badge').filter({ hasText: /^DRAFT$/ }).waitFor();
  await panel.getByRole('button', { name: '刷新发布' }).click();
  await releaseRow.locator('.badge').filter({ hasText: /^DRAFT$/ }).waitFor();
  await page.waitForTimeout(500);
  if (latestPlan?.clusterId !== clusterId || latestPlan?.state !== 'DRAFT') {
    throw new Error(`The browser did not read its persisted draft from Core: ${JSON.stringify(latestPlan)}`);
  }

  await releaseRow.getByRole('button', { name: '启动', exact: true }).click();
  await waitForPlan(current =>
    current.state === 'RUNNING' && current.stableWeight === 90 && current.canaryWeight === 10,
    '10% route activation',
  );
  const routeReadyDeadline = Date.now() + 30_000;
  let routeReadyResponse;
  while (Date.now() < routeReadyDeadline) {
    routeReadyResponse = await routeRequest(false);
    if (routeReadyResponse === '200 v1' || routeReadyResponse === '200 v2') break;
    await wait(250);
  }
  if (routeReadyResponse !== '200 v1' && routeReadyResponse !== '200 v2') {
    throw new Error(`The 10% Gateway route did not become ready: ${routeReadyResponse}`);
  }
  pump = trafficPump();
  await waitForPlan(current =>
    current.state === 'RUNNING' && current.stableWeight === 90 && current.canaryWeight === 10,
    'running 10% stage before operator pause',
  );
  await releaseRow.getByRole('button', { name: '暂停', exact: true }).click();
  await waitForPlan(current => current.state === 'PAUSED' && current.canaryWeight === 10,
    'operator pause at 10%');
  await releaseRow.getByRole('button', { name: '恢复自动推进' }).click();
  await waitForPlan(current => current.state === 'RUNNING' && current.canaryWeight === 10,
    'operator resume at 10%');
  for (const weight of [10, 50, 100]) {
    const plan = await waitForPlan(current =>
      current.state === 'PAUSED'
      && current.canaryWeight === weight
      && current.stableWeight === 100 - weight
      && analysisPassed(current, weight),
    `${weight}% stage with successful measured analysis`,
  );
    await releaseRow.getByText(`stable ${100 - weight}% / canary ${weight}%`).waitFor();
    await releaseRow.getByText(/最近测量：.*canary-http-error-rate=/).waitFor();

    if (plan.id !== latestPlan.id) throw new Error('Release identity changed while polling Core');
    await releaseRow.getByRole('button', { name: '批准下一阶段' }).click();
  }

  const completed = await waitForPlan(current =>
    current.state === 'SUCCEEDED'
    && current.stableWeight === 100
    && current.canaryWeight === 0,
  'successful terminal release with stable route restored',
  );
  await releaseRow.getByText('stable 100% / canary 0%').waitFor();
  await releaseRow.locator('.badge.succeeded').waitFor();
  if (pageErrors.length) throw new Error(`Browser errors: ${pageErrors.join('\n')}`);

  stopped = true;
  await pump;
  pump = undefined;
  console.log(JSON.stringify({
    result: 'PASS',
    clusterId,
    releaseId: completed.id,
    state: completed.state,
    terminalWeights: { stable: completed.stableWeight, canary: completed.canaryWeight },
    measurementCount: completed.measurements.length,
    routedResponses: Object.fromEntries([...responseCounts.entries()].sort()),
  }, null, 2));
} finally {
  stopped = true;
  if (pump) await pump;
  await browser.close();
}
