# Live Istio fixture and Core inventory RBAC

- Verified: 2026-10-20 11:11 UTC
- Cluster: `k3s-1`
- Scope: isolated `nanpanel-mesh` test namespace only; the Core namespace remains outside the mesh.

## RBAC

Created the `nanpanel:nanpanel-core` ServiceAccount and applied
`deploy/istio/core-inventory-rbac.yaml`. The Role grants only `list` on Services
and EndpointSlices in `nanpanel-mesh`. Live authorization checks returned:

```text
list services: yes
list endpointslices.discovery.k8s.io: yes
list pods: no
get secrets: no
```

## Mesh verification

Ran `./deploy/istio/verify-test-mesh.sh` against the live cluster:

```text
PASS: mesh-http Service TCP/80 maps to 1 ready EndpointSlice Pod endpoint(s) on TCP/8080; 3/3 workloads have Istio sidecars; allowed strict-mTLS HTTP probe returned the fixture body; denied identity received HTTP 403.
```

## Non-injected TCP target

Applied `deploy/istio/probe-target.yaml` and reran the verifier at 11:18 UTC:

```text
PASS: mesh-http maps to 1 ready TCP/8080 endpoint(s); 3/4 workloads have Istio sidecars; mesh-probe-target maps to 1 ready private TCP/8080 endpoint(s) without a sidecar; allowed strict-mTLS HTTP probe returned the fixture body; denied identity received HTTP 403.
```

This target is suitable for a raw Core TCP-connect probe while Core remains
outside the mesh. It does not establish that Core has performed or persisted a
probe.

## Reverification

- Verified: 2026-10-20 12:08 UTC
- Commands: `bash deploy/istio/verify-test-mesh.sh` and namespace-scoped
  `kubectl auth can-i` checks for the Core ServiceAccount.
- Authorization results remained `yes`, `yes`, `no`, `no` for Service list,
  EndpointSlice list, Pod list, and Secret get.

```text
PASS: mesh-http maps to 1 ready TCP/8080 endpoint(s); 3/4 workloads have Istio sidecars; mesh-probe-target maps to 1 ready private TCP/8080 endpoint(s) without a sidecar; allowed strict-mTLS HTTP probe returned the fixture body; denied identity received HTTP 403.
```

## Core runtime boundary

The running `nanpanel-core` Deployment remains 1/1 on the existing
`ghcr.io/geekcorelab/nanpanel-core:sha-c1320f5ef460e2fff94db5cb0e685e80d0524bab`
image and still uses the `default` ServiceAccount. The newly created account and
RoleBinding are not yet attached to that Deployment. Discovery and TCP probes
remain disabled; no live Core inventory or probe API acceptance is claimed.
NetBird Provider live tenant acceptance is recorded separately in
[the disposable-tenant run](2026-10-20-netbird-provider-live.md).
