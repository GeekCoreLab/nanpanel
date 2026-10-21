import { chromium, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
await mkdir(new URL('../artifacts/', import.meta.url), { recursive: true });
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173');
  await page.getByRole('button', { name: '进入控制台 →' }).click();
  await page.locator('nav a[href="#topology"]').click();
  const device = name => page.locator('.react-flow__node-device').filter({ has: page.getByText(name, { exact: true }) });
  const gateway = device('公网 Gateway'), host = device('NAT Host'), service = device('K3s Service');
  await gateway.waitFor();
  await expect(page.getByRole('heading', { name: 'Istio 服务网络', exact: true })).toBeVisible();
  await expect(page.getByText('实时 Istio 数据尚未连接')).toBeVisible();
  await expect(page.locator('.service-network-item')).toHaveCount(0);
  await page.getByLabel('拓扑编辑器').scrollIntoViewIfNeeded();
  await expect(gateway.locator('[data-handleid="out-1"]')).toHaveCSS('background-color', 'rgb(46, 175, 131)');
  await expect(host.locator('[data-handleid="in-1"]')).toHaveCSS('background-color', 'rgb(74, 134, 237)');
  async function drag(from, to) {
    const a = await from.boundingBox(), b = await to.boundingBox();
    if (!a || !b) throw new Error('Missing handle bounds');
    await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
    await page.mouse.down();
    await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 20 });
    await page.mouse.up();
  }
  await drag(gateway.locator('[data-handleid="out-1"]'), host.locator('[data-handleid="out-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await drag(gateway.locator('[data-handleid="out-1"]'), host.locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  await drag(host.locator('[data-handleid="out-1"]'), service.locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  // A used output cannot acquire a second edge.
  await drag(gateway.locator('[data-handleid="out-1"]'), service.locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  const pane = page.locator('.react-flow__pane');
  await pane.click({ button: 'right', position: { x: 100, y: 70 } });
  await page.getByRole('menuitem', { name: '虚拟交换机 4 入 / 4 出' }).click();
  await expect(device('虚拟交换机').locator('[data-handleid^="in-"]')).toHaveCount(4);
  for (const [name, count] of [['多合一转发器', '3 入 / 1 出'], ['一分多转发器', '1 入 / 3 出'], ['多进多出转发器', '3 入 / 3 出']]) {
    await page.getByRole('button', { name: '＋ 添加节点' }).click();
    await page.getByRole('menuitem', { name: `${name} ${count}` }).click();
    await device(name).waitFor();
  }
  // Fit nodes before targeting a node inserted off-screen.
  await page.getByRole('button', { name: 'fit view' }).click();
  const switchNode = device('虚拟交换机');
  await switchNode.click({ button: 'right' });
  await page.getByRole('menuitem', { name: '增加入口', exact: true }).click();
  await expect(switchNode.locator('[data-handleid^="in-"]')).toHaveCount(5);
  await switchNode.click({ button: 'right' });
  await page.getByRole('menuitem', { name: '减少入口', exact: true }).click();
  await expect(switchNode.locator('[data-handleid^="in-"]')).toHaveCount(4);
  await switchNode.click({ button: 'right' });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('menu')).toHaveCount(0);
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('草稿已保存');
  await page.locator('nav a[href="#overview"]').click();
  await page.locator('nav a[href="#topology"]').click();
  await expect(page.locator('.react-flow__node-device')).toHaveCount(7);
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  await page.getByLabel('拓扑编辑器').scrollIntoViewIfNeeded();
  await page.screenshot({ path: new URL('../artifacts/topology.png', import.meta.url).pathname, fullPage: true });
  // Remove an edge via its actual right-click menu.
  await page.locator('.react-flow__edge-interaction').first().click({ button: 'right', force: true });
  await page.getByRole('menuitem', { name: '删除连接', exact: true }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  // Deleting the middle node also removes its remaining attached edge.
  await device('NAT Host').click({ button: 'right' });
  await page.getByRole('menuitem', { name: '删除节点及关联连接' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await expect(page.locator('.react-flow__node-device')).toHaveCount(6);
  // Reload explicitly discards local edits and restores the saved graph.
  await page.getByRole('button', { name: '重新读取（丢弃未保存）' }).click();
  await expect(page.locator('.react-flow__node-device')).toHaveCount(7);
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '＋ 添加节点' }).click();
  await expect(page.getByRole('menu')).toBeVisible();
  const bounds = await page.getByRole('menu').boundingBox();
  if (bounds.x < 0 || bounds.x + bounds.width > 390 || bounds.y < 0 || bounds.y + bounds.height > 844) throw new Error('Mobile context menu escaped viewport');
  await page.keyboard.press('Escape');
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)) throw new Error('Mobile overflow');
  await page.setViewportSize({ width: 1600, height: 1200 });
  const gatewayPicker = gateway.getByRole('combobox', { name: '搜索 Gateway 主机资产' });
  await gatewayPicker.focus();
  const gatewayOptions = page.getByRole('listbox', { name: '可绑定的主机资产' });
  await expect(gateway.getByText('Mock 演示数据')).toBeVisible();
  await expect(gatewayOptions.locator('.gateway-asset-option')).toHaveCount(6);
  const listBounds = await gatewayOptions.boundingBox();
  if (!listBounds) throw new Error('Gateway asset list is not visible');
  const scrollPoint = await gatewayOptions.evaluate(element => {
    const rect = element.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    for (let y = rect.top + 2; y < rect.bottom - 2; y += 4) {
      const target = document.elementFromPoint(x, y);
      if (target && (target === element || element.contains(target))) return { x, y };
    }
    return null;
  });
  if (!scrollPoint) throw new Error('Gateway asset options are clipped outside the visible topology area');
  const viewportBeforeListScroll = await page.locator('.react-flow__viewport').getAttribute('style');
  await page.mouse.move(scrollPoint.x, scrollPoint.y);
  await page.mouse.wheel(0, 120);
  const scrollMetrics = await gatewayOptions.evaluate(element => ({
    scrollTop: element.scrollTop,
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
  }));
  if (scrollMetrics.scrollTop <= 0) throw new Error('Gateway asset options did not scroll inside the dropdown');
  const viewportAfterListScroll = await page.locator('.react-flow__viewport').getAttribute('style');
  if (viewportBeforeListScroll !== viewportAfterListScroll) throw new Error('Scrolling Gateway assets zoomed the topology canvas');
  await gatewayPicker.fill('gateway-sg');
  await page.getByRole('option', { name: /gateway-sg-01/ }).click();
  await expect(gateway.locator('.device-status')).toContainText('gateway-sg-01');
  await page.getByRole('button', { name: '＋ 添加主机资产' }).click();
  await page.locator('.topology-asset-option').filter({ hasText: 'homelab-node-01' }).click();
  await expect(device('homelab-node-01')).toBeVisible();
  await expect(page.locator('.host-route-row').filter({ hasText: 'gateway-sg-01 → homelab-node-01' })).toBeVisible();
  const observedRouter = device('自动发现 Router 192.0.2.1');
  await expect(observedRouter).toBeVisible();
  await expect(observedRouter.locator('.router-address-control code')).toHaveText('192.0.2.1');
  await expect(observedRouter.locator('.router-address-control input')).toHaveCount(0);
  await expect(page.locator('.host-route-row').filter({ hasText: 'homelab-node-01 → 自动发现 Router 192.0.2.1' })).toBeVisible();
  await expect(page.locator('.react-flow__edge')).toHaveCount(4);
  await expect(page.locator('.link-state-observed')).toHaveCount(2);
  await page.getByRole('button', { name: '＋ 添加节点' }).click();
  await page.getByRole('menuitem', { name: 'K3s 集群文件夹 1 入 / 1 出' }).click();
  const cluster = device('K3s 集群文件夹');
  await cluster.dblclick();
  await expect(page.getByRole('heading', { name: 'K3s 集群文件夹', exact: true })).toBeVisible();
  const namespace = page.locator('.kubernetes-namespace-heading').filter({ hasText: 'nanpanel-mesh' });
  await namespace.click();
  await expect(page.getByText('catalog.example.test/ → catalog-api:80')).toBeVisible();
  const pod = page.locator('.pod-open').filter({ hasText: 'catalog-api-6c847d7d4b-kd2sh' });
  await pod.click();
  await expect(page.getByText('Pod 内视图')).toBeVisible();
  await expect(page.getByText('ghcr.io/example/catalog-api:1.4.2')).toBeVisible();
  await page.getByRole('button', { name: '＋ 添加节点' }).click();
  await page.getByRole('menuitem', { name: 'K3s Service 1 入 / 0 出' }).click();
  await page.getByRole('button', { name: '＋ 添加节点' }).click();
  await page.getByRole('menuitem', { name: '公网 Gateway 0 入 / 1 出' }).click();
  await page.getByRole('button', { name: 'fit view' }).click();
  await page.waitForTimeout(300);
  await drag(device('公网 Gateway').locator('[data-handleid="out-1"]'), device('K3s Service').locator('[data-handleid="in-1"]'));
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('草稿已保存');
  await page.getByRole('button', { name: '↻ 重新读取' }).click();
  await cluster.dblclick();
  await expect(device('公网 Gateway')).toBeVisible();
  await expect(device('K3s Service')).toBeVisible();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  if (errors.length) throw new Error(errors.join('\n'));
  console.log('PASS: base topology editing, searchable Gateway host asset binding, automatic route next-hop matching, nested folder editing and persistence, Namespace/Ingress/Service/Pod/container drill-down, mobile menu; no page errors');
} finally { await browser.close(); }
