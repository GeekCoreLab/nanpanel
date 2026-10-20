# Shared-egress NetBird path diagnostic — 2026-10-20

Run identifier: `20261020080442-152566`.

The disposable peer on the public Docker host and the peer beside the K3s
fixture both observed the same external egress address. Their host candidates
used different mapped UDP ports, while the server-reflexive candidates also
reflected the shared address. ICE entered `Checking` and then `Failed`; no
candidate pair was selected and the peers remained on the Relay path.

The experiment stopped at the initial P2P gate. It did not install UDP reject
rules or claim a P2P → Relay → P2P result. The application HTTP request through
the available Relay path succeeded. Candidate address values are omitted.

Cleanup was checked after the run: the temporary K3s namespace was absent, the
public host had no remaining test containers, no temporary UDP/3478 firewalld
rule remained, and no test run directory remained on either host.

This single shared-egress topology does not establish direct-path behavior
under two independent NATs. The next run fixes the public-host peer's WireGuard
UDP port and uses the external NetBird STUN service to check whether a stable,
publicly reachable mapping enables direct connectivity.
