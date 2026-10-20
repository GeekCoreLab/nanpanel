# HK NAT candidate reachability attempt — 2026-10-20

Run identifier: 20261020104204-303030 (UTC start time: 10:42:04).

Command: NANPANEL_P0_DOCKER_SSH_ALIAS=hk-81x bash tests/p0/netbird-cross-host-udp-blocked.sh

The selected host had a private interface, a usable TUN device, and Podman.
The test detected Podman, pulled the pinned peer image, started the temporary
public control/relay endpoint, and created the disposable K3s workload. The
peer could not establish its management connection over TCP/443:

    failed creating connection to Management Service: context deadline exceeded
    NetBird peer on the selected container host did not connect to management and signal.
    exit=1

The sanitized peer log showed a TCP connection timeout to the temporary public
endpoint. The test stopped before path and HTTP assertions, so this run does not
count as NAT or Relay acceptance. It establishes that this host cannot reach
the selected test endpoint over the required control path in its current
network configuration.

Cleanup was verified: the temporary peer container and directory, public
control container and directory, and run-specific K3s namespace were absent.
