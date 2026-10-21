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

## Deployment

- Implementation commit: `29827b84964621b0c11e0cf2b95c57ffd01f6872`.
- [Publish workflow](https://github.com/GeekCoreLab/nanpanel/actions/runs/39587962017)
  passed validation and published both immutable `sha-29827b84964621b0c11e0cf2b95c57ffd01f6872`
  images.
- `./deploy/k3s/deploy.sh` completed successfully against SSH target `k3s-1`.
  The first rollout wait timed out while GHCR reset an IPv6 download connection;
  Kubernetes retained the old Core Pod, then its automatic pull retry succeeded.
  Re-running the idempotent deployment script completed the remaining rollout
  and Agent verification.
- Final remote state: Core 1/1 and Web 1/1 on the immutable images; both new
  Pods have zero restarts. `nanpanel-postgres-0` stayed Running and its existing
  5 GiB PVC remains Bound to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
  `nanpanel-agent.service` is active with zero systemd restarts.
- Runtime checks: `https://10.100.0.1:30443/q/health/ready` reports `UP`; the
  HTTPS home page returns `200 OK` and references `assets/index-B1BD5i-8.js`.
