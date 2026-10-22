# K3s Node mapping conflict and removal (Core API code evidence)

Date: 2026-10-22

## Covered behavior

The isolated Core integration case seeds a K3s cluster with two Node records and
two Host assets, then exercises the authenticated Node-to-Host API:

- Mapping the first Node succeeds.
- Mapping the second Node to the already claimed Host returns
  `409 KUBERNETES_NODE_MAPPING_CONFLICT`.
- After the second Node disappears from the refreshed inventory, Core keeps its
  tombstone and Host association until an explicit unmap request.
- Unmapping the removed Node deletes the stale mapping record and releases its
  Host, which can then be mapped to the remaining Node.

Command:

```sh
./mvnw -pl backend/panel-core -am \
  -Dtest=K3sInstallationApiTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: all 9 `K3sInstallationApiTest` cases passed.

## Boundary

The two Node records are isolated H2 fixture data. This verifies the Core API
conflict and removal state machine; it does not represent multiple live K3s
Nodes or replace target multi-node acceptance on `k3s-1`.
