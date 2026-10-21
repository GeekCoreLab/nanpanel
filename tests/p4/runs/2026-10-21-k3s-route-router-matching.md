# K3s route next-hop Router matching

## Scope

Added a top-level `ROUTER` topology node with an editable IPv4/IPv6 address.
Read-only route observations from bound Host Agents now resolve against both
selected Host interface addresses and Router node addresses. The same resolver
drives route summaries and observed topology edges. Duplicate distinct owners
remain ambiguous. Saving a Router address does not modify an operating-system
route table.

## Evidence

- Target inventory: `k3s-1` reports default gateway `10.0.0.1` on `enp6s18`;
  the address does not belong to either currently inventoried Host asset. See
  `2026-10-21-k3s-target-inventory.md`.
- Core API test saves a Router with `ipAddress: 10.0.0.1`, reads the value back,
  and rejects a malformed address with `422 INVALID_TOPOLOGY`.
- `web/scripts/topology.test.mjs` covers a unique Router owner and ambiguity
  when a Host and Router share an address.
- `NANPANEL_TEST_URL=http://127.0.0.1:5174 npm run test:topology`: passed the
  browser flow for topology editing, route matching, hierarchy persistence and
  Kubernetes drill-down against the Mock API. The Mock browser fixture does not
  contain the target `k3s-1` route snapshot.
- `./mvnw -pl backend/panel-core -am test`: passed; panel-core reports 21 tests,
  no failures or errors.
- `npm test`: passed, 11 tests.
- `npm run build`: passed. Vite reports its existing large-chunk warning.

## Target and limitations

The target `k3s-1` inventory was read-only. No Router node was added to the
production topology draft, and no production route table was changed. This
source and test evidence establishes the mapping behavior for the actual
observed gateway address, but the authenticated production UI has not yet
shown the route edge. The target route-matching acceptance gate therefore
remains open until an operator adds a Router node for `10.0.0.1` and verifies
the displayed edge against the live Agent snapshot.

Image/commit: to be filled after CI publication and target deployment.
