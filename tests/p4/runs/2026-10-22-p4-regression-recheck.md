# P4 regression recheck

Date: 2026-10-22

Source checkout: `main` at `3f502f48105cdf8bc172db791357c7825b14cfae`.

## Automated checks

- `./mvnw verify` — passed all 12 reactor modules. Core reported 47 tests with
  no failures or errors; cluster discovery, K3s installation and rollout,
  Agent auto-enrollment, Istio telemetry, and KubeEdge Provider tests passed.
  One optional NetBird live-provider test was skipped because the external
  provider environment is not configured.
- In `web/`, `npm test` — 13 passed, 0 failed. This includes the assertion that
  Mock mode returns no Service inventory or Istio traffic samples.
- In `web/`, `npm run build` — passed TypeScript checking and Vite production
  build. Vite reported the existing large-chunk advisory.
- In `agent/`, `cargo fmt --all -- --check`,
  `cargo check --all-targets --all-features`,
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `cargo test --all-features` — all passed. The Agent, Relay, Helper, and
  runtime-driver workspaces completed without test failures.

## Target UI check

The read-only browser recheck against deployed `k3s-1` is recorded in
[`k3s-route-istio-ui-live.md`](2026-10-22-k3s-route-istio-ui-live.md). It
asserted the real Service inventory and Envoy 200/403 mTLS rows, confirmed
`Istio LIVE` and 3/3 sidecars, and completed with zero topology writes. The
test uses a test-only browser session/projection, so it is not production
topology persistence or interactive admin-login evidence.

## Remaining P4 acceptance boundaries

These automated checks do not substitute for live fresh-cluster/API-outage
enrollment, production topology save/reload, real multi-node mapping/removal,
K3s install/upgrade failure recovery, or KubeEdge lifecycle acceptance. The
only available target is the already-managed single-node `k3s-1`; this run did
not run `k3s.preflight`, mutate the target cluster, or perform KubeEdge actions.
