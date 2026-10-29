import { useCallback, useEffect, useState } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import { joinHostPath, normalizeHostPath, parentHostPath } from './lib/host-paths.ts';
import type { Host, HostHelperOperationRequest, HostPage, Operation } from './lib/types';
import './files.css';

type FileEntry = { name: string; kind: 'directory' | 'file' | 'symlink' | 'other'; sizeBytes: number | null };
type Listing = { path: string; entries: FileEntry[]; truncated: boolean };

const READ_LIMIT = 64 * 1024;
const WRITE_LIMIT = 32 * 1024;
const POLL_TIMEOUT_MS = 20_000;

/**
 * 文件管理：复用已存在的类型化 Helper 能力 file.list / file.read / file.write / file.delete。
 * 每个动作仍是一次有审计的 Operation：提交后轮询到终态再渲染结果。路径安全由 Helper 负责
 * （绝对路径、拒绝 `..` 与符号链接、读取 64 KiB / 写入 32 KiB / 目录 200 项）。
 */
export function Files({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [hostId, setHostId] = useState('');
  const [path, setPath] = useState('/');
  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [selected, setSelected] = useState<string>();
  const [content, setContent] = useState('');
  const [listedPath, setListedPath] = useState('');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供主机文件接口，切换到 Live API 后可浏览与编辑文件。' : '');

  const host = hosts.find(item => item.id === hostId);

  useEffect(() => {
    if (isMock) return;
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        setHosts(result.data.items);
        setHostId(current => current || result.data.items[0]?.id || '');
      })
      .catch(cause => setError((cause as Error).message));
  }, []);

  const runHelper = useCallback(async (target: Host,
    capability: HostHelperOperationRequest['capability'], targetPath: string,
    payload: HostHelperOperationRequest['payload'], reason: string): Promise<Operation> => {
    const request: HostHelperOperationRequest = {
      capability, target: targetPath, payload, reason, timeoutSeconds: 60,
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

  const output = (operation: Operation): string => {
    const result = operation.result as { output?: string } | null;
    return typeof result?.output === 'string' ? result.output : '';
  };

  const failure = (operation: Operation): string => {
    const message = operation.error?.message;
    return typeof message === 'string' && message
      ? message
      : `操作未成功（${operation.status}）`;
  };

  async function list(directory: string) {
    if (!host || isMock) return;
    setBusy('list');
    setError('');
    try {
      const operation = await runHelper(host, 'file.list', directory, null, `浏览目录 ${directory}`);
      if (operation.status !== 'SUCCEEDED') {
        setError(failure(operation));
        return;
      }
      const listing = JSON.parse(output(operation) || '{}') as Listing;
      setEntries(Array.isArray(listing.entries) ? listing.entries : []);
      setTruncated(listing.truncated === true);
      setListedPath(directory);
      setPath(directory);
      setSelected(undefined);
      setContent('');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function open(entry: FileEntry) {
    if (!host || isMock) return;
    let target: string;
    try {
      target = joinHostPath(listedPath || path, entry.name);
    } catch (cause) {
      setError((cause as Error).message);
      return;
    }
    if (entry.kind === 'directory') {
      await list(target);
      return;
    }
    if (entry.kind !== 'file') {
      setError('只支持打开普通文件');
      return;
    }
    setBusy('read');
    setError('');
    try {
      const operation = await runHelper(host, 'file.read', target, null, `读取文件 ${target}`);
      if (operation.status !== 'SUCCEEDED') {
        setError(failure(operation));
        return;
      }
      setSelected(target);
      setContent(output(operation));
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function save() {
    if (!host || !selected || isMock) return;
    if (new TextEncoder().encode(content).length > WRITE_LIMIT) {
      setError('内容超过 32 KiB 写入上限，Helper 会拒绝保存');
      return;
    }
    setBusy('write');
    setError('');
    try {
      const operation = await runHelper(host, 'file.write', selected, { content },
        `保存文件 ${selected}`);
      if (operation.status !== 'SUCCEEDED') {
        setError(failure(operation));
        return;
      }
      notify(`已保存 ${selected}`);
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  async function remove() {
    if (!host || !selected || isMock) return;
    if (!window.confirm(`删除 ${selected}？此操作不可撤销。`)) return;
    setBusy('delete');
    setError('');
    try {
      const operation = await runHelper(host, 'file.delete', selected, null, `删除文件 ${selected}`);
      if (operation.status !== 'SUCCEEDED') {
        setError(failure(operation));
        return;
      }
      notify(`已删除 ${selected}`);
      setSelected(undefined);
      setContent('');
      await list(listedPath || '/');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  const parent = parentHostPath(listedPath || path);

  return <section className="files-workspace">
    <header className="files-header">
      <div><h2>文件</h2>
        <p>浏览与编辑主机文件，全部通过类型化 Helper 操作并留存审计：路径必须是绝对路径，拒绝 <code>..</code> 与符号链接；读取上限 64 KiB、写入上限 32 KiB、单目录最多 200 项。</p></div>
      <div className="files-toolbar">
        <label className="field"><span>主机</span>
          <select value={hostId} onChange={event => { setHostId(event.target.value); setEntries([]); setSelected(undefined); }}>
            {!hosts.length && <option value="">无可用主机</option>}
            {hosts.map(item => <option key={item.id} value={item.id}>{item.hostname} · {item.status}</option>)}
          </select></label>
        <form className="files-path" onSubmit={event => { event.preventDefault(); void list(normalizePath(path)); }}>
          <input value={path} onChange={event => setPath(event.target.value)} placeholder="/etc"
            aria-label="目录路径" />
          <button className="secondary" type="submit" disabled={isMock || !host || busy !== ''}>
            {busy === 'list' ? '读取中…' : '打开'}</button>
        </form>
      </div>
    </header>
    {error && <div className="alert error" role="alert">{error}</div>}
    <div className="files-grid">
      <div className="card files-browser">
        <div className="files-breadcrumb">
          <button type="button" className="secondary" disabled={!parent || busy !== '' || isMock}
            onClick={() => void list(parent ?? '/')}>↑ 上级</button>
          <code>{listedPath || path}</code>
          {truncated && <small>目录过大，只显示前 200 项</small>}
        </div>
        {!listedPath && !busy && <p className="application-muted">输入目录后点「打开」读取条目。</p>}
        <ul>{entries.map(entry => <li key={entry.name}>
          <button type="button" className="files-entry" disabled={busy !== '' || isMock}
            onClick={() => void open(entry)}>
            <span className="files-entry-icon">{entry.kind === 'directory' ? '▸' : entry.kind === 'file' ? '≡' : '~'}</span>
            <span className="files-entry-name">{entry.name}</span>
            <small>{entry.sizeBytes == null ? entry.kind : `${entry.sizeBytes} B`}</small>
          </button>
        </li>)}</ul>
      </div>
      <div className="card files-editor">
        <div className="files-editor-head">
          <strong>{selected ?? '未选择文件'}</strong>
          {selected && <span>{selected}</span>}
        </div>
        {selected
          ? <>
            <textarea value={content} onChange={event => setContent(event.target.value)} rows={18}
              spellCheck={false} aria-label="文件内容" />
            <div className="files-editor-actions">
              <small>{new TextEncoder().encode(content).length} B（上限 32768）</small>
              <button type="button" className="primary" disabled={busy !== '' || isMock}
                onClick={() => void save()}>{busy === 'write' ? '保存中…' : '保存'}</button>
              <button type="button" className="danger" disabled={busy !== '' || isMock}
                onClick={() => void remove()}>{busy === 'delete' ? '删除中…' : '删除'}</button>
            </div>
          </>
          : <p className="application-muted">从左侧点击一个文件查看内容。读取受 64 KiB 限制，二进制文件可能显示为乱码。</p>}
      </div>
    </div>
  </section>;
}

function normalizePath(value: string): string {
  try {
    return normalizeHostPath(value);
  } catch {
    return '/';
  }
}
