# Target K3s API and topology smoke (single cluster)

## Scope

- Date: 2026-10-21
- Source: working tree based on `b37cc0c` (uncommitted P4 changes)
- Target: actual `k3s-1`, Kubernetes `v1.33.5+k3s1`, one Ready Node.
- Core: local Quarkus dev instance and a separate disposable PostgreSQL container;
  no production Core database or records were used.
- Credential: temporary ServiceAccount with cluster-wide get/list for Nodes and
  Namespaces, plus Deployment CRUD/scale and Pod/Service/Ingress get/list
  permissions in one random namespace.
  A 15-minute token and kubeconfig were kept in a mode-700 temporary directory.

## Command and result

```sh
P4_DISPOSABLE=yes P4_TARGET_K3S=yes \
CORE_URL=http://127.0.0.1:18081 \
P4_K3S_SSH_ALIAS=k3s-1 \
P4_K3S_API_ENDPOINT=https://10.100.0.1:6443 \
NANPANEL_TEST_PASSWORD='<disposable Core password>' \
P4_NEXT_PASSWORD='<next disposable Core password>' \
tests/p4/k3s-target-api-smoke.sh
```

Result: **PASS**. Core imported the reachable K3s API with cluster type `K3S`, reported a
healthy cluster and the same one-Node count returned by the credential. The Core
Node endpoint returned the target Node UID; a temporary Host asset was mapped
to that UID. In the temporary namespace, the API created a registered
Deployment (`201`), listed it, and the target topology endpoints returned the
Namespace and its resource detail. The harness added a ClusterIP Service and an
Ingress using the unregistered `nanpanel-p4-no-controller` class and reserved
`.invalid` host; Core read the Ingress backend route and Service, plus the Pod
created by the Deployment. The Pod snapshot included container readiness,
restart count, and port `8080`. Core then scaled replicas from one to two
(`200`) and deleted the Deployment (`204`). After removing only the temporary
topology-read RoleBinding, the detail endpoint returned `403`, the cluster
summary reported `UNAVAILABLE`/`RBAC_DENIED` with stale observations, and the
Host inventory endpoint remained available (`200`). The import response did
not disclose kubeconfig/token data.

The run initially found the K3s `deployments/scale` RBAC subresource requirement;
the harness was narrowed to grant that verb only in the temporary namespace.

## Cleanup and limits

The harness deleted its Deployment and namespace; namespace deletion also
removed the temporary Service and Ingress. Follow-up checks found no
`nanpanel-p4-*` namespaces, ClusterRoles, ClusterRoleBindings, or test
Deployments. The local Core process and disposable PostgreSQL container were
stopped and removed, deleting the encrypted test kubeconfig and temporary Host
record. Existing `k3s-1` workloads and ingress routes were not modified.

This is real K3s evidence for one cluster and one Node, including Namespace,
Ingress, Service, and Pod topology reads plus scoped RBAC failure reporting.
Host inventory availability is not proof of live Host Agent OS diagnostics.
It does not prove multi-cluster K3s isolation, conflicting mappings across
multiple Nodes, unmap after Node removal, cluster-wide resource readiness,
K3s installation/upgrade, KubeEdge lifecycle, or Linux Gateway ingress. The
Ingress fixture verifies topology discovery only; it does not exercise an
Ingress controller or public traffic. Kind reports remain local API
integration evidence and are not substituted for this target run.
