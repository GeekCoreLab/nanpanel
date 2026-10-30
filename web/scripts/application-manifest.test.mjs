import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildApplicationBundle } from '../src/lib/application-manifest.ts';

const routeRow = (backends) => ({
  id: 'entry', balancerId: 'lb', serviceId: 'svc-shop', serviceName: 'shop', servicePort: 8080,
  totalWeight: 100,
  backends: backends.map(([name, share, port]) => ({
    name, weight: share, share, id: `svc-${name}`, linkId: `link-${name}`, port,
  })),
  callers: [],
});

const input = (overrides = {}) => ({
  applicationName: 'shop', namespace: 'demo',
  resources: [
    { kind: 'Deployment', name: 'shop', sourceYaml: 'apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: shop\n' },
    { kind: 'Service', name: 'shop', sourceYaml: '---\napiVersion: v1\nkind: Service\nmetadata:\n  name: shop\n' },
  ],
  routes: [routeRow([['api', 50, 8080], ['web', 50, 8081]])],
  exportedAt: '2026-10-30T10:00:00Z',
  ...overrides,
});

test('every resource and route becomes one document with a single separator', () => {
  const bundle = buildApplicationBundle(input());
  assert.equal(bundle.resourceCount, 2);
  assert.equal(bundle.routeCount, 1);
  assert.equal(bundle.skipped.length, 0);
  // One document per resource and route, each introduced by exactly one separator.
  assert.equal((bundle.yaml.match(/^kind: /gm) ?? []).length, 3);
  assert.equal((bundle.yaml.match(/^---$/gm) ?? []).length, 3);
  assert.match(bundle.yaml, /kind: Deployment/);
  assert.match(bundle.yaml, /kind: Service/);
  assert.match(bundle.yaml, /kind: VirtualService/);
  // The pre-existing document marker is stripped, not doubled.
  assert.doesNotMatch(bundle.yaml, /\n---\n---\n/);
});

test('the header states what is excluded so the file is not mistaken for a full backup', () => {
  const { yaml } = buildApplicationBundle(input());
  assert.match(yaml, /^# NaNPanel application bundle/);
  assert.match(yaml, /不含运行时状态/);
  assert.match(yaml, /# application: shop/);
  assert.match(yaml, /# namespace: demo/);
  assert.match(yaml, /# exportedAt: 2026-10-30T10:00:00Z/);
  assert.match(yaml, /# resources: 2, routes: 1/);
});

test('pending resources and routes without backends are reported, not silently dropped', () => {
  const bundle = buildApplicationBundle(input({
    resources: [
      { kind: 'Deployment', name: 'shop', sourceYaml: 'apiVersion: apps/v1\n' },
      { kind: 'Service', name: 'pending', sourceYaml: '   ' },
    ],
    routes: [routeRow([])],
  }));
  assert.equal(bundle.resourceCount, 1);
  assert.equal(bundle.routeCount, 0);
  assert.deepEqual(bundle.skipped, ['Service/pending', 'VirtualService/shop:8080']);
});

test('an application with nothing to export still produces a readable header', () => {
  const bundle = buildApplicationBundle(input({ resources: [], routes: [] }));
  assert.equal(bundle.yaml.trim().endsWith('# resources: 0, routes: 0'), true);
  assert.equal(bundle.yaml.includes('\n---\n'), false);
});

test('the route document carries the weighted destinations', () => {
  const { yaml } = buildApplicationBundle(input());
  assert.match(yaml, /hosts:\n\s+- shop\.demo\.svc\.cluster\.local/);
  assert.match(yaml, /host: api\.demo\.svc\.cluster\.local/);
  assert.match(yaml, /weight: 50/);
});
