import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareRoutePlan, compileApplicationRoutePlan, planRouteDraft, validateRoutePlan } from '../src/lib/application-routing.ts';

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

const planRow = (id, serviceName, servicePort, backends, totalWeight, balancerId) => ({
  id, balancerId: balancerId ?? `lb-${id}`, serviceName, servicePort, totalWeight,
  backends, callers: [],
});
const weighted = (name, weight) => ({ name, weight, share: weight });

test('a consistent plan has no issues', () => {
  const plan = [
    planRow('e1', 'orders', 80, [weighted('v1', 90), weighted('v2', 10)], 100),
    planRow('e2', 'inventory', 80, [weighted('inv', 100), weighted('inv2', 0)], 100),
  ];
  assert.deepEqual(validateRoutePlan(plan), []);
});

test('the same service and port drawn twice is reported as a duplicate entry', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [weighted('v1', 100), weighted('v2', 0)], 100),
    planRow('e2', 'orders', 80, [weighted('v3', 100), weighted('v4', 0)], 100),
  ]);
  const duplicate = issues.find(issue => issue.code === 'DUPLICATE_ENTRY');
  assert.ok(duplicate);
  assert.deepEqual(duplicate.routeIds, ['e1', 'e2']);
  assert.equal(duplicate.message, 'orders:80 被 2 个路由入口重复定义');
});

test('the same service on different ports is not a duplicate', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [weighted('v1', 100), weighted('v2', 0)], 100),
    planRow('e2', 'orders', 8080, [weighted('v1', 100), weighted('v2', 0)], 100),
  ]);
  assert.deepEqual(issues, []);
});

test('two services driving one balancer are reported as a shared balancer', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [weighted('v1', 100), weighted('v2', 0)], 100, 'lb-shared'),
    planRow('e2', 'inventory', 80, [weighted('i1', 100), weighted('i2', 0)], 100, 'lb-shared'),
  ]);
  const shared = issues.find(issue => issue.code === 'SHARED_BALANCER');
  assert.ok(shared);
  assert.deepEqual(shared.routeIds, ['e1', 'e2']);
  assert.equal(shared.message, '同一个流量分配器被 orders:80、inventory:80 同时驱动');
});

test('fewer than two backends is reported, matching the backend requirement', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [], 0),
    planRow('e2', 'inventory', 80, [weighted('inv', 100)], 100),
  ]);
  assert.deepEqual(issues.map(issue => issue.code), ['ONE_BACKEND', 'ONE_BACKEND']);
  assert.equal(issues[0].message, 'orders:80 未连接后端 Service（需要至少两个）');
  assert.equal(issues[1].message, 'inventory:80 只连接了 1 个后端 Service（需要至少两个）');
});

test('all-zero backends are reported once the backend count is satisfied', () => {
  const issues = validateRoutePlan([
    planRow('e1', 'orders', 80, [weighted('v1', 0), weighted('v2', 0)], 0),
  ]);
  assert.deepEqual(issues.map(issue => issue.code), ['ZERO_WEIGHT']);
  assert.equal(issues[0].message, 'orders:80 的后端权重全为 0');
});

// ---- 画布与已下发路由的对账 -------------------------------------------------
const planOf = (service, port, backends) => ({
  id: `plan-${service}`, balancerId: `lb-${service}`, serviceName: service, servicePort: port,
  totalWeight: backends.reduce((sum, [, share]) => sum + share, 0),
  backends: backends.map(([name, share]) => ({ name, weight: share, share })),
  callers: [],
});
const appliedOf = (name, service, port, backends) => ({
  name, service, port,
  backends: backends.map(([svc, weight]) => ({ service: svc, port: 8080, weight })),
});

test('a plan that matches the applied snapshot reports MATCH', () => {
  const rows = compareRoutePlan(
    [planOf('shop', 8080, [['api', 90], ['web', 10]])],
    [appliedOf('np-route-1', 'shop', 8080, [['api', 90], ['web', 10]])],
  );
  assert.equal(rows.length, 1);
  assert.equal(rows[0].status, 'MATCH');
  assert.equal(rows[0].appliedName, 'np-route-1');
});

test('changed weights are reported as a drift with both sides spelled out', () => {
  const rows = compareRoutePlan(
    [planOf('shop', 8080, [['api', 90], ['web', 10]])],
    [appliedOf('np-route-1', 'shop', 8080, [['api', 50], ['web', 50]])],
  );
  assert.equal(rows[0].status, 'DRIFT');
  assert.match(rows[0].message, /api 90% \/ web 10%/);
  assert.match(rows[0].message, /api 50% \/ web 50%/);
});

test('a canvas route that was never applied is NOT_APPLIED', () => {
  const rows = compareRoutePlan([planOf('cart', 8080, [['api', 50], ['web', 50]])], []);
  assert.equal(rows[0].status, 'NOT_APPLIED');
});

test('a cluster route the canvas dropped is STALE, and is not doubled', () => {
  const rows = compareRoutePlan(
    [planOf('cart', 8080, [['api', 50], ['web', 50]])],
    [
      appliedOf('np-route-cart', 'cart', 8080, [['api', 50], ['web', 50]]),
      appliedOf('np-route-legacy', 'legacy', 8080, [['api', 100]]),
    ],
  );
  assert.deepEqual(rows.map(row => row.status), ['MATCH', 'STALE']);
  assert.match(rows[1].message, /legacy:8080/);
});

test('rounding within one point still counts as matching', () => {
  const [row] = compareRoutePlan(
    [planOf('shop', 8080, [['api', 33], ['web', 67]])],
    [appliedOf('np-route-1', 'shop', 8080, [['api', 34], ['web', 66]])],
  );
  assert.equal(row.status, 'MATCH');
  const [drift] = compareRoutePlan(
    [planOf('shop', 8080, [['api', 33], ['web', 67]])],
    [appliedOf('np-route-1', 'shop', 8080, [['api', 60], ['web', 40]])],
  );
  assert.equal(drift.status, 'DRIFT');
});

test('a different backend set is a drift even when the totals match', () => {
  const [row] = compareRoutePlan(
    [planOf('shop', 8080, [['api', 50], ['web', 50]])],
    [appliedOf('np-route-1', 'shop', 8080, [['api', 50], ['cache', 50]])],
  );
  assert.equal(row.status, 'DRIFT');
});

// ---- 一键生成路由草稿 -------------------------------------------------------
const draftRequest = (overrides = {}) => ({
  loadBalancerId: 'lb-new', loadBalancerName: '流量分配器 1',
  entry: { serviceId: 'svc-shop', port: 8080 },
  backends: [{ serviceId: 'svc-api', port: 8080 }, { serviceId: 'svc-web', port: 8081 }],
  newLinkId: (() => { let n = 0; return () => `link-${++n}`; })(),
  ...overrides,
});

test('a two-backend draft splits the weight evenly and wires the canvas handles', () => {
  const result = planRouteDraft(draftRequest());
  assert.equal(result.ok, true);
  const { draft } = result;
  assert.deepEqual(draft.weights, [
    { outputPort: 'out-1', weight: 50 },
    { outputPort: 'out-2', weight: 50 },
  ]);
  assert.equal(draft.links.length, 3);
  const [entryLink, ...backendLinks] = draft.links;
  assert.equal(entryLink.type, 'ROUTE_ENTRY');
  assert.equal(entryLink.sourceId, 'svc-shop');
  assert.equal(entryLink.sourceHandle, 'route-output-8080');
  assert.equal(entryLink.targetId, 'lb-new');
  assert.equal(entryLink.targetHandle, 'route-input');
  assert.equal(entryLink.servicePort, 8080);
  assert.deepEqual(backendLinks.map(link => link.sourceHandle), ['route-out-1', 'route-out-2']);
  assert.deepEqual(backendLinks.map(link => link.targetId), ['svc-api', 'svc-web']);
  assert.deepEqual(backendLinks.map(link => link.targetHandle),
    ['backend-input-8080', 'backend-input-8081']);
  assert.deepEqual(backendLinks.map(link => link.servicePort), [8080, 8081]);
});

test('an odd split still totals 100, with the remainder on the first outputs', () => {
  const { draft } = planRouteDraft(draftRequest({
    backends: [
      { serviceId: 'svc-api', port: 8080 },
      { serviceId: 'svc-web', port: 8081 },
      { serviceId: 'svc-jobs', port: 8082 },
    ],
  }));
  assert.deepEqual(draft.weights.map(item => item.weight), [34, 33, 33]);
  assert.equal(draft.weights.reduce((sum, item) => sum + item.weight, 0), 100);
});

test('a draft refuses what the canvas and the server would reject', () => {
  assert.equal(planRouteDraft(draftRequest({ backends: [] })).code, 'NO_BACKENDS');
  assert.equal(planRouteDraft(draftRequest({
    backends: [
      { serviceId: 'a', port: 8080 }, { serviceId: 'b', port: 8080 },
      { serviceId: 'c', port: 8080 }, { serviceId: 'd', port: 8080 },
    ],
  })).code, 'TOO_MANY_BACKENDS');
  assert.equal(planRouteDraft(draftRequest({
    backends: [{ serviceId: 'svc-api', port: 8080 }, { serviceId: 'svc-api', port: 8081 }],
  })).code, 'DUPLICATE_BACKEND');
  assert.equal(planRouteDraft(draftRequest({
    backends: [{ serviceId: 'svc-shop', port: 8080 }, { serviceId: 'svc-api', port: 8080 }],
  })).code, 'ENTRY_AS_BACKEND');
  assert.equal(planRouteDraft(draftRequest({ entry: { serviceId: 'svc-shop', port: 0 } })).code,
    'INVALID_PORT');
  const refused = planRouteDraft(draftRequest({ backends: [] }));
  assert.equal(refused.ok, false);
  assert.match(refused.message, /后端/);
});

test('the generated draft compiles into a plan the validator accepts', () => {
  const { draft } = planRouteDraft(draftRequest());
  const balancer = {
    id: draft.loadBalancerId,
    loadBalancerConfiguration: { outputWeights: draft.weights },
  };
  const names = { 'svc-shop': 'shop', 'svc-api': 'api', 'svc-web': 'web' };
  const rows = compileApplicationRoutePlan(draft.links, [balancer], id => names[id]);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].serviceName, 'shop');
  assert.deepEqual(rows[0].backends.map(backend => [backend.name, backend.share]),
    [['api', 50], ['web', 50]]);
  assert.deepEqual(validateRoutePlan(rows), []);
});
