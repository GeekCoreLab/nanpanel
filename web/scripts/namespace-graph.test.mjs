import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deriveNamespaceSelectorEdges } from '../src/lib/namespace-graph.ts';

const resource = (kind, name, extra = {}) => ({ kind, name, ...extra });

test('a service selector that is a subset of pod labels selects the workload', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'orders', { selector: { app: 'orders' } }),
    resource('Deployment', 'orders-v2', { podLabels: { app: 'orders', version: 'v2' } }),
  ]);
  assert.deepEqual(edges, [{
    service: 'orders', workload: 'orders-v2', workloadKind: 'Deployment',
    sharedLabels: { app: 'orders' },
  }]);
});

test('a mismatched pod label produces no edge', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'orders', { selector: { app: 'orders' } }),
    resource('Deployment', 'api', { podLabels: { app: 'api' } }),
  ]);
  assert.equal(edges.length, 0);
});

test('every selector key must match, extra pod labels are ignored', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'web', { selector: { app: 'web', tier: 'front' } }),
    resource('Deployment', 'web', { podLabels: { app: 'web', tier: 'back' } }),
    resource('Deployment', 'web-front', { podLabels: { app: 'web', tier: 'front', extra: 'x' } }),
  ]);
  assert.deepEqual(edges.map(e => e.workload), ['web-front']);
});

test('services without a selector relate to nothing', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'external', { selector: {} }),
    resource('Deployment', 'anything', { podLabels: { app: 'x' } }),
  ]);
  assert.equal(edges.length, 0);
});

test('pods and statefulsets are recognised as workloads', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'redis', { selector: { app: 'redis' } }),
    resource('StatefulSet', 'redis', { podLabels: { app: 'redis' } }),
    resource('Pod', 'redis-0', { podLabels: { app: 'redis' } }),
  ]);
  assert.deepEqual(edges.map(e => `${e.workloadKind}/${e.workload}`),
    ['StatefulSet/redis', 'Pod/redis-0']);
});

test('workloads without pod labels are never targets', () => {
  const edges = deriveNamespaceSelectorEdges([
    resource('Service', 'orders', { selector: { app: 'orders' } }),
    resource('Deployment', 'orders', {}),
  ]);
  assert.equal(edges.length, 0);
});
import { layoutNamespaceGraph } from '../src/lib/namespace-graph.ts';

test('graph layout places services left and workloads right with stable ids', () => {
  const layout = layoutNamespaceGraph([
    resource('Service', 'orders', { selector: { app: 'orders' } }),
    resource('Service', 'cache'),
    resource('Deployment', 'orders', { podLabels: { app: 'orders' } }),
    resource('StatefulSet', 'cache', { podLabels: { app: 'cache' } }),
  ]);
  assert.deepEqual(layout.nodes.map(n => n.id),
    ['svc:orders', 'svc:cache', 'wl:Deployment/orders', 'wl:StatefulSet/cache']);
  assert.deepEqual(layout.nodes.map(n => n.column), [0, 0, 1, 1]);
  assert.deepEqual(layout.nodes.map(n => n.x), [0, 0, 360, 360]);
  assert.deepEqual(layout.nodes.map(n => n.y), [0, 96, 0, 96]);
});

test('graph edges only reference existing nodes and carry the selector label', () => {
  const layout = layoutNamespaceGraph([
    resource('Service', 'orders', { selector: { app: 'orders', tier: 'api' } }),
    resource('Deployment', 'orders', { podLabels: { app: 'orders', tier: 'api' } }),
    resource('Deployment', 'orphan', { podLabels: { app: 'orphan' } }),
  ]);
  const ids = new Set(layout.nodes.map(n => n.id));
  assert.equal(layout.edges.length, 1);
  assert.equal(layout.edges[0].source, 'svc:orders');
  assert.equal(layout.edges[0].target, 'wl:Deployment/orders');
  assert.equal(layout.edges[0].label, 'app=orders · tier=api');
  for (const edge of layout.edges) {
    assert.ok(ids.has(edge.source));
    assert.ok(ids.has(edge.target));
  }
});

test('same-named resources of different kinds keep distinct node ids', () => {
  const layout = layoutNamespaceGraph([
    resource('Deployment', 'web'), resource('StatefulSet', 'web'),
  ]);
  assert.deepEqual(layout.nodes.map(n => n.id), ['wl:Deployment/web', 'wl:StatefulSet/web']);
  assert.equal(new Set(layout.nodes.map(n => n.id)).size, 2);
});

test('an empty or non-graphable namespace yields no nodes', () => {
  assert.deepEqual(layoutNamespaceGraph([]), { nodes: [], edges: [] });
  assert.deepEqual(layoutNamespaceGraph([resource('ConfigMap', 'cfg')]), { nodes: [], edges: [] });
});

import { buildManagedAppIndex } from '../src/lib/namespace-graph.ts';

test('managed app index maps owned Deployment/Service nodes within one cluster+namespace', () => {
  const index = buildManagedAppIndex([
    { id: 'a1', name: 'web', namespace: 'shop', clusterId: 'c1' },
    { id: 'a2', name: 'api', namespace: 'other', clusterId: 'c1' },
    { id: 'a3', name: 'billing', namespace: 'shop', clusterId: 'c2' },
  ], 'c1', 'shop');
  assert.deepEqual(index, { 'Deployment/web': 'a1', 'Service/web': 'a1' });
});

test('managed app index is empty without a cluster or namespace', () => {
  const apps = [{ id: 'a1', name: 'web', namespace: 'shop', clusterId: 'c1' }];
  assert.deepEqual(buildManagedAppIndex(apps, '', 'shop'), {});
  assert.deepEqual(buildManagedAppIndex(apps, 'c1', ''), {});
  assert.deepEqual(buildManagedAppIndex([], 'c1', 'shop'), {});
});
