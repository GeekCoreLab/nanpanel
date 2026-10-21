# K3s target environment inventory (read-only)

## Scope

- Date: 2026-10-21
- Source revision: working tree based on `b37cc0c` (uncommitted P4 changes)
- Target: SSH alias `k3s-1`; no kubeconfig, token, or private key contents were read or recorded.
- This is an environment inventory, not a feature acceptance report.

## Commands and result

Local kubeconfig contexts:

```sh
kubectl config get-contexts -o name
```

Result: only `kind-kind-cluster` is configured locally. It is the local Kind
development fixture and is not the K3s target.

Read-only target inspection:

```sh
ssh -o BatchMode=yes -o ConnectTimeout=5 k3s-1 kubectl get nodes -o wide
ssh -o BatchMode=yes -o ConnectTimeout=5 k3s-1 kubectl get namespaces
ssh -o BatchMode=yes -o ConnectTimeout=5 k3s-1 kubectl get pods -A
ssh -o BatchMode=yes -o ConnectTimeout=5 k3s-1 'systemctl is-active nanpanel-agent; ip -j route show'
ssh -o BatchMode=yes -o ConnectTimeout=5 k3s-1 kubectl get ingress -A
```

Result: `k3s-1` is a single-node K3s cluster, Kubernetes `v1.33.5+k3s1`, with
its node Ready. It contains active `nanpanel`, `nanpanel-mesh`, `istio-system`,
and system namespaces and workloads. No Ingress resources were present during
the read. `nanpanel-agent` was active; the node's default route was via
`10.0.0.1` on `enp6s18`, with `10.100.0.1` as its internal Node IP.

This proves the target host can run its Agent and supplies a concrete local
route observation. It does not prove that the production Core has a matching
Host asset for `k3s-1` or `10.0.0.1`, nor that the topology UI displayed a
gateway match; the unauthenticated production host API returned HTTP 401. The
target route-matching acceptance therefore remains open.

## Limits and safety boundary

These commands only read cluster state. No resources, cluster credentials,
Core records, or host files were changed. Since `k3s-1` hosts active NaNPanel
and other workloads, do not use it for destructive K3s install/upgrade or
Gateway rollback experiments. Target K3s feature acceptance should use an
isolated K3s cluster; Kind results remain local API integration evidence only.
