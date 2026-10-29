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