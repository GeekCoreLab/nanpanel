# NetBird path switching from the private-interface host

- Time: 2026-10-20 11:20 UTC
- Run: `20261020112047-335539`
- Command: `NANPANEL_P2_PEER_SSH_ALIAS=rwt-dev NANPANEL_P2_PATH_WAIT_SECONDS=90 bash tests/p2/netbird-path-switch.sh`
- Peer preflight: `rwt-dev` has a private default gateway, root Docker, `/dev/net/tun`, active firewalld, and outbound TCP/443 access.
- Fixture: temporary NetBird control/relay endpoint, a Docker peer on `rwt-dev`, and a K3s HTTP workload.

## Result

The peer on `rwt-dev` and the K3s peer both reported P2P and returned the
fixture HTTP body. After UDP ingress and egress were rejected inside both peer
containers, both reported Relay and HTTP still succeeded. Removing those
temporary rules restored P2P and HTTP. Restarting the disposable NetBird
control/relay container caused both peers to reconnect to management/signal;
HTTP recovered afterward. The script exited 0.

Sanitized output:

```text
PASS: both peers reported P2P and the HTTP fixture responded before UDP restrictions.
PASS: both peers switched to Relay and the HTTP fixture remained reachable with UDP rejected.
PASS: removing UDP restrictions restored P2P and application HTTP traffic.
PASS: both peers reconnected to management/signal after the isolated gateway restart and the HTTP fixture responded.
PASS: NetBird 0.75.1 peers on rwt-dev and K3s reached the temporary control/relay over TCP/443.
Overlay HTTP fixture: run-specific body; overlay addresses omitted.
```

A follow-up read-only query to the same public IP reflection service returned
different egress addresses from `rwt-dev` and the public test host. The raw
addresses were not retained.

## Cleanup

Confirmed after exit: the K3s namespace was absent; the `rwt-dev` peer
container, peer state directory, and temporary UDP firewall rule were absent;
the public control container, Nginx vhost, server state directory, and STUN
firewall rule were absent; and the public Nginx configuration test passed.

This establishes P2P/Relay/P2P behavior on the second tested network condition.
It does not claim carrier-grade or symmetric NAT behavior.
