/**
 * Route-plan snapshots: the canvas is a live editing surface, so comparing today's plan with the
 * last one someone deliberately stored answers the question a diff of YAML cannot — which entry
 * appeared, which backend was re-weighted, and by how much.
 *
 * The snapshot is intentionally small and stable: only what a reviewer needs to judge a change.
 */

import type { RoutePlanRow } from './application-routing.ts';

export type SnapshotBackend = { name: string; port: number; share: number };
export type SnapshotRoute = {
  id: string;
  serviceName: string;
  servicePort: number;
  backends: SnapshotBackend[];
};
export type RouteSnapshot = { routes: SnapshotRoute[] };

export type RouteChange =
  | { kind: 'ADDED'; routeId: string; label: string }
  | { kind: 'REMOVED'; routeId: string; label: string }
  | { kind: 'ENTRY_PORT'; routeId: string; label: string; from: number; to: number }
  | { kind: 'BACKEND_ADDED'; routeId: string; label: string; backend: string }
  | { kind: 'BACKEND_REMOVED'; routeId: string; label: string; backend: string }
  | { kind: 'WEIGHT'; routeId: string; label: string; backend: string; from: number; to: number };

const label = (route: RoutePlanRow) => `${route.serviceName}:${route.servicePort}`;

export function buildRouteSnapshot(plan: readonly RoutePlanRow[]): RouteSnapshot {
  return {
    routes: plan.map(route => ({
      id: route.id,
      serviceName: route.serviceName,
      servicePort: route.servicePort,
      backends: route.backends
        .map(backend => ({ name: backend.name, port: backend.port, share: backend.share }))
        .sort((left, right) => left.name.localeCompare(right.name)),
    })).sort((left, right) => left.id.localeCompare(right.id)),
  };
}

/** Compares a stored snapshot (previous) with the current plan (next). */
export function diffRouteSnapshots(previous: RouteSnapshot, next: RouteSnapshot): RouteChange[] {
  const changes: RouteChange[] = [];
  const before = new Map(previous.routes.map(route => [route.id, route]));
  const after = new Map(next.routes.map(route => [route.id, route]));

  for (const route of next.routes) {
    const old = before.get(route.id);
    const name = `${route.serviceName}:${route.servicePort}`;
    if (!old) {
      changes.push({ kind: 'ADDED', routeId: route.id, label: name });
      continue;
    }
    if (old.servicePort !== route.servicePort)
      changes.push({
        kind: 'ENTRY_PORT', routeId: route.id, label: name,
        from: old.servicePort, to: route.servicePort,
      });
    const previousBackends = new Map(old.backends.map(backend => [backend.name, backend]));
    const nextBackends = new Map(route.backends.map(backend => [backend.name, backend]));
    for (const backend of route.backends) {
      const was = previousBackends.get(backend.name);
      if (!was) {
        changes.push({
          kind: 'BACKEND_ADDED', routeId: route.id, label: name, backend: backend.name,
        });
      } else if (was.share !== backend.share) {
        changes.push({
          kind: 'WEIGHT', routeId: route.id, label: name, backend: backend.name,
          from: was.share, to: backend.share,
        });
      }
    }
    for (const backend of old.backends) {
      if (!nextBackends.has(backend.name))
        changes.push({
          kind: 'BACKEND_REMOVED', routeId: route.id, label: name, backend: backend.name,
        });
    }
  }
  for (const route of previous.routes) {
    if (!after.has(route.id))
      changes.push({
        kind: 'REMOVED', routeId: route.id,
        label: `${route.serviceName}:${route.servicePort}`,
      });
  }
  return changes;
}

/** One-line Chinese description, so the panel never shows a bare kind to an operator. */
export function describeRouteChange(change: RouteChange): string {
  switch (change.kind) {
    case 'ADDED': return `新增路由 ${change.label}`;
    case 'REMOVED': return `移除路由 ${change.label}`;
    case 'ENTRY_PORT': return `${change.label} 入口端口 ${change.from} → ${change.to}`;
    case 'BACKEND_ADDED': return `${change.label} 新增后端 ${change.backend}`;
    case 'BACKEND_REMOVED': return `${change.label} 移除后端 ${change.backend}`;
    case 'WEIGHT': return `${change.label} 后端 ${change.backend} 权重 ${change.from}% → ${change.to}%`;
  }
}
