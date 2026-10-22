# ClusterProvider sanitized snapshot deployment

- Commit: `3fdc619ad0b64ccb90e098e9aa45e5081428c2aa`.
- GitHub Actions run `39746587376` succeeded. Rust format/check/clippy/tests,
  backend verification, Web build, and publication of both immutable GHCR
  images passed.
- Local Core verification: `./mvnw -pl backend/panel-core -am test` passed all
  46 tests. The PF4J API fixture exercised authenticated observation through
  Core and asserted cluster health, CloudCore status, node count, and Edge node
  count from a sanitized Core-owned snapshot. `api/openapi.yaml` parsed and
  `git diff --check` passed.
- Target: the existing single-node `k3s-1` only. Core and `agent-gateway`
  containers in `deployment/nanpanel-core` now use
  `ghcr.io/geekcorelab/nanpanel-core:sha-3fdc619ad0b64ccb90e098e9aa45e5081428c2aa`.
  Both resolved to digest
  `sha256:84e71b14c86aca30aa8c4a450d18ea8cf7cf4ea8736ad3f0be7e217a16db125e`.
- The new Core Pod reached `2/2` Ready with zero restarts; the old Pod exited.
  `/q/health/ready` returned `UP` with the database check `UP`. An unauthenticated
  `GET /api/v1/cluster-providers` returned `401 UNAUTHENTICATED` as required.
  Web remained at image `sha-8722235f8ed61e25aa32d407d1c004e0f200d8be`, `1/1`
  Ready with zero restarts; the HTTPS Web NodePort returned `200`. PostgreSQL
  remained Running at `1/1` with its pre-existing restart count unchanged.
- Only the Core Deployment image was changed. Web, PostgreSQL, Secrets, and
  PVCs were not changed. Images were consumed from GHCR; no local image upload
  was used.
- This deploys Core's credential-free cached snapshot projection and read-only
  Provider observation route. It does not install a KubeEdge Provider or add
  lifecycle execution, durable plan validation/recovery, remote credential
  enrollment, or live KubeEdge acceptance. No KubeEdge target exists. The user
  selected skipping live `k3s.preflight`; `.env` was not modified.
