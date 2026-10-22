# KubeEdge EdgeCore automatic Host mapping code check

Date: 2026-10-22

## Behavior

After a validated EdgeCore join Helper operation reports `SUCCEEDED`, Core now
checks the cluster snapshot for exactly one node that is present, Ready, marked
as a KubeEdge Edge node, observed no earlier than Helper completion, and whose
name matches the planned Host hostname case-insensitively. If the node and Host
are both unassigned, Core maps that Node UID to the planned Host and writes an
audit event. A prior mapping to another Host, a Host already mapped to another
Node, no match, or ambiguous matches leave the plan in
`AWAITING_NODE_REGISTRATION`; existing mappings are never overwritten.

## Verification

- `NetworkProviderApiTest` passed with the following sequence: Helper running;
  Helper success with no fresh Node; a fresh same-name Ready Edge node already
  mapped to another Host (plan stays waiting and mapping is preserved); then a
  different node already mapped to the target Host (again stays waiting and
  preserves the mapping); both conflicts are removed; Core automatically maps
  the Edge Node to the planned Host and reports `SUCCEEDED`.
- Command: `./mvnw -pl backend/panel-core -am -Dtest=NetworkProviderApiTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Limits

This is isolated Core database/API evidence. No KubeEdge installation or join
was run, and no automatic mapping change was made on `k3s-1`. Generic mapping
for unrelated runtime-discovery paths, concurrent real node registration,
compensation, and Core crash recovery remain outside this check.

## Deployment on `k3s-1`

- Implementation commit `bb3cb16f49038326658f16673199ef73b89b77ab` passed
  GitHub Actions run `39786370730`; Rust full checks, Maven verify, Web build,
  and Core/Web GHCR image publication succeeded. Follow-up test-only commit
  `8c39faa135e8eaafe46e68f15b1d485311ec01a5` adds both mapping-conflict
  directions to the same isolated API regression.
- Only the `core` container of Deployment `nanpanel-core` was changed to image
  `ghcr.io/geekcorelab/nanpanel-core:sha-bb3cb16f49038326658f16673199ef73b89b77ab`.
  The new Core digest is
  `sha256:feff548d0efa9448178eb93d9ac1977b9fb783d5dc981ecc42485dbfc212d4f5`;
  both Pod containers were Ready with zero restarts. `agent-gateway`, Web,
  PostgreSQL, and the database PVC remained unchanged. Core and database health
  checks returned `UP`.
- `node web/scripts/k3s-route-live-browser.mjs` passed after the rollout,
  reporting the real route gateway `10.0.0.1`, a live Istio snapshot, and
  `topologyWrites: 0`.
- The deployed behavior remains unexercised against an actual KubeEdge join;
  no KubeEdge Node or Host mapping on `k3s-1` was changed.
