# ADR-015: Isolated Linux topology Provider

- Status: Accepted; the isolated P6 implementation and lab acceptance are complete. Production-topology Apply remains a separate gate and was not run.
- Date: 2026-10-26

## Context

The topology editor stores desired graphs, while the existing `NetworkProvider`
contract already persists plans and requires validation, apply, verification,
and compensation. There was no built-in Provider that executed those plans.
Directly creating bridges, routes, firewall rules, or traffic-control rules in
the host network namespace could affect the Agent management path.

The `ROUTER.ipAddress` field remains an annotation used by read-only route
inventory. Executable Router interfaces and static routes are represented
separately by `routerConfiguration`, so observing a next hop does not configure
the virtual Router.

## Decision

1. Run the first executable Provider through the existing Core Provider
   lifecycle and the typed Agent-to-Helper operation path.
2. Give every applied topology revision its own Linux network namespace. The
   Helper may create bridge, veth, VRF, nftables, and traffic-control resources
   inside that namespace; it must not move host interfaces or configure host
   routes.
3. Support root-level `SWITCH`, `ROUTER`, `FIREWALL`, and `PACKET_TAP` devices
   with L2/Ethernet wires. Every executable Router gets a Linux VRF device and
   a distinct routing table inside the topology namespace. Router and table
   allocation follow sorted node IDs, so a saved Revision maps deterministically
   to the same table IDs. Every connected Router interface needs one usable
   IPv4 host CIDR; addresses must be unique and connected networks must not
   overlap within that Router. A topology may define at most 64 static routes
   across all Routers. Routes use an output port and an optional next hop on
   that interface's direct subnet; gateways cannot be the local, network, or
   broadcast address, and non-default routes cannot overlap connected networks.
   The Helper enables IPv4 forwarding and applies VRFs, addresses, and routes
   only inside the topology namespace. A Firewall requires connected input and output ports
   and compiles its typed default action and ordered TCP/UDP/SCTP port rules
   into a `bridge` family nftables forward chain. A Packet Tap requires one
   input (`in-1`), one business output (`out-1`), and one monitor output
   (`out-2`). Keep the monitor veth outside the bridge and use `tc mirred` on
   both business bridge ports. Verify installed state before reporting
   success. Reject mixed-provider wires and unsupported kinds.
4. Derive namespace, VRF, and interface names from validated topology, revision,
   generation, and stable IDs. Verify ownership markers, exact Router IPv4
   interface addresses and VRF membership, exact per-table static routes,
   forwarding and l3mdev policy-rule state, and absence of undeclared static or
   default routes before reporting success. Compensation removes only a
   namespace with the matching ownership marker.
5. Offer Open vSwitch as an explicit `SWITCH` backend of the built-in Provider.
   Start a private OVSDB and `ovs-vswitchd` for each topology revision; use the
   `netdev` userspace datapath inside that revision's network namespace. Keep
   OVS files, sockets, and ownership markers in a private `/run/nanpanel` path.
   Do not use a host OVS daemon or install OVS automatically. Require trusted
   OVS tools, schema, and `/dev/net/tun` before Apply. Reject Firewall and
   Packet Tap nodes in an OVS graph until their datapath behavior is verified.
6. Keep broader L3 behavior such as IPv6, NAT, dynamic routing, and cross-Provider
   routing out of scope until each capability has a typed contract and isolated
   forwarding acceptance evidence.

## Consequences

- The Provider can execute and verify bridge, multi-Router IPv4, transparent
  Firewall, and Packet Tap graphs without modifying host interfaces or routes.
  The Firewall applies its rules to bridge-forwarded frames; the Packet Tap
  captures both directions on the two business wires and sends copies over its
  dedicated monitor wire.
- A saved graph remains desired state until an operator creates and advances a
  persisted Provider operation.
- Router execution uses Linux VRF support and remains IPv4-only. IPv6, NAT,
  dynamic routing, and cross-Provider L3 behavior remain open. OVS switch
  execution uses an isolated userspace datapath; the current OVS slice rejects
  Firewall and Packet Tap devices.
  The Helper reports `network.topology.vrf` only after creating a VRF in a
  temporary named network namespace; Core requires it for Router plans.
  RX/TX counters are passive kernel snapshots; the Provider does not generate
  test traffic or claim end-to-end connectivity.
- The network namespace registry under `/run/netns` is host-visible metadata;
  all data-plane devices and routes remain inside each topology namespace.

## Verification

The Rust Helper validates the typed request again at the privilege boundary.
Provider verification checks namespace ownership, bridge/veth device kinds,
port-to-bridge membership, exact Router VRF/table/rule ownership, interface
master membership, addresses, per-table routes, and forwarding state,
exact Firewall chain policy and rules, Packet Tap `mirred` egress filters, and
absence of undeclared default routes. For OVS, it also checks private daemon
control sockets, `netdev` bridge type, exact bridge/port ownership, and bridge
membership. The isolated P6 Linux and OVS labs exercise real bridge-family
Firewall drops, routed TCP, a static IPv4 route, host nftables, `tc` behavior,
and raw Ethernet forwarding through OVS while hashing host default routes
before and after. Multi-Router tests additionally verify independent VRF route
selection for the same prefix. Core, Agent, and Helper reject invalid Router
address and route shapes before execution. Together with the per-topology
namespace boundary, these checks show that Provider resources and failures
cannot change the host management routes; no separate host-path probe is part
of this selected execution boundary. The Provider is deployed to `k3s-1` and
its runtime Helper capabilities are verified read-only. Production-topology
Apply remains a separate acceptance gate.
