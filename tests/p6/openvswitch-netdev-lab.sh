#!/usr/bin/env bash
set -euo pipefail

script_path=$(realpath -- "${BASH_SOURCE[0]}")
if [[ ${1:-} != --inside ]]; then
    command -v podman >/dev/null
    command -v ip >/dev/null
    host_route_digest=$(ip -j route show default | sha256sum | awk '{print $1}')
    podman run --rm --http-proxy=false --device=/dev/net/tun \
        --cap-add=NET_ADMIN --cap-add=NET_RAW \
        --volume="$script_path:/tmp/openvswitch-netdev-lab.sh:ro" \
        docker.io/library/debian:12-slim bash /tmp/openvswitch-netdev-lab.sh --inside
    after_route_digest=$(ip -j route show default | sha256sum | awk '{print $1}')
    if [[ $host_route_digest != "$after_route_digest" ]]; then
        printf 'Host default routes changed during the isolated OVS lab.\n' >&2
        exit 1
    fi
    printf 'Host default-route digest unchanged: %s\n' "$after_route_digest"
    exit 0
fi

apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    openvswitch-switch iproute2 python3
ip route del default >/dev/null 2>&1 || true
if [[ $(ip -j route show default) != '[]' ]]; then
    printf 'OVS lab datapath namespace unexpectedly has a default route.\n' >&2
    exit 1
fi

mkdir -p /run/openvswitch
db=/run/openvswitch/nanpanel-test.db
db_socket=/run/openvswitch/nanpanel-test.db.sock
db_pid=/run/openvswitch/nanpanel-test-db.pid
db_ctl=/run/openvswitch/nanpanel-test-db.ctl
switch_pid=/run/openvswitch/nanpanel-test-switch.pid
switch_ctl=/run/openvswitch/nanpanel-test-switch.ctl
db_log=/run/openvswitch/nanpanel-test-db.log
switch_log=/run/openvswitch/nanpanel-test-switch.log

cleanup() {
    ovs-vsctl --db="unix:$db_socket" --if-exists del-br npbr0 >/dev/null 2>&1 || true
    ovs-appctl -t "$switch_ctl" exit >/dev/null 2>&1 || true
    ovs-appctl -t "$db_ctl" exit >/dev/null 2>&1 || true
    for pid_file in "$switch_pid" "$db_pid"; do
        pid=$(cat "$pid_file" 2>/dev/null || true)
        if [[ $pid =~ ^[0-9]+$ ]]; then
            for _ in {1..40}; do
                kill -0 "$pid" 2>/dev/null || break
                sleep 0.05
            done
        fi
    done
    ip link del npp1 >/dev/null 2>&1 || true
    ip link del npp2 >/dev/null 2>&1 || true
}
trap cleanup EXIT

ovsdb-tool create "$db" /usr/share/openvswitch/vswitch.ovsschema
ovsdb-server --remote="punix:$db_socket" --pidfile="$db_pid" --unixctl="$db_ctl" \
    --log-file="$db_log" --detach --no-chdir "$db"
for _ in {1..40}; do
    if ovs-vsctl --db="unix:$db_socket" --timeout=1 --no-wait init; then
        break
    fi
    sleep 0.1
done
ovs-vswitchd --pidfile="$switch_pid" --unixctl="$switch_ctl" \
    --log-file="$switch_log" --detach --no-chdir "unix:$db_socket"
for _ in {1..40}; do
    if ovs-appctl -t "$switch_ctl" version >/dev/null 2>&1; then
        break
    fi
    sleep 0.1
done

ovs-vsctl --db="unix:$db_socket" add-br npbr0 -- set Bridge npbr0 \
    datapath_type=netdev other_config:disable-in-band=true \
    external_ids:nanpanel-owner=nanpanel-p6-lab
ip link add npp1 type veth peer name nph1
ip link add npp2 type veth peer name nph2
ovs-vsctl --db="unix:$db_socket" add-port npbr0 npp1 -- set Port npp1 \
    external_ids:nanpanel-owner=nanpanel-p6-lab-link1
ovs-vsctl --db="unix:$db_socket" add-port npbr0 npp2 -- set Port npp2 \
    external_ids:nanpanel-owner=nanpanel-p6-lab-link2
ip link set npp1 up
ip link set npp2 up
ip link set nph1 up
ip link set nph2 up

python3 - <<'PY'
import socket
import struct

marker = b"nanpanel-ovs-netdev-lab"
frame = b"\xff\xff\xff\xff\xff\xff" + b"\x02\x00\x00\x00\x00\x01" + struct.pack("!H", 0x88B5) + marker
receiver = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0003))
receiver.bind(("nph2", 0))
receiver.settimeout(5)
sender = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0003))
sender.bind(("nph1", 0))
sender.send(frame)
while True:
    packet = receiver.recv(2048)
    if marker in packet:
        break
assert packet[12:14] == struct.pack("!H", 0x88B5)
print("Open vSwitch netdev bridge forwarded a real custom EtherType frame across isolated veth ports")
receiver.close()
sender.close()
PY

[[ $(ovs-vsctl --db="unix:$db_socket" get Bridge npbr0 datapath_type) == netdev ]]
[[ $(ovs-vsctl --db="unix:$db_socket" get Bridge npbr0 external_ids:nanpanel-owner | tr -d '"') == nanpanel-p6-lab ]]
[[ $(ovs-vsctl --db="unix:$db_socket" get Port npp1 external_ids:nanpanel-owner | tr -d '"') == nanpanel-p6-lab-link1 ]]
[[ $(ovs-vsctl --db="unix:$db_socket" get Port npp2 external_ids:nanpanel-owner | tr -d '"') == nanpanel-p6-lab-link2 ]]
[[ $(ovs-vsctl --db="unix:$db_socket" list-ports npbr0 | sort | tr '\n' ' ') == 'npp1 npp2 ' ]]
printf 'Verified isolated OVS netdev bridge, exact port ownership, and zero default routes.\n'
