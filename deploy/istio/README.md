# Istio test service network

This installs a pinned Istio control plane and an isolated workload namespace on
the test K3s cluster. It does not label or restart the existing `nanpanel`
namespace. The mesh is a preparation layer for Kubernetes workload discovery,
service identity, and traffic policy; it does not replace the NetBird overlay
used by non-Kubernetes hosts or NAT traversal.

## Version and install

The test cluster is K3s Kubernetes `v1.33.5`. This fixture pins Istio `1.31.1`;
Istio's 1.31 compatibility table includes Kubernetes 1.33. Use the official
[Istio 1.31.1 release](https://github.com/istio/istio/releases/tag/1.31.1) and
extract its `istioctl` binary locally. The install script copies only that
binary and the checked-in fixture to the `k3s-1` SSH alias and uses the cluster's
existing root-owned kubeconfig remotely; it does not copy kubeconfig material to
the workstation. This cluster cannot reach Docker Hub directly, so the default
Istio image hub is `docker.1ms.run/istio`, and the fixture images use the same
registry mirror; set `ISTIO_IMAGE_HUB` and update the fixture image references
to an available mirror or registry when installing elsewhere.

```sh
ISTIOCTL_PATH=/path/to/istio-1.31.1/bin/istioctl \
  ./deploy/istio/install-test-mesh.sh
./deploy/istio/verify-test-mesh.sh
```

The installer applies `deploy/istio/probe-target.yaml` along with the strict
mesh fixture. To apply just the non-injected probe target to an existing test
mesh, use:

```sh
ssh k3s-1 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' \
  < deploy/istio/probe-target.yaml
```

The installer uses Istio's `minimal` profile, which installs the control plane
without adding an ingress gateway. It creates `istio-system` and the dedicated
`nanpanel-mesh` namespace. The latter contains a strict-mTLS HTTP fixture, an
allowed client, a denied client, and a separate non-injected HTTP workload for
Core's raw TCP service probe. That target has its own Service and Ready private
EndpointSlice on TCP/8080, so a plain TCP probe can succeed without weakening
the strict-mTLS fixture's policy. `PeerAuthentication` requires strict mesh
mTLS; `AuthorizationPolicy` permits only the `mesh-client` service account to
call the strict-mTLS fixture. The verification script checks that Service
TCP/80 maps to a ready EndpointSlice Pod on TCP/8080, injection on the three
mesh workloads and no sidecar on the probe target, a successful in-mesh HTTP
probe, and HTTP 403 for the untrusted service account.
This is cluster-native fixture evidence; it does not create platform Service
inventory or durable probe records.

The test uses auto-injection at namespace creation. Existing `nanpanel` Core,
Agent Gateway, Web, and PostgreSQL pods remain outside the mesh: their current
health and bootstrap paths are not coupled to Istio while the platform's mesh
Provider and certificate lifecycle are still unimplemented.

## Connect Core to the Service inventory

Core Service discovery uses the Kubernetes identity of the Core process and is
disabled by default. `deploy/k3s/core.yaml` creates the dedicated
`nanpanel-core` ServiceAccount, and `deploy/istio/core-inventory-rbac.yaml`
grants it only `list` access to Services, Pods, and EndpointSlices in
`nanpanel-mesh`. For Ready injected Pods, Core reads the fixed Istio Envoy
Prometheus endpoint (`PodIP:15090/stats/prometheus`) over the cluster network.
It filters to destination-side `istio_requests_total` for Services in that
namespace, retaining workload names, response codes, and the reported mTLS
security policy. It does not query arbitrary addresses, expose raw Prometheus
labels, or persist credentials. The scan is capped at 24 proxies per refresh
with 500 ms connect/read timeouts; larger meshes are sampled and reported as
partial.
Apply the RBAC file only when enabling discovery:

```sh
ssh k3s-1 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' \
  < deploy/istio/core-inventory-rbac.yaml
```

Set `NANPANEL_KUBERNETES_DISCOVERY_ENABLED=true`,
`NANPANEL_KUBERNETES_CLUSTER_ID`, and `NANPANEL_KUBERNETES_NAMESPACE` on Core.
The K3s example pins the latter values to `k3s-1` and `nanpanel-mesh` while both
discovery and probes remain false. After applying the RBAC file, verify its
scope before enabling either switch:

```sh
kubectl auth can-i list services -n nanpanel-mesh --as=system:serviceaccount:nanpanel:nanpanel-core
kubectl auth can-i list endpointslices.discovery.k8s.io -n nanpanel-mesh --as=system:serviceaccount:nanpanel:nanpanel-core
kubectl auth can-i list pods -n nanpanel-mesh --as=system:serviceaccount:nanpanel:nanpanel-core
kubectl auth can-i get secrets -n nanpanel-mesh --as=system:serviceaccount:nanpanel:nanpanel-core
```

The expected results are `yes`, `yes`, `yes`, `no`. The refresh job reads every
60 seconds; the authenticated refresh endpoint can also request one read. The
API accepts no caller-provided namespace or selector. Keep the Core namespace
outside Istio until gateway, health, and certificate paths have their own
acceptance evidence.

Active TCP checks have a separate opt-in switch,
`NANPANEL_KUBERNETES_SERVICE_PROBES_ENABLED=true`. Each refresh checks at most
32 Ready EndpointSlice ports on private IP addresses, with a 350 ms connection
timeout per target. It skips FQDN, public, loopback, link-local, UDP, and
non-Ready targets and stores per-target results for seven days. This confirms
Core-to-endpoint TCP connectivity; it does not prove an application response or
an Istio authorization policy. The isolated Istio fixture's strict-mTLS HTTP
service is expected to reject a plain TCP probe from Core while Core remains
outside the mesh; use a non-injected endpoint for a successful raw-TCP probe.
The sidecar-allowed and sidecar-denied HTTP checks remain separate identity and
policy evidence.

## Inspect and remove the fixture

```sh
ssh k3s-1 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel-mesh get pods,svc,peerauthentication,authorizationpolicy'
```

To remove only this fixture, run:

```sh
ssh k3s-1 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl delete namespace nanpanel-mesh'
```

Removing `istio-system` would also affect other workloads if they are later
joined to the mesh, so uninstall the control plane only after an inventory of
other namespaces. This test does not expose a public listener or change the
existing Traefik/FRP routes.
