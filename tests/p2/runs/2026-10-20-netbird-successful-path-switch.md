# NetBird P2P / Relay path switch — 2026-10-20

Run identifier: `20261020085438-192037` (UTC start time: 08:54:38).

Command:

```sh
NANPANEL_P2_PATH_WAIT_SECONDS=90 bash tests/p2/netbird-path-switch.sh
```

The lab used NetBird `0.75.1`, the public-host Docker bridge peer, the K3s peer,
the embedded STUN listener on UDP/3478, and the peer's fixed WireGuard port on
UDP/3479. The application HTTP fixture returned the expected run-specific body
at every verified path stage.

Sanitized command output:

```text
PASS: both peers reported P2P and the HTTP fixture responded before UDP restrictions.
PASS: both peers switched to Relay and the HTTP fixture remained reachable with UDP rejected.
PASS: removing UDP restrictions restored P2P and application HTTP traffic.
PASS: both peers reconnected to management/signal after the isolated gateway restart and the HTTP fixture responded.
PASS: NetBird 0.75.1 peers on the NAT Docker host and K3s reached the temporary public control/relay over TCP/443.
exit=0
```

The direct and relayed states were read from both peers' detailed NetBird status.
The UDP rejection rules were added only inside the two disposable peer
containers. The final restart tested reconnection after restarting the
disposable NetBird combined control/relay service; it is not a production
Envoy/FRP gateway cutover or backend change.

Cleanup was verified after exit: the temporary K3s namespace and all
run-specific containers were absent, UDP/3478 and UDP/3479 firewalld runtime
rules were closed, and no remote temporary run directory remained.

This is one successful NAT/Docker-host-to-K3s topology. A second independent
NAT condition, public ingress policy reconciliation, service discovery and
durable probes, and production gateway recovery remain open.
