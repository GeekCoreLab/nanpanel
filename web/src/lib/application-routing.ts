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
  code: 'DUPLICATE_ENTRY' | 'NO_BACKENDS' | 'ZERO_WEIGHT';
  message: string;
  routeIds: string[];
};

/**
 * Flags plan-level problems that would make the compiled Istio route ambiguous or
 * useless: the same Service:port drawn from two route entries, an entry with no
 * backend, or an entry whose backends all carry zero weight. Pure so the guard is
 * unit-tested and the canvas only renders the result.
 */
export function validateRoutePlan(plan: readonly RoutePlanRow[]): RoutePlanIssue[] {
  const issues: RoutePlanIssue[] = [];
  const byService = new Map<string, RoutePlanRow[]>();
  for (const row of plan) {
    const key = `${row.serviceName}:${row.servicePort}`;
    byService.set(key, [...(byService.get(key) ?? []), row]);
    if (row.backends.length === 0) {
      issues.push({ code: 'NO_BACKENDS', message: `${key} 未连接后端 Service`, routeIds: [row.id] });
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
  return issues;
}

export type RoutePlanBackend = { name: string; weight: number; share: number };

export type RoutePlanRow = {
  id: string;
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
      id: entry.id, serviceName, servicePort: entry.servicePort, totalWeight,
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