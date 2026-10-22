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
Kubernetes node lifecycle API. A KubeEdge Provider must use a dedicated,
cluster-scoped contract and the existing Core cluster registry. It must keep
Kubernetes workload ownership in the Kubernetes API, use typed Agent/Helper
operations for host-side work, and never pass join credentials through durable
operation output or audit details. Registration, version, certificate,
component-health, offline-autonomy, and reachable-ingress lifecycle operations
remain unimplemented. The P4 gate stays open until those paths have rejection,
recovery, and live KubeEdge evidence.
