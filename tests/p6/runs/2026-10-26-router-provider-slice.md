# Router Provider slice and deployment

## Implemented scope

The `linux-netns` Provider now reads typed `routerConfiguration` from a saved
topology revision. Each Router has an IPv4 CIDR for every connected interface
and up to 64 static routes bound to configured output ports. The Core API,
Agent daemon, and Root Helper validate interface ownership, ETHERNET links,
CIDR syntax, unique routes, connected-network conflicts, and on-link next hops.
One Router is supported per topology because a topology network namespace has
one Linux routing table.

The Helper enables IPv4 forwarding, configures interface addresses, and installs
static routes only inside the revision's isolated network namespace. Verify
checks exact addresses, forwarding state, static routes, and the absence of
undeclared default routes. No host interface or host route enters the operation
payload. A production topology Apply was not performed.

## Local verification

- `./mvnw -pl backend/panel-core -am test` — passed (77 Core tests; all
  dependency modules succeeded).
- `cargo fmt --all -- --check` — passed.
- `cargo check --all-targets --all-features` — passed.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `cargo test --all-features` — passed (62 tests across Agent, Relay, Helper,
  and runtime-driver crates).
- `npm test` — passed (18 tests).
- `npm run build` — passed; Vite reports the existing large JavaScript chunk
  advisory.
- `npm run test:topology` and `npm run test:browser` — passed against Mock mode.
- `./tests/p6/linux-network-lab.sh` — passed routed TCP over a configured
  static next hop (`198.18.3.0/24 via 198.18.2.2`), bridge Firewall and Packet
  Tap checks; host default-route digest remained
  `0ef98fc565c2ead8fcd958b1592e256030a823bf2623c9ad404432c2cdc06a32`.

## CI and target deployment

Pending commit, GitHub Actions validation, immutable GHCR image publication,
and deployment to `k3s-1`.
