# K3s live Istio service network UI and API acceptance

Date: 2026-10-22

## Release and deployment

- Commit: `1ac743598bc9ee5fef66ad10d9e166c5f40d8ab4`
- GitHub Actions: run `39700855093` (`Publish NaNPanel containers`), validation
  and GHCR publish jobs passed.
- Images:
  `ghcr.io/geekcorelab/nanpanel-core:sha-1ac743598bc9ee5fef66ad10d9e166c5f40d8ab4`
  and
  `ghcr.io/geekcorelab/nanpanel-web:sha-1ac743598bc9ee5fef66ad10d9e166c5f40d8ab4`.
- Both immutable GHCR images were retrieved on `k3s-1` through the supplied
  **GHCR image mirror** and loaded into containerd; no local image build or
  upload was used.
- `./deploy/k3s/deploy.sh` completed. Core and Web were each `1/1` Ready with
  zero pod restarts. PostgreSQL stayed Ready and its data PVC remained Bound.
  The Agent service was active with zero restarts.

## Live cluster and API evidence

`./deploy/istio/verify-test-mesh.sh` passed against the isolated Istio fixture:

- Kubernetes Service and EndpointSlice discovery returned two Services in
  `nanpanel-mesh`; each had one Ready endpoint.
- Three of four fixture workloads had Istio sidecars. `mesh-http` had a Ready
  Envoy sidecar on version `1.31.1`; the separate `mesh-probe-target` was
  intentionally not injected and remained Ready for the TCP probe.
- The allowed strict-mTLS request returned the fixture body. The denied
  identity received HTTP 403.
- Authenticated `POST /api/v1/kubernetes/service-inventory/refresh` returned
  HTTP 200. The subsequent live inventory response identified source
  `kubernetes:k3s-1`, namespace `nanpanel-mesh`, and Istio status `LIVE`.
  Envoy reported 3 discovered, 3 ready, and 3 scraped sidecars. Its real
  destination counters showed `mesh-client -> mesh-http` HTTP 200 and
  `mesh-denied-client -> mesh-http` HTTP 403, both labeled `MUTUAL_TLS`.
  Each counter was 13 requests at the final API read.
- The live endpoint records marked `mesh-http` Envoy `READY` and the separate
  probe target `NOT_INJECTED`. The service-probe API returned persisted
  `SUCCEEDED` TCP/8080 observations for the fixture Services; this optional
  TCP probe remained disabled for future polling.

## Deployed UI

The deployed Web UI was opened in Playwright. A test-only route supplied the
browser session response because no valid admin password was available for an
interactive login. All service inventory and probe requests were sent to the
deployed Core with the existing restricted read-only API key; those responses
were not intercepted or fabricated. The browser verified the `LIVE` status,
3/3 sidecar summary, HTTP 200/403 mTLS traffic rows, and that Kubernetes Service
details start collapsed. No page errors occurred. The screenshot was inspected
at `/tmp/nanpanel-istio-live-ui.png`.

The UI result proves the deployed hybrid-mode service data path and layout, but
does not prove an interactive administrator login. Mock-mode behavior is
covered by the Web test `Mock mode refuses to fabricate service inventory and
Istio traffic`; `npm run build` and `npm test` passed before deployment.

## Boundary

This run closes only the live Istio service-network P4 gate. It uses the
dedicated `nanpanel-mesh` fixture in the target K3s cluster. It does not close
the separate P4 gates for Agent auto-enrollment, target route matching,
multi-node mapping/removal, multi-node install or upgrade recovery, KubeEdge
management, or control-plane failure and data ownership.
