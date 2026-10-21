# Target K3s plus isolated K3s multi-cluster API acceptance

Date: 2026-10-21

## Scope and fixtures

- Core code: commit `6f146e38d3063c703e0e1da69cfbca21b34814ea`; the later
  `0d0b3dc` commit only records deployment evidence and does not change Core.
- Target cluster: SSH alias `k3s-1`, K3s `v1.33.5+k3s1`, one existing Ready
  Node. Its API context had a temporary 15-minute ServiceAccount with only
  `get/list` for Nodes and Namespaces.
- Second cluster: an isolated local Podman container using
  `rancher/k3s:v1.33.5-k3s1`, with K3s API enabled and Agent/flannel disabled.
  One synthetic, unschedulable NotReady Node had a fixture kubelet version.
  This was K3s, not Kind; it had no kubelet or Agent and is not target-cluster
  runtime evidence.
- Core and PostgreSQL used disposable local containers/database state. A
  temporary kubeconfig with two explicit contexts was mode `0600`.

## Command and result

The reusable [`multi-cluster-import.sh`](../multi-cluster-import.sh) harness
was run with `P4_CONTEXT_A=p4-k3s-a`, `P4_CONTEXT_B=target-k3s`,
`P4_TEST_NODE_MAPPING=no`, one expected Node in each cluster, and endpoints
`https://100.127.75.78:16443` and `https://10.100.0.1:6443`. Disposable Core
admin passwords and both short-lived credentials were supplied through
environment variables or the temporary kubeconfig; secret values are omitted.

Result: **PASS**. One Core imported both independently addressed K3s clusters
using their explicit contexts. It created, listed, scaled, and deleted a
registered Deployment in the isolated cluster. Cross-cluster resource IDs,
unowned Deployment deletion, duplicate cluster names, exec plugins, insecure
TLS, insufficient RBAC, and credential leakage were rejected.

## Target isolation and cleanup

No Node, Deployment, Service, or other workload was created or changed on
`k3s-1`. The temporary namespace, ServiceAccount, ClusterRole, and
ClusterRoleBinding were deleted. A final target check returned only the
original Ready Node and no `p4-mc-20261021` namespace/role/binding. The local
K3s and PostgreSQL containers, disposable Core process, kubeconfig, and token
files were removed.

## Remaining acceptance

This proves the cross-cluster API isolation slice with the real target cluster
included. It does not prove multiple real kubelet/Agent Nodes, mapping conflicts
or Node removal on `k3s-1`, workload scheduling in the isolated API-only
cluster, or multi-node K3s installation/upgrade recovery. Those P4 gates remain
open.
