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

At 12:25 UTC, `nanpanel-core` had rolled out to 1/1 on
`ghcr.io/geekcorelab/nanpanel-core:sha-f0d804ba3bac19a0e501317865d38129068c72e7`
using the dedicated `nanpanel-core` ServiceAccount. Discovery and TCP probes
were enabled for `k3s-1` / `nanpanel-mesh`; the Core persisted a successful
probe matching the Ready fixture endpoint and inventory snapshot. The
authenticated API acceptance is tracked separately in the [Core probe run](2026-10-20-core-service-probe-live.md)
and remains open pending a valid current administrator credential. NetBird
Provider live tenant acceptance is recorded separately in
[the disposable-tenant run](2026-10-20-netbird-provider-live.md).
