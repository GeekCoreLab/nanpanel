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

## Boundary

This is credential custody code acceptance only. ClusterProvider lifecycle plans
still reject credential references; no plan step reads these credentials and no
Core Kubernetes or Agent Helper execution, verification, compensation, or
restart reconciliation has been added. There is no live KubeEdge target, so
this does not close KubeEdge lifecycle acceptance.
