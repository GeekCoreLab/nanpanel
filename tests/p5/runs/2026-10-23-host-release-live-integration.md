# Host release live integration acceptance

## Result and boundary

**PASS for the isolated rootless Podman scenario. P5 remains in progress.**

This run exercised the real browser, Quarkus Core, mTLS Agent Gateway, two
separately enrolled Agent identities, Helper IPC, an isolated rootless Podman
VFS store, Nginx, and routed HTTP. The two Agent sandboxes used separate kept-ID
user namespaces, but both controlled the same isolated Podman service. The
hostnames and addresses were separate Core host records on one development
machine; this is not acceptance on two physical machines or two independent
runtime daemons. Docker and rootful Helper permissions were not tested.

The run used repository `HEAD` `65687945b59975199475ca9860b933ab3402bfbf` plus
the uncommitted P5 working-tree changes. Host Podman was 6.1.4. The isolated
Core/PostgreSQL instance and VFS store were disposable. No Kubernetes context
was changed.

## Command and evidence

Run `afe33b85` was started with:

```sh
python3 -u tests/p5/fixtures/host-release-live-stack.py
```

The browser result was `PASS`, with no page errors. The successful release was
`8efbd5b5-cc93-49f1-977b-80a7c4915079`; the unhealthy-candidate release was
`558c9f59-5968-40f2-84f9-2d12129855dc`. The two target records were
`p5-live-afe33b85-a` (`ac5a920e-77d6-4d98-bdec-b94bb9531a4b`) and
`p5-live-afe33b85-b` (`0e854622-3b27-4857-a819-9659d915f6d1`). Nginx listened
on port `52743` at `127.0.0.2` and `127.0.0.3`.

- The browser created a persisted `DRAFT` with two ordered targets. After
  starting a batch size of one, Core paused at target 1 with states
  `[SUCCEEDED, BASELINE_DEPLOY]`; host 2 did not advance before operator
  approval.
- After approval, both targets reached `SUCCEEDED`. Fifty requests through
  each host route returned `200 v2` (50/50 on each route).
- The browser requested manual rollback. Core rolled back the targets in
  reverse order and reached `ROLLED_BACK`; 25 requests through each host route
  returned `200 v1`.
- A `v3-broken` candidate failed the Helper's local HTTP health check. Core
  restored the stable route, stopped the failed candidate, and marked the plan
  `FAILED`. Fifty follow-up requests returned `200 v1`.
- Browser `pageErrors` was empty. `kubernetesContextChanged` was `false`.

Screenshots: [completed candidate rollout](../artifacts/host-release-live-afe33b85-succeeded.png)
and [failure compensation in progress](../artifacts/host-release-live-afe33b85.png).
The latter was captured just before the console's next five-second refresh; its
row still showed `AUTO_ROLLBACK_STOP`, while the browser's direct Core API poll
and final result confirmed `FAILED` and the 50 successful stable-route
responses. It is an in-progress view, not a terminal-state screenshot.

The test also exposed and fixed a Core heartbeat allowlist omission for the
implemented `nginx.route.set` capability. The Core reactor then passed 72 tests
with zero failures or errors; see the implementation report for Rust workspace
checks and additional local validation.

## Rerun and cleanup

A later attempt to regenerate the terminal-state screenshot stopped before
browser startup: rebuilding the uncached Ubuntu Helper/Agent image exceeded the
configured 600-second timeout in `apt-get update`. The harness removed that
attempt's disposable root. The regular Podman inventory and the existing
`localhost/nanpanel-p5-app:v1` image remained available afterward. This timeout
does not replace or invalidate run `afe33b85`; it means no updated terminal
failure screenshot was produced.

## Remaining P5 gates

This run does not close Docker acceptance, rootful Host Helper permissions,
independent per-host runtime daemons or physical multi-host delivery, active
Core/Helper restart reconciliation, or the K3s target acceptance. The shared
`k3s-1` cluster remains outside this run. Track these gates in
[`../README.md`](../README.md) and [`ADR-014`](../../../docs/adr/ADR-014-progressive-delivery.md).
