# P6 Firewall Provider slice

## Scope

- Extend `linux-netns` with a transparent bridge Firewall in root L2/Ethernet
  topologies. Firewall nodes require a typed default `ACCEPT`/`DROP` policy and
  may define up to 64 ordered TCP/UDP/SCTP destination-port rules.
- Require each rule to name connected input/output ports, reject duplicate
  match tuples, and check the same contract in Core, Agent dispatch and Root
  Helper validation.
- In the topology's isolated network namespace, install a bridge-family nft
  forward chain with the policy default and deterministic rule markers. Verify
  the chain policy, rule order, port matches, protocol, destination port and
  verdict before reporting success.
- The Provider does not apply routes or interfaces in the host network
  namespace. No Provider Apply was run against the production topology on
  `k3s-1`.

## Local validation

- `cargo fmt --all -- --check`: passed.
- `cargo check --all-targets --all-features`: passed.
- `cargo clippy --all-targets --all-features -- -D warnings`: passed.
- `cargo test --all-features`: passed, including typed Firewall dispatch,
  policy compilation, drift verification and invalid port rejection.
- `./mvnw -pl backend/panel-core -am test`: passed, including Firewall
  capability discovery and persisted Provider-plan serialization.
- `npm test`: passed (17 tests).
- `npm run build`: passed.
- `npm run test:browser`: passed; no page errors.
- `npm run test:topology`: passed; no page errors.
- `./tests/p6/linux-network-lab.sh`: passed baseline routed TCP, then a
  bridge-family Firewall drop (counter 1) and an independent
  server-namespace input drop (counter 1); Packet Tap RX reached 7 packets.
  Host default-route digest was
  unchanged: `0ef98fc565c2ead8fcd958b1592e256030a823bf2623c9ad404432c2cdc06a32`.

## Release and deployment evidence

- Commit: `6829f61014a9547373daa010bffe4ad7a45168dd`
  (`Add isolated bridge firewall support`).
- [Actions run 40222461423](https://github.com/GeekCoreLab/nanpanel/actions/runs/40222461423):
  `validate` and `publish` both succeeded.
- Immutable images:
  `ghcr.io/geekcorelab/nanpanel-core:sha-6829f61014a9547373daa010bffe4ad7a45168dd`
  and
  `ghcr.io/geekcorelab/nanpanel-web:sha-6829f61014a9547373daa010bffe4ad7a45168dd`.
  `deploy/k3s/deploy.sh` verified both GHCR manifests and deployed those tags;
  no local image upload was used.
- Before rollout, Core and Web were `1/1` Ready on the Packet Tap image. After
  rollout, both are `1/1` Ready on this commit's images. PostgreSQL remained
  `1/1` Running without a new restart, and the existing 5 GiB PVC stayed Bound
  to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- `https://10.100.0.1:30443/` returned HTTP 200. The deployed JavaScript bundle
  contains `FIREWALL`, `bridge-family`, and `nftables`. `/q/health/ready` returned
  HTTP 200 with database connections `UP`.
- `nanpanel-helper.service` and `nanpanel-agent.service` are active. The updated
  Helper SHA-256 is
  `6ad3f495fc06f5dd55ac1dfd613bbc32143104a90a611bc67a9c5f6a3ca21d87`; the
  Agent SHA-256 is
  `ec5672ca5675587ed7730900073667f4a3f75d132bf1e790fa18941a4989beaf`.
  Previous binaries remain backed up under `/var/lib/nanpanel-helper` and
  `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over `/run/nanpanel/helper.sock` as
  `nanpanel-agent` returned 14 capabilities including
  `network.topology.reconcile`.

## Acceptance boundary

Deployment confirms immutable image publication, Core/Web readiness, public
asset delivery, database health and runtime Helper capability. No authenticated
browser operation was run in this deployment check. No Provider Apply was run
against a production topology, and no host network interface or route was
changed. Router execution, OVS, management-path probes and broader L3
acceptance remain open P6 work.
