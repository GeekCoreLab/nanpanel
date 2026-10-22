# KubeEdge Provider execution reconciliation code check

Date: 2026-10-22

## Scope

This records Core code and isolated API test coverage for reconciling a persisted
`kubeedge-core` EdgeCore join plan with its Host Helper Operation and the cached
Kubernetes Node snapshot. No KubeEdge target is available, and the selected live
`k3s.preflight` was skipped.

## Behavior

- A dispatched Helper operation UUID is stored on the Provider plan. Repeated
  execution requests replay the same Helper operation by the stored UUID or its
  deterministic idempotency key, including after Core restarts.
- Reading an executing plan reconciles Helper `PENDING`/`RUNNING` to
  `EXECUTING`, uncertain results to `UNKNOWN`, and failed/cancelled results to
  `FAILED`.
- A successful Helper operation moves the plan to
  `AWAITING_NODE_REGISTRATION`. It reaches `SUCCEEDED` only when a snapshot
  observed no earlier than Helper completion contains a present, Ready,
  KubeEdge Edge Node whose case-insensitive node name matches the target Host
  hostname and whose `host_id` explicitly maps to that Host.
- The API contract and generated TypeScript model include the lifecycle states
  and nullable `hostOperationId`. Liquibase migration 036 persists the field
  and expands the state constraint.

## Verification

- Focused Core Maven API regression passed: `NetworkProviderApiTest` and
  `SystemResourceTest` (5 tests, no failures/errors). The API test exercised
  running, successful-awaiting-registration, and fresh mapped Ready Node
  transitions.
- `npm run build` passed; Vite emitted its existing ignored `use client` and
  large-chunk warnings.
- OpenAPI YAML parsed successfully and `git diff --check` passed.

## Limits

The API state transitions use the isolated Core test database. Live Helper
operation recovery, EdgeCore join, actual Edge Node registration, automatic
host mapping, and compensation remain unverified. No `.env` credential was
changed and no Helper operation was submitted to `k3s-1` for this check.

## Deployment on `k3s-1`

- Commit `b219a58459c52bd3cd9a5133c4aa56f42c2b72e1` passed GitHub Actions run
  `39784373600`; Rust format/check/clippy/tests, Maven `verify`, Web build, and
  Core and Web GHCR image publication all succeeded.
- Updated only the `core` container in Deployment `nanpanel-core` to
  `ghcr.io/geekcorelab/nanpanel-core:sha-b219a58459c52bd3cd9a5133c4aa56f42c2b72e1`.
  The Core container digest is
  `sha256:710712a7ab3855aca6633dd92a3906246ed7fae66c0b438a74ece7c405ffac4b`.
  Both containers in the resulting Pod were Ready with zero restarts; the
  `agent-gateway` sidecar remained on its prior image. Web, PostgreSQL, and its
  PVC were not changed.
- Core logs confirmed Liquibase applied
  `036-cluster-provider-execution`; `/q/health/ready` returned `UP` with the
  database check `UP`.
- `node web/scripts/k3s-route-live-browser.mjs` passed against the deployed
  console and real Istio snapshot at `2026-10-22T13:17:35.032+00:00`. It showed
  route gateway `10.0.0.1` and reported `topologyWrites: 0`.
- No `.env` change, authenticated Provider mutation, Host Helper operation, or
  KubeEdge join was performed. The selected live `k3s.preflight` was skipped.
