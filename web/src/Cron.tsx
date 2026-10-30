import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type { CronJob, CronJobList, Host, HostPage } from './lib/types';
import './cron.css';

const STATE_LABELS: Record<string, string> = {
  DRAFT: '草稿', APPLIED: '已生效', DISABLED: '已停用', FAILED: '失败', UNKNOWN: '状态未定',
};
const PRESETS: { label: string; value: string }[] = [
  { label: '每 5 分钟', value: '*/5 * * * *' },
  { label: '每小时', value: '0 * * * *' },
  { label: '每天 02:00', value: '0 2 * * *' },
  { label: '每周一 03:30', value: '30 3 * * 1' },
  { label: '每月 1 日 04:00', value: '0 4 1 * *' },
  { label: '自定义', value: '' },
];
const inFlight = (job: CronJob) =>
  job.operationStatus === 'PENDING' || job.operationStatus === 'RUNNING';

/**
 * 计划任务：面板只存意图，显式「应用」才写入主机上的受管 cron 文件（cron.write），
 * 「停用」撤回（cron.remove）。状态按最近一次操作派生，失败不会显示成已生效。
 */
export function Cron({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [jobs, setJobs] = useState<CronJob[]>([]);
  const [loading, setLoading] = useState(!isMock);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供定时任务接口，切换到 Live API 后可创建并下发。' : '');
  const [hostId, setHostId] = useState('');
  const [name, setName] = useState('');
  const [preset, setPreset] = useState(PRESETS[0].value);
  const [schedule, setSchedule] = useState(PRESETS[0].value);
  const [command, setCommand] = useState('');
  const [editing, setEditing] = useState<CronJob | null>(null);

  const load = useCallback(async () => {
    if (isMock) return;
    setLoading(true);
    try {
      const result = await api<CronJobList>('/cron-jobs', 'GET', undefined, {}, true);
      setJobs(result.data.items);
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
      .then(result => {
        const capable = result.data.items.filter(host => host.capabilities.includes('cron.write'));
        setHosts(capable);
        setHostId(current => current || capable[0]?.id || '');
      })
      .catch(() => {});
  }, [load]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (isMock) return;
    const expression = editing ? schedule : (preset || schedule);
    if (!hostId || !name.trim() || !expression.trim() || !command.trim()) {
      setError('主机、名称、计划与命令均为必填');
      return;
    }
    setBusy('save');
    try {
      if (editing) {
        await api<CronJob>(`/cron-jobs/${editing.hostId}/${editing.id}`, 'PUT', {
          schedule: expression.trim(), command: command.trim(),
        }, {}, true);
        notify(`任务 ${editing.id} 已更新，需要重新应用才会生效`);
      } else {
        await api<CronJob>('/cron-jobs', 'POST', {
          hostId, name: name.trim(), schedule: expression.trim(), command: command.trim(),
        }, {}, true);
        notify(`任务 nanpanel-${name.trim()} 已创建（草稿）`);
        setName('');
        setCommand('');
      }
      setEditing(null);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function act(job: CronJob, action: 'apply' | 'disable' | 'delete') {
    if (isMock) return;
    if (action === 'delete' && !window.confirm(`删除任务 ${job.id} 的记录？`)) return;
    setBusy(`${action}:${job.id}`);
    setError('');
    try {
      await api(`/cron-jobs/${job.hostId}/${job.id}${action === 'delete' ? '' : `/${action}`}`,
        action === 'delete' ? 'DELETE' : 'POST', undefined,
        action === 'delete' ? {} : { 'Idempotency-Key': createUuid() }, true);
      notify(action === 'apply' ? `任务 ${job.id} 已下发` : action === 'disable'
        ? `任务 ${job.id} 已停用` : `任务 ${job.id} 已删除`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
      await load();
    } finally {
      setBusy('');
    }
  }

  function beginEdit(job: CronJob) {
    setEditing(job);
    setHostId(job.hostId);
    setName(job.id.replace(/^nanpanel-/, ''));
    setPreset(PRESETS.some(preset => preset.value === job.schedule) ? job.schedule : '');
    setSchedule(job.schedule);
    setCommand(job.command);
    setError('');
  }

  function cancelEdit() {
    setEditing(null);
    setPreset(PRESETS[0].value);
    setSchedule(PRESETS[0].value);
    setCommand('');
    setName('');
    setError('');
  }

  const serving = (job: CronJob) => job.state === 'APPLIED' || job.state === 'UNKNOWN';

  return <>
    <div className="page-heading">
      <div><h1>计划任务</h1>
        <p>受管 cron：每个任务写成一个 <code>nanpanel-&lt;名称&gt;</code> 文件，只以 root 运行，
          手工维护的 crontab 不受影响。面板只保存意图，显式「应用」才写入主机。</p></div>
    </div>
    <div className="cron-grid">
      <form className="card" onSubmit={submit}>
        <h2>{editing ? `编辑 ${editing.id}` : '新建任务'}</h2>
        {!editing && <label className="field"><span>主机</span>
          <select value={hostId} onChange={event => setHostId(event.target.value)}>
            {hosts.length === 0 && <option value="">没有具备 cron.write 能力的主机</option>}
            {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}
          </select></label>}
        {!editing && <label className="field"><span>名称（2–32 位小写字母/数字/-）</span>
          <input value={name} onChange={event => setName(event.target.value)}
            placeholder="backup" pattern="[a-z][a-z0-9-]{0,30}[a-z0-9]" /></label>}
        <label className="field"><span>执行计划</span>
          <select value={preset} onChange={event => {
            setPreset(event.target.value);
            if (event.target.value) setSchedule(event.target.value);
          }}>
            {PRESETS.map(item => <option key={item.label} value={item.value}>{item.label}</option>)}
          </select></label>
        <label className="field"><span>cron 表达式</span>
          <input value={schedule} onChange={event => { setSchedule(event.target.value); setPreset(''); }}
            placeholder="*/15 2 * * *" required /></label>
        <label className="field"><span>命令（单行，≤900 字节）</span>
          <input value={command} onChange={event => setCommand(event.target.value)}
            placeholder="/usr/local/bin/backup.sh" required /></label>
        {error && <div className="alert error" role="alert">{error}</div>}
        <div className="form-actions">
          <button className="primary" disabled={isMock || busy === 'save'}>
            {busy === 'save' ? '保存中…' : editing ? '保存修改' : '创建任务'}</button>
          {editing && <button type="button" className="secondary" onClick={cancelEdit}>取消编辑</button>}
        </div>
      </form>

      <section className="card">
        <div className="cron-list-heading"><h2>任务</h2>
          <button className="secondary" disabled={loading} onClick={() => void load()}>↻ 刷新</button></div>
        {error && !editing && <div className="alert error" role="alert">{error}</div>}
        {!loading && jobs.length === 0 && <p className="muted">还没有定时任务。</p>}
        {jobs.length > 0 && <div className="table-wrap"><table>
          <thead><tr><th>任务</th><th>主机</th><th>计划</th><th>状态</th><th /></tr></thead>
          <tbody>{jobs.map(job => <tr key={`${job.hostId}:${job.id}`}>
            <td><b className="mono">{job.id}</b><small className="muted mono">{job.command}</small></td>
            <td>{job.hostname ?? job.hostId.slice(0, 8)}</td>
            <td className="mono">{job.schedule}</td>
            <td><span className={`cron-state state-${job.state.toLowerCase()}`}>
              {STATE_LABELS[job.state] ?? job.state}</span>
              {inFlight(job) && <span className="cron-state state-pending">处理中</span>}</td>
            <td className="cron-actions">
              <button className="link" disabled={busy !== '' || serving(job)}
                title={serving(job) ? '需先停用' : '修改计划或命令'} onClick={() => beginEdit(job)}>编辑</button>
              {serving(job)
                ? <button className="link" disabled={busy !== ''}
                  onClick={() => void act(job, 'disable')}>
                  {busy === `disable:${job.id}` ? '停用中…' : '停用'}</button>
                : <button className="link" disabled={busy !== ''}
                  onClick={() => void act(job, 'apply')}>
                  {busy === `apply:${job.id}` ? '下发中…' : job.state === 'DISABLED' ? '启用' : '应用'}</button>}
              <button className="link" disabled={busy !== '' || serving(job)}
                title={serving(job) ? '需先停用' : '删除记录'} onClick={() => void act(job, 'delete')}>删除</button>
            </td>
          </tr>)}</tbody>
        </table></div>}
        <p className="muted cron-note">删除只删面板记录；已生效的任务需先「停用」才会从主机撤回。</p>
      </section>
    </div>
  </>;
}
