#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

NETBIRD_VERSION="${NANPANEL_P0_NETBIRD_VERSION:-0.75.1}"
SERVER_IMAGE="${NANPANEL_P0_NETBIRD_SERVER_IMAGE:-docker.io/netbirdio/netbird-server:${NETBIRD_VERSION}}"
CLIENT_IMAGE="${NANPANEL_P0_NETBIRD_CLIENT_IMAGE:-docker.io/netbirdio/netbird:${NETBIRD_VERSION}}"
HTTP_IMAGE="${NANPANEL_P0_HTTP_IMAGE:-docker.io/library/busybox:1.37.0}"
CADDY_IMAGE="${NANPANEL_P0_CADDY_IMAGE:-docker.io/library/caddy:2.10.2-alpine}"
P0_SUBNET="${NANPANEL_P0_NETBIRD_SUBNET:-10.251.253.0/24}"
KEEP_ARTIFACTS="${KEEP_NANPANEL_P0_ARTIFACTS:-0}"

for tool in podman openssl curl jq python3 ip sha256sum tar; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf 'Missing required command: %s\n' "$tool" >&2
        exit 1
    }
done

if [[ "$(podman info --format '{{.Host.Security.Rootless}}')" != "true" ]]; then
    echo 'This test requires rootless Podman.' >&2
    exit 1
fi

if ! python3 - "$P0_SUBNET" <<'PY'
import ipaddress
import json
import subprocess
import sys

candidate = ipaddress.ip_network(sys.argv[1], strict=True)
if candidate.version != 4 or candidate.prefixlen != 24:
    raise SystemExit("the test subnet must be an IPv4 /24")

routes = json.loads(subprocess.check_output(["ip", "-j", "route", "show"], text=True))
for route in routes:
    destination = route.get("dst")
    if destination and destination != "default" and candidate.overlaps(ipaddress.ip_network(destination, strict=False)):
        raise SystemExit(f"test subnet overlaps host route {destination}")

names = subprocess.check_output(["podman", "network", "ls", "-q"], text=True).split()
if names:
    networks = json.loads(subprocess.check_output(["podman", "network", "inspect", *names], text=True))
    for network in networks:
        for subnet in network.get("subnets", []):
            existing = ipaddress.ip_network(subnet["subnet"], strict=False)
            if candidate.overlaps(existing):
                raise SystemExit(f"test subnet overlaps Podman network {network['name']} ({existing})")
PY
then
    echo 'Choose a free NANPANEL_P0_NETBIRD_SUBNET and retry.' >&2
    exit 1
fi

RUN_ID="${BASHPID}-${RANDOM}"
NETWORK_NAME="nanpanel-p0-netbird-${RUN_ID}"
SERVER_NAME="nanpanel-p0-netbird-server-${RUN_ID}"
CADDY_NAME="nanpanel-p0-netbird-caddy-${RUN_ID}"
PEER_A_NAME="nanpanel-p0-netbird-a-${RUN_ID}"
PEER_B_NAME="nanpanel-p0-netbird-b-${RUN_ID}"
HTTP_NAME="nanpanel-p0-netbird-http-${RUN_ID}"
P0_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nanpanel-p0-netbird.XXXXXX")"
chmod 700 "$P0_DIR"

SUBNET_BASE="${P0_SUBNET%/24}"
SUBNET_BASE="${SUBNET_BASE%.*}"
SERVER_IP="${SUBNET_BASE}.2"
PROXY_IP="${SUBNET_BASE}.3"
PEER_A_IP="${SUBNET_BASE}.10"
PEER_B_IP="${SUBNET_BASE}.11"
SERVER_STATE="$P0_DIR/server-state"
PEER_A_STATE="$P0_DIR/peer-a-state"
PEER_B_STATE="$P0_DIR/peer-b-state"
mkdir -m 700 "$SERVER_STATE" "$PEER_A_STATE" "$PEER_B_STATE"
P0_PAT_FILE="$P0_DIR/admin.pat"
P0_SETUP_KEY_FILE="$P0_DIR/setup-key"
P0_SETUP_KEY_ID_FILE="$P0_DIR/setup-key-id"
P0_SETUP_KEY_ID=""
P0_GEO_CACHE="${NANPANEL_P0_NETBIRD_GEO_CACHE:-$P0_DIR/geolite-cache}"
P0_LOCAL_PORT="$(python3 - <<'PY'
import socket

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
print(sock.getsockname()[1])
sock.close()
PY
)"

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    set +e
    if [[ -s "$P0_PAT_FILE" && -n "$P0_SETUP_KEY_ID" ]] && podman container exists "$SERVER_NAME"; then
        curl --noproxy '*' --silent --show-error --fail --max-time 4 \
            --cacert "$P0_DIR/tls/server.crt" \
            --resolve "$PROXY_IP:$P0_LOCAL_PORT:127.0.0.1" \
            -H "Authorization: Token $(<"$P0_PAT_FILE")" \
            -X DELETE "https://$PROXY_IP:$P0_LOCAL_PORT/api/setup-keys/$P0_SETUP_KEY_ID" \
            -o /dev/null 2>/dev/null || true
    fi
    if [[ "$KEEP_ARTIFACTS" == "1" ]]; then
        printf 'Keeping isolated P0 artifacts in %s\n' "$P0_DIR"
        printf 'Podman objects: %s, %s, %s, %s, network %s\n' \
            "$SERVER_NAME" "$CADDY_NAME" "$PEER_A_NAME" "$PEER_B_NAME" "$NETWORK_NAME"
    else
        for container_name in "$HTTP_NAME" "$PEER_B_NAME" "$PEER_A_NAME" "$CADDY_NAME" "$SERVER_NAME"; do
            podman rm -f "$container_name" >/dev/null 2>&1 || true
        done
        podman network rm "$NETWORK_NAME" >/dev/null 2>&1 || true
        rm -rf -- "$P0_DIR"
    fi
    exit "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for image in "$SERVER_IMAGE" "$CLIENT_IMAGE" "$HTTP_IMAGE" "$CADDY_IMAGE"; do
    if ! podman image exists "$image"; then
        podman pull "$image"
    fi
done

mkdir -m 700 "$P0_DIR/tls"
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
    -keyout "$P0_DIR/tls/server.key" \
    -out "$P0_DIR/tls/server.crt" \
    -subj "/CN=$PROXY_IP" \
    -addext "subjectAltName=IP:$PROXY_IP" \
    -addext 'basicConstraints=critical,CA:TRUE' >/dev/null 2>&1
chmod 600 "$P0_DIR/tls/server.key" "$P0_DIR/tls/server.crt"

P0_AUTH_SECRET="$(openssl rand -base64 32)"
P0_STORE_KEY="$(openssl rand -base64 32)"
cat >"$P0_DIR/config.yaml" <<EOF
server:
  listenAddress: ":80"
  exposedAddress: "https://$PROXY_IP:443"
  authSecret: "$P0_AUTH_SECRET"
  dataDir: "/var/lib/netbird"
  auth:
    issuer: "https://$PROXY_IP/oauth2"
    localAuthDisabled: false
    signKeyRefreshEnabled: true
    dashboardRedirectURIs:
      - "https://$PROXY_IP/nb-auth"
      - "https://$PROXY_IP/nb-silent-auth"
    cliRedirectURIs:
      - "http://localhost:53000/"
  store:
    engine: "sqlite"
    encryptionKey: "$P0_STORE_KEY"
  disableAnonymousMetrics: true
  disableGeoliteUpdate: true
EOF
chmod 600 "$P0_DIR/config.yaml"

mkdir -p "$P0_GEO_CACHE"
P0_CITY_HEADERS="$P0_DIR/city-headers.txt"
P0_CSV_HEADERS="$P0_DIR/csv-headers.txt"
P0_CITY_URL='https://pkgs.netbird.io/geolocation-dbs/GeoLite2-City/download?suffix=tar.gz'
P0_CITY_CHECKSUM_URL='https://pkgs.netbird.io/geolocation-dbs/GeoLite2-City/download?suffix=tar.gz.sha256'
P0_CSV_URL='https://pkgs.netbird.io/geolocation-dbs/GeoLite2-City-CSV/download?suffix=zip'
curl --noproxy '*' --silent --show-error --fail --location --head \
    --dump-header "$P0_CITY_HEADERS" --output /dev/null "$P0_CITY_URL"
curl --noproxy '*' --silent --show-error --fail --location --head \
    --dump-header "$P0_CSV_HEADERS" --output /dev/null "$P0_CSV_URL"
P0_CITY_ARCHIVE_NAME="$(sed -nE 's/^[Cc]ontent-[Dd]isposition:.*filename="?([^";]+)"?.*/\1/p' "$P0_CITY_HEADERS" | tail -n 1)"
P0_CSV_ARCHIVE_NAME="$(sed -nE 's/^[Cc]ontent-[Dd]isposition:.*filename="?([^";]+)"?.*/\1/p' "$P0_CSV_HEADERS" | tail -n 1)"
if [[ ! "$P0_CITY_ARCHIVE_NAME" =~ ^GeoLite2-City_([0-9]{8})\.tar\.gz$ ]]; then
    echo 'Could not determine the official GeoLite city database date.' >&2
    exit 1
fi
P0_CITY_DATE="${BASH_REMATCH[1]}"
if [[ ! "$P0_CSV_ARCHIVE_NAME" =~ ^GeoLite2-City-CSV_([0-9]{8})\.zip$ ]]; then
    echo 'Could not determine the official GeoLite names database date.' >&2
    exit 1
fi
P0_CSV_DATE="${BASH_REMATCH[1]}"
P0_CITY_ARCHIVE="$P0_GEO_CACHE/$P0_CITY_ARCHIVE_NAME"
P0_CITY_CHECKSUM="$P0_GEO_CACHE/$P0_CITY_ARCHIVE_NAME.sha256"
if [[ ! -s "$P0_CITY_ARCHIVE" ]]; then
    curl --noproxy '*' --silent --show-error --fail --location --retry 2 \
        --connect-timeout 10 --max-time 300 -o "$P0_CITY_ARCHIVE" "$P0_CITY_URL"
fi
if [[ ! -s "$P0_CITY_CHECKSUM" ]]; then
    curl --noproxy '*' --silent --show-error --fail --location --retry 2 \
        --connect-timeout 10 --max-time 30 -o "$P0_CITY_CHECKSUM" "$P0_CITY_CHECKSUM_URL"
fi
P0_EXPECTED_SHA256="$(awk 'NR == 1 {print $1}' "$P0_CITY_CHECKSUM")"
P0_ACTUAL_SHA256="$(sha256sum "$P0_CITY_ARCHIVE" | awk '{print $1}')"
if [[ -z "$P0_EXPECTED_SHA256" || "$P0_EXPECTED_SHA256" != "$P0_ACTUAL_SHA256" ]]; then
    echo 'GeoLite city database checksum verification failed.' >&2
    exit 1
fi
P0_MMDB_MEMBER="$(tar -tzf "$P0_CITY_ARCHIVE" | awk '!found && /\/GeoLite2-City\.mmdb$/ {print; found=1}')"
if [[ -z "$P0_MMDB_MEMBER" ]]; then
    echo 'GeoLite archive does not contain GeoLite2-City.mmdb.' >&2
    exit 1
fi
tar -xOzf "$P0_CITY_ARCHIVE" "$P0_MMDB_MEMBER" >"$SERVER_STATE/GeoLite2-City_${P0_CITY_DATE}.mmdb"
python3 - "$SERVER_STATE/geonames_${P0_CSV_DATE}.db" <<'PY'
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
db.execute("""
CREATE TABLE geonames (
    geoname_id INTEGER,
    locale_code TEXT,
    continent_code TEXT,
    continent_name TEXT,
    country_iso_code TEXT,
    country_name TEXT,
    subdivision_1_iso_code TEXT,
    subdivision_1_name TEXT,
    subdivision_2_iso_code TEXT,
    subdivision_2_name TEXT,
    city_name TEXT,
    metro_code TEXT,
    time_zone TEXT,
    is_in_european_union TEXT
)
""")
db.commit()
result = db.execute("PRAGMA integrity_check").fetchone()[0]
db.close()
if result != "ok":
    raise SystemExit(f"GeoLite names fixture integrity check failed: {result}")
PY

cat >"$P0_DIR/Caddyfile" <<EOF
https://$PROXY_IP {
    tls /lab/tls/server.crt /lab/tls/server.key

    @grpc header Content-Type application/grpc*
    reverse_proxy @grpc h2c://$SERVER_IP:80

    @backend path /relay* /ws-proxy/* /api/* /oauth2/*
    reverse_proxy @backend $SERVER_IP:80
}
EOF
chmod 600 "$P0_DIR/Caddyfile"

podman network create --subnet "$P0_SUBNET" "$NETWORK_NAME" >/dev/null
P0_API_URL="https://$PROXY_IP:$P0_LOCAL_PORT"
P0_CURL_RESOLVE=(--resolve "$PROXY_IP:$P0_LOCAL_PORT:127.0.0.1")
start_and_wait_for_proxy() {
    podman run -d \
        --http-proxy=false \
        --name "$SERVER_NAME" \
        --network "$NETWORK_NAME" \
        --ip "$SERVER_IP" \
        --volume "$P0_DIR:/lab:ro" \
        --volume "$SERVER_STATE:/var/lib/netbird" \
        --env NB_SETUP_PAT_ENABLED=true \
        "$SERVER_IMAGE" --config /lab/config.yaml >/dev/null

    podman run -d \
        --http-proxy=false \
        --name "$CADDY_NAME" \
        --network "$NETWORK_NAME" \
        --ip "$PROXY_IP" \
        --publish "127.0.0.1:$P0_LOCAL_PORT:443/tcp" \
        --volume "$P0_DIR:/lab:ro" \
        "$CADDY_IMAGE" caddy run --config /lab/Caddyfile --adapter caddyfile >/dev/null

    local ready=0
    for attempt in $(seq 1 120); do
        if curl --noproxy '*' --silent --fail --max-time 2 --cacert "$P0_DIR/tls/server.crt" \
            "${P0_CURL_RESOLVE[@]}" "$P0_API_URL/api/instance" -o "$P0_DIR/instance.json"; then
            ready=1
            break
        fi
        sleep 1
    done
    if [[ "$ready" != "1" ]]; then
        printf 'NetBird management API did not become ready; server and proxy logs follow.\n' >&2
        podman logs "$SERVER_NAME" >&2 || true
        podman logs "$CADDY_NAME" >&2 || true
        return 1
    fi
}

start_and_wait_for_proxy
P0_SERVER_STARTUP_LOGS="$(podman logs "$SERVER_NAME" 2>&1)"
if grep -q 'file will be downloaded' <<<"$P0_SERVER_STARTUP_LOGS"; then
    echo 'NetBird attempted an unexpected GeoLite download in the isolated server network.' >&2
    podman logs "$SERVER_NAME" >&2 || true
    exit 1
fi
if [[ "$(jq -r '.setup_required' "$P0_DIR/instance.json")" != "true" ]]; then
    echo 'Fresh NetBird instance unexpectedly reports setup complete.' >&2
    exit 1
fi

P0_ADMIN_PASSWORD="$(openssl rand -hex 24)"
jq -n --arg email "p0-${RUN_ID}@example.test" --arg password "$P0_ADMIN_PASSWORD" \
    '{email:$email,name:"NaNPanel P0 Relay Test",password:$password,create_pat:true,pat_expire_in:1}' \
    >"$P0_DIR/setup-request.json"
curl --noproxy '*' --silent --show-error --fail --max-time 10 --cacert "$P0_DIR/tls/server.crt" \
    "${P0_CURL_RESOLVE[@]}" -H 'Content-Type: application/json' \
    --data-binary "@$P0_DIR/setup-request.json" \
    "$P0_API_URL/api/setup" -o "$P0_DIR/setup-response.json"
jq -er '.personal_access_token' "$P0_DIR/setup-response.json" >"$P0_PAT_FILE"
chmod 600 "$P0_PAT_FILE"

jq -n --arg name "nanpanel-p0-$RUN_ID" \
    '{name:$name,type:"reusable",expires_in:3600,usage_limit:2,ephemeral:true}' \
    >"$P0_DIR/setup-key-request.json"
curl --noproxy '*' --silent --show-error --fail --max-time 10 --cacert "$P0_DIR/tls/server.crt" \
    "${P0_CURL_RESOLVE[@]}" -H "Authorization: Token $(<"$P0_PAT_FILE")" \
    -H 'Content-Type: application/json' --data-binary "@$P0_DIR/setup-key-request.json" \
    "$P0_API_URL/api/setup-keys" -o "$P0_DIR/setup-key-response.json"
jq -er '.key' "$P0_DIR/setup-key-response.json" >"$P0_SETUP_KEY_FILE"
jq -er '.id' "$P0_DIR/setup-key-response.json" >"$P0_SETUP_KEY_ID_FILE"
chmod 600 "$P0_SETUP_KEY_FILE" "$P0_SETUP_KEY_ID_FILE"
P0_SETUP_KEY_ID="$(<"$P0_SETUP_KEY_ID_FILE")"
P0_SETUP_KEY="$(<"$P0_SETUP_KEY_FILE")"

start_udp_blocked_peer() {
    local name="$1"
    local hostname="$2"
    local address="$3"
    local state_dir="$4"
    podman run -d \
        --http-proxy=false \
        --name "$name" \
        --hostname "$hostname" \
        --network "$NETWORK_NAME" \
        --ip "$address" \
        --device /dev/net/tun \
        --cap-add NET_ADMIN \
        --cap-add NET_RAW \
        --volume "$state_dir:/var/lib/netbird" \
        --volume "$P0_DIR/tls/server.crt:/lab/server.crt:ro" \
        --env "SSL_CERT_FILE=/lab/server.crt" \
        --env "NB_MANAGEMENT_URL=https://$PROXY_IP:443" \
        --env "NB_SETUP_KEY=$P0_SETUP_KEY" \
        --env NB_LAZY_CONN=off \
        --entrypoint /bin/sh \
        "$CLIENT_IMAGE" -ec \
        'iptables -I INPUT -i eth0 -p udp -j REJECT; iptables -I OUTPUT -o eth0 -p udp -j REJECT; exec /usr/local/bin/netbird-entrypoint.sh' \
        >/dev/null
}

start_udp_blocked_peer "$PEER_A_NAME" "p0-netbird-a" "$PEER_A_IP" "$PEER_A_STATE"
start_udp_blocked_peer "$PEER_B_NAME" "p0-netbird-b" "$PEER_B_IP" "$PEER_B_STATE"

wait_for_peer() {
    local name="$1"
    local status=''
    for attempt in $(seq 1 90); do
        status="$(podman exec "$name" netbird status 2>/dev/null || true)"
        if grep -q 'Management: Connected' <<<"$status" \
            && grep -q 'Signal: Connected' <<<"$status"; then
            return 0
        fi
        sleep 1
    done
    printf 'NetBird peer %s failed to connect.\n' "$name" >&2
    podman logs "$name" >&2 || true
    return 1
}

wait_for_peer "$PEER_A_NAME"
wait_for_peer "$PEER_B_NAME"

for name in "$PEER_A_NAME" "$PEER_B_NAME"; do
    podman exec "$name" iptables -C INPUT -i eth0 -p udp -j REJECT
    podman exec "$name" iptables -C OUTPUT -o eth0 -p udp -j REJECT
done

P0_PEER_A_OVERLAY_IP="$(podman exec "$PEER_A_NAME" ip -4 -o addr show dev wt0 \
    | awk '{split($4, address, "/"); print address[1]}')"
if [[ -z "$P0_PEER_A_OVERLAY_IP" ]]; then
    echo 'Could not read NetBird overlay address from peer A.' >&2
    exit 1
fi

P0_HTTP_DIR="$P0_DIR/http-www"
mkdir -m 700 "$P0_HTTP_DIR"
printf 'NETBIRD_TCP443_RELAY_OK\n' >"$P0_HTTP_DIR/probe"
podman run -d \
    --name "$HTTP_NAME" \
    --network "container:$PEER_A_NAME" \
    --volume "$P0_HTTP_DIR:/www:ro" \
    "$HTTP_IMAGE" httpd -f -p 18080 -h /www >/dev/null
sleep 1
P0_HTTP_BODY=''
P0_PATH_VERIFIED=0
P0_PEER_A_STATUS=''
P0_PEER_B_STATUS=''
for attempt in $(seq 1 30); do
    P0_HTTP_BODY="$(podman exec "$PEER_B_NAME" wget -T 2 -qO- \
        "http://$P0_PEER_A_OVERLAY_IP:18080/probe" 2>/dev/null || true)"
    P0_PEER_A_STATUS="$(podman exec "$PEER_A_NAME" netbird status -d 2>/dev/null || true)"
    P0_PEER_B_STATUS="$(podman exec "$PEER_B_NAME" netbird status -d 2>/dev/null || true)"
    if [[ "$P0_HTTP_BODY" == "NETBIRD_TCP443_RELAY_OK" ]] \
        && grep -Eiq 'Connection type:.*relay' <<<"$P0_PEER_A_STATUS" \
        && grep -Eiq 'Connection type:.*relay' <<<"$P0_PEER_B_STATUS"; then
        P0_PATH_VERIFIED=1
        break
    fi
    sleep 1
done
if [[ "$P0_PATH_VERIFIED" != "1" ]]; then
    echo 'UDP-blocked peer traffic did not prove a relayed path and HTTP response.' >&2
    printf 'HTTP response: %s\nPeer A status:\n%s\nPeer B status:\n%s\n' \
        "$P0_HTTP_BODY" "$P0_PEER_A_STATUS" "$P0_PEER_B_STATUS" >&2
    exit 1
fi
if [[ "$P0_HTTP_BODY" != "NETBIRD_TCP443_RELAY_OK" ]]; then
    echo "Unexpected overlay HTTP response: $P0_HTTP_BODY" >&2
    exit 1
fi

printf 'NetBird %s: two rootless Podman peers connected with UDP blocked on eth0 in both directions.\n' "$NETBIRD_VERSION"
printf 'Relay evidence: both peer details report a relayed connection; service HTTP over overlay returned %s.\n' "$P0_HTTP_BODY"
printf 'UDP evidence: iptables rejects eth0 INPUT and OUTPUT UDP on both peers; loopback remains available for the local WireGuard proxy.\n'
printf 'Control-plane/relay endpoint: https://%s:443; host publication is loopback-only at 127.0.0.1:%s.\n' \
    "$PROXY_IP" "$P0_LOCAL_PORT"
