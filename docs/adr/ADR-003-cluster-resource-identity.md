# ADR-003: Keep Host, ClusterNode, Workload, and Service identities separate

- Status: Accepted
- Date: 2026-10-21

## Context

A Kubernetes Node is a cluster-scoped object, not a Host asset. Kubernetes names
can be reused after a node is replaced, while Kubernetes UIDs identify one
object incarnation. A Host may have an Agent even when the cluster API is
unreachable. Workloads and logical Services also have independent lifecycles.

## Decision

- A registered Cluster has a platform UUID and a stable user-visible name.
- A ClusterNode is identified by `(cluster_id, Kubernetes node UID)`. Its name,
  roles, readiness, version, and observation time are refreshed from that
  cluster's API.
- A ClusterNode may map to at most one Host asset. The mapping is explicit and
  audited; matching by hostname or IP is only a suggestion.
- A Workload is identified by `(cluster_id, Kubernetes object UID)`, not by its
  mutable name. A logical Service has its own platform identity and can map to
  one or more observed endpoints.
- Removing a mapping never deletes either the Host or Kubernetes object.

## Consequences

Cluster API failures mark cluster observations stale or unavailable. They do
not change Host/Agent identity or prevent direct Host diagnostics. Resource
operations always resolve a registered cluster UUID and use that cluster's
least-privilege credentials.
