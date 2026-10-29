/**
 * Derive read-only Service -> workload relationships for an existing Kubernetes
 * namespace from Service selectors and workload pod-template labels. This makes
 * "现成资源" legible in the console without reading YAML or a separate API gateway:
 * a Service selects a workload when its selector is a subset of the pod labels.
 *
 * A pure function so the relationship semantics are unit-tested independently of
 * the React inventory view.
 */

export type NamespaceResourceLike = {
  kind: string;
  name: string;
  selector?: Record<string, string> | null;
  podLabels?: Record<string, string> | null;
};

export type SelectorEdge = {
  service: string;
  workload: string;
  workloadKind: string;
  sharedLabels: Record<string, string>;
};

const WORKLOAD_KINDS = new Set(['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Pod']);

export function deriveNamespaceSelectorEdges(
  items: readonly NamespaceResourceLike[],
): SelectorEdge[] {
  const workloads = items.filter(item =>
    WORKLOAD_KINDS.has(item.kind) && item.podLabels && Object.keys(item.podLabels).length > 0);
  const edges: SelectorEdge[] = [];
  for (const service of items) {
    if (service.kind !== 'Service') continue;
    const selector = service.selector ?? {};
    const keys = Object.keys(selector);
    if (keys.length === 0) continue;
    for (const workload of workloads) {
      const labels = workload.podLabels ?? {};
      if (keys.every(key => labels[key] === selector[key])) {
        edges.push({
          service: service.name,
          workload: workload.name,
          workloadKind: workload.kind,
          sharedLabels: Object.fromEntries(keys.map(key => [key, selector[key]])),
        });
      }
    }
  }
  return edges;
}

export type ManagedApplicationLike = {
  id: string; name: string; namespace: string; clusterId: string;
};

/**
 * Index NaNPanel-managed applications by the graph node id they own, scoped to a
 * single cluster+namespace. Only these nodes may offer "open in canvas"; anything
 * absent here is external and stays read-only.
 */
export function buildManagedAppIndex(
  apps: readonly ManagedApplicationLike[], clusterId: string, namespace: string,
): Record<string, string> {
  const index: Record<string, string> = {};
  if (!clusterId || !namespace) return index;
  for (const app of apps) {
    if (app.clusterId !== clusterId || app.namespace !== namespace) continue;
    index[`Deployment/${app.name}`] = app.id;
    index[`Service/${app.name}`] = app.id;
  }
  return index;
}

export type GraphNode = {
  id: string;
  label: string;
  kind: string;
  column: 0 | 1;
  x: number;
  y: number;
};

export type GraphEdge = { id: string; source: string; target: string; label: string };

export type NamespaceGraphLayout = { nodes: GraphNode[]; edges: GraphEdge[] };

const SETTLED_COLUMN_X = 0;
const WORKLOAD_COLUMN_X = 360;
const ROW_GAP = 96;

/**
 * Deterministic two-column layout: Services on the left, workloads on the right,
 * connected by selector-derived edges. Pure so positions and edge wiring are
 * unit-tested; the React view only maps these to React Flow nodes/edges.
 */
export function layoutNamespaceGraph(
  items: readonly NamespaceResourceLike[],
): NamespaceGraphLayout {
  const services = items.filter(item => item.kind === 'Service');
  const workloads = items.filter(item => WORKLOAD_KINDS.has(item.kind));
  const nodes: GraphNode[] = [
    ...services.map((service, row) => ({
      id: `svc:${service.name}`, label: service.name, kind: service.kind,
      column: 0 as const, x: SETTLED_COLUMN_X, y: row * ROW_GAP,
    })),
    ...workloads.map((workload, row) => ({
      id: `wl:${workload.kind}/${workload.name}`, label: workload.name, kind: workload.kind,
      column: 1 as const, x: WORKLOAD_COLUMN_X, y: row * ROW_GAP,
    })),
  ];
  const edges = deriveNamespaceSelectorEdges(items).map(edge => ({
    id: `svc:${edge.service}->wl:${edge.workloadKind}/${edge.workload}`,
    source: `svc:${edge.service}`,
    target: `wl:${edge.workloadKind}/${edge.workload}`,
    label: Object.entries(edge.sharedLabels).map(([key, value]) => `${key}=${value}`).join(' · '),
  }));
  return { nodes, edges };
}