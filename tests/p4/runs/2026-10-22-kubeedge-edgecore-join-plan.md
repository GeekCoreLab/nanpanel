# KubeEdge EdgeCore join plan code acceptance

Date: 2026-10-22

## Scope

Added a planning-only `join_edgecore` operation to `kubeedge-core`. It requires
a fresh healthy KUBEEDGE snapshot with CloudCore `HEALTHY`, an EdgeCore target
Host, a bounded CloudCore `host:port`, a valid target version, and an opaque
`EDGECORE_BOOTSTRAP_TOKEN` credential reference. Core checks the credential's
Provider/cluster scope, purpose, expiry, and revocation. It also requires the
Agent Helper Host to exist and rejects a Host already mapped to a present Node
in this cluster.

The resulting plan stores the CloudCore endpoint and credential purpose as
bounded non-secret parameters and only the credential UUID as a reference. It
does not decrypt the credential, execute `keadm join`, dispatch Helper work,
install EdgeCore, or update cluster state. CloudCore installation planning
continues to reject unrelated endpoint and credential input.

## Validation

- `python web/scripts/generate-types.py` completed after the OpenAPI request
  gained `targetEndpoint`.
- The focused Maven command passed:
  `./mvnw -B -ntp -pl backend/panel-core,plugins/provider-kubeedge -am test -Dtest=NetworkProviderApiTest,KubernetesClusterCredentialVaultTest,KubeEdgeClusterProviderTest -Dsurefire.failIfNoSpecifiedTests=false`.
- The six KubeEdge Provider tests cover a valid join plan, restore validation,
  rejection when the target Host becomes a registered Node, missing endpoint or
  credential, malformed endpoint and port, wrong component, and unhealthy
  CloudCore.
- Core API tests cover opaque credential persistence, no secret leakage,
  revocation drift, and the existing Provider contract fixture. Core validation
  also enforces credential-purpose matching before persisting any Provider
  plan.
- The Core API fixture now exercises the EdgeCore plan through HTTP: an
  existing Host and healthy CloudCore produce a persisted plan, validation
  succeeds while the Host is unmapped, and the serialized plan contains only
  the opaque credential UUID (not its material). Core rejects a credential
  purpose mismatch and rejects a second plan after that Host maps to a present
  cluster Node.
- Repository-wide `./mvnw -B -ntp verify` passed: Core ran 47 tests and the
  KubeEdge Provider ran 6 tests, all with zero failures or errors; the remaining
  Maven modules also passed.

## Boundary

This is isolated code acceptance for an EdgeCore join plan only. No KubeEdge
target is available. The plan cannot be executed; secret opening, Helper
dispatch, `keadm` execution, post-join verification, compensation, restart
reconciliation, and live lifecycle acceptance remain open P4 work.

## Core deployment on `k3s-1`

- Commit `1f1a16c95d63e025e1f362303dcfd10582ac4441` passed GitHub Actions run
  `39771560521`; the validate job and Core/Web GHCR publications succeeded.
- Only `deployment/nanpanel-core` was updated. Its `core` and `agent-gateway`
  containers now use
  `ghcr.io/geekcorelab/nanpanel-core:sha-1f1a16c95d63e025e1f362303dcfd10582ac4441`.
  The new Pod is 2/2 Ready with zero restarts and image digest
  `sha256:1b6536b5b4c2d37c695da7461e46c27405a92f3267f0fa6074633ad5709e04a5`.
  `/q/health/ready` returned `UP`, including the database health check. The old
  Pod terminated after rollout. Web stayed on `sha-8722235...`; PostgreSQL
  remained Running and its existing 5 GiB PVC stayed Bound.
- `node web/scripts/k3s-route-live-browser.mjs` passed after deployment at
  `2026-10-22T11:41:52.164+00:00`: the page showed the observed Host route,
  `Istio LIVE`, 3/3 sidecars, and live Envoy HTTP 200/403 mTLS flow rows. It
  reported `topologyWrites: 0`.
- The user chose to skip live `k3s.preflight`; no preflight or production
  topology write was performed.

This deployment proves Core startup and no regression in the real Istio UI. It
does not provide KubeEdge target or lifecycle execution acceptance.
