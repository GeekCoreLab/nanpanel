# ClusterProvider SPI deployment

- Commit: `ff53346e3ee4f21b1aef00b7429604c982582ba5`.
- GitHub Actions run `39729640209` passed validation and published both immutable
  images to GHCR.
- Focused local Maven regressions passed: `P0PluginLifecycleTest` and
  `NetworkProviderApiTest` (2 tests, no failures). OpenAPI DTO generation and
  `git diff --check` also passed.
- Target: the existing single-node K3s cluster `k3s-1`.
- Only `deployment/nanpanel-core` was updated, setting both `core` and
  `agent-gateway` containers to
  `ghcr.io/geekcorelab/nanpanel-core:sha-ff53346e3ee4f21b1aef00b7429604c982582ba5`.
  The Web Deployment, PostgreSQL StatefulSet, secrets, and PVCs were not
  changed.
- The first rollout wait reached its five-minute timeout while the node retried
  GHCR blob pulls after connection resets. The image then pulled successfully;
  a subsequent live read showed the new Pod `2/2` Ready with zero restarts and
  the Deployment `1/1` available. The old Pod was replaced after the new Pod
  started.
- Kubernetes API Pod-proxy checks returned `UP` for both `/q/health/ready` and
  `/q/health/live`; readiness included the database health check as `UP`. Both
  running containers resolve to image digest
  `sha256:7f65bab01fbaba3dc82ded249850ed3cdd415116dba3fc3261e663b4372aec3b`.
  Web remained at `sha-fbf151f1bf7eb4dd87899a61d8a34cb0d639a4f1`, `1/1` Ready;
  PostgreSQL remained Running.
- After deployment, `node web/scripts/k3s-route-live-browser.mjs` passed at
  `2026-10-22T06:08:34Z`. It read the current Host route from `k3s-1`, fetched
  service inventory and probes from the real Core API, and blocked every
  topology draft write (`topologyWrites: 0`). The rendered Istio workspace
  showed 2 Services, 2/2 Ready endpoints, 3/3 sidecars, and 26 real Envoy
  requests: 13 HTTP 200 and 13 HTTP 403, both labeled mTLS. The screenshot at
  `/tmp/nanpanel-k3s-istio-service-network-ui.png` was visually inspected.
- This deploys the SPI and plugin status enumeration only. There is still no
  concrete KubeEdge Provider, Core lifecycle API, typed execution/recovery
  flow, or live KubeEdge target; it does not close the P4 KubeEdge lifecycle
  gate. No `k3s.preflight` was run and `.env` was not changed.
