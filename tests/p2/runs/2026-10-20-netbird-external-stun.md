# Public-host peer with external STUN — 2026-10-20

Run identifier: `20261020084818-186349`.

The pinned NetBird `0.75.1` peers both connected to the disposable management,
signal, and relay services. The public-host Docker peer and K3s peer each
reported the other as `Relayed`, and the fixture's HTTP response succeeded
through the overlay.

The selected external STUN service was `Unavailable` at both peers. ICE
repeatedly entered `Checking` and then `Failed`. Only host candidates were
reported; no server-reflexive candidate or selected candidate pair was
observed. The test stopped at the initial P2P gate. It did not install UDP
reject rules or claim a P2P → Relay → P2P transition.

After exit, cleanup was verified: the temporary K3s namespace was absent, the
public test host had no run containers or UDP/3478 firewalld rule, and the lab
Docker host had no run containers. No temporary run directory remained on the
remote test hosts.

This run does not establish the direct-path behavior of the fixed-port public
peer because the configured external STUN service was unavailable. The next
attempt will use the NetBird server's embedded STUN listener on a separate UDP
port from the public peer's fixed WireGuard port.
