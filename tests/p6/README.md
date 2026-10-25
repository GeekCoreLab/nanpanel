# P6 isolated Linux network lab

`./tests/p6/linux-network-lab.sh` builds a temporary network namespace (and a
user namespace when run by a non-root user). The bridges stay in that isolated
namespace; client, router, server, and Packet Tap each get a separate network
namespace. The lab sends a real TCP request through the dual-homed IPv4 router,
confirms `tc mirred` copies packets to the tap, installs an nftables drop rule
on the server, confirms the TCP flow is blocked, and checks the rule counter.

The bridge namespace has no default route, and all links exist only inside the
temporary namespaces. The outer script hashes the host's default routes before
and after the run. The namespaces and their interfaces disappear when the
process exits; a trap also removes the test links and nftables table on failure.

The lab proves Linux bridge/router/firewall/Packet Tap kernel behavior and
management-route isolation. It does not prove that a NaNPanel Provider can
deploy the graph or reconcile its state. OVS-backed execution and Core-to-Agent
Provider dispatch still require separate P6 acceptance.
