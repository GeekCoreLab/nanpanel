# ADR-014: Progressive application delivery

## Status

Accepted for implementation. External controller and runtime acceptance is
tracked separately in [`tests/p5/README.md`](../../tests/p5/README.md).

## Context

P5 requires gradual HTTP traffic shifts, observable measurements, operator
pause/promotion, automatic and manual rollback, and Docker/Podman delivery.
Kubernetes already has an explicit cluster credential boundary and host
containers are executed through the non-root Agent and the fixed-operation
root Helper. A release controller must preserve those boundaries and must not
claim success from a requested weight or a successful create call alone.

## Decision

1. Kubernetes application releases use Argo Rollouts for ReplicaSet lifecycle
   and the Gateway API HTTPRoute traffic router plugin for weighted traffic.
   Core creates namespaced resources labelled with the persisted release ID.
   It may reuse a route or rollout only when the existing resource carries the
   same release ID and stored UID from a prior successful plan; it rejects
   unowned or differently owned resources instead of adopting them. A release
   requires an accepted, programmed Gateway parent and the configured Argo
   Rollouts controller/plugin before it can start.
2. Canary gates use HTTP request counters and error counters from the configured
   Prometheus service. The analysis query is generated from validated release
   identifiers and fixed metric names; callers cannot provide arbitrary URLs or
   PromQL. Too few observations are inconclusive and pause progression. A
   threshold failure aborts the Argo Rollout and returns traffic to stable.
   Each weight step runs a blocking inline AnalysisRun before its operator
   pause. The console shows observed request/error measurements alongside the
   route weights observed from Kubernetes.
3. Docker and Podman releases use typed Agent/Helper operations only. The
   runtime choice is the Host's existing root-managed Docker or Podman
   selection. A blue/green release prepares and verifies the inactive slot
   before switching the managed HTTP route; a batched release advances one
   Host at a time and requires a fresh HTTP health result before continuing.
   Core persists host release plans and target stages, submits stable
   idempotency keys through the existing operation journal, and accepts route
   completion only when the Helper result proves the managed route, listener,
   configuration hash, backend weights, and a successful routed HTTP response.
   The root Helper advertises `nginx.route.set` only when configured with a
   root-owned Nginx include directory and trusted Nginx binary. It writes only
   NaNPanel-owned route files, checks for port conflicts, validates the Nginx
   configuration, reloads it, and restores the prior route after a failed
   post-reload probe. Live browser acceptance now covers Core, two separately
   enrolled mTLS Agent identities, Helper IPC, routed HTTP, and both runtime
   paths: Host A uses an isolated rootless Podman VFS service, and Host B uses
   a separate rootless Docker 29.9.2 daemon through a mounted Unix socket. It
   verifies operator-gated batches, candidate traffic, reverse-order manual
   rollback, and automatic stable-route compensation after an unhealthy Docker
   candidate. Each host returned 50/50 `200 v2`; reverse rollback returned
   25/25 `200 v1` per host; failed-candidate compensation returned 50 `200 v1`
   responses. The Docker and Podman daemons ran on one physical machine, and
   both Helpers ran in rootless Podman sandboxes, so this does not establish
   physical multi-host isolation or the complete rootful Core-to-Agent release
   workflow. The earlier
   nested Docker preflight documents why Docker was moved outside the Helper
   sandbox in this environment. A live acceptance restarted Core between
   operator-gated batches, preserved the paused plan and healthy routes, then
   resumed both hosts to success with audit evidence. A later mixed-runtime run
   restarted both Helpers at the same persisted batch boundary, kept the
   existing routes healthy, and verified that the Agents returned online with
   release capabilities. A separate live run dropped a successful Helper
   result callback, let Core mark its timed-out operation `UNKNOWN`, then
   reconciled the same operation ID and key. After Agent restart its durable
   `SUCCEEDED` result completed the release without creating a second
   container; the route returned `200 v2` and Core ACK cleared the Agent
   journal. Execution with no durable terminal result remains `UNKNOWN` and
   is not retried as a new operation. A disposable-VM rootful Podman 5.4.2
   probe passed Helper `container.deploy/list/stop` and `nginx.route.set` as UID
   1000; direct and managed loopback Nginx requests returned `200 v2`. The
   complete rootful Core-to-mTLS-Agent release workflow and physically
   independent hosts remain open. See
   `tests/p5/runs/2026-10-24-rootful-podman-helper-live.md`,
   `tests/p5/runs/2026-10-24-host-release-uncertain-result-recovery-live.md`,
   `tests/p5/runs/2026-10-24-host-release-mixed-helper-core-restart-live.md`,
   `tests/p5/runs/2026-10-24-host-release-mixed-rootless-live.md`,
   `tests/p5/runs/2026-10-23-host-release-core-restart-live.md`, and
   `tests/p5/runs/2026-10-23-host-release-docker-rootless-preflight.md`.
4. Core persists Kubernetes release plans, stages, measurements, and audit
   events. Start, pause, resume, promote, and rollback are explicit transitions
   guarded by current resource ownership and UID. A stage can be promoted only
   after its current AnalysisRun has succeeded for the current canary hash.
   Kubernetes terminal success requires a completed healthy Rollout, accepted
   route references, the stable 100/canary 0 route, and a successful error-rate
   AnalysisRun with routed traffic. It does not issue a separate synthetic HTTP
   probe. Host operation IDs, stages, and target ordering are persisted for
   scheduler recovery. A live isolated run verified a Core restart at a
   persisted batch pause, route continuity while Core was stopped, Agent
   reconnection, and operator resume to terminal success. Both Podman and
   Docker Helpers have also restarted at an idle batch pause with route
   continuity and Agent capability recovery. Lost callback recovery with a
   durable Agent success is accepted; an operation with no durable terminal
   result remains `UNKNOWN` for investigation.
5. Controller installation is an operator-managed prerequisite. NaNPanel does
   not install cluster-wide CRDs/controllers as a side effect of creating an
   application release.

## Consequences

- Argo Rollouts and a compatible Gateway API implementation are independent
  runtime prerequisites; API availability alone does not prove traffic
  splitting works.
- Prometheus must expose the documented request counters for the released
  workloads. Missing or stale metrics keep the inline analysis from passing.
- Rootless Podman and rootless Docker delivery now have live
  browser-to-Core-to-Agent-to-Helper evidence for routed traffic, ordered
  approval, manual rollback, and compensation. They used separate runtime
  daemons on one physical machine. Lost callback recovery from a durable Agent
  result is also accepted. A rootful Podman 5.4.2 probe verified Helper
  container operations and managed Nginx routing with HTTP `200 v2`; the full
  rootful Core-to-mTLS-Agent workflow and physically independent hosts remain
  unverified. See the [rootful Helper report](../../tests/p5/runs/2026-10-24-rootful-podman-helper-live.md)
  and [mixed-runtime report](../../tests/p5/runs/2026-10-24-host-release-mixed-rootless-live.md)
  records the Docker socket boundary and traffic evidence. Container lifecycle
  success by itself is not application release success.
- Gateway API and Argo Rollouts acceptance now also covers a disposable real
  K3s guest, including controller readiness, Core/API and browser flows,
  weighted Gateway traffic, automatic rollback, and preservation of conflicting
  pre-existing resources. The shared `k3s-1` cluster was not used. See the
  [K3s live acceptance report](../../tests/p5/runs/2026-10-24-k3s-gateway-argo-api-live.md).
- Disposable-cluster traffic and runtime fixtures are required for acceptance.
  The shared `k3s-1` panel cluster is not the place to install rollout
  controllers or inject canary failure traffic.

## Alternatives considered

- Directly patching HTTPRoute weights from Core was rejected because it would
  duplicate Argo's ReplicaSet, pause, analysis, and abort state machine.
- Treating a Deployment's updated image or a container's successful start as a
  completed rollout was rejected because neither proves actual routed traffic
  or successful compensation.
- Accepting caller-supplied PromQL or metrics URLs was rejected because it
  would let an ordinary release request issue arbitrary queries to internal
  services.
