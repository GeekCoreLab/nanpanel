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

- Commit: `e456caa3adc1d9c21ad0f977b51ffa7d2572ac07`
  (`Add isolated IPv4 Router provider`).
- [Actions run 40225415592](https://github.com/GeekCoreLab/nanpanel/actions/runs/40225415592):
  `validate` and `publish` both succeeded.
- The immutable Core and Web tags
  `ghcr.io/geekcorelab/nanpanel-core:sha-e456caa3adc1d9c21ad0f977b51ffa7d2572ac07`
  and
  `ghcr.io/geekcorelab/nanpanel-web:sha-e456caa3adc1d9c21ad0f977b51ffa7d2572ac07`
  were inspected from GHCR by `deploy/k3s/deploy.sh` and deployed to `k3s-1`.
  No local image was uploaded.
- After rollout, the Core pod was `2/2` Ready, the Web pod was `1/1` Ready,
  and both Deployments reported `1/1` available on the new SHA tags.
  PostgreSQL remained `1/1` Running with no new restart; the existing 5 GiB
  PVC remained Bound to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- `https://10.100.0.1:30443/` and its JavaScript bundle returned HTTP 200. The
  deployed bundle contains `routerConfiguration` and `IPv4`. The proxied
  `/q/health/ready` endpoint returned `UP`, including database connections.
- `nanpanel-helper.service` and `nanpanel-agent.service` are active. The
  deployed Helper SHA-256 is
  `e8446101270c7174bc4e725468435089a0bf779eb9691c2b03a813b1d9a20436`; Agent
  SHA-256 is
  `ca9151e0cdc617344821bd8634e29534e4b1eabfcb9079ec4fb913081de6a65c`.
  The deployment script retained the previous binaries under
  `/var/lib/nanpanel-helper` and `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over
  `/run/nanpanel/helper.sock`, sent as `nanpanel-agent`, returned 14
  capabilities including `network.topology.reconcile=true`.

No authenticated browser operation or Provider Apply was performed against a
production topology during deployment verification.
