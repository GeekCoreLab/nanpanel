# P5 progressive delivery acceptance

P5 is complete only when code, rejection paths, and reproducible isolated
traffic runs cover every gate below. The shared `k3s-1` cluster is reserved for
NaNPanel itself; installing rollout controllers or injecting bad canaries there
is out of scope. Use a disposable K3s/Kind cluster and disposable Docker and
Podman workloads.

| Gate | Required evidence | Status |
| --- | --- | --- |
| Gateway API and Argo Rollouts | Validate controller/plugin/CRD prerequisites; create a release; observe HTTPRoute backend weights and Argo Rollout/AnalysisRun state; verify unowned resources are preserved and conflicts are rejected. | Passed in disposable Kind and a disposable single-node K3s guest. Live Core/API and browser-to-Core runs covered 0/10/50/100 traffic, automatic rollback, and real ownership/hostname conflict preservation. The shared `k3s-1` cluster was not accessed. |
| Docker and Podman | Run each configured runtime through the Agent/Helper boundary; exercise blue/green prepare, route switch, HTTP verification, and compensation; exercise ordered host batches with a health gate. | Mixed-runtime and rootful live browser acceptance passed. The independent-host run used local bare-metal Host A with rootless Podman VFS and separately SSH-reached Debian 12 KVM Host B with rootful Podman 4.3.1. Ordered batch approval passed; each route returned 50/50 `200 v2`; reverse rollback returned 25/25 `200 v1` per host; unhealthy-candidate compensation returned 50 `200 v1` responses on Host B. The independent-host gate is closed; the remote target was virtualized. See [`independent-host rootful Podman live acceptance`](runs/2026-10-25-independent-host-rootful-podman-live.md), [`rootful Podman live host release`](runs/2026-10-25-host-release-rootful-podman-live.md), [`rootful Podman Helper and Nginx acceptance`](runs/2026-10-24-rootful-podman-helper-live.md), and [`mixed Docker and Podman live acceptance`](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md). |
| Canary traffic and policy | Send real HTTP requests through the configured route at 0%, 10%, 50%, and 100%; show actual stable/canary request and error counts; pause and resume manually; induce a failing canary and verify automatic restoration. | Passed in Kind and disposable K3s. The K3s API run recorded 103 requests at each 0/10/50/100 stage, 27 successful measurements, and a failed v3 analysis that restored v2; 106 follow-up requests all returned `200 v2`. K3s browser acceptance completed pause/resume and stage promotion with 27 persisted measurements. Three transient Envoy upstream resets occurred during browser traffic transitions; all analysis gates passed. |
| Recovery and audit | Interrupt a rollout or Core between persisted stages; reconcile by stable idempotency key; prove the previous route remains available after failure; inspect audit records and terminal result evidence. | Kubernetes terminal plan/measurement readback after Core restart and audit rows were verified. Live host acceptance restarted both Helpers at a persisted batch boundary and Core between batches. A separate rootless Podman run dropped the first successful Helper result callback, observed Core mark the expired operation `UNKNOWN`, reconciled the same operation ID/key, restarted the Agent, and accepted its durable `SUCCEEDED` replay. The plan finished `SUCCEEDED`, the route returned `200 v2`, the container ID stayed unchanged, and Core ACK cleared the Agent journal entry. An execution with no durable terminal result remains `UNKNOWN` and is not retried as a new operation. Independent-host rootful Podman acceptance also passed ordered delivery, manual rollback, and unhealthy-candidate compensation. See [`independent-host rootful Podman live acceptance`](runs/2026-10-25-independent-host-rootful-podman-live.md), [`rootful Podman live host release`](runs/2026-10-25-host-release-rootful-podman-live.md), [`uncertain-result recovery acceptance`](runs/2026-10-24-host-release-uncertain-result-recovery-live.md), and [`mixed-runtime Helper and Core restart acceptance`](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md). |
| Console | Create, pause, resume, promote, and inspect measurements from the Kubernetes release console; create and operate host release plans. | Kubernetes live browser-to-Core flow passed in disposable Kind and K3s: login, cluster/namespace read, draft creation, start, operator pause/resume, measurement display, and 10/50/100 promotion. Host live browser-to-Core flow passed draft creation, batch approval, reverse-order rollback, and unhealthy-candidate compensation with no page errors. |

The reports in `runs/` identify source revision, controller and Gateway API
versions, runtime type, fixture images, commands, observed weights and request
counts, rollback route, and limitations. Unit/API fixtures alone do not close
the traffic acceptance gate.

## k3s-1 panel deployment

On 2026-10-25, the P5-enabled Core and Web images were deployed to the
single-node test cluster from commit `b24800f8b8a8b2455e54d778c14a847829198b2d`
after its exact-SHA Actions validation and GHCR publication succeeded. Core,
Web, database migrations, Agent synchronization, external readiness, and the
browser login shell were verified. The cluster hosts NaNPanel itself; it was
not used for P5 rollout-controller or canary-traffic experiments. See the
[`k3s-1 panel deployment report`](runs/2026-10-25-k3s1-panel-deployment.md).

## Current evidence

Two disposable Kind runs cover external controller behavior and the
NaNPanel Core/API path. A third isolated Core/UI session exercised the real
browser-to-Core path. A disposable K3s guest then passed the same Core/API and
browser acceptance against actual K3s, Envoy Gateway, and Argo Rollouts; it did
not access the shared `k3s-1` cluster. The external fixture run
includes real HTTP traffic at
0/10/50/100 percent, manual abort, and a failed error-rate analysis that
restored the stable route. The API run creates and owns a Rollout and
HTTPRoute, persists 27 successful-release and 12 rollback measurements, gates
promotion on AnalysisRun success, accepts operator pause/resume, and restores
v2 after the v3 error-rate analysis fails. Details and request counts are in
[`runs/2026-10-23-kind-gateway-argo-rollouts.md`](runs/2026-10-23-kind-gateway-argo-rollouts.md)
and [`runs/2026-10-23-nanpanel-kind-api-release.md`](runs/2026-10-23-nanpanel-kind-api-release.md).
The K3s run independently verified weighted Gateway traffic, measured
automatic rollback, live browser controls, and preservation of pre-existing
Service and HTTPRoute resources; see
[`disposable K3s live acceptance`](runs/2026-10-24-k3s-gateway-argo-api-live.md).

A supplemental Core-to-Kind probe verified ownership and hostname rejection
against the existing `p5-demo` route in the disposable Kind cluster. A
pre-existing unowned Service collision returned
`APPLICATION_RELEASE_OWNERSHIP_CONFLICT`; its UID, resourceVersion, labels,
annotations, and spec stayed unchanged, no later release resources were
created, and the probe Service was removed. A release attempting to reuse
`p5.example.test` returned `APPLICATION_RELEASE_HOSTNAME_CONFLICT`; the
existing `p5-demo` HTTPRoute retained its UID, generation, labels, annotations,
and spec, and no release resources were created. The evidence is recorded in
[`runs/2026-10-23-nanpanel-kind-api-release.md`](runs/2026-10-23-nanpanel-kind-api-release.md).

The Kubernetes live browser run used the real Quarkus Core API and disposable
Kind cluster without API interception. It verified login, namespace read,
draft creation, operator pause/resume, measured analysis, and stage promotion.
The host release live runs also used the real browser and Core API, with no API
interception. The first used two enrolled Agents, Helper IPC, isolated rootless
Podman, Nginx, and routed HTTP. The mixed-runtime run used rootless Podman for
Host A and a separate rootless Docker daemon for Host B, then verified real
routed traffic, manual rollback, failed-candidate compensation, and Core
restart recovery. The reports are [`rootless Podman live acceptance`](runs/2026-10-23-host-release-live-integration.md)
and [`mixed Docker and Podman Helper/Core restart acceptance`](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md).
The rootful Podman run completed the same browser-to-Core-to-mTLS-Agent path in
a disposable Debian 13 guest, including operator-gated batches, reverse
rollback, and automatic compensation. Its K3s runtime was stopped only within
that disposable guest during the run and restored afterward. A later
independent-host run placed rootless Podman Host A on the local bare-metal
machine and rootful Podman 4.3.1 Host B in a separately SSH-reached Debian 12
KVM guest. Candidate traffic, manual reverse rollback, unhealthy-candidate
compensation, and cleanup checks passed; the existing system Nginx and default
CNI network were preserved. See
[`independent-host rootful Podman live acceptance`](runs/2026-10-25-independent-host-rootful-podman-live.md)
and [`rootful Podman live host release`](runs/2026-10-25-host-release-rootful-podman-live.md).
The [`lost-result recovery run`](runs/2026-10-24-host-release-uncertain-result-recovery-live.md)
adds an Agent restart after a deliberately dropped successful-result callback;
it verifies the same operation identity, no duplicate container, the final
routed response, and journal removal only after Core ACK.

The P5 goal is complete. The host blue-green workflow now has a
persistent Core plan/target model, fixed Agent/Helper container operations, a
managed Nginx include adapter with route ownership checks, HTTP health proof,
compensation, reverse-order manual rollback, and operator-approved batches.
All mandatory Rust workspace checks passed, and the full Core reactor passed
73 tests with no failures or errors. The Core API regression using a fake
Agent Gateway covers persisted batch gating, a simulated HTTP 503, compensation
dispatch, and reverse-order rollback. Separate Helper IPC acceptance exercised
50/50 Nginx traffic, 100% candidate routing, stable-slot stop, and local health
rejection. The live host runs go through the real Core, two mTLS Agent
identities, Helper IPC, container runtimes, Nginx, and routed HTTP. A mixed
runtime run assigned Host A to isolated rootless Podman VFS and Host B to a
separate rootless Docker 29.9.2 daemon mounted into its Helper. Each candidate
route returned 50/50 `200 v2`; reverse rollback returned 25/25 `200 v1` per
host; failed Docker candidate compensation returned 50 `200 v1` responses.
The mixed Docker and Podman daemons ran on one physical host, and both Helpers
ran in rootless sandboxes. The independent-host acceptance now covers local
bare-metal Host A and separately SSH-reached Debian 12 KVM Host B with rootful
Podman 4.3.1. A separate rootful Podman 5.4.2 probe passed Helper
`container.deploy/list/stop` and
`nginx.route.set` as UID 1000, with direct and managed loopback Nginx requests
returning HTTP `200 v2`. The full rootful Core-to-mTLS-Agent release workflow
also passed ordered batches, manual rollback, and unhealthy-candidate
compensation in a disposable guest. A live Core process restart between
operator-gated batches also passes. Both Helpers have now been restarted at an idle batch
boundary, with route continuity and Agent capability recovery. Lost callback
recovery now passes when the Agent holds a durable successful result: Core
retains the operation as `UNKNOWN`, reconcile reuses its operation ID/key,
the restarted Agent replays the result, and Core ACK clears the journal after
the release succeeds without creating a second container. A genuinely
indeterminate operation without a durable terminal result remains `UNKNOWN`.
Docker rootless delivery, rootful Podman delivery, disposable K3s target
acceptance, and independent-host runtime acceptance are covered. The shared
`k3s-1` cluster remains read-only; no rollout
controller was installed there. See the
[`host release implementation report`](runs/2026-10-23-host-application-release-implementation.md)
and [Podman live integration report](runs/2026-10-23-host-release-live-integration.md),
[mixed-runtime live report](runs/2026-10-24-host-release-mixed-rootless-live.md),
[mixed-runtime restart report](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md),
[uncertain-result recovery report](runs/2026-10-24-host-release-uncertain-result-recovery-live.md),
[independent-host rootful Podman report](runs/2026-10-25-independent-host-rootful-podman-live.md),
[rootful Podman Helper report](runs/2026-10-24-rootful-podman-helper-live.md),
[rootful Podman live host release](runs/2026-10-25-host-release-rootful-podman-live.md),
and [Core restart live report](runs/2026-10-23-host-release-core-restart-live.md).
The earlier [nested Docker rootless preflight](runs/2026-10-23-host-release-docker-rootless-preflight.md)
explains why this environment used a separately started host-user daemon for
the mixed-runtime acceptance.
