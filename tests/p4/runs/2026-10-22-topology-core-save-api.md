# Core topology save/reload API integration

## Scope

This run exercises the deployed API contract in an isolated Quarkus test
application with an H2 database. A separate Live API browser run also uses a
disposable loopback-only PostgreSQL database. Neither run connects to or writes
the production `k3s-1` topology.

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

## Isolated Live API browser round trip

The local Quarkus dev profile ran on `127.0.0.1:18080` against a new disposable
PostgreSQL 18 container bound only to `127.0.0.1:55432`; Vite ran on
`127.0.0.1:5174` in Live API mode and proxied to that Core instance. The browser
refused non-loopback URLs and refused to proceed unless the draft was empty at
generation 0. It changed the bootstrap password through the real auth API.

Command:

```text
NANPANEL_TEST_ALLOW_WRITE=true \
NANPANEL_TEST_URL=http://127.0.0.1:5174 \
NANPANEL_TEST_PASSWORD=<disposable-local-bootstrap-password> \
npm run test:topology:core --prefix web
```

The write test requires explicit opt-in and accepts only a loopback browser
URL. `VITE_CORE_API_TARGET` must also point to the disposable local Core.

Result: PASS. The browser created Gateway, Host, Router, and K3s folder nodes,
set Router `10.0.0.1`, and connected Gateway → Host → Router. Core returned
HTTP 200 for one topology PUT and all five topology GETs. A fresh read returned
generation 1, four nodes, two links, and the Router IP; the UI reload restored
the graph and Router field, with zero overlapping cards and no page errors.
Screenshot: `web/artifacts/topology-core-save-reload.png` (ignored local
artifact).

This verifies browser-to-Core persistence against disposable local PostgreSQL.
It does not establish target credentials, production permissions, target DB
behavior, or a write/read round trip on `k3s-1`; the target gate remains open.
