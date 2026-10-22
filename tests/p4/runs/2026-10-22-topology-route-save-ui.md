# Outer topology Host asset and route save/reload UI check

Date: 2026-10-22

## Local browser run

- Browser command: `NANPANEL_TEST_URL=http://127.0.0.1:5174 npm run test:topology`
- Vite mode: `VITE_API_MODE=mock npm run dev -- --port 5173`; port 5173 was
  already occupied, so Vite served this run at 127.0.0.1:5174.
- Result: `PASS`, no browser errors.
- The flow bound a Gateway to `gateway-sg-01`, added `homelab-node-01` through
  the Host asset picker, and matched its default route to the observed Router
  `192.0.2.1`. It saved the draft and reloaded it, then confirmed both assets,
  the route row, the derived Router node, and both observed React Flow edges
  returned.
- The Router remains derived read-only route state; it is rebuilt from the
  Host route inventory after reload rather than stored as a configured node.
- Host asset placement and derived Router placement search for unoccupied
  space. The browser run checks every rendered topology card for overlap after
  route matching and reload.
- `npm run build` passed. Vite emitted its existing ignored `use client`
  directive and large-chunk warnings.
- Screenshot: [`topology-routes-saved.png`](../../../web/artifacts/topology-routes-saved.png).

This is a local UI/mock API regression, not a target data-persistence run. The
Mock service network showed the explicit live-Istio-required state with no
service items. The separate target run at
[`k3s-route-istio-ui-live.md`](2026-10-22-k3s-route-istio-ui-live.md) fetched
real Kubernetes and Istio data and blocked all topology writes. The production
topology on `k3s-1` remains unchanged; authenticated save and reload against the
target database are still open.
