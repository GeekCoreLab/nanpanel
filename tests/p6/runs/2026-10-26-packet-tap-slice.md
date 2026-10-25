# P6 Packet Tap Provider slice

- Date: 2026-10-26.
- Scope: extend the built-in `linux-netns` Provider to execute and verify a root-level `PACKET_TAP` in the selected per-topology network namespace.
- Commit: `f3a1b18978e0a0cb361ef73697a6f56bdc998634` (`Add isolated Linux packet tap support`).
- GitHub Actions: [Publish NaNPanel containers](https://github.com/GeekCoreLab/nanpanel/actions/runs/40219654056), both `validate` and `publish` jobs succeeded.
- Images: `ghcr.io/geekcorelab/nanpanel-core:sha-f3a1b18978e0a0cb361ef73697a6f56bdc998634` and `ghcr.io/geekcorelab/nanpanel-web:sha-f3a1b18978e0a0cb361ef73697a6f56bdc998634`.
- Deployment status: deployed to `k3s-1` from GHCR immutable images.

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

This slice demonstrates Packet Tap kernel behavior in the isolated Linux lab and validates the persisted Provider/Helper path with automated tests. Deployment verifies that the capability is present on `k3s-1`; it does not apply a topology there or establish production data-plane connectivity. Router, Firewall, OVS, management-path probes, and richer L2/L3 checks remain open P6 work.

## Deployment evidence

- Before rollout, Core and Web were each `1/1` Ready on image `sha-aaf16f2440ce0dee6b47dbfcaf659a72d56f2ec0`; PostgreSQL was `1/1` Ready and its 5 GiB PVC was `Bound` to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- The deployment script verified both immutable GHCR manifests, pulled the images on `k3s-1`, and completed Core and Web rollouts on the new SHA tag. No local image upload was used.
- After rollout, Core and Web were each `1/1` Ready on the `f3a1b189...` image tag. PostgreSQL remained `1/1` Ready and the same PVC remained `Bound`.
- `https://10.100.0.1:30443/` returned HTTP 200. The deployed JavaScript bundle contains `PACKET_TAP`, `Packet Tap`, and `tc mirred`. `/q/health/ready` returned HTTP 200 with database connections `UP`.
- The live authenticated browser check reached the login page, but the locally stored admin credential received HTTP 401. No session was established; no password, topology, or Provider operation was changed. Authenticated browser-visible Provider acceptance remains unverified.
- `nanpanel-helper.service` and `nanpanel-agent.service` were active. The deployed Helper SHA-256 was `869cb9bb426e7732508eef22b9831989c2c5fba3f99d544ea54c9423a7bd5e64`; Agent SHA-256 was `38cc9c3477f331a022d81b88d20cac747a5e6c0116648bec803d34a44f449d50`. The previous binaries were retained under `/var/lib/nanpanel-helper` and `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over `/run/nanpanel/helper.sock` as `nanpanel-agent` returned 14 capabilities, including `network.topology.reconcile`.
