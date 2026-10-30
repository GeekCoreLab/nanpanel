/**
 * Preview of the VirtualService body a route plan produces. Hosts and weighted
 * destinations mirror what the server builds; the resource name, labels and topology
 * annotations are added server-side (they depend on the application id and revision), so
 * this is a preview of the routing body — not a file to apply by hand.
 */

import type { RoutePlanRow } from './application-routing.ts';

export type RouteBody = {
  hosts: string[];
  gateways: string[];
  http: {
    match: { port: number }[];
    route: { destination: { host: string; port: { number: number } }; weight: number }[];
  }[];
};

export function clusterHost(service: string, namespace: string): string {
  return namespace.trim() ? `${service}.${namespace.trim()}.svc.cluster.local` : service;
}

export function buildRouteBody(row: RoutePlanRow, namespace: string): RouteBody {
  const weighted = row.backends.filter(backend => backend.share > 0);
  // Weights must total 100 for Istio; the canvas shares are already normalized, so the
  // remainder of the rounding goes to the first backend instead of producing 99%.
  const total = weighted.reduce((sum, backend) => sum + backend.share, 0);
  const routes = weighted.map((backend, index) => ({
    destination: {
      host: clusterHost(backend.name, namespace),
      port: { number: backend.port },
    },
    weight: index === 0 && total > 0 && total !== 100
      ? backend.share + (100 - total)
      : backend.share,
  }));
  return {
    hosts: [clusterHost(row.serviceName, namespace)],
    gateways: ['mesh'],
    http: [{ match: [{ port: row.servicePort }], route: routes }],
  };
}
