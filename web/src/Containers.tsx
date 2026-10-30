import { useCallback, useEffect, useState } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import { CONTAINER_STATE_LABELS, containerState, parseContainerList, type ContainerEntry } from './lib/containers.ts';
import type { Host, HostHelperOperationRequest, HostPage, Operation } from './lib/types';
import './containers.css';

const POLL_TIMEOUT_MS = 20_000;

/**
 * 容器管理：直接复用已存在的类型化 Helper 能力 container.list / logs / start / stop / remove。
 * 每个动作都是一次有审计的 Operation，提交后轮询到终态再渲染结果——面板不直接执行 docker，
 * 也不绕过主机的受限 root Helper。
 */
export function Containers({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [hostId, setHostId] = useState('');
  const [entries, setEntries] = useState<ContainerEntry[]>([]);
  const [listed, setListed] = useState(false);
  const [busy, setBusy] = useState('');
  const [logsName, setLogsName] = useState<string>();
  const [logs, setLogs] = useState('');
  const [confirmRemove, setConfirmRemove] = useState<string>();
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供主机容器接口，切换到 Live API 后可列出、启停与查看日志。' : '');

  const host = hosts.find(item => item.id === hostId);
  const canList = !!host?.capabilities.includes('container.list');

  useEffect(() => {
    if (isMock) return;
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        const capable = result.data.items.filter(item => item.capabilities.includes('container.list'));
        setHosts(capable);
        setHostId(current => current || capable[0]?.id || '');
      })
      .catch(cause => setError((cause as Error).message));
  }, []);

  const runHelper = useCallback(async (target: Host,
    capability: HostHelperOperationRequest['capability'], container: string,
    payload: HostHelperOperationRequest['payload'], reason: string): Promise<Operation> => {
    const request: HostHelperOperationRequest = {
      capability, target: container, payload, reason, timeoutSeconds: 60,
      expectedGeneration: target.generation,
    };
    const accepted = await api<Operation>(`/hosts/${target.id}/operations`, 'POST', request,
      { 'Idempotency-Key': createUuid() }, true);
    let current = accepted.data;
    const deadline = Date.now() + POLL_TIMEOUT_MS;
    while (['PENDING', 'RUNNING', 'UNKNOWN'].includes(current.status) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 600));
      current = (await api<Operation>(`/operations/${current.id}`, 'GET', undefined, {}, true)).data;
    }
    return current;
  }, []);

  const outputOf = (operation: Operation) =>
    (operation.result as { output?: string } | null)?.output ?? '';

  const load = useCallback(async () => {
    if (isMock || !host) return;
    setBusy('list');
    setError('');
    try {
      const operation = await runHelper(host, 'container.list', '', null, '列出容器');
      if (operation.status !== 'SUCCEEDED') {
        throw new Error(operation.error?.message ?? `列出容器未成功（${operation.status}）`);
      }
      setEntries(parseContainerList(outputOf(operation)));
      setListed(true);
    } catch (cause) {
      setEntries([]);
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }, [host, runHelper]);

  async function act(entry: ContainerEntry, capability: 'container.start' | 'container.stop' | 'container.remove') {
    if (!host) return;
    const verb = capability === 'container.start' ? '启动' : capability === 'container.stop' ? '停止' : '移除';
    setBusy(`${capability}:${entry.name}`);
    setError('');
    try {
      const operation = await runHelper(host, capability, entry.name, null, `${verb}容器 ${entry.name}`);
      if (operation.status !== 'SUCCEEDED') {
        throw new Error(operation.error?.message
          ?? `${verb}容器未成功（${operation.status}）`);
      }
      notify(`容器 ${entry.name} 已${verb}`);
      setConfirmRemove(undefined);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function openLogs(entry: ContainerEntry) {
    if (!host) return;
    setBusy(`logs:${entry.name}`);
    setLogsName(entry.name);
    setLogs('');
    setError('');
    try {
      const operation = await runHelper(host, 'container.logs', entry.name, null,
        `读取容器日志 ${entry.name}`);
      if (operation.status !== 'SUCCEEDED') {
        throw new Error(operation.error?.message ?? `读取日志未成功（${operation.status}）`);
      }
      setLogs(outputOf(operation) || '（无输出）');
    } catch (cause) {
      setLogs(`读取失败：${(cause as Error).message}`);
    } finally {
      setBusy('');
    }
  }

  return <>
    <div className="page-heading">
      <div>
        <h1>容器</h1>
        <p>在主机的 Docker / Podman 运行时里查看与操作容器。每个动作都是一次类型化 Helper 操作，
          由受限 root Helper 执行并留审计；端口发布仍只绑定 127.0.0.1。</p>
      </div>
      {listed && <button className="secondary" disabled={busy !== '' || !canList}
        onClick={() => void load()}>{busy === 'list' ? '读取中…' : '↻ 刷新'}</button>}
    </div>
    <section className="card">
      <div className="toolbar">
        <select aria-label="选择主机" value={hostId} onChange={event => {
          setHostId(event.target.value);
          setEntries([]);
          setListed(false);
          setError('');
        }}>
          {hosts.length === 0 && <option value="">没有具备 container.list 能力的主机</option>}
          {hosts.map(item => <option key={item.id} value={item.id}>
            {item.hostname} · {item.status}
          </option>)}
        </select>
        <button className="primary" disabled={isMock || !canList || busy !== ''}
          onClick={() => void load()}>{busy === 'list' ? '读取中…' : listed ? '重新列出' : '列出容器'}</button>
        <span className="muted">{host ? `generation ${host.generation}`
          : '选择一台已安装 Helper 的主机'}</span>
      </div>
      {error && <div className="alert">{error}</div>}
      {!listed && !error && <div className="containers-empty">
        <b>◇</b>
        <span>尚未列出容器</span>
        <small>点击「列出容器」后，面板会下发一次 container.list 操作并轮询结果。</small>
      </div>}
      {listed && entries.length === 0 && <div className="containers-empty">
        <b>◇</b><span>这台主机上没有容器</span>
        <small>可以用应用商店或 host helper 的 container.deploy 部署第一个容器。</small>
      </div>}
      {entries.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>容器</th><th>状态</th><th>运行时状态</th><th /></tr></thead>
        <tbody>{entries.map(entry => {
          const state = containerState(entry);
          const acting = busy.endsWith(`:${entry.name}`);
          return <tr key={entry.name}>
            <td className="mono">{entry.name}</td>
            <td><span className={`container-state state-${state.toLowerCase()}`}>
              {CONTAINER_STATE_LABELS[state]}</span></td>
            <td className="muted">{entry.status || '—'}</td>
            <td className="container-actions">
              {state === 'RUNNING' || state === 'PAUSED'
                ? <button className="secondary" disabled={isMock || busy !== ''}
                  onClick={() => void act(entry, 'container.stop')}>
                  {acting && busy.startsWith('container.stop') ? '停止中…' : '停止'}</button>
                : <button className="secondary" disabled={isMock || busy !== ''}
                  onClick={() => void act(entry, 'container.start')}>
                  {acting && busy.startsWith('container.start') ? '启动中…' : '启动'}</button>}
              <button className="secondary" disabled={isMock || busy !== ''}
                onClick={() => void openLogs(entry)}>日志</button>
              {confirmRemove === entry.name
                ? <>
                  <button className="danger" disabled={isMock || busy !== ''}
                    onClick={() => void act(entry, 'container.remove')}>确认移除</button>
                  <button className="secondary" onClick={() => setConfirmRemove(undefined)}>取消</button>
                </>
                : <button className="danger" disabled={isMock || busy !== ''}
                  onClick={() => setConfirmRemove(entry.name)}>移除</button>}
            </td>
          </tr>;
        })}</tbody>
      </table></div>}
      {entries.length > 0 && <p className="muted containers-note">
        移除只执行 <code>rm --force</code>，命名数据卷会保留。
      </p>}
    </section>
    {logsName && <div className="modal-backdrop" role="presentation">
      <div className="modal containers-logs" role="dialog" aria-label={`容器 ${logsName} 日志`}>
        <div className="card-title">
          <h2>容器日志 · {logsName}</h2>
          <button className="icon-button" aria-label="关闭日志" onClick={() => setLogsName(undefined)}>×</button>
        </div>
        <pre className="journal-output">{busy === `logs:${logsName}` ? '读取中…' : logs}</pre>
        <small className="muted">Helper 只返回末尾若干行，输出可能包含敏感信息。</small>
      </div>
    </div>}
  </>;
}
