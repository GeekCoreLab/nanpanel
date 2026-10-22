# Local K3s Agent auto-enrollment follow-up

Date: 2026-10-22

## Isolated Core integration coverage

- Source commit: `b67c1114891bccff0a78a41c21c2266b225ea409`
  (`Test local Kubernetes auto-enrollment paths`).
- Test command:

  ```sh
  ./mvnw -pl backend/panel-core -am test
  ```

- Result: all 38 Core and required-module tests passed. The focused
  `KubernetesAutoEnrollmentTest` contains three isolated H2/Kubernetes API
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
`k3s-1`; isolated tests cover new-cluster enrollment, readiness retry, and
idempotence. A fresh live K3s cluster with no imported kubeconfig has not yet
been auto-enrolled end to end. The live API-unreachable recovery path and
remote Agent credential issuance/rotation are also unverified or unimplemented,
so the P4 auto-enrollment gate remains open.
