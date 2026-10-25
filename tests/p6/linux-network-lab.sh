#!/usr/bin/env bash
set -Eeuo pipefail

script_path=$(realpath -- "${BASH_SOURCE[0]}")
if [[ ${1:-} != --inside || -z ${2:-} ]]; then
    for binary in unshare ip nft tc nsenter python3; do
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
        printf 'Host default routes changed during the isolated lab.\n' >&2
        exit 1
    fi
    printf 'Host default-route digest unchanged: %s\n' "$after_default_routes"
    exit 0
fi

if [[ $(readlink /proc/self/ns/net) == "$2" ]]; then
    printf 'Refusing to run without a separate network namespace.\n' >&2
    exit 1
fi

for binary in ip nft tc nsenter python3 rg; do
    command -v "$binary" >/dev/null
done

outer_netns=$(readlink /proc/self/ns/net)
namespace_pids=()
server_pid=

cleanup() {
    nft delete table bridge nanpanel_p6 2>/dev/null || true
    if [[ -n $server_pid ]]; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
    if [[ -n ${server_ns_pid:-} ]] && kill -0 "$server_ns_pid" 2>/dev/null; then
        nsenter -t "$server_ns_pid" -n nft delete table inet nanpanel_p6 2>/dev/null || true
    fi
    for namespace_pid in "${namespace_pids[@]}"; do
        kill "$namespace_pid" 2>/dev/null || true
        wait "$namespace_pid" 2>/dev/null || true
    done
    ip link del p6br1 2>/dev/null || true
    ip link del p6br2 2>/dev/null || true
}
trap cleanup EXIT

launch_namespace() {
    unshare --net sleep infinity &
    namespace_pid=$!
    namespace_pids+=("$namespace_pid")
    for _ in {1..50}; do
        if [[ -e /proc/$namespace_pid/ns/net ]] && [[ $(readlink "/proc/$namespace_pid/ns/net") != "$outer_netns" ]]; then
            return 0
        fi
        sleep 0.02
    done
    printf 'Could not start an isolated endpoint network namespace.\n' >&2
    return 1
}

launch_namespace
client_ns_pid=$namespace_pid
launch_namespace
router_ns_pid=$namespace_pid
launch_namespace
server_ns_pid=$namespace_pid
launch_namespace
tap_ns_pid=$namespace_pid

if [[ $(ip -j route show default) != '[]' ]]; then
    printf 'Isolated lab namespace unexpectedly has a default route.\n' >&2
    exit 1
fi

# The Linux bridges stay in this isolated namespace. Hosts, router, and tap
# each use their own network namespace, so each kernel has an independent FIB.
ip link add p6br1 type bridge
ip link add p6br2 type bridge
ip link add p6ca type veth peer name p6sw1c
ip link add p6r1a type veth peer name p6sw1r
ip link add p6r1b type veth peer name p6sw2r
ip link add p6sb type veth peer name p6sw2s
ip link add p6tap type veth peer name p6sw2t

ip link set p6ca netns "$client_ns_pid"
ip link set p6r1a netns "$router_ns_pid"
ip link set p6r1b netns "$router_ns_pid"
ip link set p6sb netns "$server_ns_pid"
ip link set p6tap netns "$tap_ns_pid"

for link in p6sw1c p6sw1r; do ip link set "$link" master p6br1; done
for link in p6sw2r p6sw2s; do ip link set "$link" master p6br2; done
ip link set p6br1 up
ip link set p6br2 up
for link in p6sw1c p6sw1r p6sw2r p6sw2s p6sw2t; do ip link set "$link" up; done

nsenter -t "$client_ns_pid" -n ip address add 198.18.1.2/24 dev p6ca
nsenter -t "$router_ns_pid" -n ip address add 198.18.1.1/24 dev p6r1a
nsenter -t "$router_ns_pid" -n ip address add 198.18.2.1/24 dev p6r1b
nsenter -t "$server_ns_pid" -n ip address add 198.18.2.2/24 dev p6sb
for pair in "$client_ns_pid:p6ca" "$router_ns_pid:p6r1a" "$router_ns_pid:p6r1b" "$server_ns_pid:p6sb" "$tap_ns_pid:p6tap"; do
    namespace_pid=${pair%%:*}
    link=${pair#*:}
    nsenter -t "$namespace_pid" -n ip link set "$link" up
done
nsenter -t "$router_ns_pid" -n ip link set lo up
nsenter -t "$router_ns_pid" -n sysctl -q -w net.ipv4.ip_forward=1
nsenter -t "$client_ns_pid" -n ip route add 198.18.2.0/24 via 198.18.1.1 dev p6ca
nsenter -t "$server_ns_pid" -n ip route add 198.18.1.0/24 via 198.18.2.1 dev p6sb

nsenter -t "$client_ns_pid" -n ip route get 198.18.2.2 | rg -q 'via 198\.18\.1\.1'
nsenter -t "$server_ns_pid" -n ip route get 198.18.1.2 | rg -q 'via 198\.18\.2\.1'

# A typed Router route is installed only in the isolated router namespace.
nsenter -t "$router_ns_pid" -n ip route replace 198.18.3.0/24 via 198.18.2.2 dev p6r1b proto static
nsenter -t "$router_ns_pid" -n ip route get 198.18.3.9 | rg -q 'via 198\.18\.2\.2 dev p6r1b'
nsenter -t "$router_ns_pid" -n ip -j route show 198.18.3.0/24 | python3 -c 'import json,sys; routes=json.load(sys.stdin); assert len(routes)==1 and routes[0].get("dst")=="198.18.3.0/24" and routes[0].get("gateway")=="198.18.2.2" and routes[0].get("dev")=="p6r1b" and routes[0].get("protocol")=="static"'

# Mirror router-to-switch frames to a dedicated observer veth with tc mirred.
tc qdisc add dev p6sw1r clsact
tc filter add dev p6sw1r egress matchall action mirred egress mirror dev p6sw2t

nsenter -t "$server_ns_pid" -n python3 - <<'PY' &
import socket
import time

listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
listener.bind(("198.18.2.2", 18080))
listener.listen(4)
listener.settimeout(10)
try:
    while True:
        client, _ = listener.accept()
        with client:
            client.sendall(b"p6-lab-ok")
except TimeoutError:
    time.sleep(0)
finally:
    listener.close()
PY
server_pid=$!
sleep 0.2

nsenter -t "$client_ns_pid" -n python3 - <<'PY'
import socket

with socket.create_connection(("198.18.2.2", 18080), timeout=2) as connection:
    assert connection.recv(32) == b"p6-lab-ok"
print("Baseline routed TCP request: passed")
PY

tap_rx=$(nsenter -t "$tap_ns_pid" -n ip -s link show dev p6tap | awk '/^[[:space:]]*RX:/{getline; print $2}')
if [[ ! $tap_rx =~ ^[0-9]+$ ]] || (( tap_rx < 1 )); then
    printf 'Packet Tap did not receive mirrored packets (rx=%s).\n' "$tap_rx" >&2
    exit 1
fi

# Exercise the bridge-family nftables forward hook used by the Provider's
# transparent Firewall. Keep this table in the isolated lab namespace.
nft -f - <<'NFT'
add table bridge nanpanel_p6
add chain bridge nanpanel_p6 forward { type filter hook forward priority 0; policy accept; }
add rule bridge nanpanel_p6 forward iifname "p6sw2r" oifname "p6sw2s" tcp dport 18080 counter drop comment "nanpanel-p6-bridge-firewall-test"
NFT

nsenter -t "$client_ns_pid" -n python3 - <<'PY'
import socket

try:
    socket.create_connection(("198.18.2.2", 18080), timeout=1)
except (TimeoutError, OSError):
    pass
else:
    raise SystemExit("Bridge-family Firewall rule did not block the routed TCP flow")
PY

bridge_firewall_packets=$(nft -a list chain bridge nanpanel_p6 forward | awk '/nanpanel-p6-bridge-firewall-test/{for (i=1;i<=NF;i++) if ($i=="packets") {print $(i+1); exit}}')
if [[ ! $bridge_firewall_packets =~ ^[0-9]+$ ]] || (( bridge_firewall_packets < 1 )); then
    printf 'Bridge Firewall rule counter did not increase (packets=%s).\n' "$bridge_firewall_packets" >&2
    exit 1
fi
nft delete table bridge nanpanel_p6

nsenter -t "$server_ns_pid" -n nft -f - <<'NFT'
add table inet nanpanel_p6
add chain inet nanpanel_p6 input { type filter hook input priority 0; policy accept; }
add rule inet nanpanel_p6 input iifname "p6sb" tcp dport 18080 counter drop comment "nanpanel-p6-firewall-test"
NFT

nsenter -t "$client_ns_pid" -n python3 - <<'PY'
import socket

try:
    socket.create_connection(("198.18.2.2", 18080), timeout=1)
except (TimeoutError, OSError):
    pass
else:
    raise SystemExit("Firewall rule did not block the routed TCP flow")
PY

firewall_packets=$(nsenter -t "$server_ns_pid" -n nft -a list chain inet nanpanel_p6 input | awk '/nanpanel-p6-firewall-test/{for (i=1;i<=NF;i++) if ($i=="packets") {print $(i+1); exit}}')
if [[ ! $firewall_packets =~ ^[0-9]+$ ]] || (( firewall_packets < 1 )); then
    printf 'Firewall rule counter did not increase (packets=%s).\n' "$firewall_packets" >&2
    exit 1
fi

printf 'Verified bridges=2, router=2 subnets plus static route 198.18.3.0/24 via 198.18.2.2, bridge Firewall dropped packets=%s, host Firewall dropped packets=%s, Packet Tap rx packets=%s, host default routes unchanged\n' \
    "$bridge_firewall_packets" "$firewall_packets" "$tap_rx"
