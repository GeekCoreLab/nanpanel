/**
 * Client-side host path helpers for the file manager. They only shape what the
 * console sends: the Helper still validates every path server-side (absolute path,
 * no `.`/`..`, no symlink escape), so these are convenience, never a security boundary.
 */

/** Collapse duplicate slashes and trailing slashes; `..` segments are rejected. */
export function normalizeHostPath(path: string): string {
  const trimmed = (path ?? '').trim();
  if (!trimmed) return '/';
  if (!trimmed.startsWith('/')) throw new Error('主机路径必须是绝对路径');
  const segments: string[] = [];
  for (const segment of trimmed.split('/')) {
    if (!segment || segment === '.') continue;
    if (segment === '..') throw new Error('主机路径不能包含 ..');
    segments.push(segment);
  }
  return `/${segments.join('/')}`;
}

/** Parent directory, or null at the filesystem root. */
export function parentHostPath(path: string): string | null {
  const normalized = normalizeHostPath(path);
  if (normalized === '/') return null;
  const index = normalized.lastIndexOf('/');
  return index <= 0 ? '/' : normalized.slice(0, index);
}

/** Join a directory and a single entry name. */
export function joinHostPath(directory: string, name: string): string {
  const base = normalizeHostPath(directory);
  const entry = (name ?? '').trim();
  if (!entry || entry.includes('/')) throw new Error('条目名称无效');
  if (entry === '.' || entry === '..') throw new Error('条目名称无效');
  return base === '/' ? `/${entry}` : `${base}/${entry}`;
}
