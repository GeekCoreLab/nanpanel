import { mockRequest } from './mock';
export const isMock = import.meta.env.VITE_API_MODE === 'mock';
export const mockBusiness = import.meta.env.VITE_API_MODE !== 'live';
let csrfToken = '';
export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 400) { super(message); }
}
export async function api<T>(path: string, method = 'GET', body?: unknown, headers: Record<string, string> = {}, realInHybrid = false): Promise<{ data: T; etag: string | null }> {
  const options: RequestInit = { method, credentials: 'include', headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(method !== 'GET' ? { 'X-CSRF-Token': csrfToken } : {}), ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) };
  const authPath = path.startsWith('/auth/');
  const pathname = new URL(path, window.location.origin).pathname;
  const accountPath = /^\/admin\/(users(?:\/|$)|roles$|settings$)/.test(pathname) || pathname.startsWith('/admin/audit-events');
  const realPath = authPath || accountPath;
  if (!isMock && mockBusiness && !realPath && !realInHybrid) await api('/auth/me');
  const response = (isMock || (mockBusiness && !realPath && !realInHybrid)) ? await mockRequest(path, options, !isMock && mockBusiness && !realPath) : await fetch('/api/v1' + path, options);
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    const error = new ApiError(problem.code ?? 'REQUEST_FAILED', problem.detail ?? `请求失败 (${response.status})`, response.status);
    window.dispatchEvent(new CustomEvent('api-error', { detail: error }));
    throw error;
  }
  const data = response.status === 204 ? null : await response.json();
  if (data?.csrfToken) csrfToken = data.csrfToken;
  return { data, etag: response.headers.get('ETag') };
}
