# K3s persistent rollout plan code evidence

Date: 2026-10-22

Base source revision: `ad0c702` plus the test changes described below.

## Implemented

- Core persists ordered, per-node K3s upgrade plans and their operation IDs.
- Plan creation refreshes cluster state and requires a healthy K3s cluster, all
  present Nodes Ready and mapped to Hosts, fresh Agents, available Helpers,
  advertised `k3s.upgrade` capability, and a compatible pinned target version.
- An explicit administrator start refreshes and preflights the plan again,
  checks the Node membership and Host generations, and serializes starts on the
  cluster row.
- The Core worker submits one node at a time through the existing Helper
  admission path, with a stable idempotency key for restart recovery.
- Before advancing, the worker refreshes cluster state and requires a newer
  healthy observation, all Nodes Ready, and the current Node's
  `kubeletVersion` equal to the requested version. Failed or unknown operations
  stop the plan; temporary observation failures leave it pending verification.
- The Kubernetes console exposes draft creation, explicit start, ordered step
  state, and polling of the current administrator's plans.
- Core scheduler coverage injects a terminal `UNKNOWN` result for the first
  Server operation in a two-step plan. The plan and current step become
  `UNKNOWN`, the next Agent step remains `PENDING`, and a second scheduler pass
  does not create an Agent operation.
- A single-node Core integration test starts from a persisted `SUBMITTING`
  step, dispatches the Server upgrade through a fake Agent Gateway, then
  restores the `SUBMITTING`/missing-operation-ID state. The next scheduler pass
  finds the existing Helper operation by its stable idempotency key and
  reattaches its operation ID without a second dispatch.

## Code validation

- `./mvnw -pl backend/panel-core -am -Dtest=K3sRolloutApiTest -Dsurefire.failIfNoSpecifiedTests=false test`
  passed: 5 tests, no failures, including persisted UNKNOWN termination and
  single-node SUBMITTING replay without duplicate Agent dispatch.
- `./mvnw -pl backend/panel-core -am -Dtest=K3sRolloutApiTest,K3sInstallationApiTest -Dsurefire.failIfNoSpecifiedTests=false test`
  passed: 11 tests, no failures. The two auth fixtures bootstrap the shared
  test admin only when it is absent, preventing repeated CSRF bootstrap calls
  from exhausting the suite's real rate-limit bucket.
- `./mvnw -pl backend/panel-core -am test` passed: 42 Core tests, no failures.
- `./mvnw -pl backend/panel-core -am -Dtest=SystemResourceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  passed: 4 tests, no failures.
- The first full Core run exposed a test-fixture ordering issue: the rollout
  test inserted its user before the suite's bootstrap admin and added another
  rejected request to a shared security-rate-limit bucket. The fixture now
  triggers admin bootstrap before inserting its user and avoids that extra
  rejection request. The combined rollout/API and session tests then passed
  all 7 tests, and the final full Maven verification passed across all 11
  modules (including 25 Core tests; the two opt-in provider live tests remain
  skipped unless their external test configuration is supplied).
- `npm run build` and `npm test` passed; the mock test suite reported 11
  passing tests.
- Rust formatting, all-target/all-feature check and Clippy, and all-feature
  tests passed.
- `git diff --check` passed.

## Acceptance still required

This is code-level evidence only. No rollout plan has been run against
`k3s-1`, and the worker has not yet been exercised against an isolated
multi-node K3s cluster. The UNKNOWN test verifies safe plan termination and
prevention of later-node dispatch; it does not prove recovery of an uncertain
remote Agent action. The replay test verifies persisted-state idempotency with
a fake Gateway; it is not a real Core process restart or live Agent recovery.
Successive-node upgrade behavior, Agent failure and unknown-outcome
reconciliation, actual process restart recovery, and API interruption during
verification still need disposable-cluster integration coverage. Multi-node
installation orchestration is not implemented. Do not use the production test
cluster `k3s-1` for destructive upgrade testing.
