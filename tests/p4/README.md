# P4 K3s / KubeEdge acceptance

P4 is complete only when every gate below has an implementation, rejection-path
coverage, and a reproducible report. A green unit test or a read-only topology
view alone does not complete the stage.

| Gate | Required evidence |
| --- | --- |
| Multi-cluster import | Import two independently addressed clusters using explicit kubeconfig contexts; reject invalid credentials, exec plugins, insecure TLS, unauthorized RBAC, duplicate names, and credential leakage. |
| Node mapping | Reconcile ClusterNodes by Kubernetes UID, explicitly map/unmap Hosts, reject conflicting mappings, and preserve both identities after either side is removed. |
| Basic resource management | Exercise only documented, namespace-scoped operations using the imported cluster's RBAC; reject cross-cluster IDs, unsupported kinds, unowned destructive changes, and missing permissions. |
| K3s install and upgrade | Exercise Agent-mediated server/agent installation and upgrade plans, preflight blockers, one-node-at-a-time ordering, health gates, timeout/failure reporting, and recovery without arbitrary shell access. |
| KubeEdge ownership | Verify CloudCore/EdgeCore status and registration state; prove an explicitly mapped EdgeCore Host still supports OS diagnostics while Agent container operations are denied. |
| K3s Service ingress | From a normal Linux Gateway, route through an explicit ingress/Service mapping to a Ready K3s endpoint; prove health, authorization, and rollback/failure reporting. |
| Control-plane failure | Block the Kubernetes API and prove Host Agent diagnostics remain available; report cluster observations stale without marking them healthy or stopping EdgeCore. |
| Data ownership | Verify the Kubernetes API is the sole container lifecycle owner on KubeEdge nodes and that Core does not issue duplicate Agent container operations. |

Evidence must name the exact image/commit, cluster fixture, commands, result,
and limitations. Destructive upgrade and ingress experiments belong in a
disposable cluster, never the production `k3s-1` cluster.

## Current source boundary

- Cluster import, per-cluster credentials, Kubernetes UID node reconciliation,
  explicit Host mapping, and namespace-scoped registered Deployment CRUD are
  implemented. Invalid kubeconfig forms, credential rejection, RBAC denial,
  cross-cluster resource IDs, invalid replica counts, and unowned Deployment
  changes have code-level rejection paths. These checks do not replace a
  two-cluster live import report.
- KubeEdge summaries now report containerized CloudCore Pod readiness from the
  `kubeedge` namespace. If no matching Pod is found the status is
  `NOT_OBSERVED`; permission or API failures report `UNKNOWN`. Binary CloudCore
  is not observed. EdgeCore registration is currently inferred from the edge
  Node role and Ready condition; Core does not install, join, upgrade, or remove
  EdgeCore nodes. The KubeEdge management gate remains open.
- K3s installation/upgrade orchestration and Linux Gateway-to-Service ingress
  are not implemented. No destructive tests have run against `k3s-1`.
- The latest local checks cover Maven Core/module tests, Web build, Web mock
  tests, and the topology browser scenario. They are not a K3s or KubeEdge live
  acceptance report.
