# P6 Packet Tap Provider slice

- Date: 2026-10-26.
- Scope: extend the built-in `linux-netns` Provider to execute and verify a root-level `PACKET_TAP` in the selected per-topology network namespace.
- Deployment status: awaiting immutable GHCR publication and rollout to `k3s-1`.

## Implementation

- A Packet Tap accepts exactly one `in-1`, one business `out-1`, and one monitor `out-2` L2/Ethernet wire.
- The monitor veth remains outside the Packet Tap bridge. Trusted `tc` installs egress `matchall`/`mirred mirror` filters on both business interfaces, copying both directions to the monitor wire.
- Provider verification checks veth ownership, expected bridge membership, both mirror filters, and zero default routes. Per-wire kernel counters remain available.
- The Helper advertises `network.topology.reconcile` only when both trusted `ip` and trusted `tc` are present. Its typed request is independently validated at the privilege boundary.
- Host interfaces and routes are not part of the operation payload. No Provider Apply was run against the production topology on `k3s-1`.

## Validation

- `./mvnw -pl backend/panel-core -am test`: passed, 76 Core tests.
- `npm test`: passed, 17 tests.
- `npm run build`: passed.
- `npm run test:browser`: passed, no browser page errors.
- `npm run test:topology`: passed, including Provider capability text and topology browser flows.
- Rust checks passed after the Helper change: `cargo fmt --all -- --check`, `cargo check --all-targets --all-features`, `cargo clippy --all-targets --all-features -- -D warnings`, and `cargo test --all-features`.
- `./tests/p6/linux-network-lab.sh`: passed routed TCP, nftables drop, Packet Tap RX count, and unchanged host default-route hash (`0ef98fc565c2ead8fcd958b1592e256030a823bf2623c9ad404432c2cdc06a32`).

## Acceptance boundary

This slice demonstrates Packet Tap kernel behavior in the isolated Linux lab and validates the persisted Provider/Helper path with automated tests. Deployment verifies that the capability is present on `k3s-1`; it does not apply a topology there or establish production data-plane connectivity. Router, Firewall, OVS, management-path probes, richer L2/L3 checks, and topology replay remain open P6 work.
