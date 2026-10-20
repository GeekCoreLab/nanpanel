# Service network architecture

NaNPanel composes several network layers and keeps their ownership separate:

| Scope | Component | Responsibility | Evidence |
| --- | --- | --- | --- |
| Cross-host / NAT | NetBird Provider over WireGuard and Relay | Peer discovery, overlay addresses, and direct-versus-relayed path observations | Agent/Provider observations plus path probes |
| Kubernetes workload east-west | Optional Istio service mesh | Kubernetes Service/EndpointSlice discovery, workload identity, mTLS, and L7 policy | Kubernetes API and Istio proxy status/configuration |
| Internet edge | Envoy or FRP Provider | Public TLS endpoint, host/path routing, and explicit access policy | Provider state plus external TLS and HTTP probes |
| Platform control plane | Java Core and Agent Gateway | Desired state, authorization, operation lifecycle, and observation storage | PostgreSQL, audit events, and reconciled observations |

Istio is an optional service-network adapter for Kubernetes workloads. It does
not make a non-Kubernetes host part of the cluster mesh, create NAT traversal,
or replace NetBird. A host-side workload may reach a Kubernetes Service through
an authorized overlay or an explicit Gateway route; that cross-boundary path is
separate from in-mesh mTLS.

The test environment uses Istio 1.31.1 with K3s 1.33.5 in the isolated
`nanpanel-mesh` namespace. `PeerAuthentication` is STRICT and an
`AuthorizationPolicy` permits one named service account to call a fixture
service. The production `nanpanel` namespace remains outside the mesh until the
Provider can reconcile injection, health probes, Core-to-Agent Gateway
communication, and rollback safely. See [the test deployment](../../deploy/istio/README.md)
and [ADR-013](../adr/ADR-013-istio-service-network.md).

## Resource and endpoint mapping

The platform Service is the stable logical identity. An Endpoint maps that
identity to one concrete address and port with a source and observation time.
For Kubernetes, Service and EndpointSlice records are inventory inputs; Istio
does not replace them with a second platform registry. For non-Kubernetes
services, an Agent, overlay Provider, or gateway Provider supplies the endpoint
observation. Probe results are observations with a timestamp and generation,
not a mutation of desired topology.

`panel-cluster` reads Services and EndpointSlices in one explicitly chosen
namespace and emits a bounded snapshot with service ports, endpoint addresses,
readiness conditions, Pod target identity, and observation time. Core exposes
this read-only snapshot at `GET /api/v1/kubernetes/service-inventory`; the feature
is disabled by default and the namespace and cluster identity come from server
configuration. The topology page shows the inventory in Live mode and labels
the in-memory sample in Mock mode. Core persists the latest bounded snapshot and
refreshes it every 60 seconds. An independent, disabled-by-default probe switch
can check at most 32 Ready EndpointSlice private IP/TCP ports per refresh; it
stores the latest result per service/endpoint/port and prunes records after seven
days. It skips FQDN, public, loopback, link-local, UDP, and non-Ready endpoints.
A live Core run on the test cluster persisted a successful probe matching
the current Service and EndpointSlice inventory. Authenticated API-level
acceptance still requires a valid administrator login; Istio policy
reconciliation remains pending.

Path display must be based on provider evidence. A NetBird management API peer
record can establish discovery and control-plane connectivity, but does not by
itself prove whether a specific data path is direct or relayed. That path type
must come from the peer's local connection status or an equivalent provider
observation, and a successful probe must confirm the requested service path.
The isolated Istio fixture now checks the Kubernetes-native mapping from its
Service port to a ready EndpointSlice Pod and performs allowed and denied HTTP
probes. Core separately persists the latest namespace-scoped Kubernetes
inventory and optional TCP probes; it does not yet persist Istio-specific proxy
or policy status.

The Agent can ingest optional NetBird loopback metrics as a timestamped host
summary (known, connected, P2P, and relayed peer counts). When the root Helper
finds a trusted NetBird CLI, it also reads `netbird status --json`, reduces the
result to peer FQDN, connection status, and P2P/Relay type, and reports a
timestamped per-peer snapshot through Core to the console. Addresses, keys, and
relay endpoints are discarded. This remains read-only Agent inventory.

Core also loads the first-party PF4J NetBird Provider when explicitly enabled.
It reconciles membership only for dedicated groups under a configured managed
prefix, persists operation plans and stages, audits changes, and checks live
fingerprints before apply, verification, and compensation. Core can restore the
provider's ephemeral operation context after restart only when live membership
and preserved group resources match the persisted plan. This Provider does not
manage NetBird policies, routes, or peer enrollment; its local HTTP-fixture
tests are supplemented by a live disposable-tenant run that reconciled one
connected peer and verified restart recovery and compensation. See the
[live acceptance record](../../tests/p2/runs/2026-10-20-netbird-provider-live.md)
and [Provider contract and configuration](../../plugins/provider-netbird/README.md).

A disposable cross-host lab verified P2P → Relay → P2P with an HTTP request at
each stage for one public-host Docker-to-K3s topology; see the
[acceptance record](../../tests/p2/runs/2026-10-20-netbird-successful-path-switch.md).
Keep Agent observations separate from NetBird Provider group state and from
application health checks.

## Reconciliation boundary

The NetBird Provider currently manages membership in dedicated groups. Future
Istio and Envoy/FRP Providers can map desired services, endpoints, routes, and
security policies to their external systems. They report `planned`, `applied`,
`verified`, `degraded`, or `unsupported` observations. NetBird owns overlay transport;
Istio owns in-mesh workload identity and traffic policy; Envoy/FRP owns public
ingress. The Core must reject conflicting ownership and preserve resources it
does not manage. A gateway switch is committed only after the new path passes
identity validation and an application-level probe; otherwise compensate to the
last verified route.

The NetBird managed-group Provider and Core operation lifecycle have local
HTTP-fixture coverage, and the Provider has passed live disposable-tenant
membership reconciliation. Authenticated Core inventory and probe API
responses have now passed against the target cluster using a scoped,
revocable read-only API key; the timestamp precision fix and live response
evidence are recorded in
`tests/p2/runs/2026-10-20-core-service-probe-live.md`. Production gateway
reconciliation and Istio policy reconciliation remain later Provider work;
the P2 public ingress and gateway recovery evidence is recorded in
`tests/p2/README.md`.
