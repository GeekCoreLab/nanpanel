import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareTopologyDrafts, connectionError, createTopologyNode, draftError, initialTopology, providerSupportsDevice, resolveRouteNextHop, unmatchedRouteGateways, validNodeShape } from '../src/lib/topology-model.ts';
import { mockRequest, DEMO_PASSWORD } from '../src/lib/mock.ts';
const node = kind => createTopologyNode(kind, { x: 0, y: 0 });
const link = (source, target, out = 'out-1', input = 'in-1', layer = 'L4', protocol = 'TCP') => ({ id: crypto.randomUUID(), sourceNodeId: source.id, sourcePort: out, targetNodeId: target.id, targetPort: input, layer, protocol, status: 'UNVERIFIED' });
const ports = (inputs, outputs) => [
  ...Array.from({ length: inputs }, (_, i) => ({ id: `in-${i + 1}`, direction: 'INPUT', layer: 'L4', protocols: ['TCP', 'UDP'] })),
  ...Array.from({ length: outputs }, (_, i) => ({ id: `out-${i + 1}`, direction: 'OUTPUT', layer: 'L4', protocols: ['TCP', 'UDP'] })),
];

test('direction, explicit fan-in/fan-out, port occupancy, references and cycles', () => {
  const gateway = node('GATEWAY'), merge = node('MERGE'), split = node('SPLIT'), service = node('SERVICE');
  const nodes = [gateway, merge, split, service];
  assert.equal(draftError({ nodes, links: [link(gateway, merge), link(merge, split), link(split, service)] }), null);
  assert.match(connectionError(nodes, [], link(gateway, merge, 'out-1', 'out-1')), /绿色出口/);
  assert.match(connectionError(nodes, [], link(gateway, merge, 'in-1')), /绿色出口/);
  assert.match(connectionError(nodes, [], link(merge, merge)), /自身/);
  assert.match(connectionError(nodes, [], link(gateway, service, 'out-2')), /不存在/);
  assert.match(connectionError(nodes, [link(gateway, merge)], link(gateway, service)), /占用/);
  assert.match(connectionError(nodes, [link(gateway, merge)], link(split, merge)), /占用/);
  const a = node('FORWARDER'), b = node('FORWARDER');
  assert.match(connectionError([a, b], [link(a, b)], link(b, a)), /环路/);
  const sources = [node('GATEWAY'), node('GATEWAY'), node('GATEWAY')];
  const targets = [node('SERVICE'), node('SERVICE'), node('SERVICE')];
  const fanLinks = [...sources.map((s, i) => link(s, merge, 'out-1', `in-${i + 1}`)), link(merge, split), ...targets.map((t, i) => link(split, t, `out-${i + 1}`))];
  assert.equal(draftError({ nodes: [...sources, merge, split, ...targets], links: fanLinks }), null);
  assert.equal(validNodeShape({ ...merge, outputCount: 2 }), false);
  assert.equal(validNodeShape({ ...split, inputCount: 2 }), false);
  assert.equal(validNodeShape({ ...node('SWITCH'), inputCount: 16, outputCount: 16, ports: ports(16, 16) }), true);
  assert.equal(validNodeShape(node('FIREWALL')), true);
  assert.equal(validNodeShape(node('PACKET_TAP')), true);
  assert.equal(validNodeShape({ ...node('SERVICE'), inputCount: 0, ports: [] }), false);
  const mismatchedTap = node('PACKET_TAP');
  const mismatchedPorts = mismatchedTap.ports.map((port, index) => ({
    ...port, layer: index ? 'L3' : 'L2', protocols: [index ? 'IP' : 'ETHERNET'],
  }));
  assert.equal(validNodeShape({ ...mismatchedTap, ports: mismatchedPorts }), false);
  assert.equal(validNodeShape({ ...node('FORWARDER'), inputCount: 17 }), false);
  assert.ok(draftError({ nodes: [gateway, gateway], links: [] }));
  assert.equal(draftError({ nodes: [{ ...node('ROUTER'), providerId: 'linux.bridge' }], links: [] }), null);
  assert.ok(draftError({ nodes: [{ ...node('ROUTER'), providerId: '../host' }], links: [] }));
  const switchNode = node('SWITCH');
  assert.equal(switchNode.switchBackend, 'BRIDGE');
  assert.equal(draftError({ nodes: [{ ...switchNode, switchBackend: 'OPENVSWITCH' }], links: [] }), null);
  assert.ok(draftError({ nodes: [{ ...switchNode, switchBackend: 'UNSAFE' }], links: [] }));
  assert.ok(draftError({ nodes: [{ ...node('ROUTER'), switchBackend: 'OPENVSWITCH' }], links: [] }));
  assert.ok(draftError({ nodes: [{ ...gateway, position: { x: Infinity, y: 0 } }], links: [] }));
  assert.ok(draftError({ nodes, links: [{ ...link(gateway, merge), status: 'HEALTHY' }] }));
  assert.ok(draftError({ nodes, links: [link(gateway, merge, 'out-1', 'in-1', 'L4', 'HTTP')] }));
  assert.ok(draftError({ nodes, links: [link(gateway, node('HOST'))] }));
  assert.ok(draftError({ ...initialTopology(), deployed: true }));
});

test('Provider device binding requires declared device, port, and lifecycle capabilities', () => {
  const provider = { id: 'linux-network', capabilities: [], capabilityDetails: [{
    id: 'linux.router', deviceKinds: ['ROUTER'], layers: ['L4'], protocols: ['TCP'],
    operations: ['PLAN', 'VALIDATE', 'APPLY', 'VERIFY', 'COMPENSATE'],
  }] };
  assert.equal(providerSupportsDevice(provider, 'ROUTER', 'L4', ['TCP']), true);
  assert.equal(providerSupportsDevice(provider, 'ROUTER', 'L4', ['TCP', 'UDP']), false);
  assert.equal(providerSupportsDevice(provider, 'SWITCH', 'L4', ['TCP']), false);
  assert.equal(providerSupportsDevice({ ...provider, capabilityDetails: [{ ...provider.capabilityDetails[0], deviceKinds: ['SWITCH'], layers: ['L2'], protocols: ['ETHERNET'] }] }, 'SWITCH', 'L2', ['ETHERNET']), true);
  assert.equal(providerSupportsDevice({ ...provider, capabilityDetails: [{ ...provider.capabilityDetails[0], operations: ['PLAN', 'VALIDATE'] }] }, 'ROUTER', 'L4', ['TCP']), false);
});

test('Firewall policy is bounded, port scoped, and rejects duplicate or invalid matches', () => {
  const firewall = node('FIREWALL');
  assert.deepEqual(firewall.firewallPolicy, { defaultAction: 'DROP', rules: [] });
  const rule = { inputPort: 'in-1', outputPort: 'out-1', protocol: 'TCP', destinationPort: 443, action: 'ACCEPT' };
  assert.equal(draftError({ nodes: [{ ...firewall, firewallPolicy: { defaultAction: 'DROP', rules: [rule] } }], links: [] }), null);
  assert.match(draftError({ nodes: [{ ...firewall, firewallPolicy: { defaultAction: 'DROP', rules: [{ ...rule, inputPort: 'in-2' }] } }], links: [] }), /不存在的入口或出口/);
  assert.match(draftError({ nodes: [{ ...firewall, firewallPolicy: { defaultAction: 'DROP', rules: [rule, rule] } }], links: [] }), /匹配条件不能重复/);
  assert.ok(draftError({ nodes: [{ ...firewall, firewallPolicy: { defaultAction: 'DROP', rules: [{ ...rule, destinationPort: 65536 }] } }], links: [] }));
  assert.ok(draftError({ nodes: [{ ...node('ROUTER'), firewallPolicy: { defaultAction: 'DROP', rules: [] } }], links: [] }));
});

test('Router IPv4 interfaces and static routes are scoped to connected ports and their subnets', () => {
  const source = node('SWITCH');
  const router = node('ROUTER');
  const target = node('SWITCH');
  router.ports = router.ports.map(port => ({ ...port, layer: 'L2', protocols: ['ETHERNET'] }));
  const links = [
    link(source, router, 'out-1', 'in-1', 'L2', 'ETHERNET'),
    link(router, target, 'out-1', 'in-1', 'L2', 'ETHERNET'),
  ];
  const routerConfig = {
    interfaces: [
      { port: 'in-1', address: '198.18.1.1/24' },
      { port: 'out-1', address: '198.18.2.1/24' },
    ],
    routes: [{ destination: '198.18.3.0/24', gateway: '198.18.2.2', outputPort: 'out-1' }],
  };
  assert.equal(draftError({ nodes: [source, { ...router, routerConfiguration: routerConfig }, target], links }), null);
  assert.match(draftError({ nodes: [source, { ...router, routerConfiguration: { ...routerConfig, routes: [{ ...routerConfig.routes[0], gateway: '198.18.4.2' }] } }, target], links }), /直连网段/);
  assert.match(draftError({ nodes: [source, { ...router, routerConfiguration: { interfaces: [routerConfig.interfaces[0]], routes: [] } }, target], links }), /每个已连接接口/);
  assert.match(draftError({ nodes: [source, { ...router, routerConfiguration: { ...routerConfig, routes: [{ ...routerConfig.routes[0], destination: '198.18.2.0/24' }] } }, target], links }), /直连网段/);
  assert.ok(draftError({ nodes: [source, { ...router, routerConfiguration: { ...routerConfig, routes: [{ ...routerConfig.routes[0], destination: '198.18.3.1/24' }] } }, target], links }));
  assert.ok(draftError({ nodes: [source, { ...router, routerConfiguration: routerConfig }], links }));
});

test('folder hierarchy, host asset binding and same-scope connections are validated', () => {
  const folder = node('FOLDER');
  const ingress = { ...node('GATEWAY'), parentId: folder.id };
  const service = { ...node('SERVICE'), parentId: folder.id };
  const host = { ...node('HOST'), assetId: crypto.randomUUID() };
  const gatewayAsset = { ...node('GATEWAY'), assetId: crypto.randomUUID() };
  assert.equal(draftError({ nodes: [folder, ingress, service, host, gatewayAsset], links: [link(ingress, service)] }), null);
  assert.match(connectionError([folder, ingress], [], link(folder, ingress)), /不同拓扑层级/);
  assert.match(draftError({ nodes: [folder, { ...service, parentId: crypto.randomUUID() }], links: [] }), /父节点/);
  assert.match(draftError({ nodes: [{ ...folder, parentId: service.id }, service], links: [] }), /文件夹/);
  assert.match(draftError({ nodes: [host, { ...host, id: crypto.randomUUID() }], links: [] }), /只能添加一次/);
  assert.match(draftError({ nodes: [{ ...service, assetId: crypto.randomUUID() }], links: [] }), /只有主机或 Gateway 节点/);
});

test('route next-hop matching accepts one distinct Host asset or Router owner', () => {
  const source = { id: 'source', hostname: 'source-host' };
  const target = { id: 'target', hostname: 'target-host' };
  const router = { id: 'router', hostname: 'edge-router', kind: 'ROUTER' };
  const addressOwners = new Map([
    ['10.0.0.1', [target]],
    ['10.0.0.2', [source]],
    ['10.0.0.3', [source, target]],
    ['10.0.0.4', [target, target]],
    ['10.0.0.5', [router]],
    ['10.0.0.6', [target, router]],
  ]);
  assert.deepEqual(resolveRouteNextHop(null, source.id, addressOwners), { kind: 'DIRECT' });
  assert.deepEqual(resolveRouteNextHop(' 10.0.0.1 ', source.id, addressOwners), { kind: 'MATCHED', host: target });
  assert.deepEqual(resolveRouteNextHop('10.0.0.2', source.id, addressOwners), { kind: 'SELF' });
  assert.deepEqual(resolveRouteNextHop('10.0.0.3', source.id, addressOwners), { kind: 'AMBIGUOUS' });
  assert.deepEqual(resolveRouteNextHop('10.0.0.4', source.id, addressOwners), { kind: 'MATCHED', host: target });
  assert.deepEqual(resolveRouteNextHop('10.0.0.5', source.id, addressOwners), { kind: 'MATCHED', host: router });
  assert.deepEqual(resolveRouteNextHop('10.0.0.6', source.id, addressOwners), { kind: 'AMBIGUOUS' });
  assert.deepEqual(resolveRouteNextHop('10.0.0.9', source.id, addressOwners), { kind: 'UNMATCHED' });
});

test('unmodeled unique route gateways are deduplicated for observed Router nodes', () => {
  const owners = new Map([
    ['10.0.0.1', [{ id: 'known-router', hostname: 'router', kind: 'ROUTER' }]],
    ['10.0.0.2', [{ id: 'other-host', hostname: 'other' }]],
    ['10.0.0.3', [{ id: 'one', hostname: 'one' }, { id: 'two', hostname: 'two' }]],
  ]);
  const sourceHostIds = new Map([['source', 'source']]);
  assert.deepEqual(unmatchedRouteGateways([
    { sourceHostId: 'source', gateway: '10.0.0.1' },
    { sourceHostId: 'source', gateway: '10.0.0.2' },
    { sourceHostId: 'source', gateway: ' 10.0.0.8 ' },
    { sourceHostId: 'source', gateway: '10.0.0.8' },
    { sourceHostId: 'source', gateway: '10.0.0.3' },
    { sourceHostId: 'source', gateway: null },
    { sourceHostId: 'source', gateway: '10.0.0.9' },
  ], sourceHostIds, owners), ['10.0.0.8', '10.0.0.9']);
});

test('topology revision comparison reports node and link changes by stable identity', () => {
  const gateway = node('GATEWAY');
  const host = node('HOST');
  const service = node('SERVICE');
  const oldLink = link(gateway, host);
  const before = { nodes: [gateway, host], links: [oldLink] };
  const renamedHost = { ...host, name: '边缘主机二' };
  const newLink = link(renamedHost, service, 'out-1', 'in-1', 'L4', 'UDP');
  const after = {
    nodes: [service, { ...renamedHost, position: { y: 10, x: 20 } }, gateway],
    links: [{ ...oldLink, protocol: 'UDP' }, newLink],
  };
  const diff = compareTopologyDrafts(before, after);
  assert.deepEqual(diff.nodes.added.map(item => item.id), [service.id]);
  assert.deepEqual(diff.nodes.removed, []);
  assert.deepEqual(diff.nodes.changed.map(item => item.after.id), [host.id]);
  assert.deepEqual(diff.nodes.changed[0].fields, ['name', 'position']);
  assert.deepEqual(diff.links.added.map(item => item.id), [newLink.id]);
  assert.deepEqual(diff.links.removed, []);
  assert.deepEqual(diff.links.changed.map(item => item.after.id), [oldLink.id]);
  assert.deepEqual(diff.links.changed[0].fields, ['protocol']);
  assert.deepEqual(compareTopologyDrafts(before, { nodes: [...before.nodes].reverse(), links: before.links }), {
    nodes: { added: [], removed: [], changed: [] }, links: { added: [], removed: [], changed: [] },
  });
});

test('Mock cluster hierarchy exposes namespaces, ingress routes, pods and container details', async () => {
  let csrfToken = (await (await mockRequest('/auth/csrf', { method: 'GET' })).json()).csrfToken;
  const login = await (await mockRequest('/auth/login', { method: 'POST', headers: { 'X-CSRF-Token': csrfToken }, body: JSON.stringify({ username: 'admin', password: DEMO_PASSWORD }) })).json();
  const namespaces = await (await mockRequest('/kubernetes/topology/namespaces', { method: 'GET' })).json();
  assert.ok(namespaces.namespaces.some(item => item.name === 'nanpanel-mesh'));
  const detail = await (await mockRequest('/kubernetes/topology/namespaces/nanpanel-mesh', { method: 'GET' })).json();
  assert.equal(detail.ingresses[0].routes[0].serviceName, 'catalog-api');
  assert.equal(detail.pods[0].containers[0].ports[0].containerPort, 8080);
  await mockRequest('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': login.csrfToken } });
});

test('Mock mode refuses to fabricate service inventory and Istio traffic', async () => {
  const csrfToken = (await (await mockRequest('/auth/csrf', { method: 'GET' })).json()).csrfToken;
  const login = await (await mockRequest('/auth/login', { method: 'POST', headers: { 'X-CSRF-Token': csrfToken }, body: JSON.stringify({ username: 'admin', password: DEMO_PASSWORD }) })).json();
  const inventory = await mockRequest('/kubernetes/service-inventory', { method: 'GET' });
  const refresh = await mockRequest('/kubernetes/service-inventory/refresh', { method: 'POST', headers: { 'X-CSRF-Token': login.csrfToken } });
  const probes = await mockRequest('/kubernetes/service-probes', { method: 'GET' });
  assert.equal(inventory.status, 503);
  assert.equal(refresh.status, 503);
  assert.equal(probes.status, 503);
  assert.equal((await inventory.json()).code, 'LIVE_ISTIO_REQUIRED');
  await mockRequest('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': login.csrfToken } });
});

test('Mock Provider inventory reports explicit capability details', async () => {
  const csrfToken = (await (await mockRequest('/auth/csrf', { method: 'GET' })).json()).csrfToken;
  const login = await (await mockRequest('/auth/login', { method: 'POST', headers: { 'X-CSRF-Token': csrfToken }, body: JSON.stringify({ username: 'admin', password: DEMO_PASSWORD }) })).json();
  const response = await mockRequest('/network-providers', { method: 'GET' });
  const [provider] = await response.json();
  assert.equal(response.status, 200);
  assert.equal(provider.capabilityDetails[0].id, 'overlay.group-membership.reconcile');
  assert.ok(provider.capabilityDetails[0].operations.includes('COMPENSATE'));
  assert.deepEqual(provider.capabilityDetails[0].deviceKinds, []);
  assert.deepEqual(provider.capabilityDetails[0].layers, []);
  await mockRequest('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': login.csrfToken } });
});

test('Mock topology API requires auth, CSRF, matching ETag; save is atomic and remains unverified', async () => {
  let csrf = '';
  async function call(method, body, etag, token = csrf) {
    const response = await mockRequest('/topology/draft', { method, headers: { 'X-CSRF-Token': token, ...(etag ? { 'If-Match': etag } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { response, data: await response.json() };
  }
  assert.equal((await call('GET')).response.status, 401);
  csrf = (await (await mockRequest('/auth/csrf', { method: 'GET' })).json()).csrfToken;
  const login = await (await mockRequest('/auth/login', { method: 'POST', headers: { 'X-CSRF-Token': csrf }, body: JSON.stringify({ username: 'admin', password: DEMO_PASSWORD }) })).json();
  csrf = login.csrfToken;
  const initial = await call('GET');
  assert.equal(initial.response.status, 200);
  const tag = initial.response.headers.get('ETag');
  const write = { nodes: [...initial.data.nodes, node('SWITCH'), node('FIREWALL'), node('PACKET_TAP'), node('MERGE'), node('SPLIT'), node('FORWARDER')], links: [] };
  write.links.push(link(write.nodes[0], write.nodes[1]));
  assert.equal((await call('PUT', write)).response.status, 428);
  assert.equal((await call('PUT', write, tag, 'invalid')).response.status, 403);
  assert.equal((await call('PUT', write, '"stale"')).response.status, 412);
  const invalid = { ...write, links: [{ ...write.links[0], targetPort: 'out-1' }] };
  assert.equal((await call('PUT', invalid, tag)).response.status, 422);
  assert.equal((await call('GET')).response.headers.get('ETag'), tag);
  const saved = await call('PUT', write, tag);
  assert.equal(saved.response.status, 200, JSON.stringify(saved.data));
  assert.notEqual(saved.response.headers.get('ETag'), tag);
  assert.deepEqual(saved.data.nodes, write.nodes);
  assert.equal(saved.data.links[0].status, 'UNVERIFIED');
  assert.equal(saved.data.updatedBy, login.user.id);
  const revisions = await (await mockRequest('/topology/revisions?limit=50', { method: 'GET' })).json();
  assert.equal(revisions.items[0].revisionId, saved.data.revisionId);
  assert.equal(revisions.items[0].generation, 1);
  const historical = await (await mockRequest(`/topology/revisions/${saved.data.revisionId}`, { method: 'GET' })).json();
  assert.deepEqual(historical.nodes, saved.data.nodes);
  assert.equal(historical.links[0].state, 'PENDING');
  assert.equal((await call('PUT', write, tag)).response.status, 412);
  const audit = await (await mockRequest('/admin/audit-events', { method: 'GET' })).json();
  assert.ok(audit.items.some(e => e.action === 'TOPOLOGY_DRAFT_UPDATED'));
});
