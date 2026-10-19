#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
frpc_image=${NANPANEL_P0_FRPC_IMAGE:-ghcr.io/fatedier/frpc:v0.71.0}
frps_image=${NANPANEL_P0_FRPS_IMAGE:-ghcr.io/fatedier/frps:v0.71.0}
http_image=${NANPANEL_P0_HTTP_IMAGE:-docker.io/library/busybox:1.37.0}
run_id=$$
network="nanpanel-p0-frp-$run_id"
frps="nanpanel-p0-frps-$run_id"
frpc="nanpanel-p0-frpc-$run_id"
http_service="nanpanel-p0-http-$run_id"
work=$(mktemp -d /tmp/nanpanel-p0-frp.XXXXXX)
state="$work/state"

cleanup() {
  if [ "${KEEP_NANPANEL_P0_ARTIFACTS:-0}" = 1 ]; then
    echo "Keeping isolated FRP lab artifacts: work=$work network=$network frps=$frps frpc=$frpc service=$http_service"
  else
    podman rm -f "$frpc" "$frps" "$http_service" >/dev/null 2>&1 || true
    podman network rm "$network" >/dev/null 2>&1 || true
    rm -rf "$work"
  fi
}
trap cleanup EXIT INT TERM

podman network create "$network" >/dev/null
mkdir -p "$work/www"
printf 'nanpanel-p0-http-service\n' > "$work/www/index.html"
cat > "$work/frps.toml" <<'EOF'
bindPort = 7000

[auth]
method = "token"
token = "nanpanel-p0-fixture-token"
EOF

python3 "$repo_root/tests/p0/frp-transaction.py" import \
  --source "$repo_root/tests/p0/fixtures/frpc-original.toml" --state-dir "$state"
podman run -d --http-proxy=false --name "$frps" --network "$network" --network-alias frps \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$work/frps.toml:/p0/frps.toml:ro" --entrypoint frps "$frps_image" -c /p0/frps.toml >/dev/null
podman run -d --http-proxy=false --name "$http_service" --network "$network" --network-alias http-service \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$work/www:/www:ro" --entrypoint httpd "$http_image" -f -p 8080 -h /www >/dev/null
podman run -d --http-proxy=false --name "$frpc" --network "$network" \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
  -e http_proxy= -e https_proxy= -e all_proxy= \
  -v "$state:/p0:rw" --entrypoint frpc "$frpc_image" -c /p0/active.toml >/dev/null

probe() {
  port=$1
  expected=$2
  attempt=0
  while [ "$attempt" -lt 20 ]; do
    response=$(podman run --rm --http-proxy=false --network "$network" \
      -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
      -e http_proxy= -e https_proxy= -e all_proxy= \
      --entrypoint wget "$http_image" -qO- "http://frps:$port/" 2>/dev/null || true)
    if [ "$response" = "$expected" ]; then return 0; fi
    attempt=$((attempt + 1))
    sleep 0.5
  done
  echo "FAIL: HTTP route on port $port did not return '$expected' (last response='$response')"
  podman logs "$frps" 2>&1 || true
  podman logs "$frpc" 2>&1 || true
  return 1
}

assert_absent() {
  port=$1
  if podman run --rm --http-proxy=false --network "$network" \
      -e HTTP_PROXY= -e HTTPS_PROXY= -e ALL_PROXY= \
      -e http_proxy= -e https_proxy= -e all_proxy= \
      --entrypoint wget "$http_image" -T 2 -qO- "http://frps:$port/" >/dev/null 2>&1; then
    echo "FAIL: unexpected HTTP route remains active on port $port"
    return 1
  fi
}

probe 18080 nanpanel-p0-http-service
echo 'PASS: imported FRP configuration exposes the original HTTP service route'
python3 "$repo_root/tests/p0/frp-transaction.py" stage --state-dir "$state" --proxy p0-http --remote-port 18081
python3 "$repo_root/tests/p0/frp-transaction.py" diff --state-dir "$state"
python3 "$repo_root/tests/p0/frp-transaction.py" verify --state-dir "$state" --file candidate.toml --container "$frpc"
python3 "$repo_root/tests/p0/frp-transaction.py" apply --state-dir "$state" --container "$frpc"
podman restart "$frpc" >/dev/null
probe 18081 nanpanel-p0-http-service
assert_absent 18080
echo 'PASS: applied FRP change moved the HTTP route from port 18080 to 18081'
python3 "$repo_root/tests/p0/frp-transaction.py" compensate --state-dir "$state" --container "$frpc"
podman restart "$frpc" >/dev/null
probe 18080 nanpanel-p0-http-service
assert_absent 18081
python3 "$repo_root/tests/p0/frp-transaction.py" verify --state-dir "$state" --file active.toml --container "$frpc"
echo 'PASS: compensation restored and re-verified the imported FRP route'
