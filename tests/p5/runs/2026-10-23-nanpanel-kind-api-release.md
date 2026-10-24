# P5 NaNPanel API progressive-release run

## Boundary and versions

- Repository `HEAD`: `65687945b59975199475ca9860b933ab3402bfbf` (`Match remote K3s nodes to host network identity`). The working tree also contained the P5 implementation and the user's unrelated P4 documentation changes.
- Cluster: disposable single-node Kind cluster `kind-nanpanel-p5`, Kubernetes `v1.36.1`, using the local Podman provider. Shared `k3s-1` was not modified.
- Gateway API: Envoy Gateway `v1.9.2`; Argo Rollouts `v1.10.1`; Gateway API traffic-router plugin `v0.16.0`; Prometheus `v3.15.1`.
- NaNPanel Core: Quarkus JVM process connected to a disposable PostgreSQL 17 database. The imported cluster health was `HEALTHY`.
- Fixture images: `localhost/nanpanel-p5-app:v1`, `:v2`, and `:v3-broken`. The Kind node's containerd image IDs were checked after import: v1 `221747c5dd5c`, v2 `c59ccf7ea41b`, broken v3 `a58ef8fc93d1`. This confirmed the candidate and failure images were distinct from v1.
- Gateway requests entered the Envoy data plane through `127.0.0.1:18080` with the release hostname in the `Host` header.

The initial attempt exposed a Podman-to-Kind image-import issue: all three
containerd tags pointed at v1 even though the host images differed. That run
was stopped before acceptance, the route was restored to 100/0, and v2 and
v3-broken archives were imported directly into the Kind node's containerd. The
accepted run below confirmed the actual Pod image IDs before proceeding.

## Commands

The Core and disposable database were started locally with test-only
credentials and an isolated kubeconfig. The API smoke run was:

```sh
NANPANEL_SMOKE_PASSWORD=<disposable-admin-password> \
  python3 tests/p5/fixtures/application-release-api-smoke.py \
  --cluster-id 94864152-ba08-47d2-af7c-f86907fea2d4
```

The smoke script authenticated to Core, created the release plan through the
public API, sent real HTTP traffic through the Gateway, exercised manual
pause/resume and promotion, and read persisted plan/stage/measurement views
back from Core. A second plan deployed the intentionally failing image.

## Healthy v1-to-v2 release

- Release ID: `7cf93370-79bc-4090-b9d3-309311976534`.
- The stable baseline was observed at 100/0 before candidate activation.
- At the 10% stage, an early promote request returned
  `APPLICATION_RELEASE_NOT_PAUSED` while inline analysis was still running.
  After analysis passed, the operator pause/resume flow was accepted and the
  stage could be promoted.
- Persisted analysis: 27 measurements across three successful AnalysisRuns.
- Gateway request windows observed through the API smoke run:

| Canary weight | Stable/canary route weights | Requests | Gateway responses |
| ---: | --- | ---: | --- |
| 0% | 100/0 | 107 | `200 v1`: 107 |
| 10% | 90/10 | 107 | `200 v1`: 97, `200 v2`: 10 |
| 50% | 50/50 | 108 | `200 v1`: 53, `200 v2`: 55 |
| 100% | 0/100 | 108 | `200 v2`: 108 |

The plan finished `SUCCEEDED`; Core reported the healthy Rollout and the final
route at stable/canary 100/0.

## Automatic v3 rollback

- Release ID: `a113cec0-9208-444a-8936-0b91172f94e5`.
- The v3-broken canary AnalysisRun failed at the 10% step. Its error-rate
  measurement was `[1]` (100% errors) against the configured `> 0.05` failure
  condition. The canary request counter recorded traffic, so the failure was
  based on observed errors rather than the low-sample sentinel.
- The rollout returned to stable/canary weights 100/0. Core persisted 12
  measurements across the failed and successful rollback analysis runs and
  reported terminal state `ROLLED_BACK`.
- A further 108 Gateway requests all returned `200 v2`; transport errors were
  zero.

The PostgreSQL rows for both plan IDs retained their terminal states and
weights (`SUCCEEDED`, 100/0; `ROLLED_BACK`, 100/0). The measurement table held
27 rows for the successful plan and 12 for the rollback plan.

## Core restart readback

After both plans reached terminal states, the disposable Core JVM was stopped
and restarted against the same PostgreSQL database and credential key. Core
reported ready, applied no new Liquibase changes, and authenticated API GETs
returned both plans with their original terminal states, 100/0 weights, and
27/12 measurement counts. The audit table contained 29 release events across
the two release IDs, including weight observations and state transitions.
This confirms terminal-state persistence across a Core process restart; it
does not exercise restart during an in-flight canary.

## Supplemental conflict and preservation probes

On 2026-10-23, a fresh disposable Core/PostgreSQL instance imported the same
Kind cluster (`8f7ab27c-090d-4426-ba83-3233eb8bc167`) and ran the
`--ownership-conflict-probe` and `--existing-route-conflict-probe` modes of
`application-release-api-smoke.py` against the live Kubernetes API.

- Release `d733151b-e8f4-49bc-8ac5-4cf2f2d628a5` encountered a pre-created,
  unowned Service named `np-d733151be8f4-stable`. Core returned
  `APPLICATION_RELEASE_OWNERSHIP_CONFLICT`. The Service UID,
  resourceVersion, labels, annotations, and spec were unchanged; the canary
  Service, AnalysisTemplate, HTTPRoute, and Rollout were absent. The probe
  Service was deleted after the check.
- Release `0f42c190-3f05-4d3d-b4a1-df099c2e7231` attempted to reuse the
  existing `p5-demo` HTTPRoute hostname `p5.example.test`. Core returned
  `APPLICATION_RELEASE_HOSTNAME_CONFLICT`. The existing route UID, generation,
  labels, annotations, and spec were unchanged, and none of the release's
  Services, AnalysisTemplate, HTTPRoute, or Rollout were created.

The disposable Core database was stopped after the probes. The Kind cluster's
pre-existing P5 workloads and routes remain in place.

## Live browser-to-Core acceptance

On 2026-10-23, Playwright exercised the release console against a real Quarkus
Core process, disposable PostgreSQL database, and the same isolated Kind
cluster. The UI used the public API without request interception. Cluster
`6227cd56-9f45-4de4-9307-093a08505aa9` (`nanpanel-p5-api-92731566`) was healthy.
The temporary Prometheus fixture selects `p5-api-release*` workloads, so the
browser-created application used that prefix.

```sh
NANPANEL_TEST_URL=http://127.0.0.1:5173 \
NANPANEL_BROWSER_PASSWORD=<disposable-admin-password> \
NANPANEL_BROWSER_CLUSTER_ID=6227cd56-9f45-4de4-9307-093a08505aa9 \
NANPANEL_BROWSER_CLUSTER_NAME=nanpanel-p5-api-92731566 \
NANPANEL_BROWSER_GATEWAY_URL=http://127.0.0.1:18080/ \
  npm run test:p5:live-browser
```

- Release `6aa28ef2-f9b0-427a-86b1-bffe49b94b73`, application
  `p5-api-release-ui-mvkiolfn`, Rollout `np-6aa28ef2f9b0`.
- The browser logged in, read the cluster Namespace list, created and started
  a draft, paused and resumed the active 10% stage, displayed measured
  AnalysisRun data, and promoted through 10%, 50%, and 100% canary weights.
- Core persisted 27 successful measurements: nine each for request count,
  error count, and error rate. All nine error-rate values were `0`.
- The release ended `SUCCEEDED`; Core reported the Rollout `Healthy`, stable
  100%, canary 0%. The live HTTPRoute referenced the stable Service at weight
  100 and the canary Service at weight 0.
- Once the 10% route served a successful response, the counted weighted
  traffic returned `200 v1`: 1,584 and `200 v2`: 1,956. Two Envoy upstream
  connection resets occurred during weight transitions. After completion, a
  separate 100-request probe returned 100 `200` responses with body `v2`.

This is isolated Kind and live browser-to-Core evidence. It does not validate
the Kubernetes workflow against the shared K3s target or the host release UI
against a live Host Helper. The browser-created test Rollouts, HTTPRoutes,
Services, and AnalysisTemplates were removed after evidence capture; the
pre-existing `p5-demo` route remained unchanged. The temporary Core/PostgreSQL
containers and credentials were also removed.

## Limits

This run proves the Core/API-owned Kubernetes release path and routed metric
gate in this isolated Kind environment, terminal-state readback after a Core
process restart, and real conflict/preservation behavior for unowned Services
and existing HTTPRoutes. The live browser-to-Core run above covered Kubernetes
console create, pause, resume, measurement, and promotion behavior. Restart
during an active release and idempotent replay across that restart remain
unverified. K3s target acceptance, Docker/Podman host routing, blue-green
compensation, and ordered multi-host batches remain open. No production or
shared `k3s-1` system was changed.
