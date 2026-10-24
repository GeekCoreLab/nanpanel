# P5 progressive delivery acceptance

P5 is complete only when code, rejection paths, and reproducible isolated
traffic runs cover every gate below. The shared `k3s-1` cluster is reserved for
NaNPanel itself; installing rollout controllers or injecting bad canaries there
is out of scope. Use a disposable K3s/Kind cluster and disposable Docker and
Podman workloads.

| Gate | Required evidence | Status |
| --- | --- | --- |
| Gateway API and Argo Rollouts | Validate controller/plugin/CRD prerequisites; create a release; observe HTTPRoute backend weights and Argo Rollout/AnalysisRun state; verify unowned resources are preserved and conflicts are rejected. | Passed in disposable Kind and a disposable single-node K3s guest. Live Core/API and browser-to-Core runs covered 0/10/50/100 traffic, automatic rollback, and real ownership/hostname conflict preservation. The shared `k3s-1` cluster was not accessed. |
| Docker and Podman | Run each configured runtime through the Agent/Helper boundary; exercise blue/green prepare, route switch, HTTP verification, and compensation; exercise ordered host batches with a health gate. | Mixed-runtime live browser acceptance passed with Host A on isolated rootless Podman VFS and Host B on a separate rootless Docker 29.9.2 daemon mounted into its Helper. Ordered batch approval passed; each route returned 50/50 `200 v2`; reverse rollback returned 25/25 `200 v1` per host; a broken Docker candidate triggered compensation and 50 `200 v1` responses. Separate daemons ran on one physical machine, and both Helper sandboxes remained rootless. Rootful Helper permissions and physically independent hosts remain open. See [`mixed Docker and Podman live acceptance`](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md). |
| Canary traffic and policy | Send real HTTP requests through the configured route at 0%, 10%, 50%, and 100%; show actual stable/canary request and error counts; pause and resume manually; induce a failing canary and verify automatic restoration. | Passed in Kind and disposable K3s. The K3s API run recorded 103 requests at each 0/10/50/100 stage, 27 successful measurements, and a failed v3 analysis that restored v2; 106 follow-up requests all returned `200 v2`. K3s browser acceptance completed pause/resume and stage promotion with 27 persisted measurements. Three transient Envoy upstream resets occurred during browser traffic transitions; all analysis gates passed. |
| Recovery and audit | Interrupt a rollout or Core between persisted stages; reconcile by stable idempotency key; prove the previous route remains available after failure; inspect audit records and terminal result evidence. | Kubernetes terminal plan/measurement readback after Core restart and audit rows were verified. Host stable-key replay is covered by Core API acceptance. Live host acceptance restarted both Podman and Docker Helpers at an operator-gated batch boundary while the existing routes stayed healthy; Agents returned online with release capabilities. A live Core JVM restart then restored the `PAUSED` plan, preserved routes, reconnected both Agents, resumed to `SUCCEEDED`, and read back both host-success audit events. In-flight uncertain-result recovery through a runtime remains open. See [`mixed-runtime Helper and Core restart acceptance`](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md). |
| Console | Create, pause, resume, promote, and inspect measurements from the Kubernetes release console; create and operate host release plans. | Kubernetes live browser-to-Core flow passed in disposable Kind and K3s: login, cluster/namespace read, draft creation, start, operator pause/resume, measurement display, and 10/50/100 promotion. Host live browser-to-Core flow passed draft creation, batch approval, reverse-order rollback, and unhealthy-candidate compensation with no page errors. |

The reports in `runs/` identify source revision, controller and Gateway API
versions, runtime type, fixture images, commands, observed weights and request
counts, rollback route, and limitations. Unit/API fixtures alone do not close
the traffic acceptance gate.

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

The P5 goal is still in progress. The host blue-green workflow now has a
persistent Core plan/target model, fixed Agent/Helper container operations, a
managed Nginx include adapter with route ownership checks, HTTP health proof,
compensation, reverse-order manual rollback, and operator-approved batches.
All mandatory Rust workspace checks passed, and the full Core reactor passed
72 tests with no failures or errors. The Core API regression using a fake
Agent Gateway covers persisted batch gating, a simulated HTTP 503, compensation
dispatch, and reverse-order rollback. Separate Helper IPC acceptance exercised
50/50 Nginx traffic, 100% candidate routing, stable-slot stop, and local health
rejection. The live host runs go through the real Core, two mTLS Agent
identities, Helper IPC, container runtimes, Nginx, and routed HTTP. A mixed
runtime run assigned Host A to isolated rootless Podman VFS and Host B to a
separate rootless Docker 29.9.2 daemon mounted into its Helper. Each candidate
route returned 50/50 `200 v2`; reverse rollback returned 25/25 `200 v1` per
host; failed Docker candidate compensation returned 50 `200 v1` responses.
The Docker and Podman daemons ran on one physical host, and both Helpers ran in
rootless sandboxes, so physical multi-host isolation and rootful Helper
permissions remain open. A live Core process restart between operator-gated
batches also passes. Both Helpers have now been restarted at an idle batch
boundary, with route continuity and Agent capability recovery; in-flight
uncertain-result reconciliation remains open. Docker rootless delivery and
disposable K3s target acceptance are covered. The shared
`k3s-1` cluster remains read-only; no rollout
controller was installed there. See the
[`host release implementation report`](runs/2026-10-23-host-application-release-implementation.md)
and [Podman live integration report](runs/2026-10-23-host-release-live-integration.md),
[mixed-runtime live report](runs/2026-10-24-host-release-mixed-rootless-live.md),
[mixed-runtime restart report](runs/2026-10-24-host-release-mixed-helper-core-restart-live.md),
and [Core restart live report](runs/2026-10-23-host-release-core-restart-live.md).
The earlier [nested Docker rootless preflight](runs/2026-10-23-host-release-docker-rootless-preflight.md)
explains why this environment used a separately started host-user daemon for
the mixed-runtime acceptance.
