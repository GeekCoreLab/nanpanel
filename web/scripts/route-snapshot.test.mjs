import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildRouteSnapshot, describeRouteChange, diffRouteSnapshots } from '../src/lib/route-snapshot.ts';

const route = (id, service, port, backends) => ({
  id, balancerId: `lb-${id}`, serviceId: `svc-${id}`, serviceName: service, servicePort: port,
  totalWeight: 100, callers: [],
  backends: backends.map(([name, share, backendPort]) => ({
    name, share, weight: share, id: `svc-${name}`, linkId: `l-${name}`, port: backendPort,
  })),
});

test('a snapshot is stable regardless of input order', () => {
  const a = buildRouteSnapshot([route('b', 'shop', 80, [['api', 50, 80]]), route('a', 'web', 81, [])]);
  const b = buildRouteSnapshot([route('a', 'web', 81, []), route('b', 'shop', 80, [['api', 50, 80]])]);
  assert.deepEqual(a, b);
  assert.deepEqual(a.routes.map(item => item.id), ['a', 'b']);
});

test('an unchanged plan produces no changes', () => {
  const plan = [route('a', 'web', 81, [['api', 50, 8080], ['web', 50, 8081]])];
  assert.deepEqual(diffRouteSnapshots(buildRouteSnapshot(plan), buildRouteSnapshot(plan)), []);
});

test('added and removed routes are both reported', () => {
  const before = buildRouteSnapshot([route('a', 'web', 81, [])]);
  const after = buildRouteSnapshot([route('b', 'shop', 80, [])]);
  const changes = diffRouteSnapshots(before, after);
  assert.deepEqual(changes.map(change => [change.kind, change.label]), [
    ['ADDED', 'shop:80'],
    ['REMOVED', 'web:81'],
  ]);
});

test('a re-weighted backend reports both numbers', () => {
  const before = buildRouteSnapshot([route('a', 'web', 81, [['api', 90, 8080], ['v2', 10, 8081]])]);
  const after = buildRouteSnapshot([route('a', 'web', 81, [['api', 50, 8080], ['v2', 50, 8081]])]);
  const changes = diffRouteSnapshots(before, after);
  assert.deepEqual(changes.map(change => [change.backend, change.from, change.to]),
    [['api', 90, 50], ['v2', 10, 50]]);
  assert.equal(describeRouteChange(changes[0]), 'web:81 后端 api 权重 90% → 50%');
});

test('backend churn and entry-port changes are separate statements', () => {
  const before = buildRouteSnapshot([route('a', 'web', 81, [['api', 100, 8080]])]);
  const after = buildRouteSnapshot([route('a', 'web', 82, [['api', 50, 8080], ['v2', 50, 8081]])]);
  const changes = diffRouteSnapshots(before, after);
  // Backends are compared in name order, so the surviving backend's new weight comes first.
  assert.deepEqual(changes.map(change => change.kind), ['ENTRY_PORT', 'WEIGHT', 'BACKEND_ADDED']);
  assert.equal(describeRouteChange(changes[0]), 'web:82 入口端口 81 → 82');
});

test('a backend that only changes its port is not reported as churn', () => {
  const before = buildRouteSnapshot([route('a', 'web', 81, [['api', 100, 8080]])]);
  const after = buildRouteSnapshot([route('a', 'web', 81, [['api', 100, 9090]])]);
  assert.deepEqual(diffRouteSnapshots(before, after), []);
});
