# KubeEdge EdgeCore Helper dispatch deployment

Date: 2026-10-22

## Release

- Commit: `92d86546ee0ad20bb0a069637268ced09b3d2d55`.
- GitHub Actions run: [39778990899](https://github.com/GeekCoreLab/nanpanel/actions/runs/39778990899); validation and Core/Web GHCR publication passed.
- Core image: `ghcr.io/geekcorelab/nanpanel-core:sha-92d86546ee0ad20bb0a069637268ced09b3d2d55`.
- `linux/amd64` image digest: `sha256:e145d60f957177f09d6606846c90d00c80e8de0571b5348aa3c6e0b375b52418`.

## Deployment

- Target: the single-node `k3s-1` cluster and its existing controller host.
- Only `deployment/nanpanel-core` was changed in Kubernetes; both `core` and
  `agent-gateway` now use the image above. Web, PostgreSQL, Secrets, and PVCs
  were not changed.
- The new Core image's `/work/nanpanel-helper` and `/work/nanpanel-agent`
  binaries were checksum-verified before replacing the existing host binaries.
  Previous binaries were preserved under `/var/lib/nanpanel-nanpanel-helper/` and
  `/var/lib/nanpanel-nanpanel-agent/` with their old SHA-256 in the backup filenames.
- New Helper SHA-256:
  `366bdd691d09d215417c1527376533e6fda72ea9b68884a4ac4e85441921dfb8`.
- New Agent SHA-256:
  `0b7d547f43f82ee2548d05e8c248770db7a493f06651636e334fe91ee9a414ec`.

## Verification

- Core Deployment rollout completed; the new Pod is `2/2 Ready`, both
  containers have zero restarts, and the old Pod terminated after readiness.
- The Core and Agent Gateway container image IDs match
  `sha256:c4e2e6d08925ef7202a3306ef9993dd50b5c31337b63aa096d243cd3102d97eb`.
- Internal Pod and public HTTPS `/q/health/ready` requests both returned
  `status: UP`, including the database connection health check.
- `nanpanel-agent.service` and `nanpanel-helper.service` are active; systemd
  restart counts are zero and recent error journal output is empty.
- The worktree was clean immediately after the deployment, before this record was added.

## Boundary

No `k3s.preflight` or KubeEdge lifecycle operation was run. The environment has
no KubeEdge cluster/EdgeCore target, so this deployment validates release and
service health only. It does not prove that a remote credential can be used,
`keadm join` succeeds, an Edge Node registers, or recovery/compensation works.
