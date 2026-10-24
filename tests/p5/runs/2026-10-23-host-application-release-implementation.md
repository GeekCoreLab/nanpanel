# Host application release implementation check

## Boundary

- Repository `HEAD`: `65687945b59975199475ca9860b933ab3402bfbf`; this report describes uncommitted P5 working-tree changes.
- Scope: persistent host release plans and target stages, Docker/Podman typed lifecycle operations, root Helper managed Nginx route support, operator-facing release controls, and local validation.
- The shared `k3s-1` cluster and existing workloads were not changed.

## Implemented path

- Core validates fresh, online Helper hosts and rejects hosts mapped to Kubernetes or reporting a detected Kubernetes runtime. It persists ordered target hosts, stable/candidate images, assigned loopback ports, route identity, expected host generation, operation IDs, state, and audit events.
- Each host first serves and verifies the stable slot. Core then deploys the candidate, requests the candidate-only route, requires the Helper result to contain matching route identity, listener, configuration hash, backend weights, and a successful routed HTTP response, and stops the previous stable container only after that proof.
- Plans pause at configured host batch boundaries. Operators can resume, reconcile uncertain operations, or restore successful hosts in reverse order. A stopped stable slot is retained for manual rollback.
- Core retries a lost active operation through the same idempotency key. The API test simulates a lost plan operation ID and confirms the same persisted Helper operation ID is dispatched again without creating another Core operation row.
- The Rust Helper requires a preconfigured root-owned Nginx include directory and trusted Nginx binary. It renders only NaNPanel-owned route files, rejects listener collisions and unsafe input, checks configuration, reloads Nginx, performs routed HTTP health verification, and attempts to restore the prior route after a failed verification.

## Local validation

- `cargo fmt --all -- --check` — passed.
- `cargo check --all-targets --all-features` — passed.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `cargo test --all-features` — passed after adding typed `container.deploy` payload validation and the bounded Nginx listener-rebind probe: Agent 20, Relay 4, Helper 25, Helper binary 1; 50 tests total.
- `./mvnw -pl backend/panel-core -am -Dtest=HostApplicationReleaseApiTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed: 3 API acceptance tests, including persistent draft/list behavior, invalid-listener rejection, duplicate active-plan rejection, typed dispatch, stable-key recovery, ordered batch gating, candidate-route failure compensation, and reverse-order rollback.
- `./mvnw -pl backend/panel-core -am -Dtest=HostApplicationReleaseApiTest,K3sInstallationApiTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed: 11 tests.
- `npm run test:p5:browser` — passed: Playwright exercised host target ordering/capability filtering, create/start/pause/next-batch/rollback, and Kubernetes release create/start/measurement display/pause/resume/promote/rollback with no page errors. The browser intercepted API requests; this is console UI acceptance, not Core or cluster acceptance.
- `./mvnw -pl backend/panel-core -am test` — passed after the new regression: 72 Core tests, 0 failures/errors/skips. The first full run exposed shared-IP auth throttling in test setup; the test profile now raises only the test threshold (production remains 20 requests/minute), and `SystemResourceTest` uses an isolated H2 database so its bootstrap-password lifecycle cannot inherit another test class's state.
- `npm run build` — passed.
- `python web/scripts/generate-types.py` — passed.

## Core API batch and compensation regression

The `gatesHostBatchesAndCompensatesAnUnhealthyCandidateBeforeReverseRollback`
API test creates two eligible hosts in the isolated Core database and dispatches
typed operations through a fake Agent Gateway. The first host completes and the
plan pauses before the second host until the operator resumes it. The test then
marks the second host's candidate route result as HTTP 503 and verifies Core
dispatches stable-container start, a stable 100/candidate 0 route, and candidate
stop; the plan terminates `FAILED` with the first deployment still successful.
Manual rollback then restores the first host to the original image and finishes
`ROLLED_BACK`.

The fake gateway captures Core dispatch envelopes, while the test directly
updates the isolated operation rows with synthetic results. It does not invoke
an Agent or Helper, a container runtime, Nginx, or a real HTTP endpoint. This is
Core state-machine/API evidence only; runtime-level compensation and multi-host
acceptance remain open.

## Bounded rootless runtime evidence

A disposable Ubuntu 26.04 container ran the release Helper binary using a kept-ID user namespace (`--userns=keep-id:uid=0,gid=0`) and host networking. It used a Podman 5.7.0 client connected to the existing rootless Podman 6.1.4 service, plus Nginx 1.28.3 and the real Helper IPC socket. The host's existing Podman workloads and the `kind-nanpanel-p5` cluster were left untouched.

- `helper.capabilities` advertised `container.deploy` and `nginx.route.set`; the first real deploy exposed a payload-schema omission for `container.deploy`, which was fixed and covered by a regression test. Stable, candidate, and intentionally unhealthy images were deployed through Helper IPC to rootless Podman.
- The Helper wrote an owned Nginx route, validated and reloaded Nginx, and verified the route over HTTP. A listener-rebind race found in this path is now handled by a bounded post-reload health probe with a regression test.
- With weights at 50/50, 100 requests through the Nginx listener returned exactly 50 stable and 50 candidate responses. At 100% candidate weight, requests returned the candidate. After stopping the stable container through Helper IPC, the route continued returning the candidate.
- A candidate whose local HTTP health check returned 503 was rejected. The route file SHA256 stayed unchanged (`8d47f6...5a05a`), and the existing route remained available.
- The three application containers, Helper sandbox, and temporary listeners on ports 18100–18103 were removed/confirmed stopped. Existing Kind resources and the running Prometheus port-forward were not changed.

This is real Helper IPC, rootless Podman, Nginx, and routed HTTP evidence inside a user-namespace sandbox. It is not a rootful system Helper acceptance run. Docker, rootful host permissions, the full Core-to-Agent-to-Helper release chain, runtime-level automatic compensation after a route failure, runtime-level ordered multi-host batching, and Core/Helper restart reconciliation remain unverified. The separate Core API regression covers compensation and batch state transitions through a fake gateway, without running the Helper or runtime. Native `docker`, `nginx`, `k3s`, and `k3d` executables were absent on the host; the Nginx used above was installed only inside the disposable sandbox.

The Kubernetes Kind evidence is recorded separately in [the NaNPanel API run](2026-10-23-nanpanel-kind-api-release.md) and [the Gateway/Argo Rollouts run](2026-10-23-kind-gateway-argo-rollouts.md). K3s target validation remains open.

## Host capability boundary

The host has no `docker`, `k3s`, or `k3d` executable. Podman is running in
rootless mode; the rootless service socket is available, while the system
Podman socket is not accessible to the current user. Passwordless `sudo` is
unavailable. Therefore this environment cannot run the rootful Helper/Docker
acceptance path without changing host privileges or installing a runtime. No
system Podman service, Docker installation, or shared `k3s-1` resource was
changed for this check.
