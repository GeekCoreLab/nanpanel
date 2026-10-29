import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compileApplicationRoutePlan } from '../src/lib/application-routing.ts';

const names = {
  'svc-orders': 'orders', 'svc-v1': 'orders-v1', 'svc-v2': 'orders-v2',
  'dep-orders': 'orders-deployment', 'svc-inventory': 'inventory',
};
const resourceName = (id) => names[id];

const entry = (id, serviceId, lbId, port = 80) => ({
  id, type: 'ROUTE_ENTRY', sourceId: serviceId, sourceHandle: `route-output-${port}`,
  targetId: lbId, targetHandle: 'route-input', servicePort: port,
});
const backend = (id, lbId, outPort, serviceId, port = 80) => ({
  id, type: 'ROUTE_BACKEND', sourceId: lbId, sourceHandle: `route-${outPort}`,
  targetId: serviceId, targetHandle: `backend-input-${port}`, servicePort: port,
});
const call = (id, depId, serviceId, port = 80) => ({
  id, type: 'CALL', sourceId: depId, sourceHandle: 'call-output',
  targetId: serviceId, targetHandle: `call-input-${port}`, servicePort: port,
});
const lb = (id, weights) => ({ id, loadBalancerConfiguration: { outputWeights: weights } });

test('compiles a weighted route with normalized percentages and callers', () => {
  const plan = compileApplicationRoutePlan(
    [
      entry('e1', 'svc-orders', 'lb1'),
      backend('b1', 'lb1', 'out-1', 'svc-v1'),
      backend('b2', 'lb1', 'out-2', 'svc-v2'),
      call('c1', 'dep-orders', 'svc-orders'),
    ],
    [lb('lb1', [{ outputPort: 'out-1', weight: 80 }, { outputPort: 'out-2', weight: 20 }])],
    resourceName,
  );
  assert.equal(plan.length, 1);
  const [row] = plan;
  assert.equal(row.serviceName, 'orders');
  assert.equal(row.servicePort, 80);
  assert.equal(row.totalWeight, 100);
  assert.deepEqual(row.backends, [
    { name: 'orders-v1', weight: 80, share: 80 },
    { name: 'orders-v2', weight: 20, share: 20 },
  ]);
  assert.deepEqual(row.callers, ['orders-deployment']);
});

test('normalizes non-even weights and rounds shares', () => {
  const plan = compileApplicationRoutePlan(
    [entry('e1', 'svc-orders', 'lb1'), backend('b1', 'lb1', 'out-1', 'svc-v1'),
      backend('b2', 'lb1', 'out-2', 'svc-v2')],
    [lb('lb1', [{ outputPort: 'out-1', weight: 1 }, { outputPort: 'out-2', weight: 2 }])],
    resourceName,
  );
  assert.deepEqual(plan[0].backends.map(b => b.share), [33, 67]);
});

test('zero total weight yields zero shares instead of dividing by zero', () => {
  const plan = compileApplicationRoutePlan(
    [entry('e1', 'svc-orders', 'lb1'), backend('b1', 'lb1', 'out-1', 'svc-v1')],
    [lb('lb1', [{ outputPort: 'out-1', weight: 0 }])],
    resourceName,
  );
  assert.equal(plan[0].totalWeight, 0);
  assert.deepEqual(plan[0].backends, [{ name: 'orders-v1', weight: 0, share: 0 }]);
});

test('a route entry without a backend is reported as empty', () => {
  const plan = compileApplicationRoutePlan(
    [entry('e1', 'svc-orders', 'lb1')],
    [lb('lb1', [])],
    resourceName,
  );
  assert.equal(plan.length, 1);
  assert.deepEqual(plan[0].backends, []);
  assert.equal(plan[0].totalWeight, 0);
});

test('missing balancer or weights default to full weight and generic names', () => {
  const plan = compileApplicationRoutePlan(
    [entry('e1', 'unknown-svc', 'missing-lb'), backend('b1', 'missing-lb', 'out-9', 'unknown-backend')],
    [],
    resourceName,
  );
  assert.equal(plan[0].serviceName, 'Service');
  assert.equal(plan[0].backends[0].name, 'Service');
  assert.equal(plan[0].backends[0].weight, 100);
  assert.equal(plan[0].backends[0].share, 100);
});

test('callers are matched by destination service and port only', () => {
  const plan = compileApplicationRoutePlan(
    [
      entry('e1', 'svc-orders', 'lb1', 80),
      call('c1', 'dep-orders', 'svc-orders', 80),
      call('c2', 'dep-orders', 'svc-orders', 8080),
      call('c3', 'dep-orders', 'svc-inventory', 80),
    ],
    [lb('lb1', [])],
    resourceName,
  );
  assert.equal(plan.length, 1);
  assert.deepEqual(plan[0].callers, ['orders-deployment']);
});

test('backends and calls without a route entry produce no plan row', () => {
  const plan = compileApplicationRoutePlan(
    [call('c1', 'dep-orders', 'svc-orders'), backend('b1', 'lb1', 'out-1', 'svc-v1')],
    [],
    resourceName,
  );
  assert.equal(plan.length, 0);
});
import { validateRoutePlan } from '../src/lib/application-routing.ts';

const planRow = (id, serviceName, servicePort, backends, totalWeight) => ({
  id, serviceName, servicePort, totalWeight, backends, callers: [],
});

test('a consistent plan has no issues', () => {
  const plan = [
    planRow('e1', 'orders', 80, [{ name: 'v1', weight: 100, share: 100 }], 100),
    planRow('e2', 'inventory', 80, [{ name: 'inv', weight: 100, share: 100 }], 100),
  ];
  assert.deepEqual(validateRoutePlan(plan), []);
});

test('the same service and port drawn twice is reported as a duplicate entry', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [{ name: 'v1', weight: 100, share: 100 }], 100),
    planRow('e2', 'orders', 80, [{ name: 'v2', weight: 100, share: 100 }], 100),
  ]);
  assert.equal(issues.length, 1);
  assert.equal(issues[0].code, 'DUPLICATE_ENTRY');
  assert.deepEqual(issues[0].routeIds, ['e1', 'e2']);
});

test('the same service on different ports is not a duplicate', () => {
  assert.deepEqual(validateRoutePlan([
    planRow('e1', 'orders', 80, [{ name: 'v1', weight: 100, share: 100 }], 100),
    planRow('e2', 'orders', 8080, [{ name: 'v1', weight: 100, share: 100 }], 100),
  ]), []);
});

test('an entry without backends and one with only zero weights are flagged', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [], 0),
    planRow('e2', 'inventory', 80, [{ name: 'inv', weight: 0, share: 0 }], 0),
  ]);
  assert.deepEqual(issues.map(issue => issue.code), ['NO_BACKENDS', 'ZERO_WEIGHT']);
  assert.equal(issues[0].message, 'orders:80 未连接后端 Service');
  assert.equal(issues[1].message, 'inventory:80 的后端权重全为 0');
});

test('no backends is reported instead of zero weight for the same row', () => {
  const issues = validateRoutePlan([planRow('e1', 'orders', 80, [], 0)]);
  assert.deepEqual(issues.map(issue => issue.code), ['NO_BACKENDS']);
});
