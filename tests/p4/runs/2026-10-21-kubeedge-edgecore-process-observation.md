# KubeEdge EdgeCore process observation (code-level)

- Date: 2026-10-21
- Source revision: `2f850b63bdd92c7e303a3f40d7f159ff618db29f`
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

The same Core integration scenario now marks the KubeEdge API unavailable,
maps the Edge Host, reads its fresh `edgecore.service` through
`GET /hosts/{hostId}/services` and its route inventory, then submits
`container.stop`. The request is rejected with `409 KUBERNETES_CONTAINER_OWNED`
and the test verifies that no `operation` row was created for its idempotency
key. This verifies the independent cached Host diagnostic and ownership guard
paths together; it does not simulate a live Agent response during a real
Kubernetes API outage.

The source revision's [Publish NaNPanel containers workflow](https://github.com/GeekCoreLab/nanpanel/actions/runs/39590401033)
passed Rust format/check/clippy/test, Maven verify, Web build, and publication
of both immutable images. The commit changes only tests and acceptance records;
it does not require a runtime redeployment.

## Limits

No live KubeEdge cluster or EdgeCore process was queried. KubeEdge install,
join, upgrade, certificate lifecycle, and registration management remain open.
