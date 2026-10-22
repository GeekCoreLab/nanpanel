# K3s API failure with Host diagnostics (Core integration)

Date: 2026-10-22

## Scope

The isolated Core integration test creates a mapped Host/Node pair owned by
Kubernetes, stores an Agent-observed default route, and then records a K3s API
failure through `KubernetesClusterRegistryService.reportApiFailure`.

## Result

- The cluster API reports `UNAVAILABLE`.
- `GET /api/v1/hosts/{hostId}/network-inventory` still returns the cached
  default route through `10.0.0.1`.
- An Agent `container.stop` request for the mapped Host is rejected with
  `409 KUBERNETES_CONTAINER_OWNED`.
- No Helper `operation` row is created for the rejected request.

Command:

```sh
./mvnw -pl backend/panel-core -am \
  -Dtest=K3sInstallationApiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: 8 `K3sInstallationApiTest` cases passed.

## Boundary

This proves the Core behavior when its stored cluster observation is marked
unavailable. It does not disconnect the live `k3s-1` API or prove a live Agent
heartbeat during an outage. The test uses isolated H2 state and does not alter
the target cluster.
