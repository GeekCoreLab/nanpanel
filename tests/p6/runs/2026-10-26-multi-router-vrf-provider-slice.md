# Multi-Router VRF Provider slice and deployment

- Date: 2026-10-26.
- Commit: `bdab42ae5ec099295497cc94564d8af2d7313aab` (`Add isolated multi-router VRF support`).
- GitHub Actions: [Publish NaNPanel containers, run 40231598027](https://github.com/GeekCoreLab/nanpanel/actions/runs/40231598027); `validate` and `publish` succeeded.
- Images: `ghcr.io/geekcorelab/nanpanel-core:sha-bdab42ae5ec099295497cc94564d8af2d7313aab` and `ghcr.io/geekcorelab/nanpanel-web:sha-bdab42ae5ec099295497cc94564d8af2d7313aab`. The deploy script verified both GHCR manifests and the cluster pulled these immutable tags; no local image upload was used.

## Implemented scope

- The `linux-netns` Provider now supports multiple Router nodes in one topology. Each Router receives a deterministic VRF device and independent routing table inside that topology Revision's network namespace. Router-side veth interfaces are attached to their Router VRF; interface addresses and static routes are installed in that table.
- Core, Agent daemon, and Root Helper validate the aggregate 64-route limit, unique interfaces, usable IPv4 host addresses, non-overlapping connected networks, direct-subnet gateways, and routes that do not overlap connected networks. Identical destination prefixes are allowed in different Router tables.
- Helper Verify checks VRF type, table ID, ownership alias, interface master, exact per-Router static routes, global static/default route counts, forwarding state, and the expected l3mdev rule. Core requires the explicit `network.topology.vrf` Helper capability for plans containing Routers. The Helper advertises that capability only when its isolated named-netns probe succeeds.
- No Web code changed in this slice; OpenAPI, Core, Agent, Helper, ADR-015, and the P6 lab documentation were updated.

## Local verification

- `cargo fmt --all -- --check` — passed.
- `cargo check --all-targets --all-features` — passed.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `cargo test --all-features` — passed (65 tests across Agent, Control Relay, Helper, and helper binary targets).
- `./mvnw -pl backend/panel-core -am test` — passed (78 tests; no failures).
- `./tests/p6/multi-router-vrf-lab.sh` — passed. Two independent Router VRFs forwarded TCP traffic; the same destination prefix selected distinct next hops in tables 10000 and 10001; the l3mdev rule was checked. The host default-route digest remained `0ef98fc565c2ead8fcd958b1592e256030a823bf2623c9ad404432c2cdc06a32`.
- `./tests/p6/linux-network-lab.sh` — passed for the existing bridge/firewall/packet-tap and routed TCP data plane; the host default-route digest remained unchanged.
- OpenAPI YAML parsed with PyYAML; `git diff --check` passed.

## `k3s-1` deployment evidence

- `./deploy/k3s/deploy.sh` completed with the full SHA tag after Actions publication. Core and Web each rolled out successfully and are `1/1` Ready on the new SHA tag.
- PostgreSQL remains `1/1` Ready. The existing `data-nanpanel-postgres-0` claim remains `Bound`, 5 GiB, on volume `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- `https://10.100.0.1:30443/` returned HTTP 200. `/q/health/ready` returned HTTP 200 and `UP`, including the database connection check.
- `nanpanel-helper.service` and `nanpanel-agent.service` are active. Host binaries match the binaries in the deployed Core image: Helper SHA-256 `59433ebfbc142bac4a8a35fe7c2f6171070cddfb87de8d84172b8bd9d863db82`; Agent SHA-256 `0f5d10a5ab8b9c38057fc8f78785a8ecedc4f7c817d4b7fc24b174bca4d22542`. Previous binaries remain backed up under `/var/lib/nanpanel-helper` and `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over `/run/nanpanel/helper.sock`, sent as `nanpanel-agent`, returned 15 capabilities including `network.topology.reconcile` and `network.topology.vrf`; `network.topology.ovs` remains absent on the target.

## Acceptance boundary

The user selected one isolated network namespace per topology. All Provider bridge, Router, VRF, and route operations remain inside that namespace; host interfaces and routes are not in the operation payload. The isolated lab verifies Linux VRF forwarding and independent route selection, but does not exercise the complete Core-to-Agent-to-Helper Apply lifecycle. No topology draft or production Provider Apply was performed on `k3s-1`.
