import { useCallback, useEffect, useState } from 'react';
import { api, isMock } from './lib/api';
import { latestValue, pointsAttribute, sparklineRuns, type SparkPoint } from './lib/sparkline.ts';
import type { Host, HostMetricSeries, HostPage } from './lib/types';
import './metrics.css';

const WIDTH = 420;
const HEIGHT = 64;

function Sparkline({ values, min, max, tone }: {
  values: (number | null)[]; min?: number; max?: number; tone: string;
}) {
  const runs: SparkPoint[][] = sparklineRuns(values, WIDTH, HEIGHT, min, max);
  if (runs.length === 0) return <p className="metrics-empty">暂无采样</p>;
  return <svg className="metrics-spark" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img"
    aria-label="指标趋势" preserveAspectRatio="none">
    <line x1="0" y1={HEIGHT / 2} x2={WIDTH} y2={HEIGHT / 2} className="metrics-spark-grid" />
    {runs.map((run, index) => <polyline key={index} points={pointsAttribute(run)}
      fill="none" stroke={tone} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />)}
  </svg>;
}

/**
 * 主机指标：CPU / 内存 / 负载。数据来自定时采样（Agent 上报 metrics.read 快照），
 * CPU 百分比是相邻采样的差值——第一条采样没有百分比，图上就是缺口，不会画成 0%。
 */
export function Metrics({ notify }: { notify: (message: string) => void }) {
  const [hosts, setHosts] = useState<Host[]>([]);
  const [hostId, setHostId] = useState('');
  const [series, setSeries] = useState<HostMetricSeries>();
  const [loading, setLoading] = useState(!isMock);
  const [error, setError] = useState(isMock
    ? 'Mock 演示环境未提供指标接口，切换到 Live API 后可见采样趋势。' : '');

  useEffect(() => {
    if (isMock) return;
    void api<HostPage>('/hosts?limit=100', 'GET', undefined, {}, true)
      .then(result => {
        setHosts(result.data.items);
        setHostId(current => current || result.data.items[0]?.id || '');
      })
      .catch(cause => setError((cause as Error).message));
  }, []);

  const load = useCallback(async () => {
    if (isMock || !hostId) return;
    setLoading(true);
    try {
      const result = await api<HostMetricSeries>(`/hosts/${hostId}/metrics?limit=60`, 'GET',
        undefined, {}, true);
      setSeries(result.data);
      setError(result.data.items.length === 0
        ? '这台主机还没有采样。面板每 30 秒向具备 metrics.read 能力的主机取一次快照。' : '');
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setLoading(false);
    }
  }, [hostId]);

  useEffect(() => { void load(); }, [load]);

  const values = (pick: (point: HostMetricSeries['items'][number]) => number | null) =>
    (series?.items ?? []).map(pick);
  const cpuValues = values(point => point.cpuBusyPercent);
  const memoryValues = values(point => point.memoryUsedPercent);
  const loadValues = values(point => point.load1);
  const latest = (list: (number | null)[], digits = 1) => {
    const value = latestValue(list);
    return value === null ? '未知' : `${value.toFixed(digits)}${list === loadValues ? '' : '%'}`;
  };

  return <>
    <div className="page-heading">
      <div><h1>监控</h1>
        <p>CPU、内存与负载来自主机的 <code>/proc</code> 快照，每 30 秒采样一次，保留
          {series?.retentionDays ?? 7} 天。CPU 使用率是相邻两次采样的差值，第一条采样没有百分比。</p></div>
      <button className="secondary" disabled={loading || !hostId} onClick={() => void load()}>
        {loading ? '读取中…' : '↻ 刷新'}</button>
    </div>
    <section className="card">
      <div className="toolbar">
        <select aria-label="选择主机" value={hostId} onChange={event => setHostId(event.target.value)}>
          {hosts.length === 0 && <option value="">没有已登记的主机</option>}
          {hosts.map(host => <option key={host.id} value={host.id}>{host.hostname} · {host.status}</option>)}
        </select>
        <span className="muted">{series ? `${series.items.length} 个采样点` : '—'}</span>
      </div>
      {error && <div className="alert">{error}</div>}
      {series && series.items.length > 0 && <div className="metrics-grid">
        <div className="metrics-card">
          <div className="metrics-card-heading"><strong>CPU 使用率</strong><span>{latest(cpuValues)}</span></div>
          <Sparkline values={cpuValues} min={0} max={100} tone="#a56bc4" />
        </div>
        <div className="metrics-card">
          <div className="metrics-card-heading"><strong>内存使用率</strong><span>{latest(memoryValues)}</span></div>
          <Sparkline values={memoryValues} min={0} max={100} tone="#38bdf8" />
        </div>
        <div className="metrics-card">
          <div className="metrics-card-heading"><strong>1 分钟负载</strong><span>{latest(loadValues)}</span></div>
          <Sparkline values={loadValues} tone="#22c55e" />
        </div>
      </div>}
      {series && series.items.length > 0 && <p className="muted metrics-note">
        缺口表示该时刻没有可用采样（主机离线，或该点无法计算 CPU 百分比）——面板不会把它画成 0。
      </p>}
    </section>
  </>;
}
