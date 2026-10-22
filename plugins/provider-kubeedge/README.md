# KubeEdge Provider status

The PF4J `kubeedge-core` provider is planning-only. It plans
`install_cloudcore` only for a registered KUBEEDGE cluster when the Core
snapshot reports CloudCore as `NOT_OBSERVED`. That plan contains one
`kubeedge.cloudcore.install` Kubernetes API step with a version, namespace,
and release name. It also describes `join_edgecore` only when CloudCore is
healthy, the request names a Host, and it supplies a CloudCore `host:port`
endpoint and an opaque credential reference. The resulting single
`kubeedge.edgecore.join` Agent Helper step stores the endpoint and the
`EDGECORE_BOOTSTRAP_TOKEN` purpose, while Core checks that the Host exists and
is not already mapped to a present Node in this cluster. Restore and validation
re-read the Core-owned snapshot and reject changed generation, unhealthy
CloudCore, or a Host that became registered. The provider has no Kubernetes
client, never receives credential material, and performs no cluster writes or
Helper dispatch.

The current KubeEdge support in Core also provides bounded observation and
ownership-safe Host mapping:

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
CloudCore/EdgeCore. The provider JAR is packaged in the deployed Core image
`sha-3ae2c2345bbddb932c343b1f19e64662c252172a` and present in `/work/providers`
on `k3s-1`; the authenticated provider-list route could not be checked because
the current admin login was rejected. No live KubeEdge target is available, so
this is not live KubeEdge acceptance.

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
key names. Core owns durable operation state, audit redaction, typed
Agent/Helper dispatch, and reconciliation after restart.

Core now exposes an authenticated read-only Provider observation route that
uses cached inventory; it does not refresh the cluster. Core also has a
durable plan and validation API deployed to `k3s-1`, with idempotency,
generation drift checks, and Provider state restoration covered by isolated
Core integration tests. The deployment ran its database migration and passed
readiness checks; an authenticated production plan request has not been
accepted yet. Core now has encrypted, cluster/provider-scoped KubeEdge credential
storage with metadata-only list/create responses, recent reauthentication for
write/revoke, and one-way revocation. Core accepts a credential reference only when the Provider returns the same opaque UUID in its persisted plan and rechecks its provider/cluster scope, purpose, expiry and revocation during restoration. The EdgeCore join plan references (but does not open) an `EDGECORE_BOOTSTRAP_TOKEN`; no plan execution path opens a stored secret. The API does
not execute plan steps. The snapshot and planning provider do not install CloudCore/EdgeCore,
register/remove an Edge node, upgrade components, rotate certificates, or
coordinate an ingress. Registration, version, certificate, component-health,
offline-autonomy, and reachable-ingress operations remain unimplemented. No
live KubeEdge target is available, so this change is not live KubeEdge
acceptance. The P4 gate stays open until credentials can be safely referenced
and consumed by Core execution/recovery paths, rejection coverage passes, and
live KubeEdge evidence exists.
