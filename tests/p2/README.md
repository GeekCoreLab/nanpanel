# P2 network and ingress acceptance

P2 is an end-to-end stage. Unit tests, mocked provider plans, or an Istio-only
test mesh do not satisfy it. Preserve time-stamped command output and sanitized
peer status for every run; never record setup keys, PATs, private keys, or
Cloudflare credentials.

## Automated cross-host path-switch lab

Run:

```sh
bash tests/p2/netbird-path-switch.sh
```

The test reuses the isolated cross-host NetBird fixture. It creates a temporary
control/relay endpoint and a Docker bridge peer on the selected SSH host, with
its WireGuard UDP port mapped to that host, plus a peer beside a K3s HTTP
Service. `NANPANEL_P2_PEER_SSH_ALIAS` selects the direct-path peer host in P2
mode; it defaults to the public host and can select a private-interface NAT
host for a second topology.
The test peer remains in its own network namespace and receives no host network
or host route/firewall access. The temporary control plane uses NetBird's
embedded STUN listener on a UDP port separate from the public peer's fixed
WireGuard UDP port. The script opens only those firewalld UDP runtime rules,
for at most 15 minutes when needed, and removes only rules it added. P2 peer
logs start at debug level so a failed ICE attempt can distinguish missing
candidates from failed connectivity checks. It requires both detailed peer
statuses to report P2P while UDP is available, then inserts UDP reject rules
inside the two temporary peer containers and requires Relay plus a successful
application HTTP request. It removes only those temporary rules, checks
recovery to P2P and the same HTTP body, restarts the temporary control/relay
container, and checks both peers reconnect and the HTTP request recovers. The
public endpoint uses a temporary hostname and --resolve; DNS records are not
changed. The fixture auto-selects Docker or Podman on the peer host. It removes
its K3s namespace, containers, temporary reverse-proxy vhost, and state.

`NANPANEL_P2_WIREGUARD_PORT` selects the selected peer host's fixed WireGuard
UDP port (default `3479`); `NANPANEL_P2_EMBEDDED_STUN_PORT` selects
the temporary control plane's embedded STUN UDP port (default `3478`). The
ports must differ. `NANPANEL_P2_STUN_PORT` remains a legacy alias for the peer
WireGuard port. The temporary control host must accept the embedded STUN UDP
port; the script opens only run-scoped firewalld rules on the relevant hosts.
For a peer behind NAT, upstream port forwarding is not assumed; ICE must
establish a usable direct path through that NAT for the P2P gate to pass.
`NANPANEL_P2_PATH_WAIT_SECONDS` sets the path wait from 30 to 300 seconds
(default `120`).

A path-switch run exercises direct transport and UDP-blocked Relay fallback on
the public-host Docker peer/K3s path. A second full P2P → Relay → P2P run from
the private-interface `rwt-dev` host passed with HTTP at every state and
reconnection after restarting the disposable control/relay endpoint. A
follow-up lookup to the same public IP reflection service confirmed distinct
egress addresses for `rwt-dev` and the public test host; raw addresses are not
retained. These runs establish two tested network conditions but do not claim
carrier-grade or symmetric NAT behavior. Neither gateway restart test validates
production Envoy/FRP Provider reconciliation or a production Gateway cutover.
`tests/p0/frp-route-transaction.sh` separately verifies a controlled FRP route
change and compensation in its isolated lab.

The live NetBird Provider check can be included in a full path-switch run with
`NANPANEL_P2_NETBIRD_PROVIDER_LIVE=1`. To stop after the live Provider check
instead of running the path transitions, also set
`NANPANEL_P2_NETBIRD_PROVIDER_ONLY=1`. The fixture connects both peers first,
then uses one real peer ID to exercise observe, plan, validate, apply, restart
recovery, verify, and compensation against the disposable tenant. The
[11:39 UTC Provider run](runs/2026-10-20-netbird-provider-live.md) passed.

### Latest run: 2026-10-20

The first run lacked STUN publication and was not a valid P2P attempt. A second
run published embedded STUN and observed it as Available from both peers, but
both peers shared the same external NAT address and their initial path remained
Relayed with no selected ICE candidates. UDP reject rules were not installed.
The test exited at the P2P gate, and manual checks confirmed that its namespace,
containers, state, temporary vhost, and UDP firewall rule were removed. A third
run reproduced the candidate collision and ICE failure. Its sanitized report
records host and server reflexive candidates without address values. A fourth
run moved the fixed-port Docker peer onto the public host but selected an
external STUN service that was unavailable to both peers; ICE exposed only host
candidates and failed before the P2P gate. That run also stopped before UDP
reject rules. A fifth run used embedded STUN on a separate port and passed
P2P → Relay → P2P with a successful HTTP request at each state. It also
verified peer reconnection and HTTP recovery after restarting the disposable
NetBird control/relay service. See the [successful path-switch record](runs/2026-10-20-netbird-successful-path-switch.md)
and [10:46 UTC rerun](runs/2026-10-20-netbird-successful-path-switch-rerun.md).
The earlier runs are the [first run](runs/2026-10-20-netbird-path-switch.md),
the [STUN-enabled run](runs/2026-10-20-netbird-stun-relay.md), the
[shared-egress diagnostic](runs/2026-10-20-netbird-shared-egress.md), and the
[external-STUN run](runs/2026-10-20-netbird-external-stun.md); their individual
outcomes are not acceptance evidence for the latest successful run. A separate
attempt from the hk-81x NAT candidate could not reach the temporary public
control endpoint and is documented as a failed diagnostic. The 11:03 UTC
[rwt-dev Relay run](runs/2026-10-20-netbird-rwt-nat-relay.md) first proved
UDP-restricted Relay and HTTP from the private-interface host. The 11:20 UTC
[rwt-dev path-switch run](runs/2026-10-20-netbird-rwt-path-switch.md) then
passed P2P → Relay → P2P, HTTP at every state, and reconnection after a
temporary control/relay restart. A same-service follow-up confirmed its public
egress differs from the public test host.

## Public FRP ingress and backend recovery

Run:

    ./tests/p2/frp-public-ingress.sh

The test creates two isolated K3s HTTP backends, starts a temporary FRPC client
against the existing public FRPS, and installs a run-specific Nginx TLS vhost
with Basic Auth. Curl validates the certificate chain using the system trust
store while --resolve avoids DNS changes. It checks HTTP 401 without
credentials, authorized access to backend A, an Nginx backend switch to B, and
compensation back to A. It verifies the active config hash at each stage and
removes only its own vhost, password file, FRPC container, remote files, and
K3s namespace.

The [10:59 UTC run](runs/2026-10-20-frp-public-ingress.md) passed all stages and
cleanup. This validates the public FRP/Nginx path and gateway compensation in
the test environment; Core-managed production gateway reconciliation remains
open.

## Required stage evidence

| Acceptance | Evidence to retain | Current state |
| --- | --- | --- |
| Overlay peer/path discovery | Per-peer records, host source identity, observed time, and direct/relay state | Agent → Core persistence → `/network-inventory` → console is implemented; the PF4J managed-group Provider has local HTTP-fixture coverage, durable Core operation stages, and live disposable-tenant membership reconciliation |
| Public TLS ingress and access policy | Public certificate chain, unauthorized/authorized HTTP results, active gateway config, and recovery after changing gateway/backend | Passed on the test public Nginx/FRPS path: certificate validation, 401/authorized access, backend A→B→A, and active config hashes; Core-managed production reconciliation remains open |
| Service and Endpoint mapping | Discovered Service/Endpoint source, host or workload identity, ports, and freshness | Istio fixture verifies `mesh-http` Service TCP/80 against a ready EndpointSlice Pod on TCP/8080; Core persists the latest bounded, namespace-scoped Service/EndpointSlice snapshot and shows it in the topology page; historical retention remains open |
| Service connectivity probes | Probe origin, target endpoint, protocol result, probe time, and source inventory observation time | Core implements an opt-in TCP probe for at most 32 Ready private IP EndpointSlice ports per refresh and stores latest results for seven days; live Core persisted a matching `SUCCEEDED` result for the non-injected fixture, while authenticated inventory/probe API acceptance remains open |
| At least two NAT conditions | Two independent NAT topologies, including restricted UDP, with captured path status | Accepted across distinct public egress identities: public-host and private-interface `rwt-dev` both passed P2P/Relay/P2P, HTTP at every state, and control/relay restart recovery |
| Direct/Relay switching | P2P → Relay → P2P, with a successful application-level request at every stage | Passed on both the public-host and private-interface `rwt-dev` Docker-to-K3s topologies |
| Gateway change recovery | Apply a gateway/backend change, prove the new endpoint, then prove recovery/compensation | Public FRP/Nginx test changed backend A→B and compensated B→A with TLS and access policy enforced; Core-managed production cutover remains open |
| Kubernetes workload mesh preparation | Isolated injection, strict mTLS, policy allow/deny, and production namespace boundary | Verified by `deploy/istio/verify-test-mesh.sh`; production Istio Provider remains open |

## Core service inventory and probe wiring

After the Core image with Kubernetes discovery is running and the probe switch
is enabled, create a `kubernetes:inventory:read` API key from the live console's
“账号与会话” page. Configure `NANPANEL_P2_CORE_URL` and
`NANPANEL_P2_API_KEY` in the ignored local `.env`, then run:

```sh
test_core_ca="$(mktemp)"
chmod 600 "$test_core_ca"
trap 'rm -f -- "$test_core_ca"' EXIT
ssh k3s-1 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel get secret nanpanel-web-tls -o jsonpath="{.data.tls\\.crt}"' \
  | base64 --decode > "$test_core_ca"
export CURL_CA_BUNDLE="$test_core_ca"
set -a
source .env
set +a
./tests/p2/core-service-probe-live.sh
```

The script reads the server-pinned inventory and persisted probe snapshot over
HTTPS using the limited Bearer key, and requires a `SUCCEEDED` TCP result
matching the Ready non-injected `mesh-probe-target` EndpointSlice and inventory
observation time. The key file and endpoint addresses stay in a mode-600
temporary directory and are removed on exit; output includes the target Pod
and timestamps but omits its IP. The script also accepts
`NANPANEL_P2_ADMIN_PASSWORD` for cookie-session compatibility. The read-only
key needs no refresh permission because Core refreshes inventory in the
background.

The K3s manifests define a dedicated `nanpanel-core` ServiceAccount and pin the
discovery target to `k3s-1` / `nanpanel-mesh`, with discovery and probes disabled
by default. The live test cluster now has that ServiceAccount and the
`deploy/istio/core-inventory-rbac.yaml` Role/Binding, which grants only Service
and EndpointSlice list access in the fixture namespace. Its live authorization
results are `yes`, `yes`, `no`, `no` for Service list, EndpointSlice list, Pod
list, and Secret get. See the [live RBAC and mesh record](runs/2026-10-20-istio-live-inventory-rbac.md).
The current test Core runs `sha-8aa531ef17edf58d0836625d04597ccee46908ba` under
the dedicated ServiceAccount with discovery and probes enabled; the Web
console runs the matching immutable image. Core has
persisted a `SUCCEEDED` TCP/8080 probe matching the Ready fixture endpoint and
its inventory observation time. The authenticated API run is still pending an
API key created by a current administrator session; see the [Core probe run record](runs/2026-10-20-core-service-probe-live.md).
The fixture's
`mesh-probe-target` workload opts out of sidecar injection and provides a Ready
private TCP/8080 EndpointSlice for Core's raw TCP probe. The Core namespace remains
outside the mesh so existing health, gateway, and certificate paths are not
changed by injection.

The Role/Binding has already been applied to the test cluster; repeat the four
`kubectl auth can-i` checks in `deploy/istio/README.md` before a live run. The
current deployment and persisted `SUCCEEDED` result are evidence of Core-to-
endpoint connectivity, but final acceptance also requires the authenticated
inventory and probe API responses with timestamps. Create a
`kubernetes:inventory:read` key in the live console's account page and store it
as `NANPANEL_P2_API_KEY` in ignored `.env`; do not send it in chat. Retain a
mesh HTTP allow/deny result separately. Do not use the strict-mTLS injected
fixture as the raw-TCP success target while Core is outside the mesh.

P2 remains incomplete until authenticated Core inventory and probe API
acceptance has current evidence. The Core-side discovery, TCP connection, and
database persistence have passed on the live test cluster; API acceptance is
pending a scoped API key created by a current administrator session. The live NetBird Provider run
validates a disposable tenant and does not exercise the Core operation API.
The public gateway test proves the live test route but does not provide
Core-managed production reconciliation.
