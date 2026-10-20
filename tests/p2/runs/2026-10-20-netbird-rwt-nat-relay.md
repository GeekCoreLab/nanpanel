# NetBird Relay from the private-interface host

- Time: 2026-10-20 11:03 UTC
- Run: `20261020110301-319901`
- Command: `NANPANEL_P0_DOCKER_SSH_ALIAS=rwt-dev bash tests/p0/netbird-cross-host-udp-blocked.sh`
- Peer host preflight: private interface present, `/dev/net/tun` usable, root Docker available, and TCP/443 to the public test endpoint reachable.
- Fixture: temporary NetBird control/relay endpoint on the public test host, a Docker peer on `rwt-dev`, and a disposable K3s HTTP workload.
- Follow-up at 2026-10-20 11:07 UTC: read-only requests to the same public IP reflection service from `rwt-dev` and the public test host returned different addresses. The raw addresses and temporary comparison fingerprints are not included in this report.

## Result

Both peer containers rejected UDP ingress and egress on `eth0`. NetBird's
detailed status reported `Relay`, and the overlay HTTP request from the peer
on `rwt-dev` returned the run-specific K3s fixture body. The script exited 0.

Sanitized output:

```text
PASS: both peer eth0 paths reject UDP; detailed status reports Relay and the HTTP fixture responds.
PASS: NetBird 0.75.1 peers on the selected NAT container host and K3s reached the temporary public control/relay over TCP/443.
Test endpoint: temporary HTTPS endpoint via --resolve; all temporary resources are removed on exit.
```

## Cleanup

Confirmed after exit: the K3s namespace was absent; the peer container and
`/tmp` state directory were absent on `rwt-dev`; the public control container,
Nginx vhost, and its `/tmp` state directory were absent; and `nginx -t` passed.

## Acceptance boundary

This adds a UDP-restricted Relay path from a separate private-interface host
with a distinct public egress fingerprint observed in a follow-up check. It
complements the public-host P2P → Relay → P2P run. P2P establishment from
`rwt-dev` remains untested; this does not claim behavior for carrier-grade or
symmetric NAT.
