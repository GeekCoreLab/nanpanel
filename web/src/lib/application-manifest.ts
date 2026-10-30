/**
 * Export a managed application as a multi-document YAML bundle: the resource sources the
 * console already holds plus the routing bodies the canvas currently compiles to.
 *
 * Deliberately not applied anywhere — it is an archive/GitOps artifact. Runtime state
 * (revision, applied routes, TLS material) is excluded, and resources that only exist as a
 * pending addition are skipped because they have no source yet.
 */

import { stringify } from 'yaml';
import { buildRouteBody } from './route-manifest.ts';
import type { RoutePlanRow } from './application-routing.ts';

export type ManifestResource = { kind: string; name: string; sourceYaml: string };

export type ManifestInput = {
  applicationName: string;
  namespace: string;
  resources: readonly ManifestResource[];
  routes: readonly RoutePlanRow[];
  exportedAt: string;
};

export type ManifestBundle = {
  yaml: string;
  resourceCount: number;
  routeCount: number;
  skipped: string[];
};

const HEADER = [
  '# NaNPanel application bundle',
  '# 由控制台根据当前资源源码与画布路由编译生成，用于归档 / GitOps。',
  '# 不含运行时状态、已下发的 Istio 资源、证书与密钥。',
  '# VirtualService 名称为 np-route-<service> 占位，实际下发名由服务端生成。',
];

export function buildApplicationBundle(input: ManifestInput): ManifestBundle {
  const documents: string[] = [];
  const skipped: string[] = [];
  let resourceCount = 0;
  for (const resource of input.resources) {
    const source = (resource.sourceYaml ?? '').trim();
    if (!source) {
      skipped.push(`${resource.kind}/${resource.name}`);
      continue;
    }
    // Strip an existing YAML document marker so the bundle has exactly one separator per doc.
    documents.push(source.replace(/^---\s*\n/, '').trimEnd());
    resourceCount++;
  }

  let routeCount = 0;
  for (const route of input.routes) {
    if (route.backends.length === 0) {
      skipped.push(`VirtualService/${route.serviceName}:${route.servicePort}`);
      continue;
    }
    documents.push(stringify({
      apiVersion: 'networking.istio.io/v1beta1',
      kind: 'VirtualService',
      metadata: { name: `np-route-${route.serviceName}`, namespace: input.namespace || undefined },
      spec: buildRouteBody(route, input.namespace),
    }).trimEnd());
    routeCount++;
  }

  const header = [
    ...HEADER,
    `# application: ${input.applicationName}`,
    `# namespace: ${input.namespace || '(未指定)'}`,
    `# exportedAt: ${input.exportedAt}`,
    `# resources: ${resourceCount}, routes: ${routeCount}`,
  ].join('\n');

  const yaml = documents.length === 0
    ? `${header}\n`
    : `${header}\n---\n${documents.join('\n---\n')}\n`;
  return { yaml, resourceCount, routeCount, skipped };
}
