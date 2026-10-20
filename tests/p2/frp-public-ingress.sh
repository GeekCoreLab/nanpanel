#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
public_ssh_alias=${NANPANEL_P0_PUBLIC_SSH_ALIAS-bj-aliyun}
nat_ssh_alias=${NANPANEL_P0_DOCKER_SSH_ALIAS-bt-main}
k3s_ssh_alias=${NANPANEL_P0_K3S_SSH_ALIAS-k3s-1}
public_ip=${NANPANEL_P0_PUBLIC_IP-39.106.185.211}
k3s_node_ip=${NANPANEL_P0_K3S_NODE_IP-10.100.0.1}
frpc_image=${NANPANEL_P2_FRPC_IMAGE-ghcr.io/fatedier/frpc:v0.68.1}
http_image=${NANPANEL_P0_K3S_HTTP_IMAGE-docker.1ms.run/nginx:latest}
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
namespace="nanpanel-p2-ingress-$run_id"
domain="nanpanel-p2-$run_id.geekcorelab.cn"
remote_dir="/tmp/nanpanel-p2-ingress-$run_id"
frpc_container="nanpanel-p2-frpc-$run_id"
nginx_file="/www/server/panel/vhost/nginx/nanpanel-p2-$run_id.conf"
auth_file="/tmp/nanpanel-p2-$run_id.htpasswd"
work="$(mktemp -d /tmp/nanpanel-p2-ingress.XXXXXX)"
chmod 700 "$work"
namespace_created=0
nat_dir_created=0
public_dir_created=0
frpc_created=0
vhost_created=0
auth_file_created=0

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if ((vhost_created || auth_file_created)); then
    ssh -o BatchMode=yes "$public_ssh_alias" \
      "sudo -n rm -f '$nginx_file' '$auth_file'; sudo -n nginx -t >/dev/null && sudo -n systemctl reload nginx" \
      >/dev/null 2>&1 || status=1
  fi
  if ((frpc_created)); then
    ssh -o BatchMode=yes "$nat_ssh_alias" "$nat_engine rm -f '$frpc_container'" \
      >/dev/null 2>&1 || status=1
  fi
  if ((nat_dir_created)); then
    ssh -o BatchMode=yes "$nat_ssh_alias" "rm -rf -- '$remote_dir'" \
      >/dev/null 2>&1 || status=1
  fi
  if ((public_dir_created)); then
    ssh -o BatchMode=yes "$public_ssh_alias" "rm -rf -- '$remote_dir'" \
      >/dev/null 2>&1 || status=1
  fi
  if ((namespace_created)); then
    ssh -o BatchMode=yes "$k3s_ssh_alias" \
      "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl delete namespace '$namespace' --wait=true --timeout=120s" \
      >/dev/null 2>&1 || status=1
  fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in ssh scp curl openssl python3 awk grep sed sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required command: $tool" >&2; exit 1; }
done
for value in "$public_ssh_alias" "$nat_ssh_alias" "$k3s_ssh_alias"; do
  [[ "$value" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "Invalid SSH alias: $value" >&2; exit 1; }
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$value" true
done
[[ "$public_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "Invalid public IPv4." >&2; exit 1; }
[[ "$k3s_node_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo "Invalid K3s node IPv4." >&2; exit 1; }
[[ "$frpc_image" =~ ^[A-Za-z0-9._/:@+-]+$ && "$http_image" =~ ^[A-Za-z0-9._/:@+-]+$ ]] || {
  echo 'Invalid image reference.' >&2
  exit 1
}

nat_engine="$(ssh -o BatchMode=yes "$nat_ssh_alias" \
  'if command -v docker >/dev/null 2>&1; then printf docker; elif command -v podman >/dev/null 2>&1; then printf podman; else exit 127; fi')"
[[ "$nat_engine" == docker || "$nat_engine" == podman ]] || {
  echo "No Docker-compatible engine found on $nat_ssh_alias." >&2
  exit 1
}
if [[ "$nat_engine" == podman ]] && \
  ! ssh -o BatchMode=yes "$nat_ssh_alias" 'podman info --format "{{.Host.Security.Rootless}}" | grep -qx false'; then
  echo 'This fixture requires rootful Podman for the host-network FRPC container.' >&2
  exit 1
fi

node_ports="$(ssh -o BatchMode=yes "$k3s_ssh_alias" \
  'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get services -A -o json')"
used_node_ports="$(python3 -c 'import json,sys; d=json.loads(sys.stdin.read()); print(" ".join(str(p["nodePort"]) for s in d["items"] for p in s.get("spec",{}).get("ports",[]) if p.get("nodePort"))) ' <<<"$node_ports")"
read -r node_port_a node_port_b < <(python3 - "$used_node_ports" <<'PY'
import sys
used = set(sys.argv[1].split())
available = [str(port) for port in range(31000, 32768) if str(port) not in used]
if len(available) < 2:
    raise SystemExit("No two free K3s NodePorts are available.")
print(*available[:2])
PY
)

used_remote_ports="$(ssh -o BatchMode=yes "$public_ssh_alias" 'sudo -n ss -H -ltn' \
  | awk '{print $4}' | sed -nE 's/.*:([0-9]+)$/\1/p')"
read -r remote_port_a remote_port_b < <(python3 - "$used_remote_ports" <<'PY'
import sys
used = set(sys.argv[1].split())
available = [str(port) for port in range(40081, 41081) if str(port) not in used]
if len(available) < 2:
    raise SystemExit("No two free public FRP proxy ports are available.")
print(*available[:2])
PY
)

if ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get namespace '$namespace' -o name" >/dev/null 2>&1; then
  echo "K3s namespace already exists: $namespace" >&2
  exit 1
fi
namespace_created=1
cat >"$work/k3s-fixture.yaml" <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $namespace
  labels:
    app.kubernetes.io/part-of: nanpanel-p2
    nanpanel-p2.run-id: "$run_id"
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: p2-ingress-content
  namespace: $namespace
data:
  a.html: NANPANEL_P2_INGRESS_BACKEND_A_$run_id
  b.html: NANPANEL_P2_INGRESS_BACKEND_B_$run_id
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ingress-backend-a
  namespace: $namespace
spec:
  replicas: 1
  revisionHistoryLimit: 0
  selector:
    matchLabels:
      app: ingress-backend-a
  template:
    metadata:
      labels:
        app: ingress-backend-a
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
              subPath: a.html
              readOnly: true
      volumes:
        - name: content
          configMap:
            name: p2-ingress-content
---
apiVersion: v1
kind: Service
metadata:
  name: ingress-backend-a
  namespace: $namespace
spec:
  type: NodePort
  selector:
    app: ingress-backend-a
  ports:
    - name: http
      port: 80
      targetPort: http
      nodePort: $node_port_a
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ingress-backend-b
  namespace: $namespace
spec:
  replicas: 1
  revisionHistoryLimit: 0
  selector:
    matchLabels:
      app: ingress-backend-b
  template:
    metadata:
      labels:
        app: ingress-backend-b
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
              subPath: b.html
              readOnly: true
      volumes:
        - name: content
          configMap:
            name: p2-ingress-content
---
apiVersion: v1
kind: Service
metadata:
  name: ingress-backend-b
  namespace: $namespace
spec:
  type: NodePort
  selector:
    app: ingress-backend-b
  ports:
    - name: http
      port: 80
      targetPort: http
      nodePort: $node_port_b
EOF
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  'sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f -' <"$work/k3s-fixture.yaml"
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' rollout status deployment/ingress-backend-a --timeout=180s"
ssh -o BatchMode=yes "$k3s_ssh_alias" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n '$namespace' rollout status deployment/ingress-backend-b --timeout=180s"

body_a="NANPANEL_P2_INGRESS_BACKEND_A_$run_id"
body_b="NANPANEL_P2_INGRESS_BACKEND_B_$run_id"
direct_a="$(ssh -o BatchMode=yes "$nat_ssh_alias" \
  "curl --noproxy '*' --silent --show-error --fail --max-time 8 http://$k3s_node_ip:$node_port_a/")"
direct_b="$(ssh -o BatchMode=yes "$nat_ssh_alias" \
  "curl --noproxy '*' --silent --show-error --fail --max-time 8 http://$k3s_node_ip:$node_port_b/")"
[[ "$direct_a" == "$body_a" && "$direct_b" == "$body_b" ]] || {
  echo 'NAT host did not reach both K3s backend fixtures directly.' >&2
  exit 1
}

ssh -o BatchMode=yes "$public_ssh_alias" \
  "sudo -n awk -F'\"' '/^[[:space:]]*auth\\.token[[:space:]]*=/{print \$2; exit}' /root/frp/frps.toml" \
  >"$work/frp-token"
chmod 600 "$work/frp-token"
python3 - "$work/frpc.toml" "$work/frp-token" "$public_ip" "$k3s_node_ip" \
  "$node_port_a" "$node_port_b" "$remote_port_a" "$remote_port_b" "$run_id" <<'PY'
import json
from pathlib import Path
import sys

path, token_path, public_ip, node_ip, local_a, local_b, remote_a, remote_b, run_id = sys.argv[1:]
token = Path(token_path).read_text(encoding="utf-8").strip()
if not token or any(char in token for char in "\r\n"):
    raise SystemExit("FRP auth token is missing or malformed")
content = (
    f"serverAddr = {json.dumps(public_ip)}\n"
    "serverPort = 37000\n\n"
    "[auth]\nmethod = \"token\"\n"
    f"token = {json.dumps(token)}\n\n"
    "[[proxies]]\n"
    f"name = {json.dumps('nanpanel-p2-a-' + run_id)}\n"
    "type = \"tcp\"\n"
    f"localIP = {json.dumps(node_ip)}\n"
    f"localPort = {local_a}\nremotePort = {remote_a}\n\n"
    "[[proxies]]\n"
    f"name = {json.dumps('nanpanel-p2-b-' + run_id)}\n"
    "type = \"tcp\"\n"
    f"localIP = {json.dumps(node_ip)}\n"
    f"localPort = {local_b}\nremotePort = {remote_b}\n"
)
Path(path).write_text(content, encoding="utf-8")
Path(path).chmod(0o600)
print("prepared two isolated FRP backend routes")
PY

if ssh -o BatchMode=yes "$nat_ssh_alias" "test ! -e '$remote_dir'"; then
  nat_dir_created=1
  ssh -o BatchMode=yes "$nat_ssh_alias" "umask 077 && mkdir -m 700 '$remote_dir'"
else
  echo "Temporary FRP directory already exists: $remote_dir" >&2
  exit 1
fi
if ssh -o BatchMode=yes "$public_ssh_alias" "test ! -e '$remote_dir'"; then
  public_dir_created=1
  ssh -o BatchMode=yes "$public_ssh_alias" "umask 077 && mkdir -m 700 '$remote_dir'"
else
  echo "Temporary public directory already exists: $remote_dir" >&2
  exit 1
fi
scp -q "$work/frpc.toml" "$nat_ssh_alias:$remote_dir/frpc.toml"
ssh -o BatchMode=yes "$nat_ssh_alias" \
  "chmod 600 '$remote_dir/frpc.toml' && $nat_engine pull '$frpc_image'"
ssh -o BatchMode=yes "$nat_ssh_alias" \
  "$nat_engine run --rm --network host --volume '$remote_dir:/p2:ro' --entrypoint frpc '$frpc_image' verify -c /p2/frpc.toml"
if ssh -o BatchMode=yes "$nat_ssh_alias" "$nat_engine container inspect '$frpc_container' >/dev/null 2>&1"; then
  echo "Temporary FRPC container already exists: $frpc_container" >&2
  exit 1
fi
frpc_created=1
ssh -o BatchMode=yes "$nat_ssh_alias" \
  "$nat_engine run -d --name '$frpc_container' --network host --restart=no --volume '$remote_dir:/p2:ro' --entrypoint frpc '$frpc_image' -c /p2/frpc.toml" >/dev/null
logged_in=0
for _ in $(seq 1 40); do
  if ssh -o BatchMode=yes "$nat_ssh_alias" "$nat_engine logs '$frpc_container' 2>&1" \
    | grep -q 'login to server success'; then
    logged_in=1
    break
  fi
  sleep 0.5
done
if ((logged_in == 0)); then
  ssh -o BatchMode=yes "$nat_ssh_alias" "$nat_engine logs --tail=80 '$frpc_container'" >&2
  echo 'Temporary FRPC did not authenticate to the public FRPS.' >&2
  exit 1
fi

password="$(openssl rand -hex 24)"
printf '%s\n' "$password" >"$work/password"
chmod 600 "$work/password"
password_hash="$(openssl passwd -apr1 -stdin <"$work/password")"
printf 'p2user:%s\n' "$password_hash" >"$work/htpasswd"
chmod 600 "$work/htpasswd"
python3 - "$work/curl-auth.conf" "$password" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_text("user = " + json.dumps("p2user:" + sys.argv[2]) + "\n", encoding="utf-8")
path.chmod(0o600)
PY
scp -q "$work/htpasswd" "$public_ssh_alias:$remote_dir/htpasswd"
auth_file_created=1
ssh -o BatchMode=yes "$public_ssh_alias" \
  "sudo -n install -o root -g root -m 0644 '$remote_dir/htpasswd' '$auth_file'"

cat >"$work/nginx-a.conf" <<'NGINX'
server {
    listen 443 ssl;
    server_name @DOMAIN@;
    ssl_certificate /www/server/panel/vhost/cert/geekcorelab.cn/fullchain.pem;
    ssl_certificate_key /www/server/panel/vhost/cert/geekcorelab.cn/privkey.pem;
    auth_basic "NaNPanel P2 ingress fixture";
    auth_basic_user_file @AUTH_FILE@;
    location / {
        proxy_pass http://127.0.0.1:@PORT_A@;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX
sed -i "s|@DOMAIN@|$domain|g; s|@AUTH_FILE@|$auth_file|g; s|@PORT_A@|$remote_port_a|g" "$work/nginx-a.conf"
sed "s|127.0.0.1:$remote_port_a;|127.0.0.1:$remote_port_b;|" \
  "$work/nginx-a.conf" >"$work/nginx-b.conf"

if ssh -o BatchMode=yes "$public_ssh_alias" "test ! -e '$nginx_file'"; then
  vhost_created=1
  scp -q "$work/nginx-a.conf" "$public_ssh_alias:$remote_dir/nginx-a.conf"
  ssh -o BatchMode=yes "$public_ssh_alias" \
    "sudo -n install -o root -g root -m 0600 '$remote_dir/nginx-a.conf' '$nginx_file' && sudo -n nginx -t && sudo -n systemctl reload nginx"
else
  echo "Temporary Nginx vhost already exists: $nginx_file" >&2
  exit 1
fi

public_status() {
  curl --noproxy '*' --silent --show-error --max-time 10 \
    --resolve "$domain:443:$public_ip" --output "$work/unauthorized.body" \
    --write-out '%{http_code}' "https://$domain/"
}
authorized_status() {
  curl --noproxy '*' --silent --show-error --max-time 10 \
    --resolve "$domain:443:$public_ip" --config "$work/curl-auth.conf" \
    --output "$work/authorized.body" --write-out '%{http_code}' \
    "https://$domain/"
}
assert_public_route() {
  local expected="$1"
  local status=''
  local auth_status=''
  local body=''
  for _ in $(seq 1 20); do
    status="$(public_status)" || status=000
    auth_status="$(authorized_status 2>/dev/null)" || auth_status=000
    body="$(cat "$work/authorized.body" 2>/dev/null || true)"
    if [[ "$status" == 401 && "$auth_status" == 200 && "$body" == "$expected" ]]; then
      return 0
    fi
    sleep 0.5
  done
  printf 'Public ingress assertion failed: unauthorized_status=%s authorized_status=%s authorized_body=%s\n' \
    "$status" "$auth_status" "${body:-<empty>}" >&2
  return 1
}
assert_config_hash() {
  local expected actual
  expected="$(sha256sum "$1" | awk '{print $1}')"
  actual="$(ssh -o BatchMode=yes "$public_ssh_alias" \
    "sudo -n sha256sum '$nginx_file' | awk '{print \$1}'")"
  [[ "$actual" == "$expected" ]]
}

assert_public_route "$body_a"
assert_config_hash "$work/nginx-a.conf"
printf 'PASS: public TLS certificate validation succeeded; unauthenticated request returned 401 and authorized request reached backend A.\n'

scp -q "$work/nginx-b.conf" "$public_ssh_alias:$remote_dir/nginx-b.conf"
ssh -o BatchMode=yes "$public_ssh_alias" \
  "sudo -n install -o root -g root -m 0600 '$remote_dir/nginx-b.conf' '$nginx_file' && sudo -n nginx -t && sudo -n systemctl reload nginx"
assert_public_route "$body_b"
assert_config_hash "$work/nginx-b.conf"
printf 'PASS: gateway backend change reached backend B while the access policy remained enforced.\n'

scp -q "$work/nginx-a.conf" "$public_ssh_alias:$remote_dir/nginx-a.conf"
ssh -o BatchMode=yes "$public_ssh_alias" \
  "sudo -n install -o root -g root -m 0600 '$remote_dir/nginx-a.conf' '$nginx_file' && sudo -n nginx -t && sudo -n systemctl reload nginx"
assert_public_route "$body_a"
assert_config_hash "$work/nginx-a.conf"
printf 'PASS: compensation restored backend A and preserved the TLS/access policy.\n'
printf 'PASS: isolated FRP public ingress test completed; run=%s.\n' "$run_id"
