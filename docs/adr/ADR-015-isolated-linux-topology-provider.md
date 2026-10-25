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
   Helper may create only bridge and veth resources inside that namespace; it
   must not move host interfaces or configure host routes.
3. Initially support only root-level `SWITCH` devices and L2/Ethernet wires.
   Reject mixed-provider wires and unsupported device kinds.
4. Derive namespace and interface names from validated topology, revision,
   generation, and stable IDs. Verify ownership markers and zero default routes
   before reporting success. Compensation removes only a namespace with the
   matching ownership marker.
5. Add Router, Firewall, Packet Tap, and OVS support only after each device has
   an explicit configuration model, typed Helper schema, and real kernel
   acceptance evidence.

## Consequences

- The first Provider can execute and verify bridge graphs without modifying
  host interfaces or routes.
- A saved graph remains desired state until an operator creates and advances a
  persisted Provider operation.
- Router, Firewall, Packet Tap, OVS, and management-path probes are not covered
  by this first implementation. RX/TX counters are passive kernel snapshots;
  this Provider does not generate test traffic or claim end-to-end connectivity.
- The network namespace registry under `/run/netns` is host-visible metadata;
  all data-plane devices and routes remain inside each topology namespace.

## Verification

The Rust Helper validates the typed request again at the privilege boundary.
Provider verification checks namespace ownership, bridge/veth device kinds,
port-to-bridge membership, and the absence of a default route. The isolated P6
Linux lab separately exercises real bridge, routed TCP, nftables, and `tc`
behavior while hashing the host default routes before and after. Core-to-Helper
Provider deployment still requires the P6 host acceptance run.
