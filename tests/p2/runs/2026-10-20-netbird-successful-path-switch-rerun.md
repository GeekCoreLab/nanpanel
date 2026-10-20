# NetBird P2P / Relay path-switch rerun — 2026-10-20

Run identifier: 20261020104603-306124 (UTC start time: 10:46:03).

Command: NANPANEL_P2_PATH_WAIT_SECONDS=90 bash tests/p2/netbird-path-switch.sh

Both peers reported P2P and the HTTP fixture responded. After rejecting UDP on
both peer containers, both reported Relay and the HTTP response remained
correct. Removing the UDP restrictions restored P2P, and restarting the
disposable control/relay endpoint restored both peers and the application
response.

Sanitized output:

    PASS: both peers reported P2P and the HTTP fixture responded before UDP restrictions.
    PASS: both peers switched to Relay and the HTTP fixture remained reachable with UDP rejected.
    PASS: removing UDP restrictions restored P2P and application HTTP traffic.
    PASS: both peers reconnected to management/signal after the isolated gateway restart and the HTTP fixture responded.
    exit=0

Cleanup was verified: the temporary peer and server containers and directories,
and the run-specific K3s namespace were absent. This repeats the same public-host
Docker-to-K3s topology; it is not evidence for a second independent NAT.
