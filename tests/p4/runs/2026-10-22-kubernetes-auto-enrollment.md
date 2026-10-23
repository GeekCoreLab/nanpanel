# Local K3s Agent auto-enrollment follow-up

Date: 2026-10-22

## Isolated Core integration coverage

- Source commit: `b67c1114891bccff0a78a41c21c2266b225ea409`
  (`Test local Kubernetes auto-enrollment paths`).
- Test command:

  ```sh
  ./mvnw -pl backend/panel-core -am test
  ```

- Result: all 39 Core and required-module tests passed. The focused
  `KubernetesAutoEnrollmentTest` contains four isolated H2/Kubernetes API
  cases; none of these mock responses are counted as live-cluster acceptance.
- New-cluster case: a Ready local K3s Node, kube-system UID, Kubernetes Service
  IP, and bounded Node list created one HEALTHY cluster with
  `credential_source=IN_CLUSTER`, no kubeconfig credential, the discovered
  Host association, and a UID-mapped Node. Repeating enrollment did not create
  a duplicate cluster or mapping.
- Existing-cluster case: an imported K3s record with a matching kube-system UID
  was adopted, fingerprinted, and mapped to the Host while retaining its
  `KUBECONFIG` credential source.
- Retry case: a reported Node with Ready=False created no managed cluster. A
  subsequent call after the Node became Ready completed enrollment.
- API recovery case: a temporary `503 Service Unavailable` from the local Node
  lookup created neither a cluster nor a Host mapping. A later enrollment call
  after the API recovered completed registration with one in-cluster cluster
  and one UID-mapped Node.

The full command `./mvnw -pl backend/panel-core -am test` passed after adding
the API recovery case. This is isolated failure and retry coverage; it does not
simulate an outage on the live `k3s-1` API.

## `k3s-1` live state

Core and Agent run the published `sha-1ac743598bc9ee5fef66ad10d9e166c5f40d8ab4`
release on `k3s-1`. The `nanpanel-config` ConfigMap enables both
`NANPANEL_KUBERNETES_DISCOVERY_ENABLED` and
`NANPANEL_KUBERNETES_AUTO_ENROLL_LOCAL_ENABLED`; `nanpanel-agent.service` is
active with zero restarts.

A read-only PostgreSQL query at 2026-10-22 01:02 UTC showed a fresh K3S runtime
observation for Host `k3s-1` (`detected`, `kubelet_running`, and
`api_server_local` true). The matching `k3s-1-k3s` cluster was HEALTHY, had a
non-null discovery Host and cluster fingerprint, and its Ready Node `k3s-1`
was mapped to that Host. Its credential source remains `KUBECONFIG`, which
shows this live path adopted the already imported target cluster rather than
creating a new in-cluster credential record.

## Remaining boundary

This confirms real runtime discovery and the existing-cluster adoption path on
`k3s-1`; isolated tests cover new-cluster enrollment, readiness retry, API
recovery retry, and idempotence. A fresh live K3s cluster with no imported
kubeconfig has not yet been auto-enrolled end to end. The live API-unreachable
recovery path and remote Agent credential issuance/rotation are also
unverified or unimplemented, so the P4 auto-enrollment gate remains open.

## Read-only target recheck after rollout-plan work

At 2026-10-22 03:13 UTC, `ssh k3s-1` showed the Core Deployment still using
`sha-12a7b33d269218be412e59ccd07fb55c859a0b9a` with one Ready replica. The
`nanpanel-config` ConfigMap still has discovery and local auto-enrollment
enabled. A read-only PostgreSQL query observed Host `k3s-1` reporting a running
local K3s API server at `2026-10-22T03:13:55.83Z`; cluster `k3s-1-k3s` was
HEALTHY with one Ready Node mapped to that Host and credential source
`KUBECONFIG`. This reconfirms live discovery and adoption of the existing
imported cluster. It does not prove new-cluster auto-enrollment from an empty
inventory or API outage recovery.

## Agent heartbeat integration follow-up

- `KubernetesAutoEnrollmentTest` now drives discovery through
  `/internal/agent-heartbeats` instead of calling the enrollment service
  directly. Each test has a registered Agent certificate fingerprint and
  submits a K3S runtime report with the `kubernetes.runtime.detect` capability.
- The new-cluster/idempotence case also verifies the heartbeat persisted
  `detected`, `kubelet_running`, `api_server_local`, and `runtime_kind=K3S` for
  the Host before asserting the in-cluster credential and UID mapping.
- New-cluster registration, existing-cluster adoption, NotReady retry, and
  temporary local API 503/retry all pass through the heartbeat endpoint. The
  Kubernetes API remains an isolated Fabric8 fixture, so these tests prove the
  Core integration path but do not replace live `k3s-1` fresh-cluster or outage
  acceptance.
- A mismatched Agent certificate fingerprint is rejected with HTTP 403 before
  the runtime discovery row, cluster record, or Host mapping is written.
- Command:
  `./mvnw -pl backend/panel-core -am -Dtest=KubernetesAutoEnrollmentTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Result: all five `KubernetesAutoEnrollmentTest` tests passed.

## Local Node identity guard

On 2026-10-23, local auto-enrollment was tightened because an Agent's TCP probe
of `127.0.0.1:6443` and a matching hostname alone do not prove that its local
K3s API is Core's own cluster. Before claiming or creating the in-cluster
record, Core now requires a fresh (within 120 seconds) Host network inventory
whose interface addresses include the Core Node's Kubernetes `InternalIP`.
Missing, stale, malformed, or mismatched network data defers enrollment until
a later heartbeat; remote Agent mapping continues to use the existing
credentialed-cluster checks.

Focused command:

```sh
./mvnw -pl backend/panel-core -am -Dtest=KubernetesAutoEnrollmentTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: all 13 `KubernetesAutoEnrollmentTest` tests passed, including a case
where the Agent reports a different host IP and no cluster or Node mapping is
created. This is isolated H2/Fabric8 integration coverage; it does not replace
the skipped live `k3s.preflight` or the outstanding empty-cluster live test.

## Remote Agent mapping to an already managed K3s cluster

- Source commit: `3a3627cd43654c2f05e0a398797ca57e8419d527`
  (`Auto-map remote agents to managed K3s nodes`).
- When a registered Agent reports a running remote K3s kubelet and no local API
  server, Core now considers only its cached K3S clusters with an existing
  `KUBECONFIG` or `IN_CLUSTER` credential source. A mapping is written only
  when exactly one matching hostname exists across fresh (120-second),
  HEALTHY cluster snapshots and that Node is present, Ready,
  Kubernetes-owned, and unmapped; the Agent Host must also be unmapped.
- The operation is idempotent and emits a mapping audit event. It does not
  accept or persist credentials from the Agent, create a cluster, map KubeEdge
  Nodes, or override an existing Host/Node association.
- Focused command:
  `./mvnw -pl backend/panel-core -am -Dtest=KubernetesAutoEnrollmentTest -Dsurefire.failIfNoSpecifiedTests=false test`
  — all 12 tests passed. Cases cover unique match, repeated heartbeat,
  ambiguous names, stale observations, NotReady Nodes, existing Node/Host
  mappings, KubeEdge exclusion, and invalid Agent certificate rejection.
- `./mvnw verify` also passed after this change: Core 54 tests passed and all
  reactor modules succeeded. Two optional external-provider live tests were
  skipped because their external environments are not configured.

This is Core integration coverage with isolated Kubernetes API/DB fixtures.
There is no separate remote Agent host in the available environment, so the
remote mapping has not been exercised against a real second Node. Automatic
credential issuance/rotation and import of a previously unknown remote cluster
remain open P4 work.

## Read-only target recheck at 2026-10-23 00:10 UTC

Over `ssh k3s-1`, queried only the target PostgreSQL Host discovery and
registered-cluster rows. Host `k3s-1` reported `runtime_kind=K3S`,
`detected=true`, `kubelet_running=true`, and `api_server_local=true` at
`2026-10-23T00:10:05.483Z`. Cluster `k3s-1-k3s` remained `HEALTHY` with
`credential_source=KUBECONFIG`; its `discovered_host_id` matches the reporting
Host, and its observation timestamp was `2026-10-23T00:10:00.094Z`.

This freshly verifies that the installed Agent is still detecting the local
K3s runtime and that Core has claimed the already imported cluster for that
Host. It does not prove creation from an empty imported-cluster inventory,
live API outage recovery, or remote credential issuance/rotation. The query
was read-only; no `k3s.preflight` or cluster operation ran.

## Focused regression at 2026-10-23 00:12 UTC

Command:

```text
./mvnw -pl backend/panel-core -am -Dtest=KubernetesAutoEnrollmentTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: all 12 `KubernetesAutoEnrollmentTest` cases passed (0 failures, 0
errors, 0 skips). This rechecks local enrollment/idempotence and retry paths,
remote mapping constraints, and certificate rejection against isolated test
fixtures; it is not a second-host or live API recovery acceptance.
