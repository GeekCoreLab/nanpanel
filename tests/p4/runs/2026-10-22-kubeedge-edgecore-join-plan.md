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
- Repository-wide `./mvnw -B -ntp verify` passed: Core ran 47 tests and the
  KubeEdge Provider ran 6 tests, all with zero failures or errors; the remaining
  Maven modules also passed.

## Boundary

This is isolated code acceptance for an EdgeCore join plan only. No KubeEdge
target is available. The plan cannot be executed; secret opening, Helper
dispatch, `keadm` execution, post-join verification, compensation, restart
reconciliation, and live lifecycle acceptance remain open P4 work.
