/**
 * The helper runs `docker ps --all --format "{{.Names}} {{.Status}}"` and returns stdout,
 * so the console has to turn that text back into rows. Kept pure so the parsing rules are
 * unit-tested: a stopped container must never render as running, and a name is the first
 * token (container names cannot contain spaces, but the status can).
 */

export type ContainerEntry = {
  name: string;
  status: string;
  running: boolean;
  paused: boolean;
};

export type ContainerState = 'RUNNING' | 'PAUSED' | 'STOPPED' | 'CREATED' | 'RESTARTING' | 'UNKNOWN';

export function containerState(entry: ContainerEntry): ContainerState {
  if (entry.paused) return 'PAUSED';
  if (entry.running) return 'RUNNING';
  const status = entry.status.toLowerCase();
  if (status.startsWith('restarting')) return 'RESTARTING';
  if (status.startsWith('created')) return 'CREATED';
  if (status.startsWith('exited') || status.startsWith('dead') || status.startsWith('removal'))
    return 'STOPPED';
  return 'UNKNOWN';
}

export const CONTAINER_STATE_LABELS: Record<ContainerState, string> = {
  RUNNING: '运行中',
  PAUSED: '已暂停',
  STOPPED: '已停止',
  CREATED: '已创建',
  RESTARTING: '重启中',
  UNKNOWN: '未知',
};

export function parseContainerList(output: string | null | undefined): ContainerEntry[] {
  if (!output) return [];
  const entries: ContainerEntry[] = [];
  for (const rawLine of output.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    const separator = line.indexOf(' ');
    const name = separator < 0 ? line : line.slice(0, separator);
    const status = separator < 0 ? '' : line.slice(separator + 1).trim();
    if (!name) continue;
    entries.push({
      name,
      status,
      running: /^up\b/i.test(status),
      paused: /\(paused\)/i.test(status),
    });
  }
  return entries;
}
