# K3s Agent preflight implementation evidence

Date: 2026-10-21
Source: current uncommitted worktree at implementation time
Scope: typed, read-only K3s host preflight through Core → Agent Gateway → Agent → privileged Helper.

## Implemented checks

- Accepts only role `server` or `agent`; Core, Agent, and Helper reject extra payload fields and arbitrary role values.
- Reports CPU count and `/proc/meminfo` total against the K3s minimums (server: 2 cores / 2 GiB; agent: 1 core / 512 MiB), Linux architecture, active systemd, cgroup v2 presence, `/etc/os-release`, and existing K3s binary/unit state.
- Returns a structured JSON report with `BLOCKED`, `READY_WITH_WARNINGS`, or `READY`, blocker/warning counts, and per-check details.
- Existing K3s installation blocks the install preflight and directs the operator to an upgrade/repair workflow. The operation is read-only and does not invoke an installer, download a binary, or accept shell commands.

## Validation

- `cargo fmt --all -- --check`: passed.
- `cargo check --all-targets --all-features`: passed.
- `cargo clippy --all-targets --all-features -- -D warnings`: passed.
- `cargo test --all-features`: passed (35 tests across Agent, relay, Helper, and runtime-driver crates; 0 failures).
- `./mvnw -pl backend/panel-core -am test`: passed (Core 19 tests; 0 failures), including invalid-role rejection and persisted operation-result handling.
- `npm run build`: passed; existing ReactFlow directive and bundle-size warnings remain.
- OpenAPI YAML parse and `git diff --check`: passed.

## Limits

This proves the typed preflight protocol and code-level result path only. It does not prove a live Agent/Helper execution, install or upgrade a K3s node, persist a multi-node lifecycle plan, sequence server/agent rollouts, or demonstrate health-based recovery. Those P4 gates remain open; no destructive test was run against `k3s-1`.
