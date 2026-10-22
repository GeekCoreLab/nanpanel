# P4 K3s / KubeEdge acceptance

P4 is complete only when every gate below has an implementation, rejection-path
coverage, and a reproducible report. A green unit test or a read-only topology
view alone does not complete the stage.

| Gate | Required evidence |
| --- | --- |
| Multi-cluster import | Import two independently addressed clusters using explicit kubeconfig contexts; reject invalid credentials, exec plugins, insecure TLS, unauthorized RBAC, duplicate names, and credential leakage. |
| Agent runtime auto-enrollment | A Host Agent that detects a local Kubernetes runtime automatically adds the corresponding cluster to managed inventory without manual kubeconfig import; define least-privilege credential acquisition and idempotent handling for unreachable or already-imported clusters. |
| Node mapping | Reconcile ClusterNodes by Kubernetes UID, explicitly map/unmap Hosts, reject conflicting mappings, and preserve both identities after either side is removed. |
| Basic resource management | Exercise only documented, namespace-scoped operations using the imported cluster's RBAC; reject cross-cluster IDs, unsupported kinds, unowned destructive changes, and missing permissions. |
| K3s install and upgrade | Exercise Agent-mediated server/agent installation and upgrade plans, preflight blockers, one-node-at-a-time ordering, health gates, timeout/failure reporting, and recovery without arbitrary shell access. |
| KubeEdge ownership | Verify CloudCore/EdgeCore status and registration state; prove an explicitly mapped EdgeCore Host still supports OS diagnostics while Agent container operations are denied. |
| K3s Service ingress | From a normal Linux Gateway, route through an explicit ingress/Service mapping to a Ready K3s endpoint; prove health, authorization, and rollback/failure reporting. |
| Live Istio service network | In the target cluster, show real Kubernetes Service/EndpointSlice state, injected/Ready Envoy sidecars, destination request counters, HTTP results, and mTLS labels; Mock mode must not fabricate this view and the UI must expand details on demand. |
| Control-plane failure | Block the Kubernetes API and prove Host Agent diagnostics remain available; report cluster observations stale without marking them healthy or stopping EdgeCore. |
| Data ownership | Verify the Kubernetes API is the sole container lifecycle owner on KubeEdge nodes and that Core does not issue duplicate Agent container operations. |

Evidence must name the exact image/commit, cluster fixture, commands, result,
and limitations. Destructive upgrade and ingress experiments belong in a
disposable cluster, never the production `k3s-1` cluster.

The target `k3s-1` live Istio service-network UI/API acceptance passed on
2026-10-22; exact image, GHCR mirror retrieval, real traffic values, and the
test-session limitation are recorded in
[`runs/2026-10-22-k3s-istio-service-network-live.md`](runs/2026-10-22-k3s-istio-service-network-live.md).

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
  test fixture: it does not satisfy target K3s acceptance. The reusable harness
  is named `tests/p4/multi-cluster-import.sh`; the fixture type comes from the
  explicit kubeconfig contexts and is recorded per run.
- A mixed-cluster API run imported real `k3s-1` and a separate isolated K3s
  `v1.33.5+k3s1` API into one disposable Core. Cross-cluster IDs, unowned
  deletion, invalid kubeconfig forms, duplicate names, insufficient RBAC, and
  credential leakage were rejected; Deployment create/list/scale/delete passed
  in the isolated cluster. The target cluster was read-only apart from a
  temporary namespace-scoped ServiceAccount and `get/list` ClusterRole, all of
  which were removed. Its Node was not mutated. The disposable K3s API had one
  synthetic NotReady Node and no kubelet/Agent, so target multi-node mapping and
  Node-removal acceptance remain open; see
  [`k3s-target-multi-cluster-api.md`](runs/2026-10-21-k3s-target-multi-cluster-api.md).
  The separate live
  K3s report below covers only one-cluster import/mapping and scoped Deployment
  CRUD; target multi-cluster acceptance remains open.
- Agent runtime discovery stores per-Host Kubelet/K3s observations. A new
  opt-in enrollment path is limited to a K3s API server node in the same cluster
  as Core: Core verifies a Ready Node with the reported hostname and uses its
  existing read-only in-cluster ServiceAccount instead of exporting or storing
  an admin kubeconfig/token. Kube-system Namespace UID identifies matching
  clusters; the Kubernetes Service IP narrows candidates among older imported
  records without a stored fingerprint. The gate remains open until this path is verified on `k3s-1` and
  unreachable/retry behavior is exercised; agents on remote hosts still need a
  safe credential issuance and refresh protocol before they can be auto-enrolled.
- A separate isolated K3s API-only integration run is recorded at
  `tests/p4/runs/2026-10-21-k3s-multi-cluster-api-only.md`. It used two actual
  K3s `v1.33.5+k3s1` API servers with independent endpoints and exercised the
  same import, RBAC, resource isolation, Node UID mapping conflict, removed-Node
  tombstone, and explicit unmap paths. These control planes ran with
  `--disable-agent`; their Node objects were synthetic and NotReady. This is
  stronger than the Kind API fixture but does not prove real kubelet/Agent
  behavior or multi-node lifecycle on the target `k3s-1`, so the target gate
  remains open.
- Topology route matching shares one resolver for displayed summaries and
  observed edges. Host interface addresses and top-level Router `ipAddress`
  values are eligible owners; multiple distinct owners remain ambiguous. The
  Router field is validated and persisted by Core. Frontend tests cover unique
  Host/Router ownership, duplicate references, ambiguity, self-addresses and
  unmatched gateways; Core tests cover valid Router persistence and invalid IP
  rejection. The target inventory records the `k3s-1` default gateway
  `10.0.0.1`, which is a router and has no matching Host asset. The feature
  supports representing that next hop without inventing a Host, but the live
  production topology has not been edited and authenticated UI route-edge
  evidence remains open; see `runs/2026-10-21-k3s-route-router-matching.md` and
  `runs/2026-10-21-k3s-target-inventory.md`.
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
  The KubeEdge slice also reads the mapped Host's cached `edgecore.service`
  observation and network inventory while the cluster is unavailable, rejects
  `container.stop` with `KUBERNETES_CONTAINER_OWNED`, and confirms no Helper
  operation was persisted. This is code-level evidence only; a live Host Agent
  diagnostic during a real K3s control-plane outage remains unverified.
- Fixed `k3s.preflight` and `k3s.install` Agent/Helper capabilities are available.
  Preflight reports CPU, memory, architecture, systemd, cgroup mode, SELinux
  policy applicability, distribution identity, and existing K3s files. Install
  accepts only a pinned stable version, validates K3s short/secure token syntax,
  verifies the release checksum, refuses existing K3s data/files, and writes a
  root-only systemd environment file without arbitrary shell. Server bootstrap
  accepts only a short token; agents accept short or secure tokens. A single
  server waits for a Ready Node; an agent install result explicitly leaves Node
  health pending. Core and the Kubernetes UI now persist first-Server-then-Agent
  installation plans: the encrypted short token is not returned, the Server
  must be imported and mapped before Agent install, and each Agent must map to
  a Ready Node at the requested version before the next step. API, encryption,
  and frontend build checks pass. This remains code-level evidence; sequential
  multi-node execution, restart recovery, failure injection, and K3s-specific
  live validation remain open. Code evidence is recorded in
  [`k3s-preflight-agent-helper.md`](runs/2026-10-21-k3s-preflight-agent-helper.md),
  [`k3s-install-helper-capability.md`](runs/2026-10-21-k3s-install-helper-capability.md),
  and [`k3s-installation-plan-code.md`](runs/k3s-installation-plan-code.md).
- A typed `k3s.upgrade` Agent/Helper capability now upgrades only the exact
  systemd unit and root-owned binary created by the NaNPanel installer. It
  pins and checksums the target release, rejects downgrades and Kubernetes
  minor skips, stages the new binary without replacement, retains the prior
  binary during the service health gate, and attempts automatic restoration if
  server readiness or agent service activation fails. Agent Node registration
  remains pending cluster-level verification. This is still a single-host
  operation: live K3s upgrade evidence remains open. See
  `tests/p4/runs/2026-10-21-k3s-single-node-upgrade-capability.md`.
- Core gates upgrades for a host mapped to a registered K3s cluster: its Node
  must be present and Ready, the cluster observation must be at most 120 seconds
  old, and no K3s install/upgrade may be pending anywhere in that cluster. Once
  a K3s operation finishes, a newer cluster observation is required before the
  next upgrade. A host mapped to KubeEdge or another Kubernetes kind is rejected.
  Integration coverage exercises unhealthy, concurrent, wrong-kind, and
  gate-passed paths. The per-request admission gate is also used by the
  persistent K3s upgrade-plan worker described below.
- Persistent ordered K3s **upgrade** plans are now implemented in Core and the
  Kubernetes UI. Creation saves a DRAFT after refreshing Nodes, checking every
  present Node is Ready and mapped, every mapped Host has a fresh Agent and
  available Helper with `k3s.upgrade`, and the requested version is neither a
  downgrade nor more than one Kubernetes minor ahead. An explicit start refreshes
  cluster state again and verifies the plan's Node membership, Host generations,
  and all-Node readiness. A scheduled Core worker submits one step at a time
  through the existing Helper admission path, reusing a stable idempotency key
  after restart. It advances only after a fresh observation confirms all Nodes
  Ready and the upgraded Node's `kubeletVersion` equals the requested version;
  failed or unknown operation results stop the plan, while temporary API failure
  leaves it in `VERIFYING`. API validation, authentication, version range, and
  schema tests pass. This remains code-level evidence: the successful auto-advance,
  operation failure/UNKNOWN recovery, and multi-node behavior still need an
  isolated multi-node K3s integration run. The upgrade plan was initially
  deployed at commit `6f146e3`; current `k3s-1` runs commit
  `f6c373c2fbf2f45bf88dcfec17c1a5249495e6e4`, which also includes the persistent
  installation-plan API/UI. Current image, migration, Agent, and health evidence
  is recorded in
  [`k3s-installation-plan-deploy.md`](runs/2026-10-21-k3s-installation-plan-deploy.md).
  No upgrade or installation plan was created or run on production `k3s-1`.
  The installation plan still needs the disposable multi-node run in
  [`k3s-installation-plan-code.md`](runs/k3s-installation-plan-code.md); upgrade
  recovery still needs the separate acceptance in
  [`k3s-rollout-plan-code.md`](runs/k3s-rollout-plan-code.md).
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
