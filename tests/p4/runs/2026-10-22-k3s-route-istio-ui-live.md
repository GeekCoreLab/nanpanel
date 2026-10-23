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

## Read-only recheck at 2026-10-22 06:25 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs` from the current `main`
  worktree. The SSH query read only the target Host and route snapshot; the
  browser again used a test-only session and empty draft projection, while
  Kubernetes Service inventory and Envoy telemetry came from the deployed
  Core via the restricted read-only API key.
- Result: `PASS`, Host `k3s-1`, route gateway `10.0.0.1`, inventory timestamp
  `2026-10-22T06:25:49.806Z`, `topologyWrites: 0`, and no browser page errors.
- Visually inspected the refreshed screenshots. The topology view shows the
  observed-only Router edge and searchable Gateway-to-Host binding. The
  separate Istio workspace shows 2 Services, 2/2 Ready endpoints, 3/3 sidecars,
  and 26 Envoy requests: 13 HTTP 200 and 13 HTTP 403, both labeled mTLS. The
  service list and traffic panel remain separate, with Service details
  collapsed until expanded.
- Screenshots: `/tmp/nanpanel-k3s-route-live-ui.png`,
  `/tmp/nanpanel-k3s-istio-service-network-ui.png`, and
  `/tmp/nanpanel-k3s-gateway-asset-live-ui.png`.

This recheck confirms the real single-node route and Istio display only. The
production topology remains untouched, and no `k3s.preflight` operation was
run.

## Web layout refinement deployment at 2026-10-22 07:05 UTC

- Commit `8722235f8ed61e25aa32d407d1c004e0f200d8be` passed GitHub Actions run
  `39736376356`; Rust formatting/check/Clippy/tests, Maven verification, Web
  build, and both GHCR image publications succeeded.
- Only `deployment/nanpanel-web` was updated from
  `sha-fbf151f1bf7eb4dd87899a61d8a34cb0d639a4f1` to the immutable GHCR image
  `ghcr.io/geekcorelab/nanpanel-web:sha-8722235f8ed61e25aa32d407d1c004e0f200d8be`.
  The rollout completed with Web `1/1` Ready and zero restarts. Core remained
  on `sha-ff53346e3ee4f21b1aef00b7429604c982582ba5`, `1/1` Ready with zero
  restarts; PostgreSQL remained Running. The HTTPS Web root returned HTTP 200.
- The deployment removes topology draft save/reload actions while the Istio
  read-only workspace is selected. Gateway asset unbinding is now an inline
  control that keeps the card size fixed; auto-placement accounts for Gateway,
  Router, and folder controls when avoiding overlaps.
- `node web/scripts/k3s-route-live-browser.mjs` returned `PASS` against the
  deployed Web and real Core. It displayed the observed `k3s-1 → 10.0.0.1`
  route, a Gateway bound to the live Host asset, 2 Services, 2/2 Ready
  endpoints, 3/3 sidecars, and 26 real Envoy requests (13 HTTP 200 and 13 HTTP
  403, both mTLS). `topologyWrites` remained `0`; no browser errors occurred.
- Visually inspected the screenshots at
  `/tmp/nanpanel-k3s-route-live-ui.png`,
  `/tmp/nanpanel-k3s-istio-service-network-ui.png`, and
  `/tmp/nanpanel-k3s-gateway-asset-live-ui.png`. The Istio workspace has no
  topology save/reload buttons and keeps the Service list and traffic panel in
  separate columns. The route canvas has no overlapping cards.

The deployed browser session and empty topology draft remain test-only. This
confirms the live UI and Istio data path, not production topology persistence.
No Core, database, PVC, host route, or `k3s.preflight` state was changed.

## Read-only recheck at 2026-10-23 00:07 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs` against the current
  configured HTTPS Web/Core origin. The Host and route snapshot were read over
  SSH; Kubernetes Service inventory and Istio telemetry were fetched from Core
  with the restricted read-only API key. The browser used the test-only session
  and an empty draft projection, and blocked all topology `PUT` requests.
- Result: `PASS`, Host `k3s-1`, route gateway `10.0.0.1`, inventory timestamp
  `2026-10-23T00:07:50.492Z`, no page errors, and `topologyWrites: 0`.
- The refreshed Istio screen displayed 2 Services, 2/2 Ready endpoints, 3/3
  ready/scraped sidecars, and 26 real Envoy requests: 13 HTTP 200 and 13 HTTP
  403, both labeled mTLS. The Service list and traffic panel are separate
  columns; endpoint details remain collapsed until expanded. The screenshot
  was visually inspected at `/tmp/nanpanel-k3s-istio-service-network-ui.png`.
- This confirms the current single-node read-only UI/data path and segmented
  layout. It does not save the production topology or close multi-node,
  installation/upgrade recovery, remote credential, or KubeEdge lifecycle
  gates. No `k3s.preflight` ran.

## Read-only recheck after Core credential-reference deployment at 2026-10-22 11:21 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs` against the current
  `k3s-1` Core and Web. Result: `PASS`, Host `k3s-1`, observed route gateway
  `10.0.0.1`, route observation `2026-10-22T11:21:37.162+00:00`, no browser
  errors, and `topologyWrites: 0`.
- The test fetched Kubernetes Service inventory and Envoy telemetry through the
  deployed Core and the existing restricted read-only API key. It asserted
  `Istio LIVE`, 3/3 ready/scraped sidecars, and one real HTTP 200 mTLS flow plus
  one real HTTP 403 mTLS flow. Mock mode explicitly returns
  `503 LIVE_ISTIO_REQUIRED` for these endpoints.
- Visually inspected the refreshed screenshots. The Istio workspace keeps
  Kubernetes Services and Istio traffic in separate columns, with Service
  details collapsed until expanded. The topology workspace keeps the observed
  Router read-only and shows the matched Host route without overlapping cards.
- This remains a single-node read-only UI/data-path recheck. The production
  topology draft was not written, and no `k3s.preflight` operation ran.

## Mock and live UI pair recheck at 2026-10-22 11:57 UTC

- Added a Playwright assertion that Mock mode renders neither Service cards nor
  Istio traffic rows. `npm test` passed all 13 Node tests; the Mock browser
  topology workflow passed against a dedicated `VITE_API_MODE=mock` Vite
  server, including the explicit “real Istio data is not connected” message.
- Re-ran `node web/scripts/k3s-route-live-browser.mjs` against `k3s-1`: `PASS`,
  route gateway `10.0.0.1`, route observation
  `2026-10-22T11:57:52.449+00:00`, `topologyWrites: 0`, and no page errors. The
  browser test forwards Service inventory and Envoy requests to the deployed
  Core using the restricted read-only API key; it asserted `Istio LIVE`, 3/3
  ready sidecars, and HTTP 200/403 mTLS flows.
- The refreshed screenshot again shows two distinct columns for Kubernetes
  Services and Istio traffic, with per-Service endpoint details collapsed until
  expanded. This is a read-only single-node recheck; production topology and
  `k3s.preflight` state remain untouched.

## Read-only target recheck at 2026-10-22 12:12 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs` against the deployed
  target: `PASS`, Host `k3s-1`, observed route gateway `10.0.0.1`, observation
  time `2026-10-22T12:12:07.449+00:00`, and `topologyWrites: 0`.
- The browser assertions again consumed the deployed Core's Kubernetes and
  Istio APIs. The rendered snapshot showed 2 Services, 2/2 Ready endpoints,
  3/3 Envoy sidecars, and 26 Envoy requests: 13 HTTP 200 and 13 HTTP 403,
  both labeled mTLS. Mock mode remains explicitly disconnected and does not
  fabricate this data.
- Inspected the refreshed route and service-network screenshots. The route
  canvas shows the observed `k3s-1 → 10.0.0.1` edge without overlapping cards;
  the Istio workspace separates Kubernetes Services and traffic into two
  columns and keeps per-Service endpoint details collapsed until requested.
- This is a read-only, single-node UI/data-path recheck. It does not establish
  saved production topology, multi-node behavior, K3s lifecycle recovery, or
  KubeEdge lifecycle acceptance. No `k3s.preflight` was run.

## Post-deployment visual recheck at 2026-10-22 13:41 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs` against deployed
  `k3s-1`: `PASS`, Host `k3s-1`, observed route gateway `10.0.0.1`, route
  observation `2026-10-22T13:41:36.652+00:00`, no browser errors, and
  `topologyWrites: 0`.
- The live Istio workspace rendered 2 Kubernetes Services with 2/2 Ready
  endpoints, 3/3 Envoy sidecars, and 26 real Envoy requests. The traffic panel
  showed HTTP 200 and HTTP 403 flows, both labeled mTLS. Service inventory and
  Envoy telemetry came from the deployed Core; the accompanying browser
  screenshot shows Services and traffic in separate columns with per-Service
  endpoint details collapsed until expanded.
- Visually inspected `/tmp/nanpanel-k3s-istio-service-network-ui.png` and
  confirmed the deployed layout and live status badge. The route and Gateway
  asset screenshots were also produced by the run.

This remains read-only single-node UI and data-path evidence. It does not
establish production topology persistence, multi-node behavior, K3s lifecycle
recovery, or KubeEdge lifecycle acceptance. No `k3s.preflight` ran.

## Read-only target recheck at 2026-10-22 14:11 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs`: `PASS`, Host `k3s-1`,
  observed route gateway `10.0.0.1`, route observation
  `2026-10-22T14:11:36.652+00:00`, no browser errors, and `topologyWrites: 0`.
- The browser continued to source Service inventory and Istio telemetry from
  deployed Core. Script assertions confirmed Istio `LIVE`, 3/3 sidecars,
  HTTP 200 and HTTP 403 traffic rows both labeled mTLS. Visually inspected the
  refreshed Istio screenshot: 2 Services, 2/2 Ready endpoints, separate
  Service/traffic columns, and collapsed per-Service endpoint details.
- The browser session and empty topology projection are test-only; topology
  writes were blocked. This does not prove production topology persistence.
  No `k3s.preflight` operation ran.

## Core mapping update deployment at 2026-10-22 14:34 UTC

- GitHub Actions run `39795045950` passed all displayed Rust, Maven, and Web
  validation steps. The `publish` job completed successfully and published
  `ghcr.io/geekcorelab/nanpanel-core:sha-05bb3271b41905141d101f687cc20416cfbb341e`;
  its registry manifest resolves to `sha256:2395b2dc8235ea39898f7621faef48846cf18445cf27da0f9d37301f11e3534f`.
  GitHub still reported the `validate` job as `in_progress` after all displayed
  steps had completed, so the workflow run-level conclusion had not settled.
- Updated only the `core` container in Deployment `nanpanel-core` on `k3s-1`.
  The rollout completed; the old Pod exited and the replacement Pod is Running
  with both containers Ready and zero restarts. The `agent-gateway` sidecar
  remains on `sha-92d86546ee0ad20bb0a069637268ced09b3d2d55`. The Web Deployment
  remains on `sha-8722235f8ed61e25aa32d407d1c004e0f200d8be`, Ready 1/1.
- Re-ran `node web/scripts/k3s-route-live-browser.mjs` after rollout: `PASS`,
  Host `k3s-1`, route gateway `10.0.0.1`, observation
  `2026-10-22T14:34:51.653+00:00`, no browser errors, and `topologyWrites: 0`.
  It again verified live Core Service inventory and Envoy telemetry, 3/3
  sidecars, and HTTP 200/403 mTLS rows. This is still a test-only read-only
  topology projection; it does not establish saved production topology.
- No Agent/Helper operation or `k3s.preflight` ran. The single-node target was
  not used for multi-node, fresh-install, or destructive upgrade acceptance.

## Current-state recheck at 2026-10-22 14:40 UTC

- Re-ran `node web/scripts/k3s-route-live-browser.mjs`: `PASS`, observed route
  gateway `10.0.0.1` at `2026-10-22T14:40:50.491+00:00`, and
  `topologyWrites: 0`. Core is Ready 1/1 with image
  `ghcr.io/geekcorelab/nanpanel-core:sha-05bb3271b41905141d101f687cc20416cfbb341e`
  and unchanged `agent-gateway`; Web remains Ready 1/1 on its existing image.
  All application Pods are Running; Core and Web report zero restarts.
- Inspected the refreshed Istio workspace screenshot. It shows the real Core
  Service/EndpointSlice inventory (2 Services, 2/2 Ready endpoints), 3/3 Envoy
  sidecars, and 26 Envoy/mTLS requests with separate HTTP 200 and 403 rows.
  Services and Istio traffic remain in two columns; endpoint details stay
  collapsed until requested. Mock mode remains explicitly disconnected.
- This remains a read-only test-session projection. No production topology
  save, `k3s.preflight`, Helper operation, or K3s lifecycle operation ran.
