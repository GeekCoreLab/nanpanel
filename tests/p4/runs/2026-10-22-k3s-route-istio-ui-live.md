# k3s-1 route topology and Istio service network UI recheck

Date: 2026-10-22

## Release and deployment

- Commit `5d658921d52bd4cdc0ad8e69b55f8d3f62571292` passed GitHub Actions run
  `39708548940`; Rust, Maven, Web validation and both GHCR image publications
  succeeded.
- Core and Web now run the immutable `sha-5d658921d52bd4cdc0ad8e69b55f8d3f62571292`
  images on `k3s-1`, each `1/1` Ready with zero restarts. The Agent binary was
  synchronized from Core; its SHA-256 matched the image artifact and
  `nanpanel-agent.service` is active with zero restarts.
- Direct node pulls from GHCR did not finish within the rollout wait. Both
  images were then pulled on `k3s-1` from the supplied GHCR mirror and the
  deployment script was rerun. No image was uploaded from the development
  machine. PostgreSQL remained Running and its existing 5 GiB PVC stayed Bound.
- Using the target's serving certificate, `/q/health/ready` returned `UP` and
  the HTTPS Web root returned HTTP 200.

## Follow-up release for the live Host/Gateway picker

- Commit `12a7b33d269218be412e59ccd07fb55c859a0b9a` passed GitHub Actions run
  `39711248650`; Rust, Maven, Web validation and both GHCR image publications
  succeeded.
- Core and Web images were pulled on `k3s-1` directly from the public GHCR
  repositories and deployed with `deploy/k3s/deploy.sh`. The supplied GHCR
  mirror returned the Core manifest, but its layer transfer remained below
  0.4 MiB/s, so that incomplete prefetch was stopped before the GHCR pull.
- Core and Web now use immutable `sha-12a7b33d269218be412e59ccd07fb55c859a0b9a`
  image tags and each Deployment reports `1/1` available. The current Core and
  Web pods are Ready with zero restarts. PostgreSQL remained Running and its
  existing 5 GiB PVC stayed Bound.
- The Agent service is active with zero systemd restarts. Its installed binary
  SHA-256 matches `/work/nanpanel-agent` in the Core image:
  `e9c53a930a7267f4d1819fc7a88b1234470c27ac8343609d83952d473b8e75c8`.
- The serving-certificate readiness check returned `UP`; the HTTPS Web root
  returned HTTP 200. The live browser script passed against the deployed Web,
  loaded Host and Gateway choices through the picker, rendered the observed
  `10.0.0.1` route edge and live Istio inventory, and reported zero topology
  writes. Its latest route inventory timestamp was
  `2026-10-22T02:44:40.836+00:00`.

## Read-only target inputs

- Read the `k3s-1` Host and its network inventory from the target PostgreSQL
  database over `ssh k3s-1`; the query only selected the Host, `enp6s18`, and
  its default route. The observed route is `default via 10.0.0.1` and the
  inventory timestamp was `2026-10-22T02:24:23Z` on the latest run.
- The persisted production topology was not changed. It remains generation 0
  with no nodes or links. The browser loaded an empty test-only draft projection
  and used the normal “Add Host Asset” picker to add `k3s-1` to the in-memory
  outer topology. The host-list response came from the read-only target
  PostgreSQL snapshot above; no Host node was injected into the draft response.
- Browser authentication was supplied by a test-only `/auth/me` response. Host
  route inputs came from the actual target inventory. Kubernetes service
  inventory and probes were fetched directly from the deployed Core with the
  existing restricted read-only API key; no service data was mocked.
- The rendered snapshot contained two `nanpanel-mesh` Services, 2/2 Ready
  Endpoints, three discovered/ready/scraped Istio sidecars, and 26 Envoy requests
  (26 labeled mTLS). Traffic rows showed 13 HTTP 200 requests and 13 HTTP 403
  requests, both labeled mTLS.

## UI result

`web/scripts/k3s-route-live-browser.mjs` passed against the local Web dev server
and the deployed Core on `k3s-1`. The browser displayed:

- `k3s-1` Host asset and its default route row resolving to
  `自动发现 Router 10.0.0.1`;
- a public Gateway bound through its searchable asset dropdown to the same live
  `k3s-1` Host asset;
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
screenshots were written to `/tmp/nanpanel-k3s-route-live-ui.png` and
`/tmp/nanpanel-k3s-gateway-asset-live-ui.png`. Hybrid mode now explicitly uses
the real API for Host asset pickers and the default-cluster Namespace views;
Mock labeling follows the active Mock mode rather than a separate business
mock flag. The topology browser test also covers its menu during programmatic
viewport animation.

## Read-only recheck after topology persistence regression coverage

- Recheck command: `node web/scripts/k3s-route-live-browser.mjs` from the
  repository root. It read the `k3s-1` Host and route snapshot over SSH, fetched
  Kubernetes service inventory and Istio telemetry from the deployed Core with
  the restricted read-only key, and opened the configured HTTPS Web/Core
  origin. The browser supplied a test-only session and empty draft projection;
  its route handler blocked every topology `PUT`.
- Result: `PASS`, Host `k3s-1`, route gateway `10.0.0.1`, observed at
  `2026-10-22T02:57:25.84+00:00`, `topologyWrites: 0`, no browser page errors.
- The fresh screen showed two `nanpanel-mesh` Services, 2/2 Ready endpoints,
  3/3 ready and scraped Istio sidecars, and 26 real Envoy requests: 13 HTTP 200
  and 13 HTTP 403, all labeled mTLS. The UI keeps four summary metrics above a
  two-column expandable Kubernetes Service list and Istio traffic panel; it
  does not flatten all endpoints and flows into one list.
- Screenshot files: `/tmp/nanpanel-k3s-route-live-ui.png` and
  `/tmp/nanpanel-k3s-gateway-asset-live-ui.png`; the latter was visually
  inspected after this run.
- The target production topology remains generation 0 with no nodes or links.
  Host selection and route matching were exercised in the ephemeral UI draft;
  saved production graph acceptance is still open.

## Read-only rerun after `2e11950`

`ssh k3s-1` confirmed the deployed Core and Web remain on immutable image
`sha-12a7b33d269218be412e59ccd07fb55c859a0b9a`, both `1/1` Ready. The same
browser script returned `PASS` against the live target with route gateway
`10.0.0.1` and route timestamp `2026-10-22T03:04:55.838Z`. The screenshot again
showed two real Services, 2/2 Ready endpoints, 3/3 scraped sidecars, and 26
Envoy requests (13 HTTP 200 and 13 HTTP 403, all mTLS). The observed Router
edge and searchable Gateway Host selection rendered, with `topologyWrites: 0`.

This source commit changed tests and P4 records only, so no new runtime image
was built or deployed. The browser used the existing test-only session and
ephemeral empty topology projection; production login and persisted topology
save remain unverified. Screenshot: `/tmp/nanpanel-k3s-gateway-asset-live-ui.png`.

## Acceptance boundary

This verifies the normal Host asset picker and route matching display using the
real `k3s-1` Agent inventory and live Istio service data. It does not persist a
Host node to the production topology. Production graph persistence remains
untouched by this run, so the operator must still save the intended Host node
before this route is present on a later production visit.
The single-node target is sufficient for this route and service-network
acceptance. Multi-node mapping/removal, multi-node lifecycle recovery, and
KubeEdge gates remain open because no second cluster/node or KubeEdge target is
available. `npm run build`, `npm test` (13 tests), and `npm run test:topology`
passed for this change. The build reports the existing large-chunk warning.
