import { chromium, expect } from '@playwright/test';

const expectedRoute = process.env.NANPANEL_P0_EXPECTED_ROUTE ?? 'http://frps:18081/';
const expectedBody = process.env.NANPANEL_P0_EXPECTED_BODY ?? 'nanpanel-p0-http-service';
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(process.env.NANPANEL_P0_TOPOLOGY_URL ?? 'http://127.0.0.1:4181/p0-topology.html');
  await expect(page.getByRole('heading', { name: '拓扑到实际路由 P0 实验' })).toBeVisible();
  await page.getByLabel('拓扑编辑器').scrollIntoViewIfNeeded();
  const device = name => page.locator('.react-flow__node-device').filter({ has: page.getByText(name, { exact: true }) });
  const gateway = device('公网 Gateway');
  const host = device('NAT Host');
  const service = device('K3s Service');
  await expect(gateway).toBeVisible();
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await page.getByRole('button', { name: 'fit view' }).click();
  await page.waitForTimeout(400);
  async function connect(from, to) {
    const source = await from.boundingBox();
    const target = await to.boundingBox();
    if (!source || !target) throw new Error('React Flow port is not visible');
    await page.mouse.move(source.x + source.width / 2, source.y + source.height / 2);
    await page.mouse.down();
    await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2, { steps: 20 });
    await page.mouse.up();
  }
  await connect(gateway.locator('[data-handleid="out-1"]'), host.locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  await page.waitForTimeout(400);
  await connect(host.locator('[data-handleid="out-1"]'), service.locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('草稿已保存');
  const saved = await page.evaluate(async () => (await fetch('/api/v1/topology/draft')).json());
  if (saved.links.length !== 2) throw new Error('saved topology does not contain the two route links');
  await page.getByRole('button', { name: '应用到 FRP 实际路由' }).click();
  const evidence = page.locator('.alert[role="status"]');
  await expect(evidence).toContainText('实际路由已验证');
  await expect(evidence).toContainText(expectedRoute);
  await expect(evidence).toContainText('HTTP 200');
  await expect(evidence.locator('pre')).toContainText('[[proxies]]');
  const actual = await page.evaluate(async () => (await fetch('/api/v1/topology/evidence')).json());
  if (actual.status !== 'VERIFIED' || actual.httpStatus !== 200 || actual.responseBody !== expectedBody) {
    throw new Error(`unexpected route evidence: ${JSON.stringify(actual)}`);
  }
  if (!/^[0-9a-f]{64}$/.test(actual.configSha256)) throw new Error('route evidence is missing the active config hash');
  if (errors.length) throw new Error(errors.join('\n'));
  console.log(`PASS: React Flow saved Gateway → NAT Host → Service and applied an FRP route; HTTP ${actual.httpStatus}, body=${actual.responseBody}, sha256=${actual.configSha256}`);
} finally {
  await browser.close();
}
