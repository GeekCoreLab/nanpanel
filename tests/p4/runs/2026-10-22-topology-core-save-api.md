# Core topology save/reload API integration

## Scope

This run exercises the deployed API contract in an isolated Quarkus test
application with an H2 database. It does not connect to or write the production
`k3s-1` topology.

## Command and result

```text
./mvnw -pl backend/panel-core -am -Dtest=TopologyResourceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: `TopologyResourceTest` passed (1 test, 0 failures, 0 errors, 0 skips).

## Covered behavior

- Authenticated Core API reads the current topology draft and its ETag.
- A draft containing distinct Host assets bound to Gateway and Host nodes, a
  Router next-hop IP, and a Gateway-to-Host link is saved through the CSRF and
  `If-Match` guarded endpoint.
- A fresh GET reloads the saved nodes, asset IDs, Router IP, link, and new ETag.
  The topology revision endpoint returns the saved revision and node/link
  counts.
- Reusing a stale ETag returns `412 PRECONDITION_FAILED`. Referencing a
  nonexistent Host asset returns `422 INVALID_TOPOLOGY`; neither rejection
  changes the persisted generation.

## Boundary

This establishes Core API persistence in an isolated database, not a production
write/read round trip on `k3s-1`. The live browser test continues to block
topology writes, so the target-production topology gate remains open.
