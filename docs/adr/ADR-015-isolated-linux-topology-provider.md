# ADR-015: Isolated Linux topology Provider

- Status: Accepted for the first executable Provider; broader P6 devices remain open.
- Date: 2026-10-26

## Context

The topology editor stores desired graphs, while the existing `NetworkProvider`
contract already persists plans and requires validation, apply, verification,
and compensation. There was no built-in Provider that executed those plans.
Directly creating bridges, routes, firewall rules, or traffic-control rules in
the host network namespace could affect the Agent management path.

The current graph schema does not define per-interface addresses or routes for
a virtual Router. Its `Router.ipAddress` field is an annotation used by
read-only route inventory; it is not enough to configure a real router.

## Decision

1. Run the first executable Provider through the existing Core Provider
   lifecycle and the typed Agent-to-Helper operation path.
2. Give every applied topology revision its own Linux network namespace. The
   Helper may create only bridge, veth, and traffic-control resources inside
   that namespace; it must not move host interfaces or configure host routes.
3. Support root-level `SWITCH`, `FIREWALL`, and `PACKET_TAP` devices with
   L2/Ethernet wires. A Firewall requires at least one connected input and
   output and compiles its typed default action and ordered TCP/UDP/SCTP port
   rules into a `bridge` family nftables forward chain. A Packet Tap requires
   one input (`in-1`), one business output (`out-1`), and one monitor output
   (`out-2`). Keep the monitor veth outside the bridge and use `tc mirred` on
   both business bridge ports. Verify the installed filters before reporting
   success. Reject mixed-provider wires and unsupported kinds.
4. Derive namespace and interface names from validated topology, revision,
   generation, and stable IDs. Verify ownership markers and zero default routes
   before reporting success. Compensation removes only a namespace with the
   matching ownership marker.
5. Add Router and OVS support only after each device has an explicit
   configuration model, typed Helper schema, and real kernel acceptance
   evidence.

## Consequences

- The Provider can execute and verify bridge, transparent Firewall, and Packet
  Tap graphs without modifying host interfaces or routes. The Firewall applies
  its rules to bridge-forwarded frames; the Packet Tap captures both directions
  on the two business wires and sends copies over its dedicated monitor wire.
- A saved graph remains desired state until an operator creates and advances a
  persisted Provider operation.
- Router, OVS, and management-path probes are not covered by this
  implementation. RX/TX counters are passive kernel snapshots; this Provider
  does not generate test traffic or claim end-to-end connectivity.
- The network namespace registry under `/run/netns` is host-visible metadata;
  all data-plane devices and routes remain inside each topology namespace.

## Verification

The Rust Helper validates the typed request again at the privilege boundary.
Provider verification checks namespace ownership, bridge/veth device kinds,
port-to-bridge membership, exact Firewall chain policy and rules, Packet Tap
`mirred` egress filters, and the absence of a default route. The isolated P6
Linux lab separately exercises real bridge-family Firewall drops, routed TCP,
host nftables, and `tc` behavior while hashing host default routes before and
after. The Provider is deployed to `k3s-1` and its runtime Helper capability is
verified read-only; production-topology Apply remains a separate acceptance
gate.
