# K3s single-node upgrade capability (code-level)

- Date: 2026-10-21
- Source revision: working tree based on `b37cc0c` (uncommitted P4 changes)
- Scope: typed Core → Gateway → Agent → root Helper `k3s.upgrade` operation.

## Safety and behavior

- Accepts only `server` or `agent` and a pinned `vX.Y.Z+k3sN` target.
- Only recognizes the fixed NaNPanel systemd unit and root-owned K3s binary and
  environment file. Arbitrary unit content is rejected.
- Rejects downgrades and jumps across more than one Kubernetes minor release.
- Downloads only over HTTPS from the bounded K3s release asset hosts, verifies
  the architecture-specific published SHA-256, and stages the binary without
  overwriting an existing file.
- Stops one node's unit, keeps the previous binary under a root-controlled
  rollback name during restart/health verification, and restores it if the
  server Ready-node gate or agent service activation fails.
- The request contains no token or shell field. The UI requires an explicit
  maintenance-window confirmation. An agent node result still reports that
  cluster Node readiness must be checked separately.

## Validation

- Rust format, all-target/all-feature check, Clippy with warnings denied, and
  all-feature tests are run for the required repository checks.
- Helper tests cover allowed/rejected version steps and no-replace rollback
  rename behavior. Agent/Gateway payload tests reject extra shell fields.
- Core integration coverage rejects floating versions and extra fields, and
  accepts the fixed request through the normal operation record/result path.
- Web production build validates the UI and generated request contract.

## Limits

No K3s binary was replaced on a live host or cluster. This is a single-host
operation, not a persisted, ordered upgrade plan. Server quorum checks, one-node
at-a-time sequencing, cluster-wide post-upgrade readiness, timeout recovery on
real systemd, and disposable K3s upgrade acceptance remain open. Never use the
production `k3s-1` as an upgrade fixture.
