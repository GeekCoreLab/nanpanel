# KubeEdge Provider status

The Provider is not implemented yet. The current KubeEdge support lives in Core
and provides bounded observation and ownership-safe Host mapping:

- Cluster refresh reports containerized CloudCore Pod readiness in the
  `kubeedge` namespace. Binary CloudCore is `NOT_OBSERVED`; API or RBAC failures
  are `UNKNOWN`.
- Node inventory derives Edge registration state from the Kubernetes Edge role
  label, Node presence, and Ready condition.
- A mapped Host's recent Agent observation of `edgecore.service` is reported
  separately from Kubernetes registration state. It is cached host evidence,
  not a synchronous EdgeCore health probe.
- Host-to-Node mapping is explicit and auditable. A mapped KubeEdge Host keeps
  Agent OS/network diagnostics, while Core rejects Agent `container.*`
  operations so KubeEdge remains the sole container lifecycle owner.

These Core paths have unit and integration coverage documented in
[`tests/p4/runs/2026-10-21-kubeedge-edgecore-process-observation.md`](../../tests/p4/runs/2026-10-21-kubeedge-edgecore-process-observation.md).
They do not install, join, upgrade, rotate credentials for, or remove
CloudCore/EdgeCore. No live KubeEdge target is available, so they are not live
KubeEdge acceptance.

## Provider boundary

`NetworkProvider` is the PF4J contract for network reconciliation; it is not a
Kubernetes node lifecycle API. `panel-plugin-api` defines a dedicated
`ClusterProvider` extension point, and `ProviderRuntime` loads it independently
from network providers and reports its ID in plugin status. The v2 contract
provides a Core-owned, read-only snapshot of cluster health, CloudCore status,
and Node identity/readiness. It does not provide a Kubernetes client,
kubeconfig, ServiceAccount token, or raw Kubernetes resources to the plugin.
The contract is cluster-scoped and identifies a cluster by Core registry UUID,
kind, and generation. A plan can describe ordered Kubernetes API or Agent
Helper steps; Helper steps must name a Host, and credentials are represented
only by opaque Core-owned UUID references. Plan parameters reject secret-like
key names. Core still owns durable operation state, audit redaction, typed
Agent/Helper dispatch, and reconciliation after restart.

Core now exposes an authenticated read-only Provider observation route that
uses cached inventory; it does not refresh the cluster or execute a plan. The
extension contract and loader still have no KubeEdge implementation or Core
lifecycle execution API. The v2 snapshot contract does not install CloudCore/EdgeCore,
register/remove an Edge node, upgrade components, rotate certificates, or
coordinate an ingress. Registration, version, certificate, component-health,
offline-autonomy, and reachable-ingress operations remain unimplemented. No
live KubeEdge target is available, so this change is not live KubeEdge
acceptance. The P4 gate stays open until the concrete provider, safe Core
execution/recovery paths, rejection coverage, and live KubeEdge evidence exist.
