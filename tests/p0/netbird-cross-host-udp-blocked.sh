#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

public_ssh_alias="${NANPANEL_P0_PUBLIC_SSH_ALIAS:-bj-aliyun}"
docker_ssh_alias="${NANPANEL_P0_DOCKER_SSH_ALIAS:-bt-main}"
k3s_ssh_alias="${NANPANEL_P0_K3S_SSH_ALIAS:-k3s-1}"
public_ip="${NANPANEL_P0_PUBLIC_IP:-39.106.185.211}"
netbird_version="${NANPANEL_P0_NETBIRD_VERSION:-0.75.1}"
server_image="${NANPANEL_P0_NETBIRD_SERVER_IMAGE:-netbirdio/netbird-server:${netbird_version}}"
peer_image="${NANPANEL_P0_NETBIRD_PEER_IMAGE:-netbirdio/netbird:${netbird_version}}"
web_image="${NANPANEL_P0_K3S_HTTP_IMAGE:-docker.1ms.run/nginx:latest}"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
domain="nanpanel-nb-${run_id}.geekcorelab.cn"
namespace="nanpanel-p0-nb-${run_id}"
server_container="nanpanel-p0-nb-server-${run_id}"
docker_peer="nanpanel-p0-nb-docker-${run_id}"
remote_server_dir="/tmp/${server_container}"
remote_peer_dir="/tmp/${docker_peer}"
nginx_file="/www/server/panel/vhost/nginx/${server_container}.conf"
remote_server_port=''
work="$(mktemp -d "${TMPDIR:-/tmp}/nanpanel-p0-netbird-cross.XXXXXX")"
chmod 700 "$work"
server_created=0
docker_peer_created=0
k3s_namespace_created=0
nginx_vhost_created=0
remote_server_dir_created=0
remote_peer_dir_created=0
setup_key_id=''
cleanup_failed=0

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [[ -s "$work/admin.pat" && -n "$setup_key_id" ]]; then
    printf 'Authorization: Token %s\n' "$(<"$work/admin.pat")" >"$work/api-auth.header"
    chmod 600 "$work/api-auth.header"
    curl --noproxy '*' --silent --show-error --fail --max-time 8 \
      --resolve "$domain:443:$public_ip" --header "@$work/api-auth.header" \
      -X DELETE "https://$domain/api/setup-keys/$setup_key_id" -o /dev/null >/dev/null 2>&1 || true
  fi
  if ((k3s_namespace_created)); then
    ssh -o BatchMode=yes "$k3s_ssh_alias" \
      "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl delete namespace '$namespace' --wait=true --timeout=120s" \
      >/dev/null 2>&1 || cleanup_failed=1
  fi
  if ((docker_peer_created || remote_peer_dir_created)); then
    ssh -o BatchMode=yes "$docker_ssh_alias" \
      "docker rm -f '$docker_peer' >/dev/null 2>&1 || true; rm -rf -- '$remote_peer_dir'" \
      >/dev/null 2>&1 || cleanup_failed=1
  fi
  if ((server_created || nginx_vhost_created || remote_server_dir_created)); then
    ssh -o BatchMode=yes "$public_ssh_alias" \
      "docker rm -f '$server_container' >/dev/null 2>&1 || true; sudo -n rm -f '$nginx_file'; sudo -n nginx -t >/dev/null && sudo -n systemctl reload nginx; rm -rf -- '$remote_server_dir'" \
      >/dev/null 2>&1 || cleanup_failed=1
  fi
  for alias in "$public_ssh_alias" "$docker_ssh_alias" "$k3s_ssh_alias"; do
    command ssh -o BatchMode=yes -o ControlPath="$work/ssh-%C" -O exit "$alias" >/dev/null 2>&1 || true
  done
  rm -rf -- "$work"
  if ((cleanup_failed)); then
    printf 'WARNING: one or more remote cleanup operations failed; inspect run id %s.\n' "$run_id" >&2
    ((status != 0)) || status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ssh() {
  command ssh -o ConnectionAttempts=3 -o ConnectTimeout=10 \
    -o ControlMaster=auto -o ControlPersist=90 -o ControlPath="$work/ssh-%C" "$@"
}
scp() {
  command scp -o ConnectionAttempts=3 -o ConnectTimeout=10 \
    -o ControlMaster=auto -o ControlPersist=90 -o ControlPath="$work/ssh-%C" "$@"
}

for tool in ssh scp curl openssl jq python3 sha256sum tar awk grep sed tr; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required command: $tool" >&2; exit 1; }
done
for alias in "$public_ssh_alias" "$docker_ssh_alias" "$k3s_ssh_alias"; do
  [[ "$alias" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "Invalid SSH alias: $alias" >&2; exit 1; }
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$alias" true
done
printf 'Phase: validate hosts, K3s, and pinned images.\n'
[[ "$public_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "Invalid public IPv4: $public_ip" >&2; exit 1; }
[[ "$netbird_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid NetBird version.' >&2; exit 1; }

node_status="$(ssh -o BatchMode=yes "$k3s_ssh_alias" \
  'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get nodes --no-headers' | awk 'NR == 1 {print $2}')"
[[ "$node_status" == Ready ]] || { echo "K3s node is not Ready: $node_status" >&2; exit 1; }
if ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get namespace '$namespace' -o name" >/dev/null 2>&1; then
  echo "K3s namespace already exists: $namespace" >&2
  exit 1
fi

if ! ssh -o BatchMode=yes "$public_ssh_alias" "docker image inspect '$server_image' >/dev/null 2>&1"; then
  ssh -o BatchMode=yes "$public_ssh_alias" "docker pull '$server_image'"
fi
if ! ssh -o BatchMode=yes "$docker_ssh_alias" "docker image inspect '$peer_image' >/dev/null 2>&1"; then
  ssh -o BatchMode=yes "$docker_ssh_alias" "docker pull '$peer_image'"
fi
if ! ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n k3s ctr -n k8s.io images list | grep -Fq 'docker.io/$peer_image'"; then
  printf 'Importing the pinned NetBird peer image into K3s containerd.\n'
  ssh -o BatchMode=yes "$docker_ssh_alias" "docker save '$peer_image'" \
    | ssh -o BatchMode=yes "$k3s_ssh_alias" 'sudo -n k3s ctr -n k8s.io images import -'
fi
printf 'Phase: seed the temporary control plane with the checksum-verified GeoLite database.\n'

used_ports="$(ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n ss -H -ltn' \
  | awk '{print $4}' | sed -nE 's/.*:([0-9]+)$/\1/p')"
for candidate in $(seq 42000 42999); do
  if ! grep -qx "$candidate" <<<"$used_ports"; then remote_server_port="$candidate"; break; fi
done
[[ -n "$remote_server_port" ]] || { echo 'No free temporary NetBird server port found.' >&2; exit 1; }

geo_archive="${NANPANEL_P0_NETBIRD_GEO_ARCHIVE:-}"
if [[ -z "$geo_archive" ]]; then
  geo_archive="$(find "${TMPDIR:-/tmp}" -maxdepth 4 -type f -name 'GeoLite2-City_*.tar.gz' -size +1M -print -quit 2>/dev/null || true)"
fi
if [[ -z "$geo_archive" ]]; then
  city_headers="$work/city-headers.txt"
  city_url='https://pkgs.netbird.io/geolocation-dbs/GeoLite2-City/download?suffix=tar.gz'
  curl --noproxy '*' --silent --show-error --fail --location --head \
    --dump-header "$city_headers" --output /dev/null "$city_url"
  archive_name="$(sed -nE 's/^[Cc]ontent-[Dd]isposition:.*filename="?([^";]+)"?.*/\1/p' "$city_headers" | tail -n 1)"
  geo_archive="$work/$archive_name"
  curl --noproxy '*' --silent --show-error --fail --location --retry 2 \
    --connect-timeout 10 --max-time 900 -o "$geo_archive" "$city_url"
fi
[[ -s "$geo_archive" && "$(basename "$geo_archive")" =~ ^GeoLite2-City_([0-9]{8})\.tar\.gz$ ]] || {
  echo 'No valid GeoLite city archive is available.' >&2
  exit 1
}
city_date="${BASH_REMATCH[1]}"
geo_checksum="${geo_archive}.sha256"
if [[ ! -s "$geo_checksum" ]]; then
  curl --noproxy '*' --silent --show-error --fail --location --retry 2 \
    --connect-timeout 10 --max-time 30 \
    -o "$geo_checksum" 'https://pkgs.netbird.io/geolocation-dbs/GeoLite2-City/download?suffix=tar.gz.sha256'
fi
expected_sha="$(awk 'NR == 1 {print $1}' "$geo_checksum")"
actual_sha="$(sha256sum "$geo_archive" | awk '{print $1}')"
[[ -n "$expected_sha" && "$expected_sha" == "$actual_sha" ]] || {
  echo 'GeoLite archive checksum verification failed.' >&2
  exit 1
}
mmdb_member="$(tar -tzf "$geo_archive" | awk '!found && /\/GeoLite2-City\.mmdb$/ {print; found=1}')"
[[ -n "$mmdb_member" ]] || { echo 'GeoLite archive has no City mmdb file.' >&2; exit 1; }

mkdir -m 700 "$work/server-state" "$work/docker-peer-state"
tar -xOzf "$geo_archive" "$mmdb_member" >"$work/server-state/GeoLite2-City_${city_date}.mmdb"
python3 - "$work/server-state/geonames_${city_date}.db" <<'PY'
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
db.execute("""
CREATE TABLE geonames (
    geoname_id INTEGER, locale_code TEXT, continent_code TEXT, continent_name TEXT,
    country_iso_code TEXT, country_name TEXT, subdivision_1_iso_code TEXT,
    subdivision_1_name TEXT, subdivision_2_iso_code TEXT, subdivision_2_name TEXT,
    city_name TEXT, metro_code TEXT, time_zone TEXT, is_in_european_union TEXT
)
""")
db.commit()
result = db.execute("PRAGMA integrity_check").fetchone()[0]
db.close()
if result != "ok":
    raise SystemExit(f"GeoLite names database integrity check failed: {result}")
PY


chmod 600 "$work/server-state/GeoLite2-City_${city_date}.mmdb" "$work/server-state/geonames_${city_date}.db"

auth_secret="$(openssl rand -base64 32 | tr -d '\n')"
store_key="$(openssl rand -base64 32 | tr -d '\n')"
cat >"$work/config.yaml" <<EOF
server:
  listenAddress: ":80"
  exposedAddress: "https://${domain}:443"
  authSecret: "$auth_secret"
  dataDir: "/var/lib/netbird"
  auth:
    issuer: "https://${domain}/oauth2"
    localAuthDisabled: false
    signKeyRefreshEnabled: true
    dashboardRedirectURIs:
      - "https://${domain}/nb-auth"
      - "https://${domain}/nb-silent-auth"
    cliRedirectURIs:
      - "http://localhost:53000/"
  store:
    engine: "sqlite"
    encryptionKey: "$store_key"
  disableAnonymousMetrics: true
  disableGeoliteUpdate: true
EOF
chmod 600 "$work/config.yaml"
unset auth_secret store_key

ssh -o BatchMode=yes "$public_ssh_alias" "test ! -e '$remote_server_dir' && umask 077 && mkdir -m 700 '$remote_server_dir'"
remote_server_dir_created=1
scp -q "$work/config.yaml" "$work/server-state/GeoLite2-City_${city_date}.mmdb" \
  "$work/server-state/geonames_${city_date}.db" "$public_ssh_alias:$remote_server_dir/"
ssh -o BatchMode=yes "$public_ssh_alias" \
  "chmod 600 '$remote_server_dir/config.yaml' '$remote_server_dir/GeoLite2-City_${city_date}.mmdb' '$remote_server_dir/geonames_${city_date}.db' && mkdir -m 700 '$remote_server_dir/state' && mv '$remote_server_dir/GeoLite2-City_${city_date}.mmdb' '$remote_server_dir/geonames_${city_date}.db' '$remote_server_dir/state/'"

cat >"$work/nginx-vhost.conf" <<'NGINX'
server {
    listen 443 ssl;
    http2 on;
    server_name @DOMAIN@;
    ssl_certificate /www/server/panel/vhost/cert/geekcorelab.cn/fullchain.pem;
    ssl_certificate_key /www/server/panel/vhost/cert/geekcorelab.cn/privkey.pem;

    location ~ ^/(signalexchange\.SignalExchange|management\.(ManagementService|ProxyService))/ {
        grpc_pass grpc://127.0.0.1:@PORT@;
        grpc_set_header Host $host;
        grpc_read_timeout 1h;
        grpc_send_timeout 1h;
    }
    location /relay {
        proxy_pass http://127.0.0.1:@PORT@;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
    location /ws-proxy/ {
        proxy_pass http://127.0.0.1:@PORT@;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
    location /api/ {
        proxy_pass http://127.0.0.1:@PORT@;
        proxy_set_header Host $host;
        proxy_read_timeout 1h;
    }
    location /oauth2/ {
        proxy_pass http://127.0.0.1:@PORT@;
        proxy_set_header Host $host;
        proxy_read_timeout 1h;
    }
    location / {
        return 404;
    }
}
NGINX
sed -i "s/@DOMAIN@/$domain/g; s/@PORT@/$remote_server_port/g" "$work/nginx-vhost.conf"
ssh -o BatchMode=yes "$public_ssh_alias" "test ! -e '$nginx_file'"
nginx_vhost_created=1
ssh -o BatchMode=yes "$public_ssh_alias" "sudo -n tee '$nginx_file' >/dev/null" <"$work/nginx-vhost.conf"
ssh -o BatchMode=yes "$public_ssh_alias" \
  "sudo -n chmod 600 '$nginx_file' && sudo -n nginx -t && sudo -n systemctl reload nginx"
printf 'Phase: start temporary NetBird control plane behind public HTTPS.\n'
server_created=1
ssh -o BatchMode=yes "$public_ssh_alias" \
  "docker run -d --name '$server_container' --restart=no --publish 127.0.0.1:$remote_server_port:80 --volume '$remote_server_dir:/lab:ro' --volume '$remote_server_dir/state:/var/lib/netbird' --env NB_SETUP_PAT_ENABLED=true '$server_image' --config /lab/config.yaml" \
  >/dev/null

api_ready=0
for _ in $(seq 1 120); do
  if curl --noproxy '*' --silent --fail --max-time 3 --resolve "$domain:443:$public_ip" \
    "https://$domain/api/instance" -o "$work/instance.json"; then
    api_ready=1
    break
  fi
  sleep 1
done
if ((api_ready == 0)); then
  echo 'Temporary NetBird management API did not become ready.' >&2
  ssh -o BatchMode=yes "$public_ssh_alias" "docker logs --tail=100 '$server_container'" >&2 || true
  exit 1
fi
if [[ "$(jq -r '.setup_required' "$work/instance.json")" != true ]]; then
  echo 'Fresh NetBird instance unexpectedly reports setup complete.' >&2
  exit 1
fi
printf 'Phase: create ephemeral setup key and start the UDP-blocked NAT Docker peer.\n'

admin_password="$(openssl rand -hex 24)"
jq -n --arg email "p0-${run_id}@example.test" --arg password "$admin_password" \
  '{email:$email,name:"NaNPanel P0 Cross-host Relay Test",password:$password,create_pat:true,pat_expire_in:1}' \
  >"$work/setup-request.json"
chmod 600 "$work/setup-request.json"
curl --noproxy '*' --silent --show-error --fail --max-time 15 --resolve "$domain:443:$public_ip" \
  -H 'Content-Type: application/json' --data-binary "@$work/setup-request.json" \
  "https://$domain/api/setup" -o "$work/setup-response.json"
jq -er '.personal_access_token' "$work/setup-response.json" >"$work/admin.pat"
chmod 600 "$work/admin.pat"

jq -n --arg name "nanpanel-p0-${run_id}" \
  '{name:$name,type:"reusable",expires_in:3600,usage_limit:2,ephemeral:true}' \
  >"$work/setup-key-request.json"
printf 'Authorization: Token %s\n' "$(<"$work/admin.pat")" >"$work/api-auth.header"
chmod 600 "$work/api-auth.header"
curl --noproxy '*' --silent --show-error --fail --max-time 15 --resolve "$domain:443:$public_ip" \
  --header "@$work/api-auth.header" -H 'Content-Type: application/json' \
  --data-binary "@$work/setup-key-request.json" \
  "https://$domain/api/setup-keys" -o "$work/setup-key-response.json"
jq -er '.key' "$work/setup-key-response.json" | tr -d '\r\n' >"$work/setup-key"
setup_key_id="$(jq -er '.id' "$work/setup-key-response.json")"
chmod 600 "$work/setup-key"
[[ "$(wc -c <"$work/setup-key")" -eq 36 ]] || { echo 'Unexpected NetBird setup key length.' >&2; exit 1; }

cat >"$work/docker-peer.env" <<EOF
NB_MANAGEMENT_URL=https://${domain}:443
NB_SETUP_KEY=$(<"$work/setup-key")
NB_LAZY_CONN=off
HTTP_PROXY=
HTTPS_PROXY=
ALL_PROXY=
http_proxy=
https_proxy=
all_proxy=
EOF
chmod 600 "$work/docker-peer.env"
ssh -o BatchMode=yes "$docker_ssh_alias" "test ! -e '$remote_peer_dir' && umask 077 && mkdir -m 700 '$remote_peer_dir'"
remote_peer_dir_created=1
scp -q "$work/docker-peer.env" "$docker_ssh_alias:$remote_peer_dir/peer.env"
ssh -o BatchMode=yes "$docker_ssh_alias" \
  "chmod 600 '$remote_peer_dir/peer.env' && mkdir -m 700 '$remote_peer_dir/state' && test -c /dev/net/tun && ! docker container inspect '$docker_peer' >/dev/null 2>&1"
docker_peer_created=1
ssh -o BatchMode=yes "$docker_ssh_alias" \
  "docker run -d --name '$docker_peer' --restart=no --device /dev/net/tun --cap-add NET_ADMIN --cap-add NET_RAW --add-host '$domain:$public_ip' --volume '$remote_peer_dir/state:/var/lib/netbird' --env-file '$remote_peer_dir/peer.env' --entrypoint /bin/sh '$peer_image' -ec 'iptables -I INPUT -i eth0 -p udp -j REJECT; iptables -I OUTPUT -o eth0 -p udp -j REJECT; exec /usr/local/bin/netbird-entrypoint.sh'" \
  >/dev/null

printf 'Phase: create the K3s workload and its UDP-blocked NetBird sidecar.\n'
python3 - "$work/deployment.yaml" "$namespace" "$domain" "$public_ip" "$peer_image" "$web_image" "$run_id" <<'PY'
import sys

path, namespace, domain, public_ip, peer_image, web_image, run_id = sys.argv[1:]
body = f"NANPANEL_P0_NETBIRD_CROSS_HOST_{run_id}"
yaml = f'''apiVersion: v1
kind: Namespace
metadata:
  name: {namespace}
  labels:
    app.kubernetes.io/part-of: nanpanel-p0
    pod-security.kubernetes.io/enforce: privileged
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: netbird-http-content
  namespace: {namespace}
data:
  probe: {body!r}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: netbird-k3s-service
  namespace: {namespace}
spec:
  replicas: 1
  revisionHistoryLimit: 0
  selector:
    matchLabels:
      app: netbird-k3s-service
  template:
    metadata:
      labels:
        app: netbird-k3s-service
    spec:
      automountServiceAccountToken: false
      hostAliases:
        - ip: {public_ip}
          hostnames:
            - {domain}
      containers:
        - name: netbird
          image: {peer_image}
          imagePullPolicy: IfNotPresent
          command: ["/bin/sh", "-ec"]
          args:
            - iptables -I INPUT -i eth0 -p udp -j REJECT; iptables -I OUTPUT -o eth0 -p udp -j REJECT; exec /usr/local/bin/netbird-entrypoint.sh
          env:
            - name: NB_MANAGEMENT_URL
              value: https://{domain}:443
            - name: NB_SETUP_KEY
              valueFrom:
                secretKeyRef:
                  name: netbird-setup
                  key: setup-key
            - name: NB_LAZY_CONN
              value: "off"
            - name: HTTP_PROXY
              value: ""
            - name: HTTPS_PROXY
              value: ""
            - name: ALL_PROXY
              value: ""
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              add: [NET_ADMIN, NET_RAW]
          resources:
            requests:
              cpu: 10m
              memory: 32Mi
            limits:
              cpu: 250m
              memory: 128Mi
          volumeMounts:
            - name: tun
              mountPath: /dev/net/tun
            - name: netbird-state
              mountPath: /var/lib/netbird
        - name: http
          image: {web_image}
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 80
          readinessProbe:
            httpGet:
              path: /probe
              port: http
            initialDelaySeconds: 1
            periodSeconds: 2
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
          volumeMounts:
            - name: content
              mountPath: /usr/share/nginx/html/probe
              subPath: probe
              readOnly: true
      volumes:
        - name: tun
          hostPath:
            path: /dev/net/tun
            type: CharDevice
        - name: netbird-state
          emptyDir:
            medium: Memory
        - name: content
          configMap:
            name: netbird-http-content
---
apiVersion: v1
kind: Service
metadata:
  name: netbird-k3s-service
  namespace: {namespace}
spec:
  selector:
    app: netbird-k3s-service
  ports:
    - name: http
      port: 80
      targetPort: http
'''
with open(path, "w", encoding="utf-8") as out:
    out.write(yaml)
PY

ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl create namespace '$namespace' --save-config"
k3s_namespace_created=1
python3 - "$work/setup-key" "$work/secret.yaml" "$namespace" <<'PY'
import base64
import pathlib
import sys

key_path, manifest_path, namespace = sys.argv[1:]
value = base64.b64encode(pathlib.Path(key_path).read_bytes().strip()).decode("ascii")
manifest = f'''apiVersion: v1
kind: Secret
metadata:
  name: netbird-setup
  namespace: {namespace}
type: Opaque
data:
  setup-key: {value}
'''
path = pathlib.Path(manifest_path)
path.write_text(manifest, encoding="utf-8")
path.chmod(0o600)
PY
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' <"$work/secret.yaml"
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' <"$work/deployment.yaml"
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' rollout status deployment/netbird-k3s-service --timeout=180s"

pod_name="$(ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' get pods -l app=netbird-k3s-service -o jsonpath='{.items[0].metadata.name}'")"
[[ -n "$pod_name" ]] || { echo 'K3s NetBird peer pod was not created.' >&2; exit 1; }

docker_peer_status_cmd="for attempt in \$(seq 1 120); do status=\$(docker exec '$docker_peer' netbird status 2>/dev/null || true); if printf '%s\\n' \"\$status\" | grep -q 'Management: Connected' && printf '%s\\n' \"\$status\" | grep -q 'Signal: Connected'; then printf '%s\\n' \"\$status\"; exit 0; fi; sleep 1; done; docker logs --tail=100 '$docker_peer'; exit 1"
if ! docker_peer_status="$(ssh -o BatchMode=yes "$docker_ssh_alias" "$docker_peer_status_cmd")"; then
  echo 'NetBird peer on the NAT Docker host did not connect to management and signal.' >&2
  exit 1
fi
k3s_peer_status_cmd="for attempt in \$(seq 1 120); do status=\$(sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' exec '$pod_name' -c netbird -- netbird status 2>/dev/null || true); if printf '%s\\n' \"\$status\" | grep -q 'Management: Connected' && printf '%s\\n' \"\$status\" | grep -q 'Signal: Connected'; then printf '%s\\n' \"\$status\"; exit 0; fi; sleep 1; done; sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' logs '$pod_name' -c netbird --tail=100; exit 1"
if ! k3s_peer_status="$(ssh -o BatchMode=yes "$k3s_ssh_alias" "$k3s_peer_status_cmd")"; then
  echo 'NetBird peer inside the K3s service pod did not connect to management and signal.' >&2
  exit 1
fi

ssh -o BatchMode=yes "$docker_ssh_alias" \
  "docker exec '$docker_peer' iptables -C INPUT -i eth0 -p udp -j REJECT && docker exec '$docker_peer' iptables -C OUTPUT -o eth0 -p udp -j REJECT"
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' exec '$pod_name' -c netbird -- iptables -C INPUT -i eth0 -p udp -j REJECT && sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' exec '$pod_name' -c netbird -- iptables -C OUTPUT -o eth0 -p udp -j REJECT"

docker_overlay_ip="$(ssh -o BatchMode=yes "$docker_ssh_alias" \
  "docker exec '$docker_peer' ip -4 -o addr show dev wt0" | awk '{split($4,address,"/"); print address[1]}')"
k3s_overlay_ip="$(ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' exec '$pod_name' -c netbird -- ip -4 -o addr show dev wt0" \
  | awk '{split($4,address,"/"); print address[1]}')"
[[ -n "$docker_overlay_ip" && -n "$k3s_overlay_ip" ]] || {
  echo 'Could not read NetBird overlay addresses from both peers.' >&2
  exit 1
}

expected_body="NANPANEL_P0_NETBIRD_CROSS_HOST_${run_id}"
path_verified=0
response_body=''
for _ in $(seq 1 45); do
  response_body="$(ssh -o BatchMode=yes "$docker_ssh_alias" \
    "docker exec '$docker_peer' wget -T 3 -qO- 'http://$k3s_overlay_ip/probe' 2>/dev/null || true")"
  if [[ "$response_body" == "$expected_body" ]]; then
    docker_peer_status="$(ssh -o BatchMode=yes "$docker_ssh_alias" \
      "docker exec '$docker_peer' netbird status -d 2>/dev/null || true")"
    k3s_peer_status="$(ssh -o BatchMode=yes "$k3s_ssh_alias" \
      "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' exec '$pod_name' -c netbird -- netbird status -d 2>/dev/null || true")"
    if grep -Eiq 'Connection type:.*relay' <<<"$docker_peer_status" \
      && grep -Eiq 'Connection type:.*relay' <<<"$k3s_peer_status"; then
      path_verified=1
      break
    fi
  fi
  sleep 1
done
if ((path_verified == 0)); then
  printf 'Cross-host relay path failed. HTTP body: %s\nDocker peer status:\n%s\nK3s peer status:\n%s\n' \
    "${response_body:-}" "${docker_peer_status:-not collected}" "${k3s_peer_status:-not collected}" >&2
  exit 1
fi

printf 'PASS: NetBird %s peers on the NAT Docker host and K3s connected to public control/relay over TCP/443.\n' "$netbird_version"
printf 'PASS: UDP ingress/egress is rejected on eth0 at both peers; both detailed statuses report Relay.\n'
printf 'PASS: HTTP over the relayed overlay reached the K3s workload: body=%s, Docker peer=%s, K3s peer=%s.\n' \
  "$response_body" "$docker_overlay_ip" "$k3s_overlay_ip"
printf 'Test endpoint: https://%s:443 via --resolve to %s; all temporary resources are removed on exit.\n' "$domain" "$public_ip"
