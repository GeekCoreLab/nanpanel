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

## Release and acceptance boundary

The functional change is locally validated. Append the exact commit, successful
Actions run, immutable GHCR tags, and `k3s-1` rollout/readiness evidence here
after registry publication and deployment. Deployment acceptance verifies the
Provider capability and public application response only; it does not create a
production topology or apply Firewall rules there. Router execution, OVS,
management-path probes and broader L3 acceptance remain open P6 work.
