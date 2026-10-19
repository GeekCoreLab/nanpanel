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
  if (errors.length) throw new Error(errors.join('\n'));
  console.log('PASS: port colors, strict connection directions, occupied-port rejection, context-menu presets, port adjustment, draft round-trip, edge/node deletion, mobile menu; no page errors');
} finally { await browser.close(); }
