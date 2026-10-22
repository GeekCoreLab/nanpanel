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

These tests use the isolated Core test database. They do not prove migration 036
against the production PostgreSQL instance, live Helper operation recovery,
EdgeCore join, actual Edge Node registration, automatic host mapping, or
compensation. No `.env` credential was changed and no Helper operation was
submitted to `k3s-1` for this check.
