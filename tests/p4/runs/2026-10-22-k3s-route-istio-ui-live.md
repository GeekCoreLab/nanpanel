# k3s-1 route topology and Istio service network UI recheck

Date: 2026-10-22

## Read-only target inputs

- Read the `k3s-1` Host and its network inventory from the target PostgreSQL
  database over `ssh k3s-1`; the query only selected the Host, `enp6s18`, and
  its default route. The observed route is `default via 10.0.0.1` and the
  inventory timestamp was `2026-10-22T01:53:48Z`.
- The persisted production topology was not changed. It remains generation 0
  with no nodes or links. The browser used a test-only one-Host topology draft
  projection to exercise how the real inventory renders after an operator adds
  the Host asset to the outer topology.
- Browser authentication was supplied by a test-only `/auth/me` response. Host
  route inputs came from the actual target inventory. Kubernetes service
  inventory and probes were fetched directly from the deployed Core with the
  existing restricted read-only API key; no service data was mocked.
- The rendered snapshot contained two `nanpanel-mesh` Services, 2/2 Ready
  Endpoints, three discovered/ready/scraped Istio sidecars, and 26 Envoy requests
  (26 labeled mTLS). Traffic rows showed 13 HTTP 200 requests and 13 HTTP 403
  requests, both labeled mTLS.

## UI result

`web/scripts/k3s-route-live-browser.mjs` passed twice against the local Web
dev server and the live `k3s-1` APIs. The browser displayed:

- `k3s-1` Host asset and its default route row resolving to
  `自动发现 Router 10.0.0.1`;
- the observed-only Router card and its observed route edge in React Flow;
- live `kubernetes:k3s-1` / `nanpanel-mesh` service inventory, Ready endpoints,
  Istio sidecar readiness, and actual Envoy HTTP 200/403 mTLS traffic.

The edge check exposed a React Flow lifecycle issue: derived Router nodes were
visible but their handles were not consistently measured, so the observed
edge could be omitted. Auto-discovered Router nodes now enter the editor's
ephemeral node state, are excluded from topology draft writes and configured
node counts, and have their handles refreshed after the node state is in sync.
This made the observed edge render consistently in the live browser run.

The harness blocks topology draft PUT requests and reported `topologyWrites: 0`.
It did not change the saved production topology or the host route table. The
UI screenshot was written to `/tmp/nanpanel-k3s-route-live-ui.png`.

## Acceptance boundary

This verifies the route matching display using the real `k3s-1` Agent inventory
and live Istio service data. It does not persist a Host node to the production
topology. The route is shown in the normal root view once the operator adds
that Host asset; production graph persistence remains untouched by this run.
The single-node target is sufficient for this route and service-network
acceptance. Multi-node mapping/removal, multi-node lifecycle recovery, and
KubeEdge gates remain open because no second cluster/node or KubeEdge target is
available. `npm run build`, `npm test` (13 tests), and `npm run test:topology`
passed for this change; the custom live browser harness passed twice. The
build reports the existing large-chunk warning.
