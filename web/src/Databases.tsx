import { useCallback, useEffect, useState } from 'react';
import { api, isMock } from './lib/api';
import { createUuid } from './lib/uuid.ts';
import type {
  DatabaseInstance, DatabaseList, Host, HostPage, HostHelperOperationRequest, Operation,
} from './lib/types';
import { ManagedDatabases } from './ManagedDatabases';
import './databases.css';

/**
 * 数据库：把已部署的数据库容器登记为实例并给出连接地址。读取只基于既有部署记录，
 * 不建立数据库连接；SQL 执行、备份与恢复需要真实主机，尚未实现。
 */
export function Databases({ notify }: { notify: (message: string) => void }) {
  const [instances, setInstances] = useState<DatabaseInstance[]>([]);
  const [hosts, setHosts] = useState<Host[]>([]);
  const [loading, setLoading] = useState(!isMock);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供数据库接口，切换到 Live API 后可查看实例与连接地址。' : '');

  const load = useCallback(async () => {
    if (isMock) return;
    setLoading(true);
    try {
      const result = await api<DatabaseList>('/databases', 'GET', undefined, {}, true);
      setInstances(result.data.items);
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

  async function remove(instance: DatabaseInstance) {
    const host = hosts.find(item => item.id === instance.hostId);
    if (!host) {
      setError('找不到该实例所属的主机，无法卸载');
      return;
    }
    if (!window.confirm(`卸载数据库实例 ${instance.containerName}？命名数据卷会保留，容器将被删除。`)) return;
    setBusy(instance.containerName);
    try {
      const request: HostHelperOperationRequest = {
        capability: 'container.remove', target: instance.containerName, payload: null,
        reason: `卸载数据库实例 ${instance.containerName}`, timeoutSeconds: 120,
        expectedGeneration: host.generation,
      };
      await api<Operation>(`/hosts/${host.id}/operations`, 'POST', request,
        { 'Idempotency-Key': createUuid() }, true);
      notify(`已提交卸载 ${instance.containerName}，数据卷保留`);
      await load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy('');
    }
  }

  return <section className="databases-workspace">
    <ManagedDatabases notify={notify} />
    <header className="databases-header">
      <div><h2>已发现的数据库容器</h2>
        <p>已部署的 MySQL / MariaDB / PostgreSQL / Redis / MongoDB 容器会在这里显示引擎、端口与连接地址。实例通过应用商店一键部署，卸载复用容器移除链路（命名数据卷保留）。SQL 执行、备份与恢复需要真实主机，尚未实现。</p></div>
    </header>
    {error && <div className="alert error" role="alert">{error}</div>}
    {loading && <p className="application-muted">正在读取数据库实例…</p>}
    {!loading && instances.length === 0 && <div className="card databases-empty">
      <strong>还没有数据库实例</strong>
      <p>到「应用商店」选择 MySQL / PostgreSQL / Redis 等应用一键部署，部署成功后会自动出现在这里。</p>
    </div>}
    <div className="databases-list">{instances.map(instance => <article className="card databases-card" key={`${instance.hostId}/${instance.containerName}`}>
      <div className="databases-card-head">
        <span className="databases-engine">{instance.engine.label}</span>
        <strong>{instance.containerName}</strong>
        <span className="databases-host">{instance.hostname}</span>
      </div>
      <dl className="databases-facts">
        <div><dt>镜像</dt><dd><code>{instance.image ?? '未知'}</code></dd></div>
        <div><dt>发布端口</dt><dd>{instance.hostPort ?? '未发布'}</dd></div>
        <div><dt>容器端口</dt><dd>{instance.containerPort ?? instance.engine.defaultPort}</dd></div>
      </dl>
      {instance.connectionUrl
        ? <div className="databases-connection"><span>连接地址</span><code>{instance.connectionUrl}</code></div>
        : <p className="databases-note">{instance.note || '容器未发布端口，无法从主机外部连接。'}</p>}
      <div className="databases-actions">
        <button type="button" className="secondary" disabled={isMock || busy === instance.containerName}
          onClick={() => void remove(instance)}>{busy === instance.containerName ? '提交中…' : '卸载实例'}</button>
      </div>
    </article>)}</div>
  </section>;
}
