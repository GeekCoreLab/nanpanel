import { createUuid } from './uuid.ts';
import type { NetworkProvider, TopologyDraftWrite, TopologyLayer, TopologyLink, TopologyNode, TopologyNodeKind, TopologyPort, TopologyProtocol } from './types';

export type RouteNextHopHost = { id: string; hostname: string; kind?: 'HOST' | 'ROUTER' };
export type RouteNextHopResolution =
  | { kind: 'DIRECT' }
  | { kind: 'MATCHED'; host: RouteNextHopHost }
  | { kind: 'AMBIGUOUS' }
  | { kind: 'UNMATCHED' }
  | { kind: 'SELF' };

export type ObservedRoute = { sourceHostId: string; gateway?: string | null };

export type TopologyRevisionDiff<TNode extends { id: string }, TLink extends { id: string }> = {
  nodes: { added: TNode[]; removed: TNode[]; changed: { before: TNode; after: TNode; fields: string[] }[] };
  links: { added: TLink[]; removed: TLink[]; changed: { before: TLink; after: TLink; fields: string[] }[] };
};

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort().map(key => `${JSON.stringify(key)}:${canonical(object[key])}`).join(',')}}`;
  }
  return JSON.stringify(value) ?? 'null';
}

function changedFields(before: { id: string }, after: { id: string }): string[] {
  const oldValues = before as Record<string, unknown>;
  const newValues = after as Record<string, unknown>;
  return [...new Set([...Object.keys(oldValues), ...Object.keys(newValues)])].sort()
    .filter(key => canonical(oldValues[key]) !== canonical(newValues[key]));
}

function compareById<T extends { id: string }>(before: readonly T[], after: readonly T[]) {
  const oldById = new Map(before.map(item => [item.id, item]));
  const newById = new Map(after.map(item => [item.id, item]));
  const added = after.filter(item => !oldById.has(item.id));
  const removed = before.filter(item => !newById.has(item.id));
  const changed = after.flatMap(item => {
    const old = oldById.get(item.id);
    const fields = old ? changedFields(old, item) : [];
    return old && fields.length ? [{ before: old, after: item, fields }] : [];
  });
  return { added, removed, changed };
}

/** Compare immutable revisions by stable IDs while ignoring object key order. */
export function compareTopologyDrafts<TNode extends { id: string }, TLink extends { id: string }>(
  before: { nodes: readonly TNode[]; links: readonly TLink[] },
  after: { nodes: readonly TNode[]; links: readonly TLink[] },
): TopologyRevisionDiff<TNode, TLink> {
  return { nodes: compareById(before.nodes, after.nodes), links: compareById(before.links, after.links) };
}

/** Resolve a route gateway only when exactly one different Host or Router node owns its address. */
export function resolveRouteNextHop(
  gateway: string | null | undefined,
  sourceHostId: string,
  ownersByAddress: ReadonlyMap<string, readonly RouteNextHopHost[]>,
): RouteNextHopResolution {
  const address = gateway?.trim().toLowerCase();
  if (!address) return { kind: 'DIRECT' };
  const owners = [...new Map((ownersByAddress.get(address) ?? []).map(owner => [owner.id, owner])).values()];
  if (owners.length > 1) return { kind: 'AMBIGUOUS' };
  if (!owners.length) return { kind: 'UNMATCHED' };
  if (owners[0].id === sourceHostId) return { kind: 'SELF' };
  return { kind: 'MATCHED', host: owners[0] };
}

/** Return distinct gateways that have no modeled Host or Router owner yet. */
export function unmatchedRouteGateways(
  routes: readonly ObservedRoute[],
  sourceHostIds: ReadonlyMap<string, string>,
  ownersByAddress: ReadonlyMap<string, readonly RouteNextHopHost[]>,
): string[] {
  const unmatched = new Set<string>();
  for (const route of routes) {
    const address = route.gateway?.trim().toLowerCase();
    const sourceHostId = sourceHostIds.get(route.sourceHostId);
    if (!address || !sourceHostId) continue;
    if (resolveRouteNextHop(address, sourceHostId, ownersByAddress).kind === 'UNMATCHED') {
      unmatched.add(address);
    }
  }
  return [...unmatched].sort((left, right) => left.localeCompare(right));
}

const defaultPort = (id: string, direction: TopologyPort['direction'], layer: TopologyPort['layer'] = 'L4', protocols: TopologyPort['protocols'] = ['TCP', 'UDP']): TopologyPort => ({ id, direction, layer, protocols });
const portsFor = (kind: TopologyNodeKind, inputs: number, outputs: number): TopologyPort[] => {
  const layer = kind === 'SWITCH' || kind === 'PACKET_TAP' || kind === 'FIREWALL' ? 'L2' : 'L4';
  const protocols: TopologyPort['protocols'] = kind === 'SWITCH' || kind === 'PACKET_TAP' || kind === 'FIREWALL' ? ['ETHERNET'] : ['TCP', 'UDP'];
  return [
    ...Array.from({ length: inputs }, (_, index) => defaultPort(`in-${index + 1}`, 'INPUT', layer, protocols)),
    ...Array.from({ length: outputs }, (_, index) => defaultPort(`out-${index + 1}`, 'OUTPUT', layer, protocols)),
  ];
};
const protocolLayer: Record<TopologyPort['layer'], TopologyPort['protocols'][number][]> = {
  L2: ['ETHERNET'], L3: ['IP'], L4: ['TCP', 'UDP', 'SCTP'], L7: ['HTTP', 'HTTPS'],
};

export const nodePresets: Record<TopologyNodeKind, { name: string; inputs: number; outputs: number; description: string }> = {
  GATEWAY: { name: '公网 Gateway', inputs: 0, outputs: 1, description: '公网流量源' },
  HOST: { name: 'NAT Host', inputs: 1, outputs: 1, description: '受管主机' },
  ROUTER: { name: '网络路由器', inputs: 1, outputs: 1, description: '按接口 IPv4 地址与静态路由转发' },
  FIREWALL: { name: '虚拟防火墙', inputs: 1, outputs: 1, description: '在隔离 Linux bridge 上按端口、协议与目标端口过滤帧' },
  PACKET_TAP: { name: 'Packet Tap', inputs: 1, outputs: 2, description: '将入站数据包复制到多个观测出口' },
  SERVICE: { name: 'K3s Service', inputs: 1, outputs: 0, description: '服务终点' },
  SWITCH: { name: '虚拟交换机', inputs: 4, outputs: 4, description: '所有 L2 接口均支持输入和输出连接' },
  MERGE: { name: '多合一转发器', inputs: 3, outputs: 1, description: '多个入口汇聚至一个出口' },
  SPLIT: { name: '一分多转发器', inputs: 1, outputs: 3, description: '一个入口分发至多个出口' },
  FORWARDER: { name: '多进多出转发器', inputs: 3, outputs: 3, description: '所有入口的流量都会被发送给所有出口' },
  LOAD_BALANCER: { name: '流量分配器', inputs: 1, outputs: 3, description: '按流量策略分配至多个出口' },
  MIRROR: { name: '流量镜像器', inputs: 1, outputs: 2, description: '将入口流量复制到业务与观测出口' },
  CUSTOM: { name: '自定义网络设备', inputs: 1, outputs: 1, description: '接口声明之外的设备能力需人工确认' },
  FOLDER: { name: 'K3s 集群文件夹', inputs: 1, outputs: 1, description: '双击进入集群拓扑与资源视图' },
};
export function createTopologyNode(kind: TopologyNodeKind, position: { x: number; y: number }): TopologyNode {
  const preset = nodePresets[kind];
  const ports = portsFor(kind, preset.inputs, preset.outputs);
  return { id: createUuid(), kind, name: preset.name, position, inputCount: preset.inputs, outputCount: preset.outputs, ports, parentId: null, assetId: null, clusterId: null, ipAddress: null, providerId: null, switchBackend: kind === 'SWITCH' ? 'BRIDGE' : null, firewallPolicy: kind === 'FIREWALL' ? { defaultAction: 'DROP', rules: [] } : null, routerConfiguration: null, loadBalancerConfiguration: kind === 'LOAD_BALANCER' ? { outputWeights: ports.filter(port => port.direction === 'OUTPUT').map(port => ({ outputPort: port.id, weight: 100 })) } : null };
}
export function initialTopology(): TopologyDraftWrite {
  return { nodes: [createTopologyNode('GATEWAY', { x: 0, y: 100 }), createTopologyNode('HOST', { x: 350, y: 180 }), createTopologyNode('SERVICE', { x: 700, y: 100 })], links: [] };
}
export function validNodeShape(node: TopologyNode): boolean {
  const i = node.inputCount, o = node.outputCount;
  if (![i, o].every(n => Number.isInteger(n) && n >= 0 && n <= 16)) return false;
  if (!Array.isArray(node.ports) || node.ports.length !== i + o) return false;
  const expected = portsFor(node.kind, i, o);
  if (node.ports.some((port, index) => !port || port.id !== expected[index].id || port.direction !== expected[index].direction
    || !['L2', 'L3', 'L4', 'L7'].includes(port.layer) || !Array.isArray(port.protocols) || !port.protocols.length
    || port.protocols.some(protocol => !protocolLayer[port.layer]?.includes(protocol)))) return false;
  const firstPort = node.ports[0];
  const firstProtocols = firstPort ? [...firstPort.protocols].sort().join(',') : '';
  const uniformPortCapability = !!firstPort && node.ports.every(port => port.layer === firstPort.layer
    && [...port.protocols].sort().join(',') === firstProtocols);
  const outputPorts = node.ports.filter(port => port.direction === 'OUTPUT').map(port => port.id);
  const pairedSwitchPorts = node.kind === 'SWITCH'
    ? Array.from({ length: Math.min(i, o) }, (_, index) => [
      node.ports.find(port => port.id === `in-${index + 1}`),
      node.ports.find(port => port.id === `out-${index + 1}`),
    ] as const)
    : [];
  const validSwitchPortPairs = pairedSwitchPorts.every(([input, output]) => !!input && !!output
    && input.layer === output.layer
    && [...input.protocols].sort().join(',') === [...output.protocols].sort().join(','));
  const loadBalancerConfiguration = node.loadBalancerConfiguration;
  const validLoadBalancerConfiguration = loadBalancerConfiguration == null
    ? true
    : node.kind === 'LOAD_BALANCER'
      && Array.isArray(loadBalancerConfiguration.outputWeights)
      && loadBalancerConfiguration.outputWeights.length === outputPorts.length
      && outputPorts.every(outputPort => loadBalancerConfiguration.outputWeights.some(weight =>
        weight.outputPort === outputPort && Number.isInteger(weight.weight)
          && weight.weight >= 0 && weight.weight <= 100))
      && new Set(loadBalancerConfiguration.outputWeights.map(weight => weight.outputPort)).size === outputPorts.length;
  if (!validLoadBalancerConfiguration || !validSwitchPortPairs) return false;
  switch (node.kind) {
    case 'GATEWAY': return i === 0 && o >= 1;
    case 'SERVICE': return i >= 1 && o === 0;
    case 'HOST': return i >= 1 && o >= 1;
    case 'ROUTER': return i >= 1 && o >= 1;
    case 'FIREWALL': return i >= 1 && o >= 1 && uniformPortCapability;
    case 'PACKET_TAP': return i === 1 && o >= 2 && uniformPortCapability;
    case 'MERGE': return i >= 2 && o === 1;
    case 'SPLIT': return i === 1 && o >= 2;
    case 'LOAD_BALANCER': return i >= 1 && o >= 2;
    case 'MIRROR': return i === 1 && o >= 2;
    case 'CUSTOM': return i >= 1 && o >= 1;
    case 'SWITCH': return i >= 2 && o >= 2 && i === o;
    case 'FORWARDER': return i >= 2 && o >= 2;
    case 'FOLDER': return i >= 1 && o >= 1;
    default: return false;
  }
}

/** Treat matching switch input/output declarations as the same physical interface. */
export function topologyPhysicalPortKey(node: Pick<TopologyNode, 'id' | 'kind'> | undefined, portId: string): string {
  const switchPort = /^(?:in|out)-([1-9]|1[0-6])$/.exec(portId);
  const physicalPortId = node?.kind === 'SWITCH' && switchPort ? `interface-${switchPort[1]}` : portId;
  return `${node?.id ?? ''}:${physicalPortId}`;
}

/** A provider is selectable only when it declares the device, port, and full safe lifecycle. */
export function providerSupportsDevice(
  provider: NetworkProvider,
  kind: TopologyNodeKind,
  layer: TopologyLayer,
  protocols: readonly TopologyProtocol[],
): boolean {
  const requiredOperations = ['PLAN', 'VALIDATE', 'APPLY', 'VERIFY', 'COMPENSATE'];
  return provider.capabilityDetails?.some(capability =>
    capability.deviceKinds.includes(kind)
      && capability.layers.includes(layer)
      && protocols.every(protocol => capability.protocols.includes(protocol))
      && requiredOperations.every(operation => capability.operations.includes(operation))) ?? false;
}
export function connectionError(nodes: TopologyNode[], links: TopologyLink[], candidate: TopologyLink): string | null {
  const source = nodes.find(n => n.id === candidate.sourceNodeId), target = nodes.find(n => n.id === candidate.targetNodeId);
  if (!source || !target) return '连接引用的节点不存在';
  if (source.id === target.id) return '不允许节点连接自身';
  if ((source.parentId ?? null) !== (target.parentId ?? null)) return '不同拓扑层级之间不能直接连接';
  if (!/^out-([1-9]|1[0-6])$/.test(candidate.sourcePort) || !/^in-([1-9]|1[0-6])$/.test(candidate.targetPort)) return '只能从绿色出口连接到蓝色入口';
  if (Number(candidate.sourcePort.slice(4)) > source.outputCount || Number(candidate.targetPort.slice(3)) > target.inputCount) return '连接引用的端口不存在';
  const sourcePort = source.ports.find(port => port.id === candidate.sourcePort), targetPort = target.ports.find(port => port.id === candidate.targetPort);
  if (!['L2', 'L3', 'L4', 'L7'].includes(candidate.layer) || !protocolLayer[candidate.layer]?.includes(candidate.protocol) || sourcePort?.layer !== candidate.layer || targetPort?.layer !== candidate.layer
    || !sourcePort.protocols.includes(candidate.protocol) || !targetPort.protocols.includes(candidate.protocol)) return '连接的网络层与协议必须同时受两端口支持';
  if ([source, target].some(node => node.kind === 'LOAD_BALANCER') && !['L4', 'L7'].includes(candidate.layer)) return '流量分配器只支持 L4/L7 流量接口，不能连接 L2/L3 端口';
  const occupiedPorts = new Set(links.flatMap(link => [
    topologyPhysicalPortKey(nodes.find(node => node.id === link.sourceNodeId), link.sourcePort),
    topologyPhysicalPortKey(nodes.find(node => node.id === link.targetNodeId), link.targetPort),
  ]));
  if (occupiedPorts.has(topologyPhysicalPortKey(source, candidate.sourcePort))
    || occupiedPorts.has(topologyPhysicalPortKey(target, candidate.targetPort))) return '接口已被占用，请增加接口或添加转发节点';
  const visited = new Set<string>();
  const reaches = (id: string): boolean => { if (id === source.id) return true; if (visited.has(id)) return false; visited.add(id); return links.filter(l => l.sourceNodeId === id).some(l => reaches(l.targetNodeId)); };
  return reaches(target.id) ? '该连接将形成转发环路' : null;
}

/**
 * The port declaration proves a protocol match, while traffic-device execution
 * semantics remain provider-specific. Ask an operator before connecting through
 * devices whose runtime capabilities are not declared by the selected provider.
 */
export function connectionReviewMessage(nodes: readonly TopologyNode[], candidate: TopologyLink): string | null {
  const source = nodes.find(node => node.id === candidate.sourceNodeId);
  const target = nodes.find(node => node.id === candidate.targetNodeId);
  if (!source || !target) return null;
  const needsReview = new Set<TopologyNodeKind>([
    'SWITCH', 'FIREWALL', 'PACKET_TAP', 'MERGE', 'SPLIT', 'FORWARDER', 'LOAD_BALANCER', 'MIRROR', 'CUSTOM',
  ]);
  const devices = [source, target].filter(node => needsReview.has(node.kind));
  if (!devices.length) return null;
  const names = devices.map(node => `“${node.name}”`).join('、');
  return `${names} 的端口层与协议声明匹配，但当前拓扑没有该设备的 Provider 能力清单；请人工确认流量方向、分配/复制方式及目标接口支持此连接。`;
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const keysAre = (value: Record<string, unknown>, keys: string[]) => Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
/** Runtime validation is shared by the editor and Mock boundary. */
export function draftError(value: unknown): string | null {
  if (!record(value) || !keysAre(value, ['nodes', 'links']) || !Array.isArray(value.nodes) || !Array.isArray(value.links)) return '草稿必须仅包含 nodes 与 links 数组';
  if (value.nodes.length > 100 || value.links.length > 800) return '草稿最多包含 100 个节点与 800 条连接';
  const ids = new Set<string>();
  for (const n of value.nodes) {
    const requiredNodeKeys = ['id', 'kind', 'name', 'position', 'inputCount', 'outputCount', 'ports'];
    const optionalNodeKeys = ['parentId', 'assetId', 'clusterId', 'ipAddress', 'description', 'providerId', 'switchBackend', 'firewallPolicy', 'routerConfiguration', 'loadBalancerConfiguration'];
    if (!record(n) || requiredNodeKeys.some(key => !Object.hasOwn(n, key)) || Object.keys(n).some(key => !requiredNodeKeys.includes(key) && !optionalNodeKeys.includes(key)) || typeof n.id !== 'string' || !uuid.test(n.id) || ids.has(n.id)) return '节点 ID 必须为唯一 UUID，且不能包含未定义字段';
    ids.add(n.id);
    if (typeof n.name !== 'string' || !n.name.trim() || [...n.name].length > 100 || !record(n.position) || !keysAre(n.position, ['x', 'y']) || ![n.position.x, n.position.y].every(v => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 10000)) return '节点名称或坐标无效';
    if (!Array.isArray(n.ports) || n.ports.some(port => !record(port) || !keysAre(port, ['id', 'direction', 'layer', 'protocols']) || typeof port.id !== 'string' || !['INPUT', 'OUTPUT'].includes(String(port.direction)) || !['L2', 'L3', 'L4', 'L7'].includes(String(port.layer)) || !Array.isArray(port.protocols) || !port.protocols.length)) return '端口必须包含唯一 ID、方向、网络层与协议';
    if (!validNodeShape(n as unknown as TopologyNode)) return '节点种类或入口/出口数量不符合约束';
    const parentId = typeof n.parentId === 'string' ? n.parentId : null;
    if (n.parentId !== undefined && n.parentId !== null && (typeof n.parentId !== 'string' || !uuid.test(n.parentId))) return '父文件夹 ID 无效';
    if (n.assetId !== undefined && n.assetId !== null && (typeof n.assetId !== 'string' || !uuid.test(n.assetId))) return '主机资产 ID 无效';
    if (n.assetId != null && n.kind !== 'HOST' && n.kind !== 'GATEWAY') return '只有主机或 Gateway 节点可以绑定主机资产';
    if (n.clusterId !== undefined && n.clusterId !== null && (typeof n.clusterId !== 'string' || !uuid.test(n.clusterId))) return 'Kubernetes 集群 ID 无效';
    if (n.clusterId != null && n.kind !== 'FOLDER') return '只有文件夹节点可以关联 Kubernetes 集群';
    if (n.ipAddress !== undefined && n.ipAddress !== null && (typeof n.ipAddress !== 'string' || n.kind !== 'ROUTER' || !validIpAddress(n.ipAddress))) return '下一跳 IP 只能设置在 Router 节点上，且必须是有效 IPv4/IPv6 地址';
    if (n.description !== undefined && n.description !== null && (typeof n.description !== 'string' || [...n.description].length > 500)) return '节点用途说明最多 500 个 Unicode 字符';
    if (n.providerId !== undefined && n.providerId !== null && (typeof n.providerId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(n.providerId))) return '网络 Provider ID 格式无效';
    if (n.switchBackend !== undefined && n.switchBackend !== null
      && (n.kind !== 'SWITCH' || !['BRIDGE', 'OPENVSWITCH'].includes(String(n.switchBackend)))) return '交换机后端只能为 SWITCH 选择 BRIDGE 或 OPENVSWITCH';
    if (n.firewallPolicy !== undefined && n.firewallPolicy !== null) {
      if (n.kind !== 'FIREWALL' || !record(n.firewallPolicy) || !keysAre(n.firewallPolicy, ['defaultAction', 'rules'])
        || !['ACCEPT', 'DROP'].includes(String(n.firewallPolicy.defaultAction)) || !Array.isArray(n.firewallPolicy.rules) || n.firewallPolicy.rules.length > 64) return '防火墙策略结构或节点种类无效';
      const matches = new Set<string>();
      for (const rule of n.firewallPolicy.rules) {
        if (!record(rule) || !keysAre(rule, ['inputPort', 'outputPort', 'protocol', 'destinationPort', 'action'])
          || typeof rule.inputPort !== 'string' || !/^in-([1-9]|1[0-6])$/.test(rule.inputPort)
          || typeof rule.outputPort !== 'string' || !/^out-([1-9]|1[0-6])$/.test(rule.outputPort)
          || !['TCP', 'UDP', 'SCTP'].includes(String(rule.protocol))
          || !Number.isInteger(rule.destinationPort) || Number(rule.destinationPort) < 1 || Number(rule.destinationPort) > 65535
          || !['ACCEPT', 'DROP'].includes(String(rule.action))) return '防火墙规则字段无效';
        if (!n.ports.some(port => port.id === rule.inputPort && port.direction === 'INPUT')
          || !n.ports.some(port => port.id === rule.outputPort && port.direction === 'OUTPUT')) return '防火墙规则引用了不存在的入口或出口';
        const key = `${rule.inputPort}:${rule.outputPort}:${rule.protocol}:${rule.destinationPort}`;
        if (matches.has(key)) return '防火墙规则匹配条件不能重复';
        matches.add(key);
      }
    }
    if (n.routerConfiguration !== undefined && n.routerConfiguration !== null) {
      const config = n.routerConfiguration;
      if (n.kind !== 'ROUTER' || !record(config) || !keysAre(config, ['interfaces', 'routes'])
        || !Array.isArray(config.interfaces) || config.interfaces.length > 16
        || !Array.isArray(config.routes) || config.routes.length > 64) return 'Router 配置必须包含最多 16 个接口地址与 64 条静态路由';
      const configuredPorts = new Set<string>();
      const subnets = new Map<string, { network: number; prefix: number }>();
      for (const iface of config.interfaces) {
        if (!record(iface) || !keysAre(iface, ['port', 'address']) || typeof iface.port !== 'string'
          || typeof iface.address !== 'string' || !n.ports.some(port => port.id === iface.port)
          || !parseIpv4Cidr(iface.address) || configuredPorts.has(iface.port)) return 'Router 接口必须引用唯一端口并填写有效 IPv4 CIDR';
        configuredPorts.add(iface.port);
        const parsed = parseIpv4Cidr(iface.address);
        if (parsed) subnets.set(iface.port, parsed);
      }
      const destinations = new Set<string>();
      for (const route of config.routes) {
        if (!record(route) || !keysAre(route, ['destination', 'gateway', 'outputPort'])
          || typeof route.destination !== 'string' || !canonicalIpv4Network(route.destination)
          || route.gateway !== null && (typeof route.gateway !== 'string' || !validIpv4(route.gateway))
          || typeof route.outputPort !== 'string' || !configuredPorts.has(route.outputPort)
          || !n.ports.some(port => port.id === route.outputPort && port.direction === 'OUTPUT')
          || destinations.has(route.destination)) return 'Router 静态路由必须引用已配置接口并使用唯一 IPv4 网段';
        destinations.add(route.destination);
        const destination = parseIpv4Cidr(route.destination);
        if (destination && [...subnets.values()].some(subnet => subnet.network === destination.network && subnet.prefix === destination.prefix)) return '静态路由不能覆盖 Router 接口的直连网段';
        if (typeof route.gateway === 'string') {
          const subnet = subnets.get(route.outputPort);
          const gateway = ipv4Number(route.gateway);
          if (!subnet || gateway === null || ((gateway & maskForPrefix(subnet.prefix)) >>> 0) !== subnet.network) return 'Router 下一跳必须位于所选接口的直连网段';
        }
      }
    }
    if (n.loadBalancerConfiguration !== undefined && n.loadBalancerConfiguration !== null) {
      if (!record(n.loadBalancerConfiguration) || !keysAre(n.loadBalancerConfiguration, ['outputWeights'])
          || !Array.isArray(n.loadBalancerConfiguration.outputWeights)
          || n.loadBalancerConfiguration.outputWeights.some(weight => !record(weight)
            || !keysAre(weight, ['outputPort', 'weight']) || typeof weight.outputPort !== 'string'
            || !Number.isInteger(weight.weight) || Number(weight.weight) < 0 || Number(weight.weight) > 100)) {
        return '流量分配器出口权重必须是 0–100 的整数';
      }
      if (n.kind !== 'LOAD_BALANCER') return '只有流量分配器可以配置出口权重';
    }
    if (parentId === n.id) return '文件夹不能包含自身';
  }
  const nodesById = new Map((value.nodes as TopologyNode[]).map(n => [n.id, n]));
  const linkedAssets = new Set<string>();
  for (const n of value.nodes as TopologyNode[]) {
    let parentId = n.parentId ?? null;
    const seen = new Set<string>([n.id]);
    let depth = 0;
    while (parentId) {
      const parent = nodesById.get(parentId);
      if (!parent || parent.kind !== 'FOLDER') return '父节点必须是存在的文件夹';
      if (seen.has(parentId)) return '文件夹层级不能形成循环';
      seen.add(parentId); parentId = parent.parentId ?? null;
      if (++depth > 8) return '文件夹最多嵌套 8 层';
    }
    if (n.assetId) {
      if (n.kind !== 'HOST' && n.kind !== 'GATEWAY') return '只有主机或 Gateway 节点可以绑定主机资产';
      if (linkedAssets.has(n.assetId)) return '同一主机资产只能添加一次';
      linkedAssets.add(n.assetId);
    }
  }
  const links: TopologyLink[] = []; ids.clear();
  for (const l of value.links) {
    if (!record(l) || !keysAre(l, ['id', 'sourceNodeId', 'sourcePort', 'targetNodeId', 'targetPort', 'layer', 'protocol', 'status']) || typeof l.id !== 'string' || !uuid.test(l.id) || ids.has(l.id) || l.status !== 'UNVERIFIED' || typeof l.sourcePort !== 'string' || typeof l.targetPort !== 'string' || typeof l.layer !== 'string' || typeof l.protocol !== 'string') return '连接 ID、端口或未验证状态无效';
    ids.add(l.id);
    const error = connectionError(value.nodes as TopologyNode[], links, l as unknown as TopologyLink);
    if (error) return error;
    links.push(l as unknown as TopologyLink);
  }
  for (const node of value.nodes as TopologyNode[]) {
    if (node.kind !== 'ROUTER' || !node.routerConfiguration) continue;
    const connectedPorts = new Set(links.flatMap(link => [
      ...(link.targetNodeId === node.id ? [link.targetPort] : []),
      ...(link.sourceNodeId === node.id ? [link.sourcePort] : []),
    ]));
    if (node.routerConfiguration.interfaces.some(iface => !connectedPorts.has(iface.port))) return 'Router 接口地址不能配置在未连接的端口上';
    if (node.routerConfiguration.interfaces.length && [...connectedPorts].some(port => !node.routerConfiguration?.interfaces.some(iface => iface.port === port))) return 'Router 的每个已连接接口都必须配置 IPv4 地址';
  }
  return null;
}

function ipv4Number(value: string): number | null {
  if (!validIpv4(value)) return null;
  return value.split('.').reduce((result, octet) => ((result << 8) | Number(octet)) >>> 0, 0);
}

function validIpv4(value: string): boolean {
  const octets = value.split('.');
  return octets.length === 4 && octets.every(part => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255);
}

function maskForPrefix(prefix: number): number {
  return prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0;
}

function parseIpv4Cidr(value: string): { network: number; prefix: number } | null {
  const match = /^(\d{1,3}(?:\.\d{1,3}){3})\/(\d|[12]\d|3[0-2])$/.exec(value);
  if (!match || !validIpv4(match[1])) return null;
  const address = ipv4Number(match[1]);
  if (address === null) return null;
  const prefix = Number(match[2]);
  return { network: (address & maskForPrefix(prefix)) >>> 0, prefix };
}

function canonicalIpv4Network(value: string): boolean {
  const parsed = parseIpv4Cidr(value);
  const address = value.split('/')[0];
  const number = ipv4Number(address);
  return parsed !== null && number !== null && parsed.network === number;
}

function validIpAddress(value: string): boolean {
  const address = value.trim();
  if (!address || address.length > 45 || !/^[0-9a-f:.]+$/i.test(address)) return false;
  if (!address.includes(':')) {
    const octets = address.split('.');
    return octets.length === 4 && octets.every(part => /^\d{1,3}$/.test(part) && Number(part) <= 255);
  }
  try {
    new URL(`http://[${address}]/`);
    return true;
  } catch {
    return false;
  }
}
