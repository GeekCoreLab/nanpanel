# P5 disposable Gateway API and Argo Rollouts run

## Boundary and versions

- Repository `HEAD` before this run: `6568794` (`Match remote K3s nodes to host network identity`).
- Cluster: disposable single-node `kind-nanpanel-p5` using the local Podman provider. The shared `k3s-1` cluster was not modified.
- Gateway API: CRDs from Envoy Gateway `v1.9.2`; controller and data plane both reached `Running`.
- Argo Rollouts controller and CLI: `v1.10.1`.
- Gateway API traffic-router plugin: `v0.16.0`, loaded from a local plugin binary in a temporary controller image.
- Prometheus: `v3.15.1`; Python fixture image: `python:3.13-alpine`.
- Public Gateway address was not assigned by Kind. Requests entered the Envoy data plane through a local port-forward and used `Host: p5.example.test`.

The node had no usable registry egress. Controller, plugin, application, and
Prometheus images were pulled on the host with proxy variables removed and
imported into the disposable Kind node. Argo Rollouts CRDs required server-side
apply because their annotations exceed the client-side annotation limit.

## Commands and observations

The route was reached with:

```sh
kubectl --context kind-nanpanel-p5 -n envoy-gateway-system port-forward \
  svc/envoy-default-eg-e41e7b31 18080:80
python3 tests/p5/fixtures/send-traffic.py --count 300
```

The traffic script sends real requests through the Gateway. Prometheus scraped
each app Pod directly and attached the Argo pod-template hash, so per-version
counters matched the HTTP response counts after the next scrape.

| Canary weight | Route backend weights, stable/canary | Requests | Gateway responses |
| ---: | --- | ---: | --- |
| 0% | 100/0 | 100 | `200 v1`: 100 |
| 10% | 90/10 | 300 | `200 v1`: 273, `200 v2`: 27 |
| 50% | 50/50 | 300 | `200 v1`: 151, `200 v2`: 149 |
| 100% | 0/100 | 100 | `200 v2`: 100 |

At each configured pause, Rollout status reported `Paused`; the matching
HTTPRoute weights were visible in the API. Running the Argo promote command
advanced 10% to 50%, 50% to 100%, and 100% to the stable v2 ReplicaSet. The
completed v2 route returned to stable/canary weights 100/0 and a direct Gateway
request returned `200 v2`.

### Manual abort

An intermediate v3 candidate returned `503` for 30 of 300 requests at 10%
canary weight. That first analysis query was using a short-name Prometheus
address and did not reliably observe the canary counter. It was corrected to
use the service FQDN, per-Pod scraping, the Argo canary hash, and a minimum
sample count. The v3 candidate was manually aborted with:

```sh
/tmp/kubectl-argo-rollouts abort p5-demo \
  --context kind-nanpanel-p5 --namespace default
```

Argo restored the HTTPRoute to stable/canary 100/0; 100 subsequent requests
returned `200 v2`. This is a recorded manual rollback, not a passing automatic
metrics gate.

### Automatic error-metric rollback

The corrected AnalysisTemplate evaluated only the canary ReplicaSet hash and
required at least 20 requests in the 30-second window. A v4 candidate at 10%
returned `503`. The Prometheus target was confirmed `up` before load was sent.
Across the 35-second load window, the Gateway handled 12,949 requests:

- `503 v4`: 235, all served by the canary Pod before abort.
- `200 v2`: 12,714, including traffic after Argo restored the stable route.
- The AnalysisRun finished `Failed` with measurement `[1]`; the configured
  failure threshold was `> 0.05` and `failureLimit: 0`.
- Argo set the v4 canary weight to 0 and stable v2 to 100. A further 100
  requests returned `200 v2`.

The first version of the analysis fixture exposed two real integration errors:
the analysis controller could not resolve a short service name from its own
namespace, and the initial Prometheus counter scrape could miss requests sent
before a new Pod had been scraped. The checked-in fixtures now use the service
FQDN, namespace-scoped Pod discovery, a `rollout_hash` target label, an initial
analysis delay, and a low-sample Inconclusive sentinel. This run validates the
corrected automatic failure path.

## Acceptance still open

This report proves isolated upstream controller behavior and the fixture
configuration. It does not prove NaNPanel Core/API/UI ownership, durable
measurements/audit, controller restart reconciliation, pre-existing route
preservation, Docker or Podman blue/green routing, or ordered multi-host
releases. Those remain open P5 gates. No run was made against production or
shared `k3s-1` infrastructure.
