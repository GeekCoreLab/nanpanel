# P5 disposable K3s Gateway API and Argo Rollouts acceptance

## Boundary and versions

- Repository `HEAD`: `65687945b59975199475ca9860b933ab3402bfbf`. The P5 implementation and acceptance scripts were present as working-tree changes during this run.
- Cluster: disposable single-node Debian 13 Trixie guest under QEMU/KVM, node `nanpanel-p5-vm`, Kubernetes/K3s `v1.36.4+k3s1`, containerd `2.3.4-k3s1.36`. The guest was created only for this acceptance. Shared `k3s-1` was not accessed or changed.
- Gateway API CRDs: `v1.6.1`; Envoy Gateway controller/data plane: `v1.9.2`. `GatewayClass/eg` was Accepted and `Gateway/eg` was Programmed with its Envoy data plane Ready.
- Argo Rollouts: `v1.10.1`; Gateway API traffic-router plugin: `v0.16.0`, loaded from the local test plugin image. Prometheus: `v3.15.1`.
- NaNPanel Core ran in the guest as a Quarkus JVM process and used an isolated PostgreSQL 17 container. Fixture images were imported individually into the guest containerd and their manifest digests were checked to ensure v1, v2, and broken v3 were distinct.

The guest used QEMU user-mode networking. Core reached the guest K3s API at
`10.0.2.15:6443`; the host-side browser reached Core and the Envoy listener
through temporary SSH forwards. No controller, release workload, or fault
traffic was sent to the shared `k3s-1` cluster.

## Controller and cluster readiness

The single K3s node was `Ready`. Envoy Gateway controller and data-plane
Deployments were available; Argo Rollouts was `1/1` Ready with no restarts at
final inspection. The `eg` GatewayClass reported `Accepted=True`; Gateway `eg`
reported `Programmed=True` and address `10.0.2.15`. The imported cluster was
reported by Core as `HEALTHY`:

- Cluster ID: `1682aa30-6e09-4ba6-a215-dcb8cda62fde`
- Cluster name: `nanpanel-p5-api-92852924`

## Core/API canary traffic

The existing `tests/p5/fixtures/application-release-api-smoke.py` ran against
the live Core API and the guest's internal K3s kubeconfig. It created a healthy
v1-to-v2 release and sent real HTTP requests through the Envoy Gateway using
the release hostname:

| Canary weight | Stable/canary route weights | Requests | Gateway responses |
| ---: | --- | ---: | --- |
| 0% | 100/0 | 103 | `200 v1`: 103 |
| 10% | 90/10 | 103 | `200 v1`: 92, `200 v2`: 11 |
| 50% | 50/50 | 103 | `200 v1`: 50, `200 v2`: 53 |
| 100% | 0/100 | 103 | `200 v2`: 103 |

The release (`aa108aee-092c-4b4b-be2b-31781e3f928c`) persisted 27 successful
measurements, paused at the expected gate, rejected an early analyze request
with `APPLICATION_RELEASE_NOT_PAUSED`, and finished `SUCCEEDED` at stable 100%
and canary 0%.

A second release (`229330e9-4b23-41c1-9226-b46d38b2aa77`) used the intentionally
broken v3 image. Its measured analysis failed, Core reported `ROLLED_BACK`,
and the route returned to stable 100/canary 0. The persisted plan had 12
measurements; a subsequent 106-request Gateway probe returned `200 v2` for
all requests with no transport errors.

## Ownership and hostname conflict probes

Both existing conflict-probe modes in the same API smoke script ran against
the live K3s API. They returned the expected rejection and preserved the
pre-existing resource fields:

- An unowned stable Service collision, release
  `4e376400-2bf4-4733-8632-c0677519d7df`, returned
  `APPLICATION_RELEASE_OWNERSHIP_CONFLICT`. Labels, annotations, spec, UID,
  and resourceVersion were unchanged; no later release resources were created.
- An existing `p5-demo` HTTPRoute hostname collision, release
  `137de854-178d-414d-8c2e-7767bf0928e3`, returned
  `APPLICATION_RELEASE_HOSTNAME_CONFLICT`. Labels, annotations, spec, UID,
  and generation were unchanged; no release resources were created.

## Live browser-to-Core acceptance

On 2026-10-24, `web/scripts/p5-console-live-browser.mjs` ran in Playwright
against the real Core API and the disposable K3s cluster. Vite was configured
with `VITE_API_MODE=live`; Core and Gateway were reached through the temporary
SSH forwards. The browser logged in, read the cluster Namespace list, created
and started a release draft, paused and resumed the active 10% step, read the
persisted measurements, and approved the 10%, 50%, and 100% stages.

- Release ID: `73b8b9af-adc2-4cca-8394-eda6c1fde27c`.
- Result: `SUCCEEDED`, stable 100%, canary 0%, 27 persisted measurements.
- The traffic pump recorded `200 v1`: 1,632 and `200 v2`: 2,008 responses.
  Three transient Envoy upstream resets were observed during route transitions.
  Every measured stage passed its configured analysis threshold; the final route
  was stable 100/canary 0. The separate API traffic windows above had zero
  transport errors.
- Playwright reported no page errors.

The browser run can be repeated with the existing script after starting the
Core/API and Gateway SSH forwards and a live-mode Vite server:

```sh
VITE_API_MODE=live VITE_CORE_API_TARGET=http://127.0.0.1:18081 \
  npm run dev -- --port 5173

NANPANEL_TEST_URL=http://127.0.0.1:5173 \
NANPANEL_BROWSER_PASSWORD=<disposable-admin-password> \
NANPANEL_BROWSER_CLUSTER_ID=1682aa30-6e09-4ba6-a215-dcb8cda62fde \
NANPANEL_BROWSER_CLUSTER_NAME=nanpanel-p5-api-92852924 \
NANPANEL_BROWSER_GATEWAY_URL=http://127.0.0.1:18080/ \
  node web/scripts/p5-console-live-browser.mjs
```

The API smoke and conflict probes were run inside the guest with
`NANPANEL_SMOKE_API=http://127.0.0.1:18081`, the disposable admin password,
and `/tmp/p5-k3s-internal.yaml`:

```sh
python3 /home/p5/application-release-api-smoke.py \
  --kubeconfig /tmp/p5-k3s-internal.yaml --context default \
  --cluster-id 1682aa30-6e09-4ba6-a215-dcb8cda62fde

python3 /home/p5/application-release-api-smoke.py \
  --kubeconfig /tmp/p5-k3s-internal.yaml --context default \
  --cluster-id 1682aa30-6e09-4ba6-a215-dcb8cda62fde \
  --ownership-conflict-probe

python3 /home/p5/application-release-api-smoke.py \
  --kubeconfig /tmp/p5-k3s-internal.yaml --context default \
  --cluster-id 1682aa30-6e09-4ba6-a215-dcb8cda62fde \
  --existing-route-conflict-probe
```

## Limits and cleanup

This run closes the disposable K3s Gateway API/Argo Rollouts acceptance,
including the real browser-to-Core flow, weighted HTTP traffic, automatic
rollback, and resource conflict preservation. It does not validate the shared
`k3s-1` cluster or production rollout controller installation. Host release
in-flight uncertain-result recovery, rootful Helper permissions, and
physically independent runtime hosts remain open P5 gates. The disposable
guest, database, Core process, and temporary SSH forwards are test resources.
