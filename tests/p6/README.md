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
not apply a NaNPanel Provider graph or reconcile its state. The first Router
Provider supports IPv4 and one routing table per topology; multiple independent
Routers and broader L3 behavior remain separate P6 acceptance items.

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
