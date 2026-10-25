import { useEffect, useState } from 'react';
import { api, isMock } from './lib/api';
import type { NetworkProvider } from './lib/types';

export default function NetworkProviderCapabilities() {
  const [providers, setProviders] = useState<NetworkProvider[]>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function load() {
    setBusy(true);
    setError('');
    try {
      const result = await api<NetworkProvider[]>('/network-providers');
      setProviders(result.data);
    } catch (reason) {
      setError((reason as Error).message);
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return <section className="network-provider-capabilities card" aria-labelledby="network-provider-capabilities-title">
    <div className="card-title">
      <div><h2 id="network-provider-capabilities-title">已加载 Provider 能力</h2><p>设备类型、网络层、协议与完整变更生命周期都必须匹配，才能绑定到拓扑节点</p></div>
      <button type="button" className="secondary" disabled={busy} onClick={() => void load()}>{busy ? '读取中…' : '重新读取能力'}</button>
    </div>
    {isMock && <div className="alert">Mock 示例能力；不会应用到真实设备。</div>}
    {error && <div className="alert error" role="alert">{error}</div>}
    {providers?.length === 0 && !error && <p className="network-provider-capabilities-empty">Core 当前未加载 NetworkProvider。拓扑中的 Firewall、Switch、Router 或 Packet Tap 仍只是期望节点。</p>}
    {providers && providers.length > 0 && <div className="network-provider-capabilities-list">
      {providers.map(provider => <article className="network-provider-capability-provider" key={provider.id}>
        <div className="network-provider-capability-heading"><strong>{provider.name ?? provider.id}</strong><span>{provider.version ?? '版本未知'}</span></div>
        <small><code>{provider.id}</code>{provider.apiVersion && <> · {provider.apiVersion}</>}</small>
        {provider.capabilityDetails?.length ? <ul>
          {provider.capabilityDetails.map(capability => <li key={capability.id}>
            <strong>{capability.id}</strong>
            <span>操作：{capability.operations.length ? capability.operations.join(' · ') : '未声明'}</span>
            <span>设备：{capability.deviceKinds.length ? capability.deviceKinds.join(' · ') : '未声明'}</span>
            <span>网络层：{capability.layers.length ? capability.layers.join(' / ') : '未声明'}</span>
            <span>协议：{capability.protocols.length ? capability.protocols.join(' / ') : '未声明'}</span>
          </li>)}
        </ul> : <p>Provider 未声明细分能力；不能据此确认节点或链路可执行。</p>}
      </article>)}
    </div>}
  </section>;
}
