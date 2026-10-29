import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import type {
  AlertCondition, AlertRule, AlertRuleList, FiringAlertList, Host, HostPage,
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

  const load = useCallback(async () => {
    if (isMock) return;
    setLoading(true);
    try {
      const [ruleResult, alertResult] = await Promise.all([
        api<AlertRuleList>('/alert-rules', 'GET', undefined, {}, true),
        api<FiringAlertList>('/alerts', 'GET', undefined, {}, true),
      ]);
      setRules(ruleResult.data.items);
      setFiring(alertResult.data);
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
                {rule.hostname ? ` · ${rule.hostname}` : ' · 全部主机'}</small>
            </div>
            <span className={`alerts-severity alerts-severity-${rule.severity.toLowerCase()}`}>
              {SEVERITY_LABELS[rule.severity] ?? rule.severity}</span>
            <button type="button" className="secondary" disabled={isMock || busy === rule.id}
              onClick={() => void remove(rule)}>删除</button>
          </li>)}</ul>
        </div>
      </div>
    </div>
  </section>;
}
