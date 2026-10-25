# P6 contract and UI alignment release

- Date: 2026-10-26.
- Commit: `efebc15972a8c844164a862b91bf499e11899c47` (`Describe multi-router VRF support`).
- GitHub Actions: [Publish NaNPanel containers, run 40233035427](https://github.com/GeekCoreLab/nanpanel/actions/runs/40233035427); `validate` and `publish` succeeded.
- Images: `ghcr.io/geekcorelab/nanpanel-core:sha-efebc15972a8c844164a862b91bf499e11899c47` and `ghcr.io/geekcorelab/nanpanel-web:sha-efebc15972a8c844164a862b91bf499e11899c47`.

## Alignment and local verification

- Updated the topology contract and ADR-015 to describe the implemented Router/VRF, Bridge/Firewall/Packet Tap, per-topology netns ownership, and the selected management-path boundary. The Web Provider panel now describes multiple independent IPv4 VRF tables and the `network.topology.vrf` capability.
- `npm test` passed (18 tests); `npm run build` passed. `git diff --check` passed.
- This is a documentation and UI-copy alignment release. The multi-Router data-plane implementation and isolated Linux lab evidence remain recorded in the [Multi-Router VRF Provider report](2026-10-26-multi-router-vrf-provider-slice.md).

## `k3s-1` deployment evidence

- Core and Web run the immutable `sha-efebc15972a8c844164a862b91bf499e11899c47` images; both Deployments are `1/1` Ready. The existing PostgreSQL StatefulSet remains `1/1` Ready and its 5 GiB claim remains Bound to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- `https://10.100.0.1:30443/` and the referenced JavaScript bundle returned HTTP 200. The deployed bundle contains the multiple-IPv4-VRF-table panel text and `network.topology.vrf`. `/q/health/ready` reports UP, including the database connection check.
- `nanpanel-helper.service` and `nanpanel-agent.service` are active. Deployed-image and host binary hashes match: Helper `59433ebfbc142bac4a8a35fe7c2f6171070cddfb87de8d84172b8bd9d863db82`; Agent `1a007c0f6791bb87af62bbea24a68f0e8b2141e6a6843fcfe0a3476e089eb298`.
- A read-only `helper.capabilities` request over `/run/nanpanel/helper.sock`, sent as `nanpanel-agent`, returned 15 capabilities including `network.topology.reconcile` and `network.topology.vrf`. `network.topology.ovs` remains absent because the target lacks the optional OVS host prerequisites.

## Acceptance boundary

No topology draft or production Provider Apply was performed on `k3s-1`. The selected P6 execution boundary is one isolated network namespace per topology; isolated Linux labs verify the data plane and that host default routes remain unchanged. Full Core-to-Agent-to-Helper Apply lifecycle and production-topology Apply remain separate acceptance work.
