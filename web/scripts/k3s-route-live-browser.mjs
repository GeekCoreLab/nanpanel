import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { chromium, expect } from '@playwright/test';

const repoRoot = new URL('../../', import.meta.url);
const envText = await readFile(new URL('.env', repoRoot), 'utf8');
const env = Object.fromEntries(envText.split(/\r?\n/)
  .filter(line => /^[A-Za-z_][A-Za-z0-9_]*=/.test(line))
  .map(line => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator), line.slice(separator + 1)];
  }));
const coreOrigin = env.NANPANEL_P2_CORE_URL;
const webOrigin = process.env.NANPANEL_ROUTE_WEB_URL || coreOrigin;
const apiKey = env.NANPANEL_P2_API_KEY;
if (!coreOrigin?.startsWith('https://') || !apiKey) {
  throw new Error('The ignored .env must provide the P2 HTTPS origin and read-only API key.');
}

const sql = `
SELECT jsonb_build_object(
  'host', jsonb_build_object(
    'id', h.id, 'hostname', h.hostname, 'os', h.os, 'arch', h.arch,
    'generation', h.generation,
    'status', CASE WHEN a.last_seen > now() - interval '5 minutes'
      THEN 'ONLINE' ELSE 'UNKNOWN' END,
    'capabilities', h.capabilities, 'agentId', a.id,
    'lastSeenAt', a.last_seen, 'createdAt', h.created_at
  ),
  'inventory', jsonb_build_object(
    'interfaces', COALESCE((
      SELECT jsonb_agg(iface)
      FROM jsonb_array_elements(i.interfaces::jsonb) iface
      WHERE iface->>'name' = 'enp6s18'
    ), '[]'::jsonb),
    'routes', COALESCE((
      SELECT jsonb_agg(route)
      FROM jsonb_array_elements(i.routes::jsonb) route
      WHERE route->>'destination' = 'default'
        AND route->>'gateway' = '10.0.0.1'
    ), '[]'::jsonb),
    'observedAt', i.observed_at,
    'overlay', NULL, 'overlayPeers', '[]'::jsonb,
    'overlayPeersObservedAt', NULL
  )
)
FROM host h
JOIN host_network_inventory i ON i.host_id = h.id
LEFT JOIN LATERAL (
  SELECT id, last_seen FROM agent
  WHERE host_id = h.id ORDER BY last_seen DESC NULLS LAST, id LIMIT 1
) a ON TRUE
WHERE h.hostname = 'k3s-1';
`;
const targetData = JSON.parse(execFileSync('ssh', [
  'k3s-1',
  'sudo k3s kubectl -n nanpanel exec -i nanpanel-postgres-0 -- psql -U nanpanel -d nanpanel -qAt',
], { input: sql, encoding: 'utf8' }).trim());
const { host, inventory } = targetData;
if (!host?.id || !inventory?.observedAt
  || !inventory.routes.some(route => route.gateway === '10.0.0.1')) {
  throw new Error('The live k3s-1 inventory did not contain the expected default route.');
}

const hostNode = {
  id: '00000000-0000-4000-8000-000000000001',
  kind: 'HOST',
  name: host.hostname,
  position: { x: 120, y: 120 },
  inputCount: 1,
  outputCount: 1,
  ports: [
    { id: 'in-1', direction: 'INPUT', layer: 'L4', protocols: ['TCP', 'UDP'] },
    { id: 'out-1', direction: 'OUTPUT', layer: 'L4', protocols: ['TCP', 'UDP'] },
  ],
  parentId: null,
  assetId: host.id,
  clusterId: null,
  ipAddress: null,
};
const syntheticSession = {
  user: {
    id: 'route-live-test', username: 'admin', displayName: 'Route UI Test',
    roles: ['ADMIN'], status: 'ACTIVE', mustChangePassword: false,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), lastLoginAt: null,
  },
  session: {
    id: 'route-live-test-session', userId: 'route-live-test', current: true,
    createdAt: new Date().toISOString(), authenticatedAt: new Date().toISOString(),
    lastSeenAt: new Date().toISOString(), idleExpiresAt: new Date(Date.now() + 1800000).toISOString(),
    absoluteExpiresAt: new Date(Date.now() + 43200000).toISOString(),
    clientIp: null, userAgent: 'P4 live route browser acceptance',
  },
  permissions: ['*'], csrfToken: 'test-only-csrf-token',
};

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1600, height: 1100 } });
  const page = await context.newPage();
  const pageErrors = [];
  let topologyWrites = 0;
  page.on('pageerror', error => { pageErrors.push(error.message); console.log(error.stack); });
  page.on('console', message => {
    if (['warning', 'error'].includes(message.type())) console.log(`${message.type()}: ${message.text()}`);
  });
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (pathname === '/api/v1/auth/me') {
      await route.fulfill({ json: syntheticSession });
      return;
    }
    if (pathname === '/api/v1/topology/draft' && request.method() === 'GET') {
      await route.fulfill({
        status: 200,
        headers: { etag: '"test-only-route-projection"' },
        json: { revisionId: 'test-only-route-projection', generation: 0, nodes: [hostNode], links: [] },
      });
      return;
    }
    if (pathname === `/api/v1/hosts/${host.id}`) {
      await route.fulfill({ json: host });
      return;
    }
    if (pathname === `/api/v1/hosts/${host.id}/network-inventory`) {
      await route.fulfill({ json: inventory });
      return;
    }
    if (pathname === '/api/v1/kubernetes/clusters') {
      await route.fulfill({ json: { items: [] } });
      return;
    }
    if (pathname === '/api/v1/topology/draft' && request.method() === 'PUT') {
      topologyWrites++;
      await route.fulfill({ status: 409, json: { code: 'TEST_WRITE_BLOCKED', detail: 'Topology writes are disabled in this test.' } });
      return;
    }
    if (pathname === '/api/v1/kubernetes/service-inventory'
      || pathname === '/api/v1/kubernetes/service-probes') {
      const liveResponse = await context.request.get(new URL(pathname, coreOrigin).toString(), {
        headers: { authorization: `Bearer ${apiKey}` },
      });
      await route.fulfill({ status: liveResponse.status(), contentType: 'application/json', body: await liveResponse.body() });
      return;
    }
    await route.fulfill({ status: 404, json: { code: 'TEST_ROUTE_NOT_CONFIGURED' } });
  });

  await page.goto(`${webOrigin}/#topology`, { waitUntil: 'networkidle' });
  const hostCard = page.locator('.react-flow__node-device')
    .filter({ has: page.getByText('k3s-1', { exact: true }) });
  await expect(hostCard).toBeVisible();
  await page.getByRole('button', { name: 'fit view' }).click();
  const routerCard = page.locator('.react-flow__node-device')
    .filter({ has: page.getByText('自动发现 Router 10.0.0.1', { exact: true }) });
  await expect(routerCard).toBeVisible();
  await expect(routerCard.getByText('Agent 自动发现 · 只读观测')).toBeVisible();
  await expect(page.locator('.host-route-row').filter({ hasText: 'k3s-1 → 自动发现 Router 10.0.0.1（Router）' })).toBeVisible();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
  await expect(page.getByText('LIVE', { exact: true })).toBeVisible();
  await expect(page.getByText('3/3', { exact: true })).toBeVisible();
  expect(await page.locator('.service-network-item').count()).toBeGreaterThan(0);
  await page.screenshot({ path: '/tmp/nanpanel-k3s-route-live-ui.png', fullPage: true });
  if (topologyWrites !== 0) throw new Error('The live route acceptance test attempted to save topology.');
  if (pageErrors.length) throw new Error(`Browser errors: ${pageErrors.join('; ')}`);
  console.log(JSON.stringify({
    result: 'PASS',
    host: host.hostname,
    routeGateway: '10.0.0.1',
    routeObservedAt: inventory.observedAt,
    routerNode: 'ephemeral read-only UI projection',
    topologyWrites,
    screenshot: '/tmp/nanpanel-k3s-route-live-ui.png',
  }, null, 2));
} finally {
  await browser.close();
}
