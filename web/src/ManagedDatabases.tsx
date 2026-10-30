import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type {
  DatabaseEngineOption, Host, HostPage, ManagedDatabase, ManagedDatabaseConnection,
  ManagedDatabaseCreated, ManagedDatabaseList,
} from './lib/types';
import './managed-databases.css';

const STATE_LABELS: Record<string, string> = {
  DRAFT: '部署中', APPLIED: '运行中', FAILED: '失败', UNKNOWN: '状态未定',
};
const DEFAULT_PORTS: Record<string, number> = { mysql: 13306, mariadb: 13307, postgres: 15432 };

/**
 * 托管数据库实例：面板自己在主机上起容器、记住端口与数据卷，并对它的生命周期负责。
 * 口令由面板生成、只随创建响应返回一次——数据库、审计与日志里都没有它。
 */
export function ManagedDatabases({ notify }: { notify: (message: string) => void }) {
  const [instances, setInstances] = useState<ManagedDatabase[]>([]);
  const [engines, setEngines] = useState<DatabaseEngineOption[]>([]);
  const [hosts, setHosts] = useState<Host[]>([]);
  const [hostId, setHostId] = useState('');
  const [engine, setEngine] = useState('mysql');
  const [port, setPort] = useState(String(DEFAULT_PORTS.mysql));
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [created, setCreated] = useState<ManagedDatabaseCreated | null>(null);
  const [connection, setConnection] = useState<ManagedDatabaseConnection | null>(null);

  const load = useCallback(async () => {
    if (isMock) return;
    try {
      const result = await api<ManagedDatabaseList>('/database-instances', 'GET', undefined, {}, true);
      setInstances(result.data.items);
      setError('');
    } catch (cause) {
      setError((cause as Error).message);
    }
  }, []);

  useEffect(() => {
    if (isMock) return;
    void load();
    void api<{ items: DatabaseEngineOption[] }>('/database-instances/engines', 'GET', undefined, {}, true)
      .then(result => setEngines(result.data.items))
      .catch(() => {});
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        const capable = result.data.items.filter(host => host.capabilities.includes('container.deploy'));
        setHosts(capable);
        setHostId(current => current || capable[0]?.id || '');
      })
      .catch(() => {});
  }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    if (!hostId) { setError('请选择一台具备 container.deploy 能力的主机'); return; }
    setBusy('create');
    setError('');
    try {
      const result = await api<ManagedDatabaseCreated>('/database-instances', 'POST', {
        hostId, engine, port: Number(port),
      }, { 'Idempotency-Key': createUuid() }, true);
      // The response is the only place the password ever exists.
      setCreated(result.data);
      notify(`${engine} 实例已下发部署`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function showConnection(instance: ManagedDatabase) {
    setBusy(`connection:${instance.id}`);
    try {
      const result = await api<ManagedDatabaseConnection>(
        `/database-instances/${instance.id}/connection`, 'GET', undefined, {}, true);
      setConnection(result.data);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function remove(instance: ManagedDatabase) {
    if (isMock) return;
    if (!window.confirm(`删除 ${instance.engine} 实例 ${instance.containerName}？容器会停止并移除，`
      + `数据卷 ${instance.dataVolume} 保留在主机上。`)) return;
    setBusy(`delete:${instance.id}`);
    try {
      await api(`/database-instances/${instance.id}`, 'DELETE', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      notify('数据库实例已删除，数据卷保留');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  return <section className="managed-databases">
    <header className="databases-header">
      <div><h2>托管实例</h2>
        <p>面板在主机上部署 MySQL / MariaDB / PostgreSQL 容器，只发布到 <code>127.0.0.1</code>，
          端口与数据卷由面板记录。**口令只显示一次**，面板不保存；删除实例不会删除数据卷。</p></div>
    </header>
    {error && <div className="alert error" role="alert">{error}</div>}
    <form className="card managed-databases-form" onSubmit={create}>
      <label className="field"><span>主机</span>
        <select value={hostId} onChange={event => setHostId(event.target.value)}>
          {hosts.length === 0 && <option value="">没有具备 container.deploy 能力的主机</option>}
          {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}
        </select></label>
      <label className="field"><span>引擎</span>
        <select value={engine} onChange={event => {
          setEngine(event.target.value);
          setPort(String(DEFAULT_PORTS[event.target.value] ?? ''));
        }}>
          {engines.map(item => <option key={item.id} value={item.id}>{item.id} {item.version}</option>)}
        </select></label>
      <label className="field"><span>主机端口</span>
        <input value={port} onChange={event => setPort(event.target.value)} inputMode="numeric" /></label>
      <button className="primary" disabled={isMock || busy === 'create'}>
        {busy === 'create' ? '下发中…' : '创建实例'}</button>
    </form>
    {instances.length === 0
      ? <p className="application-muted">还没有托管实例。</p>
      : <div className="table-wrap"><table>
        <thead><tr><th>实例</th><th>主机 / 端口</th><th>数据卷</th><th>状态</th><th /></tr></thead>
        <tbody>{instances.map(instance => <tr key={instance.id}>
          <td><b>{instance.engine} {instance.version}</b>
            <small className="mono muted">{instance.containerName} · {instance.databaseName}</small></td>
          <td>{instance.hostname ?? instance.hostId.slice(0, 8)}
            <small className="mono muted">127.0.0.1:{instance.port}</small></td>
          <td className="mono">{instance.dataVolume}</td>
          <td><span className={`backup-state state-${instance.state.toLowerCase()}`}>
            {STATE_LABELS[instance.state] ?? instance.state}</span></td>
          <td className="backups-actions">
            <button className="link" disabled={busy !== ''} onClick={() => void showConnection(instance)}>连接</button>
            <button className="link" disabled={busy !== ''} onClick={() => void remove(instance)}>删除</button>
          </td>
        </tr>)}</tbody>
      </table></div>}
    {created && <div className="modal-backdrop"><div className="modal" role="dialog" aria-label="实例口令">
      <div className="card-title"><h2>请立即保存口令</h2>
        <button className="icon-button" aria-label="关闭口令" onClick={() => setCreated(null)}>×</button></div>
      <div className="alert">面板**不保存**这个口令，关闭后无法再次取回。忘记时只能在主机上重置，或删除实例重建。</div>
      <dl className="managed-credentials">
        <div><dt>地址</dt><dd className="mono">{created.credentials.host}:{created.credentials.port}</dd></div>
        <div><dt>用户</dt><dd className="mono">{created.credentials.username}</dd></div>
        <div><dt>库名</dt><dd className="mono">{created.credentials.database}</dd></div>
        <div><dt>口令</dt><dd className="mono">{created.credentials.password}</dd></div>
      </dl>
      <div className="form-actions">
        <button className="primary" onClick={() => {
          void navigator.clipboard?.writeText(
            `${created.credentials.username}@${created.credentials.host}:${created.credentials.port}/${created.credentials.database} ${created.credentials.password}`);
          notify('已复制连接信息');
        }}>复制</button>
        <button className="secondary" onClick={() => setCreated(null)}>我已保存</button>
      </div>
    </div></div>}
    {connection && <div className="modal-backdrop"><div className="modal" role="dialog" aria-label="连接信息">
      <div className="card-title"><h2>连接信息</h2>
        <button className="icon-button" aria-label="关闭连接信息" onClick={() => setConnection(null)}>×</button></div>
      <dl className="managed-credentials">
        <div><dt>地址</dt><dd className="mono">{connection.host}:{connection.port}</dd></div>
        <div><dt>用户</dt><dd className="mono">{connection.username}</dd></div>
        <div><dt>库名</dt><dd className="mono">{connection.database}</dd></div>
        <div><dt>口令</dt><dd>{connection.passwordStored ? '已保存' : '面板不保存'}</dd></div>
      </dl>
      <p className="muted">{connection.note}</p>
    </div></div>}
  </section>;
}
