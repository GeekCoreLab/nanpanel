import { createUuid } from './uuid.ts';
import type { TopologyDraftWrite, TopologyLink, TopologyNode, TopologyNodeKind, TopologyPort } from './types';

const defaultPort = (id: string, direction: TopologyPort['direction']): TopologyPort => ({ id, direction, layer: 'L4', protocols: ['TCP', 'UDP'] });
const portsFor = (inputs: number, outputs: number): TopologyPort[] => [
  ...Array.from({ length: inputs }, (_, index) => defaultPort(`in-${index + 1}`, 'INPUT')),
  ...Array.from({ length: outputs }, (_, index) => defaultPort(`out-${index + 1}`, 'OUTPUT')),
];
const protocolLayer: Record<TopologyPort['layer'], TopologyPort['protocols'][number][]> = {
  L2: ['ETHERNET'], L3: ['IP'], L4: ['TCP', 'UDP', 'SCTP'], L7: ['HTTP', 'HTTPS'],
};

export const nodePresets: Record<TopologyNodeKind, { name: string; inputs: number; outputs: number; description: string }> = {
  GATEWAY: { name: '公网 Gateway', inputs: 0, outputs: 1, description: '公网流量源' },
  HOST: { name: 'NAT Host', inputs: 1, outputs: 1, description: '受管主机' },
  SERVICE: { name: 'K3s Service', inputs: 1, outputs: 0, description: '服务终点' },
  SWITCH: { name: '虚拟交换机', inputs: 4, outputs: 4, description: '多端口逻辑互联' },
  MERGE: { name: '多合一转发器', inputs: 3, outputs: 1, description: '多个入口汇聚至一个出口' },
  SPLIT: { name: '一分多转发器', inputs: 1, outputs: 3, description: '一个入口分发至多个出口' },
  FORWARDER: { name: '多进多出转发器', inputs: 3, outputs: 3, description: '所有入口的流量都会被发送给所有出口' },
  FOLDER: { name: 'K3s 集群文件夹', inputs: 1, outputs: 1, description: '双击进入集群拓扑与资源视图' },
};
export function createTopologyNode(kind: TopologyNodeKind, position: { x: number; y: number }): TopologyNode {
  const preset = nodePresets[kind];
  return { id: createUuid(), kind, name: preset.name, position, inputCount: preset.inputs, outputCount: preset.outputs, ports: portsFor(preset.inputs, preset.outputs), parentId: null, assetId: null };
}
export function initialTopology(): TopologyDraftWrite {
  return { nodes: [createTopologyNode('GATEWAY', { x: 0, y: 100 }), createTopologyNode('HOST', { x: 350, y: 180 }), createTopologyNode('SERVICE', { x: 700, y: 100 })], links: [] };
}
export function validNodeShape(node: TopologyNode): boolean {
  const i = node.inputCount, o = node.outputCount;
  if (![i, o].every(n => Number.isInteger(n) && n >= 0 && n <= 16)) return false;
  if (!Array.isArray(node.ports) || node.ports.length !== i + o) return false;
  const expected = portsFor(i, o);
  if (node.ports.some((port, index) => !port || port.id !== expected[index].id || port.direction !== expected[index].direction
    || !['L2', 'L3', 'L4', 'L7'].includes(port.layer) || !Array.isArray(port.protocols) || !port.protocols.length
    || port.protocols.some(protocol => !protocolLayer[port.layer]?.includes(protocol)))) return false;
  switch (node.kind) {
    case 'GATEWAY': return i === 0 && o >= 1;
    case 'SERVICE': return i >= 1 && o === 0;
    case 'HOST': return i >= 1 && o >= 1;
    case 'MERGE': return i >= 2 && o === 1;
    case 'SPLIT': return i === 1 && o >= 2;
    case 'SWITCH': case 'FORWARDER': return i >= 2 && o >= 2;
    case 'FOLDER': return i >= 1 && o >= 1;
    default: return false;
  }
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
  if (links.some(l => (l.sourceNodeId === source.id && l.sourcePort === candidate.sourcePort) || (l.targetNodeId === target.id && l.targetPort === candidate.targetPort))) return '端口已被占用，请增加端口或添加转发节点';
  const visited = new Set<string>();
  const reaches = (id: string): boolean => { if (id === source.id) return true; if (visited.has(id)) return false; visited.add(id); return links.filter(l => l.sourceNodeId === id).some(l => reaches(l.targetNodeId)); };
  return reaches(target.id) ? '该连接将形成转发环路' : null;
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
    const optionalNodeKeys = ['parentId', 'assetId'];
    if (!record(n) || requiredNodeKeys.some(key => !Object.hasOwn(n, key)) || Object.keys(n).some(key => !requiredNodeKeys.includes(key) && !optionalNodeKeys.includes(key)) || typeof n.id !== 'string' || !uuid.test(n.id) || ids.has(n.id)) return '节点 ID 必须为唯一 UUID，且不能包含未定义字段';
    ids.add(n.id);
    if (typeof n.name !== 'string' || !n.name.trim() || [...n.name].length > 100 || !record(n.position) || !keysAre(n.position, ['x', 'y']) || ![n.position.x, n.position.y].every(v => typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 10000)) return '节点名称或坐标无效';
    if (!Array.isArray(n.ports) || n.ports.some(port => !record(port) || !keysAre(port, ['id', 'direction', 'layer', 'protocols']) || typeof port.id !== 'string' || !['INPUT', 'OUTPUT'].includes(String(port.direction)) || !['L2', 'L3', 'L4', 'L7'].includes(String(port.layer)) || !Array.isArray(port.protocols) || !port.protocols.length)) return '端口必须包含唯一 ID、方向、网络层与协议';
    if (!validNodeShape(n as unknown as TopologyNode)) return '节点种类或入口/出口数量不符合约束';
    const parentId = typeof n.parentId === 'string' ? n.parentId : null;
    if (n.parentId !== undefined && n.parentId !== null && (typeof n.parentId !== 'string' || !uuid.test(n.parentId))) return '父文件夹 ID 无效';
    if (n.assetId !== undefined && n.assetId !== null && (typeof n.assetId !== 'string' || !uuid.test(n.assetId))) return '主机资产 ID 无效';
    if (n.assetId != null && n.kind !== 'HOST' && n.kind !== 'GATEWAY') return '只有主机或 Gateway 节点可以绑定主机资产';
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
  return null;
}
