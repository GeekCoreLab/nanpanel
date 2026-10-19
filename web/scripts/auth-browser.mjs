import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';

const password = process.env.NANPANEL_TEST_PASSWORD;
if (!password) throw new Error('Set NANPANEL_TEST_PASSWORD to an existing account password; first change the bootstrap password.');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(process.env.NANPANEL_TEST_URL ?? 'http://127.0.0.1:5173');
  await page.getByLabel('用户名', { exact: true }).fill(process.env.NANPANEL_TEST_USERNAME ?? 'admin');
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '进入控制台 →' }).click();
  await page.getByRole('heading', { name: '基础设施总览' }).waitFor();
  await page.reload();
  await page.getByRole('heading', { name: '基础设施总览' }).waitFor();
  const cookie = (await page.context().cookies()).find(item => item.name === 'nanpanel_session');
  assert(cookie?.httpOnly);
  assert.equal(cookie.sameSite, 'Lax');
  await page.getByRole('link', { name: '◎ 账号与会话' }).click();
  await page.getByText('当前浏览器', { exact: true }).waitFor();
  await page.getByRole('button', { name: '撤销当前会话并退出' }).click();
  await page.getByRole('heading', { name: '登录你的工作空间' }).waitFor();
  await page.reload();
  await page.getByRole('heading', { name: '登录你的工作空间' }).waitFor();
  assert.deepEqual(errors, []);
  console.log('Real backend browser login, refresh, cookie and session revocation passed.');
} finally {
  await browser.close();
}
