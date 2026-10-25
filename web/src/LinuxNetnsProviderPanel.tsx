import { useEffect, useMemo, useState } from 'react';
import { api, isMock } from './lib/api';
import type { Host, HostPage, NetworkProvider, NetworkProviderObservation, NetworkProviderOperation, TopologyDraft } from './lib/types';

const PROVIDER = 'linux-netns';
const CAPABILITY = 'network.topology.reconcile';
const operationStates = ['PLANNED', 'VALIDATED', 'APPLYING', 'APPLIED', 'VERIFIED', 'DRIFTED', 'COMPENSATING', 'COMPENSATED', 'UNKNOWN', 'FAILED'] as const;
type InterfaceMetrics = { rxBytes: number; rxPackets: number; txBytes: number; txPackets: number };
type WireMetrics = { linkId: string; source: InterfaceMetrics; target: InterfaceMetrics };

export default function LinuxNetnsProviderPanel({ ready, expectedEtag }: { ready: boolean; expectedEtag: string }) {
  const [providers, setProviders] = useState<NetworkProvider[]>();
  const [hosts, setHosts] = useState<Host[]>([]);
  const [selectedHostId, setSelectedHostId] = useState('');
  const [reason, setReason] = useState('在每拓扑独立 Linux 网络命名空间中应用桥接拓扑');
  const [operation, setOperation] = useState<NetworkProviderOperation>();
  const [observation, setObservation] = useState<NetworkProviderObservation>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const availableHosts = useMemo(() => hosts.filter(host => host.capabilities.includes(CAPABILITY)), [hosts]);

  async function load() {
    setBusy(true); setError('');
    try {
      const [providerResult, hostResult] = await Promise.all([
        api<NetworkProvider[]>('/network-providers', 'GET', undefined, {}, true),
        api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true),
      ]);
      setProviders(providerResult.data);
      setHosts(hostResult.data.items);
      setSelectedHostId(current => current || hostResult.data.items.find(host => host.capabilities.includes(CAPABILITY))?.id || '');
    } catch (cause) { setError((cause as Error).message); }
    finally { setBusy(false); }
  }

  useEffect(() => { void load(); }, []);

  async function createPlan() {
    if (isMock) { setError('Mock 模式不会执行真实 Linux Provider 操作。'); return; }
    if (!ready) { setError('先保存当前拓扑，再为该已保存 Revision 创建计划。'); return; }
    const host = availableHosts.find(value => value.id === selectedHostId);
    if (!host || host.status !== 'ONLINE' || !host.agentId) { setError('请选择 Agent 在线且报告 Linux netns 能力的执行主机。'); return; }
    if (!reason.trim() || reason.trim().length > 500 || /[\u0000-\u001f\u007f]/.test(reason)) { setError('操作原因必须为 1–500 个无控制字符的字符。'); return; }
    setBusy(true); setError('');
    try {
      const current = await api<TopologyDraft>('/topology/draft', 'GET', undefined, {}, true);
      if (current.etag !== expectedEtag) throw new Error('拓扑 Revision 已在其他会话中变化，请重新读取后再创建计划。');
      const result = await api<NetworkProviderOperation>(`/network-providers/${PROVIDER}/plans`, 'POST', {
        desiredSnapshot: JSON.stringify({ hostId: host.id, hostGeneration: host.generation, reason: reason.trim(), revisionId: current.data.revisionId, generation: current.data.generation }),
      }, {}, true);
      setOperation(result.data);
      setObservation(undefined);
    } catch (cause) { setError((cause as Error).message); }
    finally { setBusy(false); }
  }

  async function step(action: 'validate' | 'apply' | 'verify' | 'compensate') {
    if (!operation) return;
    setBusy(true); setError('');
    try {
      const result = await api<NetworkProviderOperation>(
        `/network-providers/${PROVIDER}/operations/${operation.id}/${action}`, 'POST', undefined, {}, true);
      setOperation(result.data);
      setObservation(undefined);
    } catch (cause) { setError((cause as Error).message); }
    finally { setBusy(false); }
  }

  async function refreshOperation() {
    if (!operation) return;
    setBusy(true); setError('');
    try {
      const result = await api<NetworkProviderOperation>(
        `/network-providers/${PROVIDER}/operations/${operation.id}`, 'GET', undefined, {}, true);
      setOperation(result.data);
    } catch (cause) { setError((cause as Error).message); }
    finally { setBusy(false); }
  }

  async function readWireMetrics() {
    if (!operation) return;
    setBusy(true); setError('');
    try {
      const result = await api<NetworkProviderObservation>(`/network-providers/${PROVIDER}/observations`, 'POST', { resourceKey: operation.id }, {}, true);
      setObservation(result.data);
    } catch (cause) { setError((cause as Error).message); }
    finally { setBusy(false); }
  }

  const canVerify = operation && ['APPLYING', 'APPLIED', 'UNKNOWN', 'VERIFIED', 'DRIFTED'].includes(operation.state);
  const canCompensate = operation && ['APPLYING', 'APPLIED', 'UNKNOWN', 'VERIFIED', 'DRIFTED', 'COMPENSATING'].includes(operation.state);
  const providerLoaded = providers?.some(provider => provider.id === PROVIDER) ?? false;
  let wireMetrics: WireMetrics[] = [];
  let observedAt: string | number | undefined;
  try {
    const snapshot = observation ? JSON.parse(observation.snapshot) as { observedAt?: unknown; linkMetrics?: unknown } : undefined;
    if (typeof snapshot?.observedAt === 'string' || (typeof snapshot?.observedAt === 'number' && Number.isFinite(snapshot.observedAt))) observedAt = snapshot.observedAt;
    if (Array.isArray(snapshot?.linkMetrics)) {
      wireMetrics = snapshot.linkMetrics.filter((wire): wire is WireMetrics => {
        if (!wire || typeof wire !== 'object') return false;
        const value = wire as Partial<WireMetrics>;
        const counters = [value.source, value.target];
        return typeof value.linkId === 'string' && counters.every(counter => counter &&
          [counter.rxBytes, counter.rxPackets, counter.txBytes, counter.txPackets]
            .every(metric => typeof metric === 'number' && Number.isFinite(metric) && metric >= 0));
      });
    }
  } catch { wireMetrics = []; }

  return <section className="netbird-provider linux-netns-provider card" aria-labelledby="linux-netns-provider-title">
    <div className="card-title"><div><h2 id="linux-netns-provider-title">Linux Bridge · Open vSwitch · Router · Firewall · 每拓扑隔离 netns</h2><p>当前实现执行 SWITCH、ROUTER、FIREWALL 与 PACKET_TAP。Open vSwitch 使用每拓扑私有 OVSDB 和 netdev 用户态 datapath；需要执行主机报告 network.topology.ovs，且暂不支持同一 Provider 计划混用 Firewall 或 Packet Tap。Router 支持多个互相独立的 IPv4 VRF 路由表、接口 CIDR 和静态路由（每拓扑最多 64 条），并在隔离 netns 内转发；含 Router 的计划要求执行主机报告 network.topology.vrf。Firewall 使用 bridge-family nftables；Packet Tap 使用 tc mirred 镜像两侧业务帧。</p></div>
      <span className={`badge ${providerLoaded ? 'available' : 'pending'}`}>{providerLoaded ? 'Provider 已装载' : 'Provider 不可用'}</span>
      {isMock && <span className="badge pending">Mock 模式 · 不执行</span>}
    </div>
    {!providerLoaded && <p className="netbird-provider-empty">Core 没有提供内建 Linux netns Provider。</p>}
    {providerLoaded && <div className="netbird-provider-form">
      <label className="field"><span>执行主机</span><select value={selectedHostId} onChange={event => setSelectedHostId(event.target.value)} disabled={busy || isMock}>
        <option value="">选择已报告 netns 能力的主机</option>
        {availableHosts.map(host => <option key={host.id} value={host.id} disabled={host.status !== 'ONLINE' || !host.agentId}>{host.hostname} · {host.status} · generation {host.generation}</option>)}
      </select></label>
      <label className="field"><span>审计原因</span><input value={reason} maxLength={500} onChange={event => setReason(event.target.value)} disabled={busy || isMock} /></label>
      <div className="form-actions"><button type="button" className="secondary" disabled={busy} onClick={() => void load()}>{busy ? '处理中…' : '刷新主机能力'}</button><button type="button" className="primary" disabled={busy || isMock || !ready || !selectedHostId} onClick={() => void createPlan()}>{busy ? '处理中…' : '为已保存 Revision 创建计划'}</button></div>
    </div>}
    {providerLoaded && availableHosts.length === 0 && <p className="netbird-provider-empty">还没有主机报告 <code>{CAPABILITY}</code>。部署 Root Helper 后等待 Agent 心跳，再刷新能力。</p>}
    {operation && <div className="netbird-provider-operation">
      <div className="section-heading"><div><h3>Provider 操作阶段</h3><p><code>{operation.id}</code> · 拓扑 Revision generation {operation.generation}</p></div><span className={`badge ${operation.state === 'VERIFIED' || operation.state === 'COMPENSATED' ? 'available' : operation.state === 'DRIFTED' || operation.state === 'UNKNOWN' || operation.state === 'FAILED' ? 'failed' : 'pending'}`}>{operation.state}</span></div>
      <p className="netbird-provider-detail">{operation.detail}</p>
      <div className="form-actions">
        {operation.state === 'PLANNED' && <button type="button" className="secondary" disabled={busy} onClick={() => void step('validate')}>校验 Revision、主机与 Helper 能力</button>}
        {operation.state === 'VALIDATED' && <button type="button" className="primary" disabled={busy} onClick={() => void step('apply')}>应用隔离 Linux 拓扑</button>}
        {canVerify && <button type="button" className="secondary" disabled={busy} onClick={() => void step('verify')}>验证 Bridge/OVS、Router 与静态路由</button>}
        {canCompensate && <button type="button" className="danger" disabled={busy} onClick={() => void step('compensate')}>补偿并删除该 Revision 命名空间</button>}
        {operation.state === 'VERIFIED' && <button type="button" className="secondary" disabled={busy} onClick={() => void readWireMetrics()}>读取虚拟网线计数器</button>}
        <button type="button" className="secondary" disabled={busy} onClick={() => void refreshOperation()}>刷新操作状态</button>
      </div>
      {observation && <div className="linux-netns-metrics" aria-label="虚拟网线收发计数器">
        <p>Helper 观测于 {new Date(observedAt ?? Date.now()).toLocaleString()}</p>
        {wireMetrics.length ? <div className="linux-netns-metrics-table-wrap"><table><thead><tr><th>虚拟网线</th><th>源端 RX bytes / packets</th><th>源端 TX bytes / packets</th><th>目标端 RX bytes / packets</th><th>目标端 TX bytes / packets</th></tr></thead><tbody>{wireMetrics.map(wire => <tr key={wire.linkId}><td><code>{wire.linkId}</code></td><td>{wire.source.rxBytes.toLocaleString()} / {wire.source.rxPackets.toLocaleString()}</td><td>{wire.source.txBytes.toLocaleString()} / {wire.source.txPackets.toLocaleString()}</td><td>{wire.target.rxBytes.toLocaleString()} / {wire.target.rxPackets.toLocaleString()}</td><td>{wire.target.txBytes.toLocaleString()} / {wire.target.txPackets.toLocaleString()}</td></tr>)}</tbody></table></div> : <p>该 Revision 没有虚拟网线计数器。</p>}
      </div>}
      <span className="sr-only">{operationStates.includes(operation.state) ? operation.state : 'UNKNOWN'}</span>
    </div>}
    {error && <div className="alert error" role="alert">{error}</div>}
    <p className="netbird-provider-note">接口、地址、静态路由、Bridge/OVS 与 Firewall 规则都限制在该拓扑专属 netns；不会配置宿主网卡或宿主路由。当前每个拓扑最多一个 Router；OVS 需要执行主机报告 network.topology.ovs。</p>
  </section>;
}
