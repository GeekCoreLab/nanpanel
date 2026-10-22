# k3s-1 topology and Istio freshness UI deployment

Date: 2026-10-22

## Release

- Source commit: `d69b2b4e3e73daa942d80cad822e0437081f76e6`.
- GitHub Actions run `39722720975` completed successfully. Rust, Maven, Web
  validation and both Core/Web GHCR image publications passed.
- GHCR manifest inspection found the Web image's Linux/amd64 manifest digest
  `sha256:d4b60f02cb9f7427804569839915b1e0e3a1cc72503fe0ed630be06b8b244893`.
- `k3s-1` previously ran Web commit `sha-12a7b33...`; its deployment had one
  available replica and the serving Pod had zero restarts. The new Web image
  was pulled by the target node from GHCR and applied only to
  `deployment/nanpanel-web`. No local image upload was used.
- The rollout completed with Web `1/1` available. The running Pod is
  `nanpanel-web-5b95b486d4-z6gj7`, Ready with zero restarts, using
  `ghcr.io/geekcorelab/nanpanel-web:sha-d69b2b4e3e73daa942d80cad822e0437081f76e6`
  (`imageID` `sha256:3167e02cd59e6f870cba4cd5abe428d3da36e7df03f86a5a06a5a753ebfdff7b`).
- Core remained on `sha-12a7b33...`; PostgreSQL remained Running with its
  existing PVC and restart count unchanged at 1.

## Read-only deployed UI acceptance

`node web/scripts/k3s-route-live-browser.mjs` returned `PASS` against the
deployed HTTPS Web/Core origin. It used the existing restricted read-only
Kubernetes inventory API key and a test-only browser session. The script
intercepted all topology draft writes; `topologyWrites` was `0`.

The live page showed the `k3s-1` Host and its observed default route resolving
to `自动发现 Router 10.0.0.1`, plus the actual `kubernetes:k3s-1` /
`nanpanel-mesh` snapshot: 2 Services, 2/2 Ready endpoints, Istio `LIVE`, 3/3
ready/scraped sidecars, and 26 Envoy requests. The two visible traffic rows
showed HTTP 200 and HTTP 403, both labeled mTLS. The Service list remained
expandable beside the separate Istio traffic panel. Screenshots were visually
checked at `/tmp/nanpanel-k3s-route-live-ui.png` and
`/tmp/nanpanel-k3s-gateway-asset-live-ui.png`.

The deployed UI also polls the persisted Core snapshot every 30 seconds while
the page is visible, avoids overlapping inventory requests, and marks
Service inventory and Istio telemetry stale independently after two minutes.
The live snapshot was fresh during this run, so the stale-state appearance is
covered by code review/build rather than an aged production snapshot.

## Acceptance boundary

This run proves the updated Web image is deployed and renders actual target
Kubernetes/Istio data without topology writes. It does not change or persist
the production topology; authenticated target save/reload and the remaining
P4 KubeEdge, multi-node and lifecycle-recovery gates remain open.

## Current-state revalidation

Rechecked on 2026-10-22 from the current `main` worktree:

- `ssh k3s-1 kubectl -n nanpanel get deployments,pods -o wide` reported Core
  and Web deployments `1/1`; the Core and Web Pods were Running with zero
  restarts. The Web image remained
  `ghcr.io/geekcorelab/nanpanel-web:sha-d69b2b4e3e73daa942d80cad822e0437081f76e6`.
- `ssh k3s-1 kubectl -n nanpanel-mesh get pods -o wide` reported all four
  fixture workloads Running; three had Ready Envoy sidecars and the probe
  target had no sidecar, as intended.
- `node web/scripts/k3s-route-live-browser.mjs` returned `PASS` against the
  deployed Web and real Core snapshot. It displayed the `k3s-1` default route
  via `10.0.0.1`, the live Istio state and sidecars, and the Gateway host
  picker. Its topology write counter remained `0`. The inventory timestamp was
  `2026-10-22T04:47:40.837+00:00`; screenshots were regenerated under `/tmp`.

This is read-only route and Istio UI revalidation. It does not perform the
separately skipped `k3s.preflight` operation or save production topology.
