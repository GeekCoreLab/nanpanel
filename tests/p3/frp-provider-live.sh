#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
frpc_image=${NANPANEL_P3_FRPC_IMAGE:-ghcr.io/fatedier/frpc:v0.71.0}
frps_image=${NANPANEL_P3_FRPS_IMAGE:-ghcr.io/fatedier/frps:v0.71.0}
http_image=${NANPANEL_P3_HTTP_IMAGE:-docker.io/library/busybox:1.37.0}
run_id=$$
network="nanpanel-p3-frp-provider-$run_id"
frps="nanpanel-p3-frps-$run_id"
frpc="nanpanel-p3-frpc-$run_id"
http_service="nanpanel-p3-http-$run_id"
work=$(mktemp -d /tmp/nanpanel-p3-frp-provider.XXXXXX)
expected_body=nanpanel-p3-provider-http-service
host_ports=$(python3 -c 'import socket; sockets=[]
for _ in range(2):
    sock=socket.socket(); sock.bind(("127.0.0.1", 0)); sockets.append(sock)
print(" ".join(str(sock.getsockname()[1]) for sock in sockets))
for sock in sockets: sock.close()')
set -- $host_ports
old_host_port=$1
new_host_port=$2

cleanup() {
  if [ "${KEEP_NANPANEL_P3_ARTIFACTS:-0}" = 1 ]; then
    echo "Keeping isolated FRP Provider lab artifacts: work=$work network=$network frps=$frps frpc=$frpc service=$http_service"
  else
    podman rm -f "$frpc" "$frps" "$http_service" >/dev/null 2>&1 || true
    podman network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
  fi
}
trap cleanup EXIT INT TERM

mkdir -p "$work/www"
printf '%s' "$expected_body" > "$work/www/index.html"
cat > "$work/frps.toml" <<'EOF'
bindPort = 7000
auth.method = "token"
auth.token = "nanpanel-p3-frp-fixture-token"
EOF
cat > "$work/frpc.toml" <<'EOF'
serverAddr = "frps"
serverPort = 7000
auth.method = "token"
auth.token = "nanpanel-p3-frp-fixture-token"
webServer.addr = "127.0.0.1"
webServer.port = 7400

[[proxies]]
name = "nanpanel-managed-live"
type = "tcp"
localIP = "http-service"
localPort = 8080
remotePort = 18081
EOF
cat > "$work/frpc-wrapper" <<'EOF'
#!/bin/sh
set -eu
if [ "$#" -ne 3 ] || [ "$2" != "-c" ]; then
  exit 2
fi
case "$3" in
  "$NANPANEL_P3_CONFIG_DIR"/*.toml) config_name=${3##*/} ;;
  *) exit 2 ;;
esac
case "$1" in
  verify|reload|status) ;;
  *) exit 2 ;;
esac
exec podman exec "$NANPANEL_P3_FRPC_CONTAINER" frpc "$1" -c "/p3/$config_name"
EOF
chmod 700 "$work/frpc-wrapper"

podman network create "$network" >/dev/null
podman run -d --http-proxy=false --name "$frps" --network "$network" --network-alias frps \
  -p "127.0.0.1:$old_host_port:18081" -p "127.0.0.1:$new_host_port:18082" \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$work/frps.toml:/p3/frps.toml:ro" --entrypoint frps "$frps_image" -c /p3/frps.toml >/dev/null
podman run -d --http-proxy=false --name "$http_service" --network "$network" --network-alias http-service \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$work/www:/www:ro" --entrypoint httpd "$http_image" -f -p 8080 -h /www >/dev/null
podman run -d --http-proxy=false --name "$frpc" --network "$network" \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$work:/p3:rw" --entrypoint frpc "$frpc_image" -c /p3/frpc.toml >/dev/null

probe() {
  port=$1
  attempt=0
  while [ "$attempt" -lt 30 ]; do
    if response=$(curl --noproxy '*' -fsS --max-time 2 "http://127.0.0.1:$port/" 2>/dev/null) \
        && [ "$response" = "$expected_body" ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 0.5
  done
  echo "FAIL: HTTP route on host port $port did not return '$expected_body'"
  podman logs "$frps" 2>&1 || true
  podman logs "$frpc" 2>&1 || true
  return 1
}

assert_absent() {
  port=$1
  if curl --noproxy '*' -fsS --max-time 2 "http://127.0.0.1:$port/" >/dev/null 2>&1; then
    echo "FAIL: unexpected HTTP route remains active on host port $port"
    return 1
  fi
}

probe "$old_host_port"
echo 'PASS: isolated real FRPC exposes the original managed route'
NANPANEL_FRP_LIVE_TEST=1 \
NANPANEL_FRPC_CONFIG="$work/frpc.toml" \
NANPANEL_FRPC_BINARY="$work/frpc-wrapper" \
NANPANEL_FRPC_RELOAD=true \
NANPANEL_FRP_PROBE_URL="http://127.0.0.1:$new_host_port/" \
NANPANEL_FRP_PROBE_BODY="$expected_body" \
NANPANEL_P3_FRPC_CONTAINER="$frpc" \
NANPANEL_P3_CONFIG_DIR="$work" \
  "$repo_root/mvnw" -pl plugins/provider-frp -am \
    -Dtest=FrpRouteProviderLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
probe "$old_host_port"
assert_absent "$new_host_port"
echo 'PASS: the PF4J FRP Provider changed the live route, verified its HTTP response, and compensated to the original route'
