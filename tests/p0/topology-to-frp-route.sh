#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
frpc_image=${NANPANEL_P0_FRPC_IMAGE:-ghcr.io/fatedier/frpc:v0.71.0}
frps_image=${NANPANEL_P0_FRPS_IMAGE:-ghcr.io/fatedier/frps:v0.71.0}
http_image=${NANPANEL_P0_HTTP_IMAGE:-docker.io/library/busybox:1.37.0}
run_id=$$
network="nanpanel-p0-topology-$run_id"
frps="nanpanel-p0-topology-frps-$run_id"
frpc="nanpanel-p0-topology-frpc-$run_id"
http_service="nanpanel-p0-topology-http-$run_id"
work=$(mktemp -d /tmp/nanpanel-p0-topology.XXXXXX)
state="$work/state"
api_log="$work/api.log"
vite_log="$work/vite.log"
api_pid=''
vite_pid=''

cleanup() {
  [ -z "$vite_pid" ] || kill "$vite_pid" >/dev/null 2>&1 || true
  [ -z "$api_pid" ] || kill "$api_pid" >/dev/null 2>&1 || true
  [ -z "$vite_pid" ] || wait "$vite_pid" >/dev/null 2>&1 || true
  [ -z "$api_pid" ] || wait "$api_pid" >/dev/null 2>&1 || true
  if [ "${KEEP_NANPANEL_P0_ARTIFACTS:-0}" = 1 ]; then
    echo "Keeping isolated topology lab artifacts: work=$work network=$network frps=$frps frpc=$frpc service=$http_service"
    [ ! -f "$api_log" ] || cat "$api_log"
    [ ! -f "$vite_log" ] || tail -50 "$vite_log"
  else
    podman rm -f -t 1 "$frpc" "$frps" "$http_service" >/dev/null 2>&1 || true
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
  --source "$repo_root/tests/p0/fixtures/frpc-empty.toml" --state-dir "$state"

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
podman exec "$frpc" frpc verify -c /p0/active.toml

NANPANEL_P0_API_PORT=4180 \
NANPANEL_P0_FRP_NETWORK="$network" \
NANPANEL_P0_FRPC_CONTAINER="$frpc" \
NANPANEL_P0_FRP_STATE="$state" \
node "$repo_root/tests/p0/topology-lab-api.mjs" > "$api_log" 2>&1 &
api_pid=$!
VITE_API_MODE=live VITE_P0_LAB=true \
node "$repo_root/web/node_modules/vite/bin/vite.js" "$repo_root/web" \
  --config "$repo_root/web/vite.p0.config.ts" > "$vite_log" 2>&1 &
vite_pid=$!

ready=0
attempt=0
while [ "$attempt" -lt 60 ]; do
  if curl --noproxy '*' -fsS http://127.0.0.1:4180/api/v1/topology/draft >/dev/null 2>&1 \
      && curl --noproxy '*' -fsS http://127.0.0.1:4181/p0-topology.html >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$api_pid" 2>/dev/null || ! kill -0 "$vite_pid" 2>/dev/null; then break; fi
  attempt=$((attempt + 1))
  sleep 0.5
done
if [ "$ready" != 1 ]; then
  echo 'FAIL: P0 topology API or Vite page did not start'
  cat "$api_log" "$vite_log" 2>/dev/null || true
  exit 1
fi

NANPANEL_P0_TOPOLOGY_URL=http://127.0.0.1:4181/p0-topology.html \
  node "$repo_root/web/scripts/p0-topology-route.mjs"
podman exec "$frpc" frpc verify -c /p0/active.toml
echo 'PASS: React Flow topology update deployed and verified one real HTTP route through FRP'
