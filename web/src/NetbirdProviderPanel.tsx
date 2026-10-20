import { useEffect, useMemo, useState } from 'react';
import { api, isMock } from './lib/api';
import type { NetworkProvider, NetworkProviderObservation, NetworkProviderOperation } from './lib/types';

const PROVIDER = 'netbird-groups';
type ObservedGroup = { groupName: string; fingerprint: string; exists: boolean; groupId?: string; peerIds?: string[]; resourceCount?: number };

export default function NetbirdProviderPanel() {
  const [available, setAvailable] = useState<boolean>();
  const [loadError, setLoadError] = useState('');
  const [groupName, setGroupName] = useState('nanpanel-managed-app');
  const [generation, setGeneration] = useState(1);
  const [peerInput, setPeerInput] = useState('');
  const [observation, setObservation] = useState<NetworkProviderObservation>();
  const [operation, setOperation] = useState<NetworkProviderOperation>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const peers = useMemo(() => peerInput.split(/[\s,]+/).map(value => value.trim()).filter(Boolean), [peerInput]);
  const uniquePeers = new Set(peers);
  let observed: ObservedGroup | undefined;
  try { observed = observation ? JSON.parse(observation.snapshot) as ObservedGroup : undefined; } catch { observed = undefined; }

  useEffect(() => {
    let active = true;
    void api<NetworkProvider[]>('/network-providers').then(result => {
      if (active) setAvailable(result.data.some(provider => provider.id === PROVIDER));
    }).catch(reason => { if (active) setLoadError((reason as Error).message); });
    return () => { active = false; };
  }, []);

  async function observeGroup() {
    setBusy(true); setError('');
    try {
      const result = await api<NetworkProviderObservation>(`/network-providers/${PROVIDER}/observations`, 'POST', { resourceKey: groupName });
      setObservation(result.data); setOperation(undefined);
    } catch (reason) { setError((reason as Error).message); }
    finally { setBusy(false); }
  }

  async function createPlan() {
    if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(groupName) || !Number.isSafeInteger(generation) || generation < 1) {
      setError('组名或期望代次无效，请检查格式'); return;
    }
    if (!peers.length || peers.length > 512 || uniquePeers.size !== peers.length || peers.some(peer => !/^[A-Za-z0-9_-]{8,64}$/.test(peer))) {
      setError('请输入 1–512 个有效且不重复的 NetBird peer ID'); return;
    }
    setBusy(true); setError('');
    const desiredSnapshot = JSON.stringify({ groupName, generation, peerIds: peers });
    try {
      const result = await api<NetworkProviderOperation>(`/network-providers/${PROVIDER}/plans`, 'POST', {
        desiredSnapshot,
        ...(observation ? { observedSnapshot: observation.snapshot } : {}),
      });
      setOperation(result.data);
      setGeneration(value => Math.max(value + 1, result.data.generation + 1));
    } catch (reason) { setError((reason as Error).message); }
    finally { setBusy(false); }
  }

  async function step(action: 'validate' | 'apply' | 'verify' | 'compensate') {
    if (!operation) return;
    setBusy(true); setError('');
    try {
      const result = await api<NetworkProviderOperation>(
        `/network-providers/${PROVIDER}/operations/${operation.id}/${action}`, 'POST');
      setOperation(result.data);
    } catch (reason) { setError((reason as Error).message); }
    finally { setBusy(false); }
  }

  const canVerify = operation && ['APPLYING', 'APPLIED', 'UNKNOWN', 'VERIFIED', 'DRIFTED'].includes(operation.state);
  const canCompensate = operation && ['APPLYING', 'APPLIED', 'UNKNOWN', 'VERIFIED', 'DRIFTED', 'COMPENSATING'].includes(operation.state);

  return <section className="netbird-provider card" aria-labelledby="netbird-provider-title">
    <div className="card-title"><div><h2 id="netbird-provider-title">NetBird 受管 peer 组</h2><p>只操作专属前缀分组；每个变更都先观察、校验，再显式应用</p></div>
      {available === true && <span className="badge available">Provider 已装载</span>}{available === false && <span className="badge pending">Provider 未装载</span>}{isMock && <span className="badge pending">Mock 示例</span>}
    </div>
    {loadError && <div className="alert error" role="alert">{loadError}</div>}
    {available === false && <p className="netbird-provider-empty">Core 未启用 NetBird Provider。真实 API 凭据只配置在 Core 服务端环境中。</p>}
    {available && <>
      <div className="netbird-provider-form">
        <label className="field"><span>受管组名（需匹配 Core 配置的受管前缀）</span><input value={groupName} maxLength={63} onChange={event => { setGroupName(event.target.value); setObservation(undefined); }} /></label>
        <label className="field"><span>期望代次</span><input type="number" min={1} max={2147483647} value={generation} onChange={event => setGeneration(Number(event.target.value))} /></label>
        <label className="field netbird-peer-field"><span>Peer ID（每行一个，最多 512 个）</span><textarea value={peerInput} rows={3} placeholder="从 NetBird 管理端复制 peer ID" onChange={event => setPeerInput(event.target.value)} /></label>
        <div className="form-actions"><button className="secondary" disabled={busy || !groupName} onClick={() => void observeGroup()}>{busy ? '处理中…' : '读取当前组'}</button><button className="primary" disabled={busy} onClick={() => void createPlan()}>{busy ? '处理中…' : '创建计划'}</button></div>
      </div>
      {observed && <div className="netbird-provider-observation" role="status"><strong>{observed.exists ? '当前分组存在' : '当前分组不存在'}</strong>{observed.exists && <><span>{observed.peerIds?.length ?? 0} 个 peer</span><span>{observed.resourceCount ?? 0} 个既有资源</span><code>{observed.peerIds?.join(', ')}</code></>}<code>{observed.fingerprint}</code></div>}
      {operation && <div className="netbird-provider-operation">
        <div className="section-heading"><div><h3>操作阶段</h3><p><code>{operation.id}</code> · 第 {operation.generation} 代</p></div><span className={`badge ${operation.state === 'VERIFIED' || operation.state === 'COMPENSATED' ? 'available' : operation.state === 'DRIFTED' || operation.state === 'UNKNOWN' ? 'failed' : 'pending'}`}>{operation.state}</span></div>
        <p className="netbird-provider-detail">{operation.detail}</p>
        <div className="form-actions">
          {operation.state === 'PLANNED' && <button className="secondary" disabled={busy} onClick={() => void step('validate')}>校验外部状态</button>}
          {operation.state === 'VALIDATED' && <button className="primary" disabled={busy} onClick={() => void step('apply')}>应用变更</button>}
          {canVerify && <button className="secondary" disabled={busy} onClick={() => void step('verify')}>验证结果</button>}
          {canCompensate && <button className="danger" disabled={busy} onClick={() => void step('compensate')}>补偿变更</button>}
        </div>
      </div>}
    </>}
    {error && <div className="alert error" role="alert">{error}</div>}
    <p className="netbird-provider-note">peer 组协调不会配置 NetBird policy、路由、TLS 公网入口或 Kubernetes Istio 策略。应用后必须验证；遇到漂移时 Provider 会拒绝覆盖。</p>
  </section>;
}
