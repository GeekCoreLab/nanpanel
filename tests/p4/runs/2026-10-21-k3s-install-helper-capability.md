# K3s single-host install capability evidence

- Source: current worktree on `main`; exact commit is not recorded because the
  P4 changes are still uncommitted.
- Scope: typed first-server bootstrap and agent join through the existing
  Agent → Helper operation channel. This does not create a persistent rollout
  plan and does not implement upgrades.
- Release: the UI defaults to the pinned stable `v1.37.1+k3s1`; the request can
  select another pinned `vX.Y.Z+k3sN` release. The Helper downloads the
  architecture-specific binary and checksum manifest over HTTPS, checks the
  SHA-256 digest, and rejects a pre-existing K3s binary, unit, environment file,
  or data directory.
- Host writes: the binary and systemd unit are published with no-replace atomic
  renames beneath trusted host directories. The token is written only to a
  root-readable (`0600`) EnvironmentFile under `/etc/systemd/system`; no shell
  command or arbitrary arguments are accepted. SELinux-enabled hosts are
  blocked because this capability does not install the K3s SELinux policy.
- Ordering/health: a first server waits up to 180 seconds for a Ready Node.
  Agent installation reports `PENDING_CLUSTER_NODE_HEALTH_CHECK`; it does not
  claim successful cluster registration. Persistent server-before-agent
  ordering, multi-node health gates, rollback/recovery, and upgrade sequencing
  remain unimplemented.
- Automated evidence: Helper token validation and no-overwrite/private-mode
  tests; daemon and Gateway payload validation tests; Core request validation
  test. Passed commands: `cargo +1.98.1 fmt --manifest-path agent/Cargo.toml
  --all -- --check`, `cargo +1.98.1 check --manifest-path agent/Cargo.toml
  --all-targets --all-features`, `cargo +1.98.1 clippy --manifest-path
  agent/Cargo.toml --all-targets --all-features -- -D warnings`, `cargo
  +1.98.1 test --manifest-path agent/Cargo.toml --all-features`, `./mvnw -pl
  backend/panel-core -am test`, and `npm run build` from `web/`.
- Live boundary: no installation was run against `k3s-1` or another host. The
  low-level capability is not yet acceptable as P4 installation-plan evidence.
