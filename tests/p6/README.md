# P6 isolated Linux network lab

`./tests/p6/linux-network-lab.sh` builds a temporary network namespace (and a
user namespace when run by a non-root user). The bridges stay in that isolated
namespace; client, router, server, and Packet Tap each get a separate network
namespace. The lab sends a real TCP request through the dual-homed IPv4 router,
confirms `tc mirred` copies packets to the tap, installs a bridge-family
nftables forward drop between the router and server bridges, confirms the TCP
flow is blocked and checks its rule counter. It then tests an independent
server-namespace input drop rule.

The bridge namespace has no default route, and all links exist only inside the
temporary namespaces. The outer script hashes the host's default routes before
and after the run. The namespaces and their interfaces disappear when the
process exits; a trap also removes the test links and nftables table on failure.

The lab proves Linux bridge/router/static-route/bridge-Firewall/Packet Tap
kernel behavior and management-route isolation. Provider unit/API checks cover
typed Router and Firewall plans plus Helper policy verifiers, but the lab does
not apply a NaNPanel Provider graph or reconcile its state.

`./tests/p6/multi-router-vrf-lab.sh` creates one isolated topology namespace
with two Linux VRF Routers and separate endpoint namespaces. It installs the
same destination prefix in both VRF tables with different next hops, checks
VRF-specific route lookup, and sends a real TCP request across both Routers.
The script hashes host default routes before and after. This validates Linux
VRF dataplane behavior and the namespace boundary, not the complete
Core-to-Agent-to-Helper Apply lifecycle. Router execution requires kernel VRF
support; IPv6, NAT, dynamic routing, and cross-Provider L3 remain outside this
P6 slice.
The Helper reports `network.topology.vrf` only when it can create a VRF in a
temporary named network namespace; Core requires that capability when a saved
plan includes Routers.

`./tests/p6/openvswitch-netdev-lab.sh` downloads a temporary Debian container,
installs OVS only inside that container, removes its default route, starts a
private OVSDB and `ovs-vswitchd`, and attaches two veth ports to a `netdev`
bridge. A raw Ethernet broadcast with a custom EtherType must arrive on the
other veth; the script also checks exact Bridge/Port ownership markers and
hashes host default routes before and after. The container is removed after the
lab, and no host OVS package or daemon is installed. This proves the OVS
userspace datapath behavior, not a Core-to-Helper Provider Apply. The target
`k3s-1` currently lacks the optional OVS tools and therefore does not advertise
`network.topology.ovs`; the deployed Provider rejects OVS plans there until an
operator separately provisions those host prerequisites.
