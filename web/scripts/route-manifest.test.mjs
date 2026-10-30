import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildRouteBody, clusterHost } from '../src/lib/route-manifest.ts';

const row = (backends, port = 8080) => ({
  id: 'entry', balancerId: 'lb', serviceId: 'svc-shop', serviceName: 'shop', servicePort: port,
  totalWeight: 100,
  backends: backends.map(([name, share, backendPort]) => ({
    name, share, weight: share, id: `svc-${name}`, linkId: `link-${name}`, port: backendPort,
  })),
  callers: [],
});

test('hosts are fully qualified inside a namespace', () => {
  assert.equal(clusterHost('shop', 'demo'), 'shop.demo.svc.cluster.local');
  assert.equal(clusterHost('shop', '  '), 'shop');
});

test('the body mirrors the weighted routing the server builds', () => {
  const body = buildRouteBody(row([['api', 50, 8080], ['web', 50, 8081]]), 'demo');
  assert.deepEqual(body.hosts, ['shop.demo.svc.cluster.local']);
  assert.deepEqual(body.gateways, ['mesh']);
  assert.deepEqual(body.http[0].match, [{ port: 8080 }]);
  assert.deepEqual(body.http[0].route, [
    { destination: { host: 'api.demo.svc.cluster.local', port: { number: 8080 } }, weight: 50 },
    { destination: { host: 'web.demo.svc.cluster.local', port: { number: 8081 } }, weight: 50 },
  ]);
});

test('rounding never produces a body whose weights do not total 100', () => {
  const body = buildRouteBody(row([['api', 33, 8080], ['web', 33, 8081], ['jobs', 33, 8082]]), 'demo');
  const weights = body.http[0].route.map(entry => entry.weight);
  assert.deepEqual(weights, [34, 33, 33]);
  assert.equal(weights.reduce((sum, value) => sum + value, 0), 100);
});

test('zero-share backends are dropped instead of being sent 0%', () => {
  const body = buildRouteBody(row([['api', 100, 8080], ['web', 0, 8081]]), 'demo');
  assert.deepEqual(body.http[0].route.map(entry => entry.destination.host),
    ['api.demo.svc.cluster.local']);
  assert.equal(body.http[0].route[0].weight, 100);
});

test('a route without backends still yields a valid shell body', () => {
  const body = buildRouteBody(row([]), 'demo');
  assert.deepEqual(body.http[0].route, []);
  assert.deepEqual(body.hosts, ['shop.demo.svc.cluster.local']);
});
