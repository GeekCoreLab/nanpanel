import { chromium, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const baseUrl = process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173';
const parsedBaseUrl = new URL(baseUrl);
if (process.env.NANPANEL_TEST_ALLOW_WRITE !== 'true') {
  throw new Error('Set NANPANEL_TEST_ALLOW_WRITE=true only for an isolated disposable Core database.');
}
if (!['127.0.0.1', 'localhost', '::1'].includes(parsedBaseUrl.hostname)) {
  throw new Error('This write test only accepts a loopback browser URL.');
}

await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } });
  const pageErrors = [];
  const draftResponses = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('response', response => {
    if (new URL(response.url()).pathname === '/api/v1/topology/draft') {
      draftResponses.push({ method: response.request().method(), status: response.status() });
    }
  });

  const username = process.env.NANPANEL_TEST_USERNAME ?? 'admin';
  let password = process.env.NANPANEL_TEST_PASSWORD ?? 'test-only-password';
  async function login() {
    await page.getByLabel('用户名').fill(username);
    await page.getByLabel('密码').fill(password);
    await page.getByRole('button', { name: '进入控制台 →' }).click();
  }

  await page.goto(baseUrl);
  await login();
  await expect(page.locator('.environment')).toHaveText('◉ Live API');
  const passwordChange = page.getByRole('button', { name: '修改密码并退出' });
  if (await passwordChange.count()) {
    const replacement = process.env.NANPANEL_TEST_UPDATED_PASSWORD
      ?? 'e2e-only-topology-password-updated-2026';
    const passwordChanged = page.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/v1/auth/password' && response.status() === 204);
    await page.getByLabel('当前密码').fill(password);
    await page.getByLabel('新密码（15–128 字符）').fill(replacement);
    await page.getByLabel('确认新密码').fill(replacement);
    await passwordChange.click();
    await passwordChanged;
    password = replacement;
    await page.getByRole('button', { name: '进入控制台 →' }).waitFor();
    await login();
  }
  await expect(page.locator('.environment')).toHaveText('◉ Live API');
  await page.locator('nav a[href="#topology"]').click();
  await page.getByLabel('拓扑编辑器').waitFor();

  const initialDraft = await page.evaluate(async () => {
    const response = await fetch('/api/v1/topology/draft', { credentials: 'include' });
    if (!response.ok) throw new Error(`Core draft read failed: HTTP ${response.status}`);
    return response.json();
  });
  if (initialDraft.generation !== 0 || initialDraft.nodes.length || initialDraft.links.length) {
    throw new Error('Refusing to write: the isolated Core topology draft is not empty generation 0.');
  }

  const device = name => page.locator('.react-flow__node-device')
    .filter({ has: page.getByText(name, { exact: true }) });
  for (const [label, item] of [
    ['公网 Gateway', '公网 Gateway 0 入 / 1 出'],
    ['NAT Host', 'NAT Host 1 入 / 1 出'],
    ['网络路由器', '网络路由器 1 入 / 1 出'],
    ['K3s 集群文件夹', 'K3s 集群文件夹 1 入 / 1 出'],
  ]) {
    await page.getByRole('button', { name: '＋ 添加节点' }).click();
    await page.getByRole('menuitem', { name: item }).click();
    await device(label).waitFor();
  }
  await page.getByRole('button', { name: 'fit view' }).click();
  await page.getByLabel('下一跳 IP 地址').fill('10.0.0.1');

  const gateway = device('公网 Gateway');
  const host = device('NAT Host');
  const output = await gateway.locator('[data-handleid="out-1"]').boundingBox();
  const input = await host.locator('[data-handleid="in-1"]').boundingBox();
  if (!output || !input) throw new Error('Could not locate Gateway/Host handles.');
  await page.mouse.move(output.x + output.width / 2, output.y + output.height / 2);
  await page.mouse.down();
  await page.mouse.move(input.x + input.width / 2, input.y + input.height / 2, { steps: 20 });
  await page.mouse.up();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);

  const router = device('网络路由器');
  const hostOutput = await host.locator('[data-handleid="out-1"]').boundingBox();
  const routerInput = await router.locator('[data-handleid="in-1"]').boundingBox();
  if (!hostOutput || !routerInput) throw new Error('Could not locate Host/Router handles.');
  await page.mouse.move(hostOutput.x + hostOutput.width / 2, hostOutput.y + hostOutput.height / 2);
  await page.mouse.down();
  await page.mouse.move(routerInput.x + routerInput.width / 2, routerInput.y + routerInput.height / 2, { steps: 20 });
  await page.mouse.up();
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);

  const overlaps = await page.locator('.react-flow__node-device').evaluateAll(nodes =>
    nodes.flatMap((node, index) => {
      const bounds = node.getBoundingClientRect();
      return nodes.slice(index + 1).filter(other => {
        const candidate = other.getBoundingClientRect();
        return bounds.left < candidate.right && bounds.right > candidate.left
          && bounds.top < candidate.bottom && bounds.bottom > candidate.top;
      }).map(other => `${node.textContent?.trim()} overlaps ${other.textContent?.trim()}`);
    }));
  if (overlaps.length) throw new Error(`Topology cards overlap: ${overlaps.join('; ')}`);

  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('草稿已保存');
  const savedDraft = await page.evaluate(async () => {
    const response = await fetch('/api/v1/topology/draft', { credentials: 'include' });
    if (!response.ok) throw new Error(`Core saved-draft read failed: HTTP ${response.status}`);
    return response.json();
  });
  if (savedDraft.generation !== 1 || savedDraft.nodes.length !== 4 || savedDraft.links.length !== 2) {
    throw new Error('Core did not persist the expected generation, nodes, and link.');
  }
  const savedRouter = savedDraft.nodes.find(node => node.kind === 'ROUTER');
  if (savedRouter?.ipAddress !== '10.0.0.1') throw new Error('Router next-hop IP was not persisted.');

  await page.getByRole('button', { name: '↻ 重新读取' }).click();
  await expect(page.getByRole('status')).toContainText('已读取 Revision 1');
  await expect(device('公网 Gateway')).toBeVisible();
  await expect(device('NAT Host')).toBeVisible();
  await expect(device('网络路由器')).toBeVisible();
  await expect(device('K3s 集群文件夹')).toBeVisible();
  await expect(page.getByLabel('下一跳 IP 地址')).toHaveValue('10.0.0.1');
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  await page.screenshot({
    path: fileURLToPath(new URL('../artifacts/topology-core-save-reload.png', import.meta.url)),
    fullPage: true,
  });

  const writes = draftResponses.filter(response => response.method === 'PUT');
  const reads = draftResponses.filter(response => response.method === 'GET');
  if (writes.length !== 1 || writes[0].status !== 200 || reads.length < 3
      || reads.some(response => response.status !== 200)) {
    throw new Error(`Unexpected real Core topology API traffic: ${JSON.stringify(draftResponses)}`);
  }
  if (pageErrors.length) throw new Error(pageErrors.join('\n'));
  console.log(JSON.stringify({
    result: 'PASS', apiMode: 'Live API', initialGeneration: initialDraft.generation,
    savedGeneration: savedDraft.generation, nodes: savedDraft.nodes.length,
    links: savedDraft.links.length, topologyApi: draftResponses, overlaps: 0,
    screenshot: 'web/artifacts/topology-core-save-reload.png',
  }, null, 2));
} finally {
  await browser.close();
}
