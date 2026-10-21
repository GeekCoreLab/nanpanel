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
  changes have code-level rejection paths. A successfully observed Node removal
  retains its mapped UID and Host as a non-present tombstone; container
  operations remain Kubernetes-owned until an explicit unmap, and the Host
  asset remains independent. Core integration coverage verifies conflicting
  Host mapping returns `409`, the removed-node tombstone, remapping rejection,
  host diagnostics, and explicit release. The target-K3s live report below
  covers one-node import, UID mapping, and scoped Deployment CRUD; broader
  target K3s behavior remains open.
- A local two-cluster Kind API integration report is recorded at
  `tests/p4/runs/2026-10-21-kind-multi-cluster-import.md`. It covers independent
  API endpoints, namespace-scoped Deployment create/list/scale/delete, ownership
  and cross-cluster rejection, and RBAC paths. Kind is only the local API/CRUD
  test fixture: it does not satisfy any target K3s acceptance. The separate live
  K3s report below covers only one-cluster import/mapping and scoped Deployment
  CRUD; target multi-cluster acceptance remains open.
- Topology route matching now shares one resolver for displayed summaries and
  observed edges. `web/scripts/topology.test.mjs` covers direct routes, one
  distinct Host owner, duplicate references to the same owner, ambiguous
  multiple owners, self-addresses, and unmatched gateways; the browser fixture
  confirms a unique next-hop match. The target inventory records an active
  Agent and `k3s-1` default gateway `10.0.0.1`, but no authenticated production
  Core evidence maps that address to a Host asset. Target route matching remains
  open; see `runs/2026-10-21-k3s-target-inventory.md`.
- A live target-K3s smoke report is recorded at
  `tests/p4/runs/2026-10-21-k3s-target-api-smoke.md`. Using a disposable local
  Core/database and a short-lived, namespace-scoped K3s credential, it imported
  `k3s-1` as K3S, read its Node UID, mapped a temporary Host asset, browsed the
  live Namespace/Ingress/Service/Pod topology snapshot, and exercised
  Deployment create/list/scale/delete in a dedicated temporary namespace. The
  disposable Ingress used an unregistered class and `.invalid` hostname; no
  controller or public traffic path was exercised. Removing the temporary
  topology-read RoleBinding produced `403`, marked cluster observations stale,
  and left the Host inventory endpoint available. The namespace and temporary
  RBAC were verified removed. This closes the single-cluster API, topology read,
  and target RBAC-denial slice; multi-cluster isolation, multiple-Node mapping
  conflicts/removal, Agent diagnostics on a real mapped host, and host route
  next-hop matching on the target remain open.
- KubeEdge summaries now report containerized CloudCore Pod readiness from the
  `kubeedge` namespace. If no matching Pod is found the status is
  `NOT_OBSERVED`; permission or API failures report `UNKNOWN`. Binary CloudCore
  is not observed. Node inventory returns `edgeCoreRegistrationStatus`
  (`NOT_EDGE_NODE`, `NOT_PRESENT`, `REGISTERED_NOT_READY`, or
  `REGISTERED_READY`) from the Edge role label, Node presence, and Ready
  condition. For explicitly mapped Edge nodes, it also joins the latest
  `edgecore.service` systemd observation from the Host Agent and reports
  `edgeCoreProcessStatus` as `ACTIVE`, `INACTIVE`, `STALE`, `NOT_OBSERVED`,
  `NOT_MAPPED`, or `UNKNOWN`. Active/inactive requires both the service
  observation and Agent heartbeat to be fresh. This is cached host service
  evidence, not a synchronous process probe. Core still does not install, join,
  upgrade, or remove EdgeCore nodes. The code-level report is at
  `tests/p4/runs/2026-10-21-kubeedge-edgecore-process-observation.md`; the
  KubeEdge management gate remains open.
- Cluster summaries expose `observationsStale` when the last successful refresh
  is older than 120 seconds or a refresh fails. Core integration coverage marks
  a cluster unavailable after an API failure while retaining the last
  observation, and confirms the independent Host inventory API still responds.
  This is code-level evidence only; live Host Agent diagnostics during a K3s
  control-plane outage remain unverified.
- Fixed `k3s.preflight` and `k3s.install` Agent/Helper capabilities are available.
  Preflight reports CPU, memory, architecture, systemd, cgroup mode, SELinux
  policy applicability, distribution identity, and existing K3s files. Install
  accepts only a pinned stable version, validates K3s short/secure token syntax,
  verifies the release checksum, refuses existing K3s data/files, and writes a
  root-only systemd environment file without arbitrary shell. Server bootstrap
  accepts only a short token; agents accept short or secure tokens. A single
  server waits for a Ready Node; an agent install result explicitly leaves Node
  health pending. This is a per-host capability, not a persistent rollout plan:
  ordered server/agent orchestration, upgrades, health gates across nodes,
  failure recovery, and K3s-specific live validation remain open. Code evidence
  is recorded in [`k3s-preflight-agent-helper.md`](runs/2026-10-21-k3s-preflight-agent-helper.md)
  and [`k3s-install-helper-capability.md`](runs/2026-10-21-k3s-install-helper-capability.md).
- A typed `k3s.upgrade` Agent/Helper capability now upgrades only the exact
  systemd unit and root-owned binary created by the NaNPanel installer. It
  pins and checksums the target release, rejects downgrades and Kubernetes
  minor skips, stages the new binary without replacement, retains the prior
  binary during the service health gate, and attempts automatic restoration if
  server readiness or agent service activation fails. Agent Node registration
  remains pending cluster-level verification. This is still a single-host
  operation: persistent ordered plans, cross-node health gates and live K3s
  upgrade evidence remain open. See
  `tests/p4/runs/2026-10-21-k3s-single-node-upgrade-capability.md`.
- The live Linux Gateway-to-Ready-K3s-Service path, TLS/access policy, backend
  transition, and compensation passed on 2026-10-21; evidence is in
  `runs/2026-10-21-linux-gateway-k3s-service-ingress.md`. FRPC/Nginx were
  configured directly for the isolated run. Topology-driven remote Gateway
  reconciliation remains unimplemented. No destructive K3s upgrade tests have
  run against `k3s-1`.
- Local checks also cover Maven Core/module tests, Web build, Web mock tests, and
  the topology browser scenario. They are not a K3s or KubeEdge live acceptance
  report. `KubernetesTopologyDiscoveryTest` also passes against a Kubernetes
  API mock; it validates bounded Namespace, Pod, Service, and Ingress reads.
- On 2026-10-21, `npm run build` passed, the topology Playwright scenario passed
  against the mock console, and the targeted Core command below passed all seven
  tests. The browser scenario verifies Gateway host selection, list scroll
  movement without changing the React Flow viewport, route matching, nested
  topology editing, and Kubernetes drill-down. These checks strengthen the
  documented UI/code paths but do not close the outstanding cluster lifecycle,
  KubeEdge, or topology-driven remote Gateway reconciliation gates.

  ```sh
  ./mvnw -pl backend/panel-core -am \
    -Dtest=SystemResourceTest,KubernetesClusterRegistryServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
  VITE_API_MODE=mock npm run dev
  NANPANEL_TEST_URL=http://127.0.0.1:5174 npm run test:topology
  npm run build
  ```
