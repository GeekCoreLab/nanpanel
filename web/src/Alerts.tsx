import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import type {
  AlertChannel, AlertChannelList, AlertCondition, AlertEvent, AlertEventList, AlertRule,
  AlertRuleList, FiringAlertList,
  Host, HostPage,
} from './lib/types';
import './alerts.css';

const CONDITION_LABELS: Record<AlertCondition, string> = {
  HOST_NOT_ONLINE: '主机不在线',
  HELPER_UNAVAILABLE: 'Helper 不可用',
  SERVICE_INACTIVE: 'systemd 服务未运行',
};
const SEVERITY_LABELS: Record<string, string> = { CRITICAL: '严重', WARNING: '警告' };

/**
 * 告警：规则建立在 NaNPanel 已经采集的观测之上（主机状态、Helper 可用性、systemd 状态），
 * 列表为实时求值——观测恢复即消失，不引入额外的告警状态机。
 */
export function Alerts({ notify }: { notify: (message: string) => void }) {
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [firing, setFiring] = useState<FiringAlertList>();
  const [hosts, setHosts] = useState<Host[]>([]);
  const [loading, setLoading] = useState(!isMock);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供告警接口，切换到 Live API 后可定义规则并查看实时告警。' : '');
  const [name, setName] = useState('');
  const [condition, setCondition] = useState<AlertCondition>('HOST_NOT_ONLINE');
  const [hostId, setHostId] = useState('');
  const [unitName, setUnitName] = useState('');
  const [severity, setSeverity] = useState<'WARNING' | 'CRITICAL'>('WARNING');
  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [channelId, setChannelId] = useState('');
  const [channelName, setChannelName] = useState('');
  const [channelUrl, setChannelUrl] = useState('');
  const [notifyRecovery, setNotifyRecovery] = useState(true);
  const [events, setEvents] = useState<AlertEvent[]>([]);

  const load = useCallback(async () => {
    if (isMock) return;
    setLoading(true);
    try {
      const [ruleResult, alertResult, channelResult, eventResult] = await Promise.all([
        api<AlertRuleList>('/alert-rules', 'GET', undefined, {}, true),
        api<FiringAlertList>('/alerts', 'GET', undefined, {}, true),
        api<AlertChannelList>('/alert-channels', 'GET', undefined, {}, true),
        api<AlertEventList>('/alert-events?limit=20', 'GET', undefined, {}, true),
      ]);
      setRules(ruleResult.data.items);
      setFiring(alertResult.data);
      setChannels(channelResult.data.items);
      setEvents(eventResult.data.items);
      setError('');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isMock) return;
    void load();
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => setHosts(result.data.items))
      .catch(() => {});
  }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    setBusy('create');
    try {
      await api<AlertRule>('/alert-rules', 'POST', {
        name: name.trim(),
        condition,
        hostId: hostId || null,
        unitName: condition === 'SERVICE_INACTIVE' ? unitName.trim() : null,
        severity,
        channelId: channelId || null,
      }, {}, true);
      notify(`告警规则「${name.trim()}」已创建`);
      setName('');
      setUnitName('');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function createChannel(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    setBusy('channel');
    try {
      await api<AlertChannel>('/alert-channels', 'POST', {
        name: channelName.trim(), url: channelUrl.trim(), notifyRecovery,
      }, {}, true);
      notify(`通知渠道「${channelName.trim()}」已创建`);
      setChannelName('');
      setChannelUrl('');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function testChannel(channel: AlertChannel) {
    setBusy(`test:${channel.id}`);
    try {
      await api(`/alert-channels/${channel.id}/test`, 'POST', undefined, {}, true);
      notify(`测试通知已投递到「${channel.name}」`);
    } catch (cause) {
      setError(`测试投递失败：${(cause as Error).message}`);
    } finally {
      setBusy('');
      await load();
    }
  }

  async function removeChannel(channel: AlertChannel) {
    if (!window.confirm(`删除通知渠道「${channel.name}」？绑定它的规则将不再通知。`)) return;
    setBusy(channel.id);
    try {
      await api(`/alert-channels/${channel.id}`, 'DELETE', undefined, {}, true);
      notify(`通知渠道「${channel.name}」已删除`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function remove(rule: AlertRule) {
    if (!window.confirm(`删除告警规则「${rule.name}」？`)) return;
    setBusy(rule.id);
    try {
      await api(`/alert-rules/${rule.id}`, 'DELETE', undefined, {}, true);
      notify(`告警规则「${rule.name}」已删除`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  return <section className="alerts-workspace">
    <header className="alerts-header">
      <div><h2>告警</h2>
        <p>基于已采集的观测实时求值：主机状态、Helper 可用性与 systemd 单元状态。观测恢复后告警立即消失，不保存历史告警状态。</p></div>
    </header>
    {error && <div className="alert error" role="alert">{error}</div>}
    <div className="alerts-grid">
      <form className="card alerts-form" onSubmit={create}>
        <span className="eyebrow">NEW RULE</span>
        <h3>新增规则</h3>
        <label className="field"><span>名称</span>
          <input value={name} onChange={event => setName(event.target.value)} required
            placeholder="例如：生产主机离线" /></label>
        <label className="field"><span>条件</span>
          <select value={condition} onChange={event => setCondition(event.target.value as AlertCondition)}>
            {Object.entries(CONDITION_LABELS).map(([value, label]) =>
              <option key={value} value={value}>{label}</option>)}
          </select></label>
        {condition === 'SERVICE_INACTIVE' && <label className="field"><span>systemd 单元</span>
          <input value={unitName} onChange={event => setUnitName(event.target.value)} required
            placeholder="sshd.service" /></label>}
        <label className="field"><span>主机（留空=全部）</span>
          <select value={hostId} onChange={event => setHostId(event.target.value)}>
            <option value="">全部主机</option>
            {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname}</option>)}
          </select></label>
        <label className="field"><span>级别</span>
          <select value={severity} onChange={event => setSeverity(event.target.value as 'WARNING' | 'CRITICAL')}>
            <option value="WARNING">警告</option>
            <option value="CRITICAL">严重</option>
          </select></label>
        <label className="field"><span>通知渠道（可选）</span>
          <select value={channelId} onChange={event => setChannelId(event.target.value)}>
            <option value="">不通知</option>
            {channels.map(channel => <option key={channel.id} value={channel.id}>{channel.name}</option>)}
          </select></label>
        <button className="primary" type="submit" disabled={isMock || busy === 'create'}>
          {busy === 'create' ? '创建中…' : '创建规则'}</button>
      </form>

      <div className="alerts-columns">
        <div className="card alerts-firing">
          <div className="alerts-card-heading"><h3>当前告警</h3>
            <span>{firing ? `${firing.alerts.length} 条 · ${firing.evaluatedRules} 条规则` : '—'}</span></div>
          {loading && <p className="application-muted">正在评估…</p>}
          {!loading && firing && firing.alerts.length === 0 && <p className="application-muted">当前没有触发中的告警。</p>}
          <ul>{firing?.alerts.map(alert => <li key={`${alert.ruleId}-${alert.hostname}`}>
            <span className={`alerts-severity alerts-severity-${alert.severity.toLowerCase()}`}>
              {SEVERITY_LABELS[alert.severity] ?? alert.severity}</span>
            <div className="alerts-item-copy">
              <strong>{alert.ruleName}</strong>
              <small>{alert.hostname} · {alert.detail}</small>
            </div>
            <code>{CONDITION_LABELS[alert.condition as AlertCondition] ?? alert.condition}</code>
          </li>)}</ul>
        </div>

        <div className="card alerts-rules">
          <div className="alerts-card-heading"><h3>规则</h3><span>{rules.length}</span></div>
          {!loading && rules.length === 0 && <p className="application-muted">还没有规则。左侧新建后立即生效。</p>}
          <ul>{rules.map(rule => <li key={rule.id}>
            <div className="alerts-item-copy">
              <strong>{rule.name}</strong>
              <small>{CONDITION_LABELS[rule.condition] ?? rule.condition}
                {rule.unitName ? ` · ${rule.unitName}` : ''}
                {rule.hostname ? ` · ${rule.hostname}` : ' · 全部主机'}
                {rule.channelName ? ` · 通知 ${rule.channelName}` : ' · 仅控制台可见'}</small>
            </div>
            <span className={`alerts-severity alerts-severity-${rule.severity.toLowerCase()}`}>
              {SEVERITY_LABELS[rule.severity] ?? rule.severity}</span>
            <button type="button" className="secondary" disabled={isMock || busy === rule.id}
              onClick={() => void remove(rule)}>删除</button>
          </li>)}</ul>
        </div>

        <div className="card alerts-channels">
          <div className="alerts-card-heading"><h3>通知渠道</h3><span>{channels.length}</span></div>
          {channels.length === 0 && <p className="application-muted">
            尚未配置渠道。没有渠道时告警只在控制台可见，不会通知任何人。</p>}
          <ul>{channels.map(channel => <li key={channel.id}>
            <div className="alerts-item-copy">
              <strong>{channel.name}</strong>
              <small className="mono">{channel.url}</small>
              <small>{channel.enabled ? '已启用' : '已停用'} · 被 {channel.ruleCount} 条规则使用
                {channel.notifyRecovery ? ' · 恢复也通知' : ' · 只通知告警'}
                {channel.lastStatus ? ` · 上次投递 ${channel.lastStatus === 'SUCCEEDED' ? '成功' : '失败'}`
                  : ' · 尚未投递'}
                {channel.lastError ? `（${channel.lastError}）` : ''}</small>
            </div>
            <button type="button" className="secondary" disabled={isMock || busy !== ''}
              onClick={() => void testChannel(channel)}>
              {busy === `test:${channel.id}` ? '投递中…' : '测试'}</button>
            <button type="button" className="danger" disabled={isMock || busy !== ''}
              onClick={() => void removeChannel(channel)}>删除</button>
          </li>)}</ul>
          <form className="alerts-channel-form" onSubmit={createChannel}>
            <label className="field"><span>名称</span>
              <input value={channelName} onChange={event => setChannelName(event.target.value)}
                required maxLength={120} placeholder="例如：值班群 Webhook" /></label>
            <label className="alerts-checkbox">
              <input type="checkbox" checked={notifyRecovery}
                onChange={event => setNotifyRecovery(event.target.checked)} />
              <span>恢复时也通知</span></label>
            <label className="field"><span>Webhook 地址</span>
              <input value={channelUrl} onChange={event => setChannelUrl(event.target.value)}
                required maxLength={500} placeholder="https://example.com/hooks/nanpanel" /></label>
            <button className="secondary" type="submit" disabled={isMock || busy === 'channel'}>
              {busy === 'channel' ? '创建中…' : '添加渠道'}</button>
          </form>
        </div>
      </div>
    </div>

    <div className="card alerts-events">
      <div className="alerts-card-heading"><h3>通知历史</h3>
        <span>{events.length ? `最近 ${events.length} 条` : '暂无记录'}</span></div>
      {events.length === 0
        ? <p className="application-muted">
          还没有投递记录。规则绑定渠道后，告警触发与恢复都会在这里留下一次实际投递尝试。</p>
        : <div className="table-wrap"><table>
          <thead><tr><th>时间</th><th>类型</th><th>规则</th><th>对象</th><th>渠道</th><th>结果</th></tr></thead>
          <tbody>{events.map(item => <tr key={item.id}>
            <td className="mono">{new Date(item.createdAt).toLocaleString()}</td>
            <td><span className={`alerts-severity alerts-severity-${item.kind === 'RECOVERED' ? 'warning' : item.severity.toLowerCase()}`}>
              {item.kind === 'RECOVERED' ? '恢复' : '告警'}</span></td>
            <td>{item.ruleName}</td>
            <td>{item.hostname ?? '—'}{item.detail ? <small className="muted"> · {item.detail}</small> : null}</td>
            <td>{item.channelName ?? '—'}</td>
            <td>{item.status === 'SUCCEEDED' ? '已送达'
              : <span title={item.error ?? ''}>失败{item.error ? `（${item.error}）` : ''}</span>}</td>
          </tr>)}</tbody>
        </table></div>}
    </div>
  </section>;
}
