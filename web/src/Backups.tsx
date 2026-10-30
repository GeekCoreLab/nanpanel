import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type { BackupList, BackupRecord, Host, HostPage } from './lib/types';
import './backups.css';

const STATE_LABELS: Record<string, string> = {
  DRAFT: '创建中', APPLIED: '已完成', FAILED: '失败', UNKNOWN: '状态未定',
};
const SIZE_UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
function humanSize(bytes: number | null) {
  if (bytes === null) return '—';
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < SIZE_UNITS.length - 1) { value /= 1024; unit++; }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${SIZE_UNITS[unit]}`;
}

/**
 * 备份：面板记住「谁在什么时候对哪台主机的哪个路径做了什么备份」。文件名、大小与 SHA-256
 * 都来自主机返回的结果；恢复只允许恢复到空目录（Helper 侧强制），不会覆盖线上文件。
 */
export function Backups({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [records, setRecords] = useState<BackupRecord[]>([]);
  const [hostId, setHostId] = useState('');
  const [source, setSource] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState('');
  const [restoring, setRestoring] = useState<BackupRecord | null>(null);
  const [target, setTarget] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供备份接口，切换到 Live API 后可创建与恢复。' : '');

  const load = useCallback(async () => {
    if (isMock) return;
    try {
      const result = await api<BackupList>('/backups', 'GET', undefined, {}, true);
      setRecords(result.data.items);
      setError('');
    } catch (cause) {
      setError((cause as Error).message);
    }
  }, []);

  useEffect(() => {
    if (isMock) return;
    void load();
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        const capable = result.data.items.filter(host => host.capabilities.includes('backup.create'));
        setHosts(capable);
        setHostId(current => current || capable[0]?.id || '');
      })
      .catch(() => {});
  }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    if (!hostId || !source.trim() || !name.trim()) {
      setError('主机、来源路径与名称均为必填');
      return;
    }
    setBusy('create');
    setError('');
    try {
      await api('/backups', 'POST', {
        hostId, source: source.trim(), name: name.trim(),
      }, { 'Idempotency-Key': createUuid() }, true);
      notify('备份任务已下发，完成后会出现在列表里');
      setSource('');
      setName('');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function restore(event: FormEvent) {
    event.preventDefault();
    if (!restoring || isMock) return;
    setBusy(`restore:${restoring.id}`);
    setError('');
    try {
      await api(`/backups/${restoring.id}/restore`, 'POST', { target: target.trim() },
        { 'Idempotency-Key': createUuid() }, true);
      notify(`已下发恢复到 ${target.trim()}`);
      setRestoring(null);
      setTarget('');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function remove(record: BackupRecord) {
    if (isMock) return;
    if (!window.confirm(`删除备份 ${record.file}？主机的归档文件也会被删除。`)) return;
    setBusy(`delete:${record.id}`);
    setError('');
    try {
      await api(`/backups/${record.id}`, 'DELETE', undefined,
        { 'Idempotency-Key': createUuid() }, true);
      notify('备份已删除');
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  return <>
    <div className="page-heading">
      <div><h1>备份</h1>
        <p>对主机上的目录或文件打包为受管 <code>tar.gz</code>，记录大小与 SHA-256。
          恢复只能恢复到**空目录**，失败不会留下半成品。</p></div>
      <button className="secondary" onClick={() => void load()}>↻ 刷新</button>
    </div>
    <div className="backups-grid">
      <form className="card" onSubmit={create}>
        <h2>新建备份</h2>
        <label className="field"><span>主机</span>
          <select value={hostId} onChange={event => setHostId(event.target.value)}>
            {hosts.length === 0 && <option value="">没有具备 backup.create 能力的主机</option>}
            {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}
          </select></label>
        <label className="field"><span>来源路径（绝对路径）</span>
          <input value={source} onChange={event => setSource(event.target.value)}
            placeholder="/etc/nginx" required /></label>
        <label className="field"><span>名称（2–32 位小写字母/数字/-）</span>
          <input value={name} onChange={event => setName(event.target.value)}
            placeholder="nginx" pattern="[a-z][a-z0-9-]{0,30}[a-z0-9]" required /></label>
        {error && !restoring && <div className="alert error" role="alert">{error}</div>}
        <button className="primary" disabled={isMock || busy === 'create'}>
          {busy === 'create' ? '下发中…' : '创建备份'}</button>
      </form>

      <section className="card">
        <div className="backups-list-heading"><h2>备份记录</h2><span className="muted">{records.length} 条</span></div>
        {error && restoring && <div className="alert error" role="alert">{error}</div>}
        {records.length === 0 && !error && <p className="muted">还没有备份。</p>}
        {records.length > 0 && <div className="table-wrap"><table>
          <thead><tr><th>文件</th><th>主机 / 来源</th><th>大小</th><th>状态</th><th /></tr></thead>
          <tbody>{records.map(record => <tr key={record.id}>
            <td><b className="mono">{record.file}</b>
              {record.sha256 && <small className="mono muted">{record.sha256.slice(0, 16)}…</small>}</td>
            <td>{record.hostname ?? record.hostId.slice(0, 8)}
              <small className="mono muted">{record.source}</small></td>
            <td>{humanSize(record.sizeBytes)}</td>
            <td><span className={`backup-state state-${record.state.toLowerCase()}`}>
              {STATE_LABELS[record.state] ?? record.state}</span></td>
            <td className="backups-actions">
              <button className="link" disabled={busy !== '' || record.state !== 'APPLIED'}
                title={record.state === 'APPLIED' ? '恢复到空目录' : '备份尚未完成'}
                onClick={() => { setRestoring(record); setTarget(''); setError(''); }}>恢复</button>
              <button className="link" disabled={busy !== ''} onClick={() => void remove(record)}>删除</button>
            </td>
          </tr>)}</tbody>
        </table></div>}
      </section>
    </div>
    {restoring && <div className="modal-backdrop"><div className="modal" role="dialog" aria-label="恢复备份">
      <div className="card-title"><h2>恢复 {restoring.file}</h2>
        <button className="icon-button" aria-label="关闭恢复" onClick={() => setRestoring(null)}>×</button></div>
      <div className="alert">目标目录必须**已存在且为空**。面板不会合并或覆盖已有文件，恢复失败时目标保持原样。</div>
      <form onSubmit={restore}>
        <label className="field"><span>目标目录（绝对路径）</span>
          <input value={target} onChange={event => setTarget(event.target.value)}
            placeholder="/srv/restore" required /></label>
        <div className="form-actions">
          <button className="primary" disabled={busy !== ''}>{busy ? '下发中…' : '确认恢复'}</button>
          <button className="secondary" type="button" onClick={() => setRestoring(null)}>取消</button>
        </div>
      </form>
    </div></div>}
  </>;
}
