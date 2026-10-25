#!/usr/bin/env bash
set -Eeuo pipefail

script_path=$(realpath -- "${BASH_SOURCE[0]}")
if [[ ${1:-} != --inside || -z ${2:-} ]]; then
    for binary in unshare ip nsenter python3 sysctl sha256sum awk; do
        command -v "$binary" >/dev/null
    done
    host_netns=$(readlink /proc/self/ns/net)
    host_default_routes=$(ip -j route show default | sha256sum | awk '{print $1}')
    if [[ $(id -u) -eq 0 ]]; then
        unshare --net -- "$script_path" --inside "$host_netns"
    else
        unshare --user --map-root-user --net -- "$script_path" --inside "$host_netns"
    fi
    after_default_routes=$(ip -j route show default | sha256sum | awk '{print $1}')
    if [[ $host_default_routes != "$after_default_routes" ]]; then
        printf 'Host default routes changed during the isolated VRF lab.\n' >&2
        exit 1
    fi
    printf 'Host default-route digest unchanged: %s\n' "$after_default_routes"
    exit 0
fi

if [[ $(readlink /proc/self/ns/net) == "$2" ]]; then
    printf 'Refusing to run without a separate network namespace.\n' >&2
    exit 1
fi

for binary in ip nsenter python3 sysctl; do
    command -v "$binary" >/dev/null
done

outer_netns=$(readlink /proc/self/ns/net)
namespace_pids=()
server_pid=

cleanup() {
    if [[ -n $server_pid ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    for namespace_pid in "${namespace_pids[@]}"; do
        if kill -0 "$namespace_pid" 2>/dev/null; then
            kill "$namespace_pid" 2>/dev/null || true
            wait "$namespace_pid" 2>/dev/null || true
        fi
    done
}
trap cleanup EXIT

launch_namespace() {
    unshare --net sleep infinity &
    namespace_pid=$!
    namespace_pids+=("$namespace_pid")
    for _ in {1..50}; do
        if [[ -e /proc/$namespace_pid/ns/net ]] \
            && [[ $(readlink "/proc/$namespace_pid/ns/net") != "$outer_netns" ]]; then
            return 0
        fi
        sleep 0.02
    done
    printf 'Could not start an isolated endpoint namespace.\n' >&2
    return 1
}

launch_namespace
topology_ns_pid=$namespace_pid
launch_namespace
client_ns_pid=$namespace_pid
launch_namespace
server_ns_pid=$namespace_pid

nsenter -t "$topology_ns_pid" -n ip link add br1 type bridge
nsenter -t "$topology_ns_pid" -n ip link add br2 type bridge
nsenter -t "$topology_ns_pid" -n ip link add br3 type bridge
for bridge in br1 br2 br3; do
    nsenter -t "$topology_ns_pid" -n ip link set "$bridge" up
done

create_bridge_port() {
    bridge=$1
    switch_if=$2
    peer_if=$3
    move_to=${4:-}
    nsenter -t "$topology_ns_pid" -n ip link add "$switch_if" type veth peer name "$peer_if"
    if [[ -n $move_to ]]; then
        nsenter -t "$topology_ns_pid" -n ip link set "$peer_if" netns "$move_to"
    fi
    nsenter -t "$topology_ns_pid" -n ip link set "$switch_if" master "$bridge"
    nsenter -t "$topology_ns_pid" -n ip link set "$switch_if" up
}

create_bridge_port br1 p6sw1c p6client "$client_ns_pid"
create_bridge_port br1 p6sw1r1 p6r1a
create_bridge_port br2 p6sw2r1 p6r1b
create_bridge_port br2 p6sw2r2 p6r2a
create_bridge_port br3 p6sw3r2 p6r2b
create_bridge_port br3 p6sw3s p6server "$server_ns_pid"

nsenter -t "$topology_ns_pid" -n ip link set lo up
nsenter -t "$topology_ns_pid" -n ip link add npr00 type vrf table 10000
nsenter -t "$topology_ns_pid" -n ip link set dev npr00 alias nanpanel:vrf-lab:router-1:10000:vrf
nsenter -t "$topology_ns_pid" -n ip link set npr00 up
nsenter -t "$topology_ns_pid" -n ip link add npr01 type vrf table 10001
nsenter -t "$topology_ns_pid" -n ip link set dev npr01 alias nanpanel:vrf-lab:router-2:10001:vrf
nsenter -t "$topology_ns_pid" -n ip link set npr01 up
for interface in p6r1a p6r1b; do
    nsenter -t "$topology_ns_pid" -n ip link set dev "$interface" master npr00
    nsenter -t "$topology_ns_pid" -n ip link set "$interface" up
done
for interface in p6r2a p6r2b; do
    nsenter -t "$topology_ns_pid" -n ip link set dev "$interface" master npr01
    nsenter -t "$topology_ns_pid" -n ip link set "$interface" up
done

nsenter -t "$topology_ns_pid" -n ip address add 198.18.1.1/24 dev p6r1a
nsenter -t "$topology_ns_pid" -n ip address add 198.18.2.1/24 dev p6r1b
nsenter -t "$topology_ns_pid" -n ip address add 198.18.2.2/24 dev p6r2a
nsenter -t "$topology_ns_pid" -n ip address add 198.18.3.1/24 dev p6r2b
nsenter -t "$topology_ns_pid" -n sysctl -q -w net.ipv4.ip_forward=1
nsenter -t "$topology_ns_pid" -n ip route replace table 10000 198.18.3.0/24 via 198.18.2.2 dev p6r1b proto static
nsenter -t "$topology_ns_pid" -n ip route replace table 10001 198.18.1.0/24 via 198.18.2.1 dev p6r2a proto static

nsenter -t "$client_ns_pid" -n ip address add 198.18.1.2/24 dev p6client
nsenter -t "$client_ns_pid" -n ip link set p6client up
nsenter -t "$client_ns_pid" -n ip route add 198.18.3.0/24 via 198.18.1.1 dev p6client
nsenter -t "$server_ns_pid" -n ip address add 198.18.3.2/24 dev p6server
nsenter -t "$server_ns_pid" -n ip link set p6server up
nsenter -t "$server_ns_pid" -n ip route add 198.18.1.0/24 via 198.18.3.1 dev p6server

nsenter -t "$topology_ns_pid" -n ip route replace table 10000 198.18.99.0/24 via 198.18.2.2 dev p6r1b proto static
nsenter -t "$topology_ns_pid" -n ip route replace table 10001 198.18.99.0/24 via 198.18.3.2 dev p6r2b proto static
nsenter -t "$topology_ns_pid" -n ip route get vrf npr00 198.18.99.9 | rg -q 'via 198\.18\.2\.2 dev p6r1b'
nsenter -t "$topology_ns_pid" -n ip route get vrf npr01 198.18.99.9 | rg -q 'via 198\.18\.3\.2 dev p6r2b'
nsenter -t "$topology_ns_pid" -n ip route get vrf npr00 198.18.3.9 | rg -q 'via 198\.18\.2\.2 dev p6r1b'
nsenter -t "$topology_ns_pid" -n ip route get vrf npr01 198.18.1.9 | rg -q 'via 198\.18\.2\.1 dev p6r2a'
nsenter -t "$topology_ns_pid" -n ip -j -d link show dev npr00 \
    | python3 -c 'import json,sys; link=json.load(sys.stdin)[0]; assert link["linkinfo"]["info_data"]["table"] == 10000 and link["ifalias"].endswith(":vrf")'
nsenter -t "$topology_ns_pid" -n ip -j rule show \
    | python3 -c 'import json,sys; rules=[r for r in json.load(sys.stdin) if "l3mdev" in r]; assert len(rules)==1 and rules[0].get("priority")==1000'
nsenter -t "$topology_ns_pid" -n ip -j route show table all \
    | python3 -c 'import json,sys; routes=json.load(sys.stdin); got={(r.get("table"),r.get("dst"),r.get("gateway"),r.get("dev")) for r in routes if r.get("protocol")=="static"}; assert ("10000","198.18.99.0/24","198.18.2.2","p6r1b") in got and ("10001","198.18.99.0/24","198.18.3.2","p6r2b") in got'

nsenter -t "$server_ns_pid" -n python3 - <<'PY' &
import socket

listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("198.18.3.2", 18081))
listener.listen(2)
listener.settimeout(10)
try:
    connection, _ = listener.accept()
    with connection:
        connection.sendall(b"multi-vrf-ok")
finally:
    listener.close()
PY
server_pid=$!
sleep 0.2
nsenter -t "$client_ns_pid" -n python3 - <<'PY'
import socket

with socket.create_connection(("198.18.3.2", 18081), timeout=2) as connection:
    assert connection.recv(32) == b"multi-vrf-ok"
print("TCP crossed two independent Router VRFs: passed")
PY

printf 'Verified two VRFs with independent table IDs 10000/10001, same-prefix routes selected different gateways, routed TCP passed, and no host route was touched.\n'
