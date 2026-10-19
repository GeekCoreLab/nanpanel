#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
image=localhost/nanpanel-p0-agent:local
run_id=$$
gateway="nanpanel-p0-gateway-$run_id"
agent="nanpanel-p0-agent-$run_id"
cert_dir=$(mktemp -d /tmp/nanpanel-p0-certs.XXXXXX)
gateway_log=$(mktemp /tmp/nanpanel-p0-gateway.XXXXXX.log)
agent_log=$(mktemp /tmp/nanpanel-p0-agent.XXXXXX.log)
listen_addr=${NANPANEL_P0_BIND_IP:-172.16.87.1}
port=55443

cleanup() {
  if [ "${KEEP_NANPANEL_P0_ARTIFACTS:-0}" = 1 ]; then
    echo "Keeping P0 Agent artifacts: certs=$cert_dir gateway_log=$gateway_log agent_log=$agent_log gateway=$gateway agent=$agent"
  else
    podman rm -f "$agent" "$gateway" >/dev/null 2>&1 || true
    rm -rf "$cert_dir"
    rm -f "$gateway_log" "$agent_log"
  fi
}
trap cleanup EXIT INT TERM

if ! ip -o -4 addr show | awk '{split($4, a, "/"); print a[1]}' | rg -Fxq "$listen_addr"; then
  echo "Set NANPANEL_P0_BIND_IP to a private IPv4 address assigned to this host; refusing wildcard or unbound listen addresses."
  exit 1
fi
proxy_host=${NANPANEL_P0_PROXY_HOST:-$listen_addr}
proxy_port=${NANPANEL_P0_PROXY_PORT:-${HTTP_PROXY-}}
proxy_port=${proxy_port##*:}
if [ -n "${HTTP_PROXY:-}" ]; then
  proxy_url="http://$proxy_host:$proxy_port"
  podman build --build-arg "HTTP_PROXY=$proxy_url" --build-arg "HTTPS_PROXY=$proxy_url" \
    --build-arg "http_proxy=$proxy_url" --build-arg "https_proxy=$proxy_url" \
    -f "$repo_root/agent/p0-lab/Containerfile" -t "$image" "$repo_root/agent"
else
  podman build -f "$repo_root/agent/p0-lab/Containerfile" -t "$image" "$repo_root/agent"
fi
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=NaNPanel P0 Lab CA' \
  -keyout "$cert_dir/ca.key" -out "$cert_dir/ca.pem" >/dev/null 2>&1

make_cert() {
  name=$1
  common_name=$2
  san=$3
  usage=$4
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$common_name" \
    -keyout "$cert_dir/$name.key" -out "$cert_dir/$name.csr" >/dev/null 2>&1
  printf 'basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=%s\nsubjectAltName=%s\n' "$usage" "$san" > "$cert_dir/$name.ext"
  openssl x509 -req -days 2 -in "$cert_dir/$name.csr" -CA "$cert_dir/ca.pem" -CAkey "$cert_dir/ca.key" \
    -CAcreateserial -extfile "$cert_dir/$name.ext" -out "$cert_dir/$name.pem" >/dev/null 2>&1
}

make_cert server nanpanel-p0.test 'DNS:nanpanel-p0.test' serverAuth
make_cert agent nanpanel-p0-agent 'DNS:nanpanel-p0-agent' clientAuth
chmod 0600 "$cert_dir/"*.key

podman run -d --name "$gateway" --network host -v "$cert_dir:/certs:ro" \
  -e "NANPANEL_GATEWAY_LISTEN=$listen_addr:$port" \
  -e NANPANEL_GATEWAY_CLIENT_CA=/certs/ca.pem -e NANPANEL_GATEWAY_CERT=/certs/server.pem \
  -e NANPANEL_GATEWAY_KEY=/certs/server.key \
  --entrypoint /usr/local/bin/nanpanel-agent "$image" gateway >/dev/null

podman run -d --network pasta --name "$agent" --add-host "nanpanel-p0.test:$listen_addr" \
  --unsetenv HTTP_PROXY --unsetenv HTTPS_PROXY --unsetenv ALL_PROXY \
  --unsetenv http_proxy --unsetenv https_proxy --unsetenv all_proxy \
  -e NO_PROXY=nanpanel-p0.test -e no_proxy=nanpanel-p0.test \
  -e NANPANEL_AGENT_ENDPOINT="https://nanpanel-p0.test:$port" \
  -e NANPANEL_AGENT_TLS_DOMAIN=nanpanel-p0.test -e NANPANEL_AGENT_ID=p0-nat-agent \
  -e NANPANEL_AGENT_CA=/certs/ca.pem -e NANPANEL_AGENT_CERT=/certs/agent.pem \
  -e NANPANEL_AGENT_KEY=/certs/agent.key -e NANPANEL_AGENT_HEARTBEAT_SECS=1 \
  -e NANPANEL_AGENT_RECONNECT_MIN_MS=200 -e NANPANEL_AGENT_RECONNECT_MAX_MS=1000 \
  -v "$cert_dir:/certs:ro" --entrypoint /usr/local/bin/nanpanel-agent "$image" >/dev/null

host_netns=$(readlink /proc/self/ns/net)
agent_netns=$(podman exec "$agent" readlink /proc/1/ns/net)
[ "$host_netns" != "$agent_netns" ] || { echo 'FAIL: Agent did not run in an isolated network namespace'; exit 1; }
agent_network=$(podman inspect --format '{{.HostConfig.NetworkMode}}' "$agent")
[ "$agent_network" = 'pasta' ] || { echo "FAIL: Agent is not behind the isolated pasta network (got '$agent_network')"; exit 1; }
sleep 4
podman logs "$agent" > "$agent_log" 2>&1
podman logs "$gateway" > "$gateway_log" 2>&1
grep -q 'mTLS gateway stream established' "$agent_log" || { echo 'FAIL: no mTLS stream established'; cat "$agent_log" "$gateway_log"; exit 1; }
grep -q 'agent heartbeat' "$gateway_log" || { echo 'FAIL: gateway received no heartbeats'; cat "$agent_log" "$gateway_log"; exit 1; }

podman restart "$gateway" >/dev/null
sleep 5
podman logs "$agent" > "$agent_log" 2>&1
podman logs "$gateway" > "$gateway_log" 2>&1
connections=$(rg -c 'mTLS gateway stream established' "$agent_log" || true)
heartbeats=$(rg -c 'agent heartbeat' "$gateway_log" || true)
[ "${connections:-0}" -ge 2 ] || { echo 'FAIL: Agent did not reconnect after gateway restart'; cat "$agent_log" "$gateway_log"; exit 1; }
[ "${heartbeats:-0}" -ge 2 ] || { echo 'FAIL: Gateway did not receive heartbeats after reconnection'; cat "$agent_log" "$gateway_log"; exit 1; }
echo "PASS: rootless Podman Agent used the pasta egress network in a separate namespace ($agent_netns), established an outbound mTLS stream, reconnected after gateway restart (streams=$connections), and resumed heartbeats (heartbeats=$heartbeats)"
