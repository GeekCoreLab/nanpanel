# Isolated K3s multi-cluster API acceptance (API-only)

## Scope

- Date: 2026-10-21
- Source baseline: `b9ab427959a1c342ff6d4f27fd3d9f7ae8d1a5e2` plus the local
  P4 harness and safe diagnostic-log changes in the working tree.
- K3s image: `docker.io/rancher/k3s:v1.33.5-k3s1`, image ID
  `ab07a958282cb0689e6620ede5e4ce48e447b5ed3588d9e5e35606659ae8e8f7`.
- Fixture: two isolated K3s API servers, `p4-k3s-a` at
  `https://172.30.92.10:6443` and `p4-k3s-b` at `https://172.30.92.11:6443`.
  Each used `--disable-agent`. One synthetic NotReady Node was added to each
  API server; the test created one additional temporary synthetic Node in each.
- Core: current workspace Quarkus artifact mounted into a local JVM runtime;
  `quarkus-run.jar` SHA-256 `df0f80a57ff24481615b7f018b8dcc2ee23e87e5e315686fdb099bd3e9ad21e6`.
  PostgreSQL and Core used disposable local containers and database state.
- `k3s-1`, its workloads, and its database were not modified.

## Command and result

Two explicit kubeconfig contexts were written to a mode-`0600` temporary file.
The test used disposable Core passwords supplied only through environment
variables:

```sh
P4_DISPOSABLE=yes \
P4_TEST_NODE_MAPPING=yes \
CORE_URL=http://127.0.0.1:18081 \
P4_ORIGIN=http://127.0.0.1:5173 \
P4_CONTEXT_A=p4-k3s-a \
P4_CONTEXT_B=p4-k3s-b \
P4_CLUSTER_A_NAME=p4-k3s-api-a \
P4_CLUSTER_B_NAME=p4-k3s-api-b \
P4_FIXTURE_NAME='isolated K3s API-only' \
P4_EXPECTED_NODE_COUNT=2 \
P4_API_ENDPOINT_A=https://172.30.92.10:6443 \
P4_API_ENDPOINT_B=https://172.30.92.11:6443 \
NANPANEL_TEST_PASSWORD='<disposable Core password>' \
P4_NEXT_PASSWORD='<next disposable Core password>' \
KUBECONFIG='<mode-0600 two-context kubeconfig>' \
tests/p4/kind-multi-cluster-import.sh
```

Result: **PASS**. Core imported both distinct K3s endpoints under their explicit
contexts. The run rejected duplicate cluster names, exec plugins, insecure TLS,
insufficient RBAC, credential leakage, invalid Deployment input, cross-cluster
resource IDs, and deletion of an unowned Deployment. It created, listed,
scaled, and deleted a registered Deployment. The selected Host mapped to a Node
UID; mapping that Host to another Node in the same cluster and to a Node in the
other cluster both returned `409`. Removing the mapped synthetic Node left a
non-present UID tombstone and Host mapping; remapping returned `404`; explicit
unmap returned `204`; the Host identity remained present.

## Cleanup and limitations

The test trap deleted all additional synthetic Nodes and temporary Kubernetes
RBAC/resources. The disposable Core and PostgreSQL containers, database, and
kubeconfig were removed after this report was recorded. The two K3s API servers
did not run kubelet, Agent, or workloads; their Nodes were API fixtures and
NotReady. Deployment requests therefore proved API ownership and isolation, not
scheduling or runtime health. This closes only the multi-cluster API and Node
mapping code-path slice. It does not close live multi-node acceptance on a
running K3s cluster, K3s install/upgrade, Agent diagnostics during an API
outage, or KubeEdge lifecycle acceptance.
