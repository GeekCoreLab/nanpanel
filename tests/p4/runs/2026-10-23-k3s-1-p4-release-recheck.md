# k3s-1 P4 release and live Istio UI recheck

## Scope

This records deployment of the already validated `main` revision and a read-only
single-node UI recheck. It does not claim the multi-node, KubeEdge, K3s install,
or upgrade-recovery acceptance gates are complete.

## Release

- Source: `main` at `65687945b59975199475ca9860b933ab3402bfbf`.
- [GitHub Actions run `39878798071`](https://github.com/GeekCoreLab/nanpanel/actions/runs/39878798071): Rust format/check/clippy/tests, Maven verify,
  Web build, and both GHCR image publications succeeded.
- Deployed immutable Core and Web images tagged
  `sha-65687945b59975199475ca9860b933ab3402bfbf` with
  `deploy/k3s/deploy.sh` over `ssh k3s-1`.
- The deployment script confirmed the Core and Web rollouts. Both Deployments
  are `1/1` Ready; their new Pods have zero restarts. The Agent binary was
  synchronized from the Core image and its previous binary was retained at
  `/var/lib/nanpanel-agent/nanpanel-agent.pre-603c5026b64883dd81324df1efd513937300fe198cf977a40ff640dd7db21a5f`.
- PostgreSQL remained on its existing `postgres:18` StatefulSet. PVC
  `data-nanpanel-postgres-0` stayed Bound with UID
  `3428a4ec-567f-4678-b44c-fd548a57e5b2` and 5 GiB capacity. Existing database,
  TLS, Agent, SSH credential, and Kubernetes credential Secrets were retained.
- The only Kubernetes Node remains `k3s-1` (`v1.33.5+k3s1`). No
  `k3s.preflight`, installation, upgrade, or Helper operation was run.

## Live UI and data evidence

Ran `node web/scripts/k3s-route-live-browser.mjs` after deployment. Result:

```json
{
  "result": "PASS",
  "host": "k3s-1",
  "routeGateway": "10.0.0.1",
  "routerNode": "ephemeral read-only UI projection",
  "topologyWrites": 0
}
```

The browser loaded the deployed HTTPS console (HTTP 200), displayed the real
`k3s-1` Host route to `10.0.0.1`, and showed the actual Core-collected
`kubernetes:k3s-1` / `nanpanel-mesh` Istio snapshot. The service panel showed
2 Services, 2/2 Ready endpoints, 3/3 ready Istio sidecars, and live Envoy
request counters. It showed both the allowed HTTP 200 and denied HTTP 403
flows with mTLS labels. The test used the existing read-only API key for the
Istio endpoints and a test-only session/Host projection for the browser; it did
not validate an administrator login or write topology data.

Screenshots captured by the run:

- `/tmp/nanpanel-k3s-istio-service-network-ui.png`
- `/tmp/nanpanel-k3s-route-live-ui.png`
- `/tmp/nanpanel-k3s-gateway-asset-live-ui.png`

## Boundary

This confirms the current single-node deployment and real Istio inventory/flow
display. It does not close the open P4 acceptance items requiring target
topology persistence, multiple real Nodes, a remote Agent host, KubeEdge, or
real installation/upgrade recovery. Live `k3s.preflight` was skipped as
requested.
