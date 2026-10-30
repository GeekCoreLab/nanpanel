/**
 * Compile the drag-drawn application traffic graph into a human-readable weighted
 * route plan. This mirrors the Istio VirtualService intent the canvas produces:
 * a stable Service (ROUTE_ENTRY) fanning out to backend Services (ROUTE_BACKEND)
 * through a LOAD_BALANCER whose output weights are normalized to percentages.
 *
 * Kept as a pure function so the routing semantics are unit-tested independently
 * of React state, and so the UI and any future exporter share one implementation.
 */

export type TrafficLinkLike = {
  id: string;
  type: 'CALL' | 'ROUTE_ENTRY' | 'ROUTE_BACKEND';
  sourceId: string;
  sourceHandle: string;
  targetId: string;
  targetHandle: string;
  servicePort: number;
};

export type LoadBalancerNodeLike = {
  id: string;
  loadBalancerConfiguration?: { outputWeights: { outputPort: string; weight: number }[] } | null;
};

export type RoutePlanIssue = {
  code: 'DUPLICATE_ENTRY' | 'SHARED_BALANCER' | 'ONE_BACKEND' | 'ZERO_WEIGHT';
  message: string;
  routeIds: string[];
};

/**
 * Flags plan-level problems a weighted Istio route cannot express. The rules mirror
 * the backend traffic reconciler exactly, so the canvas never offers an apply the
 * server will reject: one route entry per Service:port, one entry per balancer, at
 * least two backends per entry, and at least one non-zero backend weight.
 */
export function validateRoutePlan(plan: readonly RoutePlanRow[]): RoutePlanIssue[] {
  const issues: RoutePlanIssue[] = [];
  const byService = new Map<string, RoutePlanRow[]>();
  const byBalancer = new Map<string, RoutePlanRow[]>();
  for (const row of plan) {
    const key = `${row.serviceName}:${row.servicePort}`;
    byService.set(key, [...(byService.get(key) ?? []), row]);
    byBalancer.set(row.balancerId, [...(byBalancer.get(row.balancerId) ?? []), row]);
    if (row.backends.length < 2) {
      issues.push({
        code: 'ONE_BACKEND',
        message: row.backends.length === 0
          ? `${key} 未连接后端 Service（需要至少两个）`
          : `${key} 只连接了 1 个后端 Service（需要至少两个）`,
        routeIds: [row.id],
      });
    } else if (row.totalWeight === 0) {
      issues.push({ code: 'ZERO_WEIGHT', message: `${key} 的后端权重全为 0`, routeIds: [row.id] });
    }
  }
  for (const [key, rows] of byService) {
    if (rows.length > 1) {
      issues.push({
        code: 'DUPLICATE_ENTRY',
        message: `${key} 被 ${rows.length} 个路由入口重复定义`,
        routeIds: rows.map(row => row.id),
      });
    }
  }
  for (const rows of byBalancer.values()) {
    if (rows.length > 1) {
      const services = rows.map(row => `${row.serviceName}:${row.servicePort}`).join('、');
      issues.push({
        code: 'SHARED_BALANCER',
        message: `同一个流量分配器被 ${services} 同时驱动`,
        routeIds: rows.map(row => row.id),
      });
    }
  }
  return issues;
}

export type RoutePlanBackend = { name: string; weight: number; share: number };

export type RoutePlanRow = {
  id: string;
  balancerId: string;
  serviceName: string;
  servicePort: number;
  totalWeight: number;
  backends: RoutePlanBackend[];
  callers: string[];
};

const DEFAULT_WEIGHT = 100;

export function compileApplicationRoutePlan(
  trafficLinks: readonly TrafficLinkLike[],
  nodes: readonly LoadBalancerNodeLike[],
  resourceName: (id: string) => string | undefined,
): RoutePlanRow[] {
  const nodeById = new Map(nodes.map(node => [node.id, node]));
  return trafficLinks.filter(link => link.type === 'ROUTE_ENTRY').map(entry => {
    const serviceName = resourceName(entry.sourceId) ?? 'Service';
    const balancer = nodeById.get(entry.targetId);
    const weights = new Map((balancer?.loadBalancerConfiguration?.outputWeights ?? [])
      .map(item => [item.outputPort, item.weight] as const));
    const backends = trafficLinks
      .filter(link => link.type === 'ROUTE_BACKEND' && link.sourceId === entry.targetId)
      .map(link => {
        const outputPort = link.sourceHandle.replace(/^route-/, '');
        return {
          name: resourceName(link.targetId) ?? 'Service',
          weight: weights.get(outputPort) ?? DEFAULT_WEIGHT,
        };
      });
    const totalWeight = backends.reduce((sum, backend) => sum + backend.weight, 0);
    return {
      id: entry.id, balancerId: entry.targetId, serviceName,
      servicePort: entry.servicePort, totalWeight,
      backends: backends.map(backend => ({
        ...backend,
        share: totalWeight === 0 ? 0 : Math.round(backend.weight / totalWeight * 100),
      })),
      callers: trafficLinks
        .filter(link => link.type === 'CALL' && link.targetId === entry.sourceId
          && link.servicePort === entry.servicePort)
        .map(link => resourceName(link.sourceId))
        .filter((name): name is string => !!name),
    };
  });
}
export type AppliedRouteBackendLike = { service: string; port: number; weight: number };

export type AppliedRouteLike = {
  name: string;
  service: string;
  port: number;
  backends: readonly AppliedRouteBackendLike[];
};

export type RouteDriftStatus = 'MATCH' | 'DRIFT' | 'NOT_APPLIED' | 'STALE';

export type RouteDriftRow = {
  status: RouteDriftStatus;
  key: string;
  message: string;
  routeId?: string;
  appliedName?: string;
};

const routeKey = (service: string, port: number) => `${service}:${port}`;

/**
 * Compare what the canvas draws with what the cluster was actually told. The applied
 * side is the snapshot the panel wrote, so this catches both "I changed the weights but
 * never applied" and "the cluster runs a route the canvas no longer declares" — the two
 * mistakes a drag-and-drop router invites. Weights are compared with a one-point
 * tolerance because the canvas rounds percentages and the server normalizes integers.
 */
export function compareRoutePlan(
  plan: readonly RoutePlanRow[],
  applied: readonly AppliedRouteLike[],
): RouteDriftRow[] {
  const remaining = new Map(applied.map(route => [routeKey(route.service, route.port), route]));
  const rows: RouteDriftRow[] = [];
  for (const route of plan) {
    const key = routeKey(route.serviceName, route.servicePort);
    const live = remaining.get(key);
    if (!live) {
      rows.push({
        status: 'NOT_APPLIED', key, routeId: route.id,
        message: `${key} 在画布上存在，但尚未写入集群`,
      });
      continue;
    }
    remaining.delete(key);
    const expected = new Map(route.backends.map(backend => [backend.name, backend.share] as const));
    const actual = new Map(live.backends.map(backend => [backend.service, backend.weight] as const));
    const sameBackends = expected.size === actual.size
      && [...expected.keys()].every(name => actual.has(name));
    const sameWeights = sameBackends
      && [...expected].every(([name, share]) => Math.abs(share - (actual.get(name) ?? 0)) <= 1);
    if (sameWeights) {
      rows.push({
        status: 'MATCH', key, routeId: route.id, appliedName: live.name,
        message: `${key} 与已下发一致`,
      });
      continue;
    }
    const described = (entries: Iterable<[string, number]>) => {
      const parts = [...entries].map(([name, share]) => `${name} ${share}%`);
      return parts.length === 0 ? '无后端' : parts.join(' / ');
    };
    rows.push({
      status: 'DRIFT', key, routeId: route.id, appliedName: live.name,
      message: `${key} 画布为 ${described(expected)}，已下发为 ${described(actual)}`,
    });
  }
  for (const route of remaining.values()) {
    const key = routeKey(route.service, route.port);
    rows.push({
      status: 'STALE', key, appliedName: route.name,
      message: `${key} 集群仍有该路由，但画布已不再声明`,
    });
  }
  return rows;
}
