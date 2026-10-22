# KubeEdge Provider credential custody code acceptance

Date: 2026-10-22

## Implementation

- Added Liquibase migration `035-cluster-provider-credentials` with credentials
  scoped to a registered cluster and ClusterProvider.
- Added authenticated metadata list, create, and one-way revoke endpoints for
  KubeEdge credential purposes. Create and revoke require a session authenticated
  within five minutes; create accepts at most 32 KiB and optional expiry up to
  366 days.
- Core encrypts material with AES-256-GCM using the configured Kubernetes
  credential key. AAD binds the ciphertext to the credential UUID, provider ID,
  and cluster UUID. API responses and audit details omit the material. The
  package-level Core open path checks provider, cluster, purpose, revocation, and
  expiry before decrypting.

## Validation

- `KubernetesClusterCredentialVaultTest` passed: ciphertext does not expose the
  input, correct context decrypts, a different provider/cluster fails, and a
  tampered installation-token envelope is rejected.
- `NetworkProviderApiTest` passed the credential create/list/revoke API path in
  an isolated KUBEEDGE provider fixture. It confirmed response/list metadata
  contain no submitted secret, stored bytes do not contain the cleartext,
  Core-only decryption returns the original value, and expired or revoked
  credentials cannot be opened.
- The focused Maven command was
  `./mvnw -pl backend/panel-core -am test -Dtest=NetworkProviderApiTest,KubernetesClusterCredentialVaultTest -Dsurefire.failIfNoSpecifiedTests=false`.
- Repository-wide `./mvnw verify` passed, including Core migration startup,
  Kubernetes auto-enrollment regressions, and all four KubeEdge Provider tests.
- `python web/scripts/generate-types.py` completed after the OpenAPI update.

## Opaque plan reference follow-up

- Core now accepts a requested credential UUID only when it belongs to the
  selected Provider and cluster and remains unexpired and unrevoked. The
  Provider must return the same UUID in one or more plan steps; Core persists
  only the UUID and rechecks credential status before saving and when restoring
  a plan for validation. A revoked, expired, cross-scope, or omitted/mismatched
  reference is rejected or marks a restored plan `DRIFTED` before Provider
  validation. No secret material is sent to the Provider or serialized in the
  plan.
- `NetworkProviderApiTest` now covers invalid revoked references, valid UUID
  persistence with no secret leakage, successful active-reference restoration,
  and revocation before restoration. The focused command passed with 3 tests:
  `./mvnw -pl backend/panel-core -am test -Dtest=NetworkProviderApiTest,KubernetesClusterCredentialVaultTest -Dsurefire.failIfNoSpecifiedTests=false`.
- The KubeEdge CloudCore installation plan still rejects an EdgeCore bootstrap
  token because CloudCore installation does not consume it. There is no
  EdgeCore join plan or lifecycle executor, and no credential secret is opened
  by a plan step. This plumbing does not claim lifecycle execution acceptance.
- After this follow-up, repository-wide `./mvnw verify` passed: Core ran 47
  tests with no failures, the four KubeEdge Provider tests passed, and the
  other modules passed (two opt-in live-provider tests remained skipped).
  `python web/scripts/generate-types.py` also completed successfully.

## Boundary

This is credential custody and opaque plan-reference code acceptance only. No
plan step reads these credentials and no Core Kubernetes or Agent Helper
execution, verification, compensation, or restart reconciliation has been
added. There is no live KubeEdge target, so this does not close KubeEdge
lifecycle acceptance.

## Core deployment on `k3s-1`

- Commit `3ae2c2345bbddb932c343b1f19e64662c252172a` passed GitHub Actions run
  `39760996496`; Rust, Maven, Web build and both GHCR image publications
  succeeded. The published image was pulled by `k3s-1` from GHCR.
- Only `deployment/nanpanel-core` was updated; its `core` and `agent-gateway`
  containers now use the immutable `sha-3ae2c2345bbddb932c343b1f19e64662c252172a`
  image. The rollout reached 1/1 available; the new Pod's two containers were
  Ready with zero restarts and image digest
  `sha256:b27739cdd360292245123ed6eead22cb501d650ee87d322aac728f10b512216c`.
  Web stayed on `sha-8722235...`; PostgreSQL remained Running with the same PVC
  and restart count.
- Core logs confirmed Liquibase applied
  `035-cluster-provider-credentials` successfully. `/q/health/ready` returned
  HTTP 200 with Core and database checks UP. An unauthenticated metadata GET
  returned HTTP 401 as required; no secret or database credential record was
  created on the target.
- After deployment, `node web/scripts/k3s-route-live-browser.mjs` passed against
  the deployed Web and live Core snapshot at
  `2026-10-22T10:55:41.716+00:00`. It required both real HTTP 200 and 403 mTLS
  flow rows, showed Istio LIVE and 3/3 sidecars, and reported
  `topologyWrites: 0`.

This deployment proves schema startup, readiness, the authentication boundary,
and no regression in the real Istio UI. It does not prove authenticated
credential CRUD on the target or any KubeEdge lifecycle execution.
