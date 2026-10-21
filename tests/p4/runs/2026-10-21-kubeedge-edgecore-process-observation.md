# KubeEdge EdgeCore process observation (code-level)

- Date: 2026-10-21
- Source revision: working tree based on `b37cc0c` (uncommitted P4 changes)
- Scope: merge a mapped Host Agent's recent `edgecore.service` systemd observation into the Kubernetes Node response.

## Behavior

- `edgeCoreRegistrationStatus` remains Kubernetes-side evidence derived from
  the Edge role label, Node presence, and Ready condition.
- `edgeCoreProcessStatus` is independent. It reports `NOT_APPLICABLE`,
  `NOT_MAPPED`, `NOT_OBSERVED`, `STALE`, `ACTIVE`, `INACTIVE`, or `UNKNOWN`.
- `ACTIVE`/`INACTIVE` requires a recent EdgeCore service observation and a
  recent Agent heartbeat within the configured host offline threshold.
- The API exposes the service observation timestamp. This is cached Agent
  inventory, not a synchronous process probe or a KubeEdge registration check.
- Container lifecycle ownership remains `KUBEEDGE`; ordinary Host Agent
  container operations continue to be rejected for mapped Edge nodes.

## Validation

Command:

```sh
./mvnw -pl backend/panel-core -am \
  -Dtest=KubernetesClusterRegistryServiceTest,SystemResourceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: 7 tests passed. Coverage includes unmapped, unobserved, active,
inactive, stale-service, and stale-Agent states. Authenticated node mapping
responses verify both that a Ready Node without a Host service observation
remains `NOT_OBSERVED` and that a fresh mapped `edgecore.service` observation
is returned as `ACTIVE` with its timestamp.

## Limits

No live KubeEdge cluster or EdgeCore process was queried. KubeEdge install,
join, upgrade, certificate lifecycle, and registration management remain open.
