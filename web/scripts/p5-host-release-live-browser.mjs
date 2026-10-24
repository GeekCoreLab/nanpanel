import { chromium } from '@playwright/test';
import http from 'node:http';
import { dirname } from 'node:path';
import { mkdir } from 'node:fs/promises';

const baseUrl = process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173';
const username = process.env.NANPANEL_BROWSER_USERNAME ?? 'admin';
const password = process.env.NANPANEL_BROWSER_PASSWORD;
const runId = process.env.NANPANEL_P5_RUN_ID;
const hostA = process.env.NANPANEL_P5_HOST_A;
const hostB = process.env.NANPANEL_P5_HOST_B;
const failureHost = process.env.NANPANEL_P5_FAILURE_HOST ?? hostA;
const failureAddress = process.env.NANPANEL_P5_FAILURE_ADDRESS ?? '127.0.0.2';
const runtimeAssignments = process.env.NANPANEL_P5_RUNTIME_ASSIGNMENTS ?? 'podman,podman';
const listenPort = Number(process.env.NANPANEL_P5_LISTEN_PORT);
const screenshotPath = process.env.NANPANEL_P5_SCREENSHOT;
if (!password || !runId || !hostA || !hostB || !listenPort || !screenshotPath) {
  throw new Error('Missing live host release acceptance settings');
}

const app = `p5app${runId}`;
const routeHost = `${app}.example.test`;
const pageErrors = [];
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
await mkdir(dirname(screenshotPath), { recursive: true });
page.on('pageerror', error => pageErrors.push(error.message));
page.on('dialog', dialog => dialog.accept());

async function currentPlan(name, id = undefined) {
  return page.evaluate(async ({ planName, planId }) => {
    const response = await fetch('/api/v1/host-application-releases', { credentials: 'include' });
    if (!response.ok) throw new Error(`Core release list returned ${response.status}`);
    const body = await response.json();
    return (body.items ?? []).find(plan => plan.name === planName && (!planId || plan.id === planId)) ?? null;
  }, { planName: name, planId: id });
}

async function waitPlan(name, id, predicate, label, timeout = 360_000) {
  const deadline = Date.now() + timeout;
  let latest;
  while (Date.now() < deadline) {
    latest = await currentPlan(name, id);
    if (latest && predicate(latest)) return latest;
    if (latest && ['UNKNOWN', 'ROLLED_BACK'].includes(latest.state)) {
      throw new Error(`${label}: plan became ${latest.state}: ${JSON.stringify(latest)}`);
    }
    await page.waitForTimeout(1000);
  }
  throw new Error(`${label}: timed out; last plan=${JSON.stringify(latest)}`);
}

function routedRequest(address) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      hostname: address,
      port: listenPort,
      method: 'GET',
      headers: { host: routeHost },
      timeout: 5000,
    }, response => {
      const chunks = [];
      response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({
        status: response.statusCode,
        body: Buffer.concat(chunks).toString().trim(),
      }));
    });
    request.on('timeout', () => request.destroy(new Error('routed request timed out')));
    request.on('error', reject);
    request.end();
  });
}

async function assertTraffic(address, expectedBody, count = 50) {
  const responses = await Promise.all(Array.from({ length: count }, () => routedRequest(address)));
  const distribution = {};
  for (const response of responses) {
    const key = `${response.status} ${response.body}`;
    distribution[key] = (distribution[key] ?? 0) + 1;
  }
  if (Object.keys(distribution).length !== 1 || distribution[`200 ${expectedBody}`] !== count) {
    throw new Error(`Unexpected routed traffic at ${address}: ${JSON.stringify(distribution)}`);
  }
  return distribution;
}

async function fillReleaseForm({ name, image, targets }) {
  const panel = page.locator('.host-release-panel');
  await panel.getByLabel('应用名称').fill(name);
  await panel.getByLabel('当前稳定镜像').fill('localhost/nanpanel-p5-app:v1');
  await panel.getByLabel('候选镜像').fill(image);
  await panel.getByLabel('容器端口').fill('8080');
  await panel.getByLabel('HTTP 主机名').fill(routeHost);
  await panel.getByLabel('Nginx 监听端口').fill(String(listenPort));
  await panel.getByLabel('健康检查路径').fill('/health');
  await panel.getByLabel('每批主机数').fill('1');
  for (const [hostname, address] of targets) {
    const target = panel.locator('.host-release-target').filter({ hasText: hostname });
    await target.locator('input[type="checkbox"]').check();
    await target.getByLabel(`${hostname} Nginx 监听地址`).fill(address);
  }
  await panel.getByLabel('发布原因').fill(`P5 real Core Agent Helper ${runtimeAssignments} acceptance ${runId}`);
  await panel.getByRole('button', { name: '创建主机发布草稿' }).click();
}

try {
  await page.goto(`${baseUrl}/#hosts`);
  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: /进入控制台/ }).click();
  await page.getByRole('heading', { name: '主机资产' }).waitFor();
  if (await page.getByText('Live API').count() === 0) {
    throw new Error('Console did not enter live API mode');
  }
  const panel = page.locator('.host-release-panel');
  await panel.getByRole('heading', { name: '普通主机蓝绿发布' }).waitFor();
  for (const hostname of [hostA, hostB]) {
    const target = panel.locator('.host-release-target').filter({ hasText: hostname });
    await target.waitFor();
    if (await target.locator('input[type="checkbox"]').isDisabled()) {
      throw new Error(`${hostname} is not eligible for host releases`);
    }
  }

  await fillReleaseForm({
    name: app,
    image: 'localhost/nanpanel-p5-app:v2',
    targets: [[hostA, '127.0.0.2'], [hostB, '127.0.0.3']],
  });
  await panel.getByText(new RegExp(`${app} · localhost/nanpanel-p5-app:v1 → localhost/nanpanel-p5-app:v2`)).waitFor();
  const firstPlan = await currentPlan(app);
  if (!firstPlan || firstPlan.state !== 'DRAFT' || firstPlan.targets.length !== 2) {
    throw new Error(`Core did not persist the browser-created draft: ${JSON.stringify(firstPlan)}`);
  }

  const firstRow = panel.locator('.host-release-plan').filter({ hasText: firstPlan.id });
  await firstRow.getByRole('button', { name: '启动', exact: true }).click();
  const batchPause = await waitPlan(app, firstPlan.id,
    plan => plan.state === 'PAUSED' && plan.activeOrdinal === 1,
    'first host batch pause');
  if (batchPause.targets[0].state !== 'SUCCEEDED' || batchPause.targets[1].state !== 'BASELINE_DEPLOY') {
    throw new Error(`The second host was not gated on operator approval: ${JSON.stringify(batchPause)}`);
  }
  const firstHostTraffic = await assertTraffic('127.0.0.2', 'v2', 50);
  await firstRow.getByRole('button', { name: '批准下一批' }).click();
  const completed = await waitPlan(app, firstPlan.id, plan => plan.state === 'SUCCEEDED',
    'second host batch completion');
  if (completed.targets.some(target => target.state !== 'SUCCEEDED')) {
    throw new Error(`Not all live host targets completed: ${JSON.stringify(completed.targets)}`);
  }
  const secondHostTraffic = await assertTraffic('127.0.0.3', 'v2', 50);
  const succeededScreenshot = screenshotPath.replace(/\.png$/, '-succeeded.png');
  await page.screenshot({ path: succeededScreenshot, fullPage: true });

  await firstRow.getByRole('button', { name: '回滚已完成主机' }).click();
  const rolledBack = await waitPlan(app, firstPlan.id,
    plan => plan.state === 'ROLLED_BACK',
    'reverse-order manual rollback');
  if (rolledBack.targets[0].state !== 'ROLLED_BACK' || rolledBack.targets[1].state !== 'ROLLED_BACK') {
    throw new Error(`Both hosts were not rolled back: ${JSON.stringify(rolledBack.targets)}`);
  }
  const rollbackA = await assertTraffic('127.0.0.2', 'v1', 25);
  const rollbackB = await assertTraffic('127.0.0.3', 'v1', 25);

  await fillReleaseForm({
    name: app,
    image: 'localhost/nanpanel-p5-app:v3-broken',
    targets: [[failureHost, failureAddress]],
  });
  const plansAfterFailureDraft = await page.evaluate(async () => {
    const response = await fetch('/api/v1/host-application-releases', { credentials: 'include' });
    return (await response.json()).items;
  });
  const failurePlan = plansAfterFailureDraft.find(plan => plan.name === app && plan.state === 'DRAFT');
  if (!failurePlan) throw new Error('Core did not persist the unhealthy candidate draft');
  const failureRow = panel.locator('.host-release-plan').filter({ hasText: failurePlan.id });
  await failureRow.getByRole('button', { name: '启动', exact: true }).click();
  const failed = await waitPlan(app, failurePlan.id,
    plan => plan.state === 'FAILED',
    'unhealthy candidate automatic compensation');
  if (failed.targets[0].state !== 'FAILED') {
    throw new Error(`Unhealthy candidate did not fail its target: ${JSON.stringify(failed)}`);
  }
  const compensationTraffic = await assertTraffic(failureAddress, 'v1', 50);
  await page.screenshot({ path: screenshotPath, fullPage: true });
  if (pageErrors.length) throw new Error(`Browser errors: ${pageErrors.join('\n')}`);

  console.log(JSON.stringify({
    result: 'PASS',
    flow: 'real-browser -> Core -> mTLS Agent -> Unix-socket Helper -> configured container runtime -> Nginx HTTP',
    runtimeBoundary: `${runtimeAssignments} assigned to two enrolled Agent identities in separate user-namespace Helper sandboxes`,
    releaseId: firstPlan.id,
    releaseState: rolledBack.state,
    orderedBatchStates: batchPause.targets.map(target => target.state),
    completedTargets: completed.targets.map(target => target.state),
    candidateResponses: { hostA: firstHostTraffic, hostB: secondHostTraffic },
    rollbackResponses: { hostA: rollbackA, hostB: rollbackB },
    unhealthyPlanId: failurePlan.id,
    unhealthyPlanState: failed.state,
    compensationResponses: compensationTraffic,
    pageErrors,
    screenshots: [succeededScreenshot, screenshotPath],
  }, null, 2));
} finally {
  await browser.close();
}
