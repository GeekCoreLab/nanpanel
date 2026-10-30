import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type {
  BackupList, BackupRecord, DatabaseEngineOption, Host, HostPage, ManagedDatabase,
  ManagedDatabaseConnection, ManagedDatabaseCreated, ManagedDatabaseList,
} from './lib/types';
import './managed-databases.css';

type OperationRow = {
  id: string; capability: string; status: string;
  result?: { output?: string } | null;
  error?: Record<string, unknown> | null;
};

/** The helper answers with a JSON string inside the operation result. */
function mountpointOf(operation: OperationRow): string | null {
  const raw = operation.result?.output;
  if (typeof raw !== 'string') return null;
  try {
    const parsed = JSON.parse(raw) as { mountpoint?: string };
    return typeof parsed.mountpoint === 'string' ? parsed.mountpoint : null;
  } catch {
    return null;
  }
}

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
  const [volume, setVolume] = useState<{ instance: ManagedDatabase; path: string } | null>(null);
  const [history, setHistory] = useState<{ instance: ManagedDatabase; items: BackupRecord[] } | null>(null);

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

  /**
   * The mountpoint only exists on the host, so this dispatches an inspection and waits for its
   * operation instead of building a path the panel cannot know.
   */
  async function resolveVolume(instance: ManagedDatabase) {
    if (isMock) return;
    setBusy(`volume:${instance.id}`);
    setError('');
    try {
      const submitted = await api<{ operationId: string }>(
        `/database-instances/${instance.id}/volume`, 'POST', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      const operationId = submitted.data.operationId;
      for (let attempt = 0; attempt < 15; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 2000));
        const listed = await api<{ items: OperationRow[] }>(
          '/operations?capability=container.volume.path&limit=20', 'GET', undefined, {}, true);
        const operation = listed.data.items.find(item => item.id === operationId);
        if (!operation) continue;
        if (operation.status === 'SUCCEEDED') {
          const path = mountpointOf(operation);
          if (!path) throw new Error('主机返回了无法解析的卷信息');
          setVolume({ instance, path });
          return;
        }
        if (operation.status === 'FAILED' || operation.status === 'UNKNOWN')
          throw new Error(`读取挂载点失败：${JSON.stringify(operation.error ?? {})}`);
      }
      throw new Error('等待主机响应超时，请稍后在「操作任务」里查看结果');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function backupInstance(instance: ManagedDatabase, source: string) {
    if (isMock) return;
    setBusy(`backup:${instance.id}`);
    setError('');
    try {
      await api('/backups', 'POST', {
        hostId: instance.hostId, source, name: instance.containerName,
        instanceId: instance.id,
      }, { 'Idempotency-Key': createUuid() }, true);
      notify(`已下发备份 ${instance.containerName}（数据卷目录）`);
      setVolume(null);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function openHistory(instance: ManagedDatabase) {
    if (isMock) return;
    setBusy(`history:${instance.id}`);
    setError('');
    try {
      const result = await api<BackupList>(
        `/backups?instanceId=${instance.id}`, 'GET', undefined, {}, true);
      setHistory({ instance, items: result.data.items });
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function restoreRecord(record: BackupRecord) {
    const target = window.prompt(
      `把 ${record.file} 恢复到哪个空目录？（绝对路径；目录必须已存在且为空）`, '/srv/restore');
    if (!target) return;
    setBusy(`restore:${record.id}`);
    try {
      await api(`/backups/${record.id}/restore`, 'POST', { target: target.trim() },
        { 'Idempotency-Key': createUuid() }, true);
      notify(`已下发恢复到 ${target.trim()}`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function deleteRecord(record: BackupRecord) {
    if (!window.confirm(`删除备份 ${record.file}？主机上的归档也会被删除。`)) return;
    setBusy(`delete:${record.id}`);
    try {
      await api(`/backups/${record.id}`, 'DELETE', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      if (history) await openHistory(history.instance);
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
            <button className="link" disabled={busy !== ''}
              title="向主机询问命名卷的真实挂载点，并可从那里创建备份"
              onClick={() => void resolveVolume(instance)}>数据卷</button>
            <button className="link" disabled={busy !== ''}
              onClick={() => void openHistory(instance)}>备份</button>
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
    {volume && <div className="modal-backdrop"><div className="modal" role="dialog" aria-label="数据卷位置">
      <div className="card-title"><h2>{volume.instance.containerName} 的数据卷</h2>
        <button className="icon-button" aria-label="关闭数据卷" onClick={() => setVolume(null)}>×</button></div>
      <p className="muted">这个路径由主机上的 <code>volume inspect</code> 返回，面板不拼接、不推测。</p>
      <dl className="managed-credentials">
        <div><dt>挂载点</dt><dd className="mono">{volume.path}</dd></div>
        <div><dt>卷名</dt><dd className="mono">{volume.instance.dataVolume}</dd></div>
      </dl>
      <div className="alert">备份会把整个数据目录打包；正在写入的数据库文件可能不一致，恢复前请在主机上确认服务已停止。</div>
      <div className="form-actions">
        <button className="primary" disabled={busy !== ''}
          onClick={() => void backupInstance(volume.instance, volume.path)}>
          {busy.startsWith('backup:') ? '下发中…' : '备份这个目录'}</button>
        <button className="secondary" onClick={() => setVolume(null)}>关闭</button>
      </div>
    </div></div>}
    {history && <div className="modal-backdrop"><div className="modal role-editor" role="dialog"
      aria-label="实例备份历史">
      <div className="card-title"><h2>{history.instance.containerName} 的备份</h2>
        <button className="icon-button" aria-label="关闭备份历史" onClick={() => setHistory(null)}>×</button></div>
      {history.items.length === 0
        ? <p className="muted">这个实例还没有备份。用「数据卷」读出挂载点后即可备份它的数据目录。</p>
        : <div className="table-wrap"><table>
          <thead><tr><th>文件</th><th>大小</th><th>状态</th><th /></tr></thead>
          <tbody>{history.items.map(record => <tr key={record.id}>
            <td><b className="mono">{record.file}</b>
              {record.sha256 && <small className="mono muted">{record.sha256.slice(0, 12)}…</small>}</td>
            <td>{record.sizeBytes === null ? '—' : `${(record.sizeBytes / 1048576).toFixed(1)} MiB`}</td>
            <td><span className={`backup-state state-${record.state.toLowerCase()}`}>
              {record.state === 'APPLIED' ? '已完成' : record.state === 'FAILED' ? '失败'
                : record.state === 'UNKNOWN' ? '状态未定' : '进行中'}</span></td>
            <td className="backups-actions">
              <button className="link" disabled={busy !== '' || record.state !== 'APPLIED'}
                onClick={() => void restoreRecord(record)}>恢复</button>
              <button className="link" disabled={busy !== ''}
                onClick={() => void deleteRecord(record)}>删除</button>
            </td>
          </tr>)}</tbody>
        </table></div>}
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
