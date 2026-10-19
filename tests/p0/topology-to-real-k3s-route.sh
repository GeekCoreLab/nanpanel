#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

public_ssh_alias="${NANPANEL_P0_PUBLIC_SSH_ALIAS:-bj-aliyun}"
docker_ssh_alias="${NANPANEL_P0_DOCKER_SSH_ALIAS:-bt-main}"
k3s_ssh_alias="${NANPANEL_P0_K3S_SSH_ALIAS:-k3s-1}"
public_ip="${NANPANEL_P0_PUBLIC_IP:-39.106.185.211}"
k3s_node_ip="${NANPANEL_P0_K3S_NODE_IP:-10.100.0.1}"
frpc_image="${NANPANEL_P0_FRPC_IMAGE:-ghcr.io/fatedier/frpc:v0.68.1}"
http_image="${NANPANEL_P0_K3S_HTTP_IMAGE:-docker.1ms.run/nginx:latest}"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
namespace="nanpanel-p0-real-${run_id}"
domain="nanpanel-p0-${run_id}.geekcorelab.cn"
remote_dir="/tmp/nanpanel-p0-real-${run_id}"
frpc_container="nanpanel-p0-frpc-${run_id}"
nginx_file="/www/server/panel/vhost/nginx/nanpanel-p0-${run_id}.conf"
work="$(mktemp -d "${TMPDIR:-/tmp}/nanpanel-p0-real.XXXXXX")"
chmod 700 "$work"
state_dir="$work/frp-state"
api_pid=''
vite_pid=''
namespace_created=0
container_created=0
remote_dir_created=0
vhost_created=0

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [[ -n "$vite_pid" ]]; then kill "$vite_pid" >/dev/null 2>&1; wait "$vite_pid" >/dev/null 2>&1; fi
  if [[ -n "$api_pid" ]]; then kill "$api_pid" >/dev/null 2>&1; wait "$api_pid" >/dev/null 2>&1; fi
  if ((container_created)); then ssh -o BatchMode=yes "$docker_ssh_alias" "docker rm -f '$frpc_container'" >/dev/null 2>&1; fi
  if ((remote_dir_created)); then ssh -o BatchMode=yes "$docker_ssh_alias" "rm -rf '$remote_dir'" >/dev/null 2>&1; fi
  if ((vhost_created)); then
    ssh -o BatchMode=yes "$public_ssh_alias" "sudo -n rm -f '$nginx_file'" >/dev/null 2>&1
    ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n nginx -t' >/dev/null 2>&1
    ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n systemctl reload nginx' >/dev/null 2>&1
  fi
  if ((namespace_created)); then
    ssh -o BatchMode=yes "$k3s_ssh_alias" "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl delete namespace '$namespace' --wait=true --timeout=120s" >/dev/null 2>&1
  fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in ssh scp python3 node curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required command: $tool" >&2; exit 1; }
done
for value in "$public_ssh_alias" "$docker_ssh_alias" "$k3s_ssh_alias"; do
  [[ "$value" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "Invalid SSH alias: $value" >&2; exit 1; }
done
for image in "$frpc_image" "$http_image"; do
  [[ "$image" =~ ^[A-Za-z0-9._/:@+-]+$ ]] || { echo "Invalid image reference: $image" >&2; exit 1; }
done
[[ "$public_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "Invalid public IPv4: $public_ip" >&2; exit 1; }
[[ "$k3s_node_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "Invalid K3s node IPv4: $k3s_node_ip" >&2; exit 1; }

node_status="$(ssh -o BatchMode=yes "$k3s_ssh_alias" 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get nodes --no-headers' | awk 'NR == 1 {print $2}')"
[[ "$node_status" == 'Ready' ]] || { echo "K3s node is not Ready: $node_status" >&2; exit 1; }

used_node_ports="$(ssh -o BatchMode=yes "$k3s_ssh_alias" 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get services -A -o json' \
  | python3 -c 'import json,sys; data=json.load(sys.stdin); print(" ".join(str(p["nodePort"]) for s in data["items"] for p in s.get("spec",{}).get("ports",[]) if p.get("nodePort")))')"
node_port="$(python3 - "$used_node_ports" <<'PY'
import sys
used = set(sys.argv[1].split())
for candidate in range(31000, 32768):
    if str(candidate) not in used:
        print(candidate)
        break
PY
)"
[[ -n "$node_port" ]] || { echo 'No free K3s NodePort found in 31000-32767.' >&2; exit 1; }

used_remote_ports="$(ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n ss -H -ltn' \
  | awk '{print $4}' | sed -nE 's/.*:([0-9]+)$/\1/p')"
remote_port=''
for candidate in $(seq 40081 41080); do
  if ! grep -qx "$candidate" <<<"$used_remote_ports"; then remote_port="$candidate"; break; fi
done
[[ -n "$remote_port" ]] || { echo 'No free FRP TCP proxy port found in 40081-41080.' >&2; exit 1; }

cat >"$work/k3s-fixture.yaml" <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $namespace
  labels:
    app.kubernetes.io/part-of: nanpanel-p0
    nanpanel-p0.run-id: "$run_id"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: p0-k3s-http-content
  namespace: $namespace
data:
  index.html: nanpanel-p0-real-k3s-service
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: p0-k3s-http
  namespace: $namespace
spec:
  replicas: 1
  revisionHistoryLimit: 0
  selector:
    matchLabels:
      app: p0-k3s-http
  template:
    metadata:
      labels:
        app: p0-k3s-http
    spec:
      containers:
        - name: nginx
          image: $http_image
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 80
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
          volumeMounts:
            - name: content
              mountPath: /usr/share/nginx/html/index.html
              subPath: index.html
              readOnly: true
      volumes:
        - name: content
          configMap:
            name: p0-k3s-http-content
---
apiVersion: v1
kind: Service
metadata:
  name: p0-k3s-http
  namespace: $namespace
spec:
  type: NodePort
  selector:
    app: p0-k3s-http
  ports:
    - name: http
      port: 80
      targetPort: http
      nodePort: $node_port
EOF

if ssh -o BatchMode=yes "$k3s_ssh_alias" "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get namespace '$namespace' -o name" >/dev/null 2>&1; then
  echo "K3s namespace already exists: $namespace" >&2
  exit 1
fi
namespace_created=1
ssh -o BatchMode=yes "$k3s_ssh_alias" 'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' <"$work/k3s-fixture.yaml"
ssh -o BatchMode=yes "$k3s_ssh_alias" "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' rollout status deployment/p0-k3s-http --timeout=120s"
direct_body="$(ssh -o BatchMode=yes "$docker_ssh_alias" "curl --noproxy '*' --silent --show-error --fail --max-time 8 http://$k3s_node_ip:$node_port/")"
[[ "$direct_body" == 'nanpanel-p0-real-k3s-service' ]] || { echo "NAT Docker host could not reach K3s Service: $direct_body" >&2; exit 1; }

ssh -o BatchMode=yes "$public_ssh_alias" "sudo -n awk -F'\"' '/^[[:space:]]*auth\\.token[[:space:]]*=/{print \$2; exit}' /root/frp/frps.toml" >"$work/frp-token"
chmod 600 "$work/frp-token"
python3 - "$work/frpc-original.toml" "$work/frp-token" "$public_ip" <<'PY'
import json
from pathlib import Path
import sys

config_path = Path(sys.argv[1])
token_path = Path(sys.argv[2])
server_addr = sys.argv[3]
token = token_path.read_text().strip()
if not token or any(char in token for char in "\r\n"):
    raise SystemExit("FRP auth token is missing or malformed")
content = (
    f'serverAddr = {json.dumps(server_addr)}\n'
    'serverPort = 37000\n\n'
    '[auth]\n'
    'method = "token"\n'
    f'token = {json.dumps(token)}\n'
)
config_path.write_text(content, encoding="utf-8")
config_path.chmod(0o600)
print("prepared isolated FRPC baseline")
PY
python3 tests/p0/frp-transaction.py import --source "$work/frpc-original.toml" --state-dir "$state_dir"

ssh -o BatchMode=yes "$docker_ssh_alias" "test ! -e '$remote_dir'"
remote_dir_created=1
ssh -o BatchMode=yes "$docker_ssh_alias" "umask 077; mkdir -m 700 -p '$remote_dir'"
scp -q "$work/frpc-original.toml" "$docker_ssh_alias:$remote_dir/original.toml"
scp -q "$work/frpc-original.toml" "$docker_ssh_alias:$remote_dir/frpc.toml"
ssh -o BatchMode=yes "$docker_ssh_alias" "chmod 600 '$remote_dir/original.toml' '$remote_dir/frpc.toml'; docker pull '$frpc_image'"
ssh -o BatchMode=yes "$docker_ssh_alias" "docker run --rm --network host --volume '$remote_dir:/p0:ro' --entrypoint frpc '$frpc_image' verify -c /p0/frpc.toml"
if ssh -o BatchMode=yes "$docker_ssh_alias" "docker container inspect '$frpc_container' >/dev/null 2>&1"; then
  echo "FRPC container already exists: $frpc_container" >&2
  exit 1
fi
container_created=1
ssh -o BatchMode=yes "$docker_ssh_alias" "docker run -d --name '$frpc_container' --network host --restart=no --volume '$remote_dir:/p0:rw' --entrypoint frpc '$frpc_image' -c /p0/frpc.toml" >/dev/null
logged_in=0
for _ in $(seq 1 40); do
  if ssh -o BatchMode=yes "$docker_ssh_alias" "docker logs '$frpc_container' 2>&1" | grep -q 'login to server success'; then logged_in=1; break; fi
  sleep 0.5
done
if ((logged_in == 0)); then
  ssh -o BatchMode=yes "$docker_ssh_alias" "docker logs --tail=80 '$frpc_container'" >&2
  echo 'Temporary FRPC did not authenticate to the existing FRPS.' >&2
  exit 1
fi

cat >"$work/nginx-vhost.conf" <<'NGINX'
server {
    listen 443 ssl;
    server_name @DOMAIN@;
    ssl_certificate /www/server/panel/vhost/cert/geekcorelab.cn/fullchain.pem;
    ssl_certificate_key /www/server/panel/vhost/cert/geekcorelab.cn/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:@REMOTE_PORT@;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX
sed -i "s/@DOMAIN@/$domain/g; s/@REMOTE_PORT@/$remote_port/g" "$work/nginx-vhost.conf"
ssh -o BatchMode=yes "$public_ssh_alias" "test ! -e '$nginx_file'"
vhost_created=1
ssh -o BatchMode=yes "$public_ssh_alias" "sudo -n tee '$nginx_file' >/dev/null" <"$work/nginx-vhost.conf"
ssh -o BatchMode=yes "$public_ssh_alias" "sudo -n chmod 600 '$nginx_file'"
ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n nginx -t'
ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n systemctl reload nginx'

NANPANEL_P0_API_PORT=4180 \
NANPANEL_P0_REMOTE_FRPC_CONTAINER="$frpc_container" \
NANPANEL_P0_FRP_STATE="$state_dir" \
NANPANEL_P0_FRP_REMOTE_PORT="$remote_port" \
NANPANEL_P0_REAL_REMOTE_ROUTE=1 \
NANPANEL_P0_REMOTE_SSH_ALIAS="$docker_ssh_alias" \
NANPANEL_P0_REMOTE_FRPC_DIR="$remote_dir" \
NANPANEL_P0_PUBLIC_URL="https://$domain/" \
NANPANEL_P0_PUBLIC_RESOLVE="$domain:443:$public_ip" \
NANPANEL_P0_EXPECTED_BODY='nanpanel-p0-real-k3s-service' \
NANPANEL_P0_SERVICE_LOCAL_IP="$k3s_node_ip" \
NANPANEL_P0_SERVICE_LOCAL_PORT="$node_port" \
  node "$repo_root/tests/p0/topology-lab-api.mjs" >"$work/api.log" 2>&1 &
api_pid=$!
VITE_API_MODE=live VITE_P0_LAB=true \
  node "$repo_root/web/node_modules/vite/bin/vite.js" "$repo_root/web" \
  --config "$repo_root/web/vite.p0.config.ts" >"$work/vite.log" 2>&1 &
vite_pid=$!

ready=0
for _ in $(seq 1 60); do
  if curl --noproxy '*' -fsS http://127.0.0.1:4180/api/v1/topology/draft >/dev/null 2>&1 \
      && curl --noproxy '*' -fsS http://127.0.0.1:4181/p0-topology.html >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$api_pid" 2>/dev/null || ! kill -0 "$vite_pid" 2>/dev/null; then break; fi
  sleep 0.5
done
if ((ready == 0)); then
  cat "$work/api.log" "$work/vite.log" >&2
  echo 'P0 topology API or Vite page did not start.' >&2
  exit 1
fi

NANPANEL_P0_TOPOLOGY_URL=http://127.0.0.1:4181/p0-topology.html \
NANPANEL_P0_EXPECTED_ROUTE="https://$domain/" \
NANPANEL_P0_EXPECTED_BODY='nanpanel-p0-real-k3s-service' \
  node "$repo_root/web/scripts/p0-topology-route.mjs"

echo "PASS: React Flow applied a route through public HTTPS → existing FRPS → NAT Docker host → K3s Service; domain=$domain port=$node_port remotePort=$remote_port"
