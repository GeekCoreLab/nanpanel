# Isolated Open vSwitch Provider slice and deployment

- Date: 2026-10-26.
- Commit: `42fae125d6ef12b26ea0becbd07a42f35512142a` (`Add isolated Open vSwitch topology backend`).
- GitHub Actions: [Publish NaNPanel containers, run 40228869901](https://github.com/GeekCoreLab/nanpanel/actions/runs/40228869901); `validate` and `publish` both succeeded.
- Images: `ghcr.io/geekcorelab/nanpanel-core:sha-42fae125d6ef12b26ea0becbd07a42f35512142a` and `ghcr.io/geekcorelab/nanpanel-web:sha-42fae125d6ef12b26ea0becbd07a42f35512142a`. The deploy script inspected the remote GHCR manifests and the cluster pulled these immutable tags; no local image upload was used.

## Implemented scope

- OpenAPI, Core, Agent daemon, and the Web topology editor carry an optional
  `switchBackend` value (`BRIDGE` or `OPENVSWITCH`). Existing switches default
  to Linux Bridge. Core still requires the selected execution Helper to report
  `network.topology.ovs` before validating an OVS plan.
- The Root Helper uses a private OVSDB database, socket, control sockets, pid
  files, and logs under `/run/nanpanel/topology-ovs/<namespace>`. Both
  `ovsdb-server` and `ovs-vswitchd` start with `ip netns exec` in the
  topology Revision's dedicated network namespace. Bridges use the OVS
  userspace `netdev` datapath; Bridge and Port rows carry topology ownership
  markers checked by Verify. Compensate stops only the marked private daemons
  before removing the owned namespace.
- OVS capability is advertised only when trusted OVS executables and schema
  are present and `/dev/net/tun` is an openable character device. OVS plans
  reject Firewall and Packet Tap nodes for the same Provider execution. The
  UI exercises the backend selector and rejects that incompatible combination
  before saving the draft.

## Local verification

- `cargo fmt --all -- --check` — passed.
- `cargo check --all-targets --all-features` — passed.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `cargo test --all-features` — passed (64 tests across Agent, Control Relay,
  Helper, and helper binary targets).
- `./mvnw -pl backend/panel-core -am test` — passed (78 tests; no failures).
- `npm test` — passed (18 tests).
- `npm run build` — passed; Vite emitted its existing large JavaScript chunk
  advisory.
- `npm run test:topology` — passed against Mock mode, including Provider
  selection, OVS/Firewall conflict rejection, and switching back to Bridge.
- `./tests/p6/openvswitch-netdev-lab.sh` — passed with Debian Open vSwitch 3.1.
  A real custom EtherType Ethernet frame crossed an OVS `netdev` bridge between
  veth ports; exact Bridge/Port ownership values were checked. The host default
  route digest remained
  `0ef98fc565c2ead8fcd958b1592e256030a823bf2623c9ad404432c2cdc06a32`, and no
  host OVS package or daemon was installed.
- An attempt to extend this disposable-container lab with a nested named
  network namespace was refused by Podman when mounting `/sys` (`Operation not
  permitted`); the extension was reverted. Therefore the successful lab proves
  the userspace OVS datapath inside the disposable container boundary, not the
  Helper's complete Core-to-OVS Apply path.

## `k3s-1` deployment evidence

- `./deploy/k3s/deploy.sh` completed with the full SHA image tag after the
  successful Actions publish. Core and Web each reported a successful rollout
  and are `1/1` Ready on the new SHA tag.
- PostgreSQL is `1/1` Ready. The existing `data-nanpanel-postgres-0` claim
  remains `Bound`, 5 GiB, on volume
  `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- `https://10.100.0.1:30443/` and the deployed JavaScript bundle returned HTTP
  200; the bundle contains `OPENVSWITCH`, `linux.ovs`, and
  `network.topology.ovs`. `/q/health/ready` returned `UP`, including the
  database connection check.
- `nanpanel-helper.service` and `nanpanel-agent.service` are active. Host
  binaries match the binaries in the deployed Core image: Helper SHA-256
  `89ece880e161c5b457eef25781d7f14f91cc67e99ea4c1b0ed5179710390a083`, Agent
  SHA-256 `4cf789e530ecb58169b488f6c310f9e4f3f5a802ea331f64d2471cddc17fce59`.
  The previous binaries remain backed up under `/var/lib/nanpanel-helper` and
  `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over `/run/nanpanel/helper.sock`,
  sent as `nanpanel-agent`, returned 14 capabilities including
  `network.topology.reconcile`; `network.topology.ovs` was absent. A read-only
  check found all five OVS executables absent on `k3s-1`, so no OVS plan can be
  applied there until its operator separately provisions the host prerequisites.

## Acceptance boundary

No topology draft, Provider operation, or production network configuration was
changed on `k3s-1`. The code and Web selector are deployed, but the target does
not have OVS prerequisites and no production Provider Apply was run. The
disposable lab validates packet forwarding in the OVS userspace datapath, not
the complete Core-to-Agent-to-Helper Provider lifecycle. Multiple Routers with
independent routing tables and broader L3 address/route validation remain open
P6 work.
