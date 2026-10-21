# Local Kind two-cluster API integration report (not K3s acceptance)

## Scope

- Source revision: `b37cc0c3d63ecc195ed7e08c3d961daf82fda015` plus the working-tree P4 changes present during this run.
- Core artifact: local `backend/panel-core/target/quarkus-app`; `quarkus-run.jar` SHA-256 `420953ee5a88929bbdc7f2963caf443a46012b13ccd99cf3b3bc7ef8bde5bf53`.
- Fixture: two newly created disposable Kind clusters, `kind-p4-a` and `kind-p4-b`, Kubernetes `v1.36.1`, one Ready control-plane Node each.
- Runtime: Podman `6.1.4`, Kind `v0.32.0`, kubectl `v1.36.2`; Core and its PostgreSQL database ran in disposable containers on the isolated Kind network.
- The fixture used each API server's container-network endpoint (`10.89.8.12:6443` and `10.89.8.13:6443`). This keeps the test inside the production endpoint validation rules, which reject loopback API addresses. No production cluster or host asset data was used.

## Command and result

The harness was run with disposable credentials supplied only through environment variables:

```sh
P4_DISPOSABLE=yes \
P4_ORIGIN=http://127.0.0.1:5173 \
NANPANEL_TEST_PASSWORD='<disposable bootstrap password>' \
P4_NEXT_PASSWORD='<disposable replacement password>' \
P4_API_ENDPOINT_A=https://10.89.8.12:6443 \
P4_API_ENDPOINT_B=https://10.89.8.13:6443 \
tests/p4/multi-cluster-import.sh
```

Result: **PASS**. Both explicit kubeconfig contexts imported as distinct healthy clusters with one Node each. A third context with Node/Namespace read-only RBAC imported successfully; its Deployment create request returned `403`. For the first cluster, a registered Deployment was created (`201`), listed, scaled from one to two replicas, and deleted (`204`). Invalid replica count returned `422`; deleting that Deployment through the other cluster ID returned `404`; deleting an unmanaged Deployment through the control plane returned `404` and the workload remained present. Duplicate cluster names returned `409`; exec plugins and insecure TLS returned `422`; a context without required Node/Namespace read RBAC returned `403`; the cluster list contained no kubeconfig credentials. Harness cleanup removed temporary Deployments, ServiceAccounts, ClusterRole/Binding, and files; the disposable Core database was discarded with its container.

## Limits

This proves API behavior against two local Kind Kubernetes API servers only. Kind is not K3s and is not the target environment, so this report does **not** satisfy the P4 multi-cluster import, node mapping, or resource-management gates. It also does not establish K3s installation/upgrade, KubeEdge lifecycle, Linux Gateway ingress, Agent diagnostics during control-plane failure, or behavior on `k3s-1`. All target K3s gates remain open.
