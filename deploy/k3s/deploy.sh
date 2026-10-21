#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ssh_target=${K3S_SSH_TARGET:-k3s-1}
image_tag=${NANPANEL_IMAGE_TAG:?Set NANPANEL_IMAGE_TAG to the successful Actions tag, for example sha-<full-commit>}
ghcr_username=${GHCR_USERNAME:?Set GHCR_USERNAME}
ghcr_token=${GHCR_TOKEN:?Set GHCR_TOKEN to a token with read:packages}

if [[ ! "$image_tag" =~ ^sha-[0-9a-f]{40}$ ]]; then
    printf 'Expected immutable tag sha- followed by 40 lowercase hex characters.\n' >&2
    exit 2
fi

tls_host=${NANPANEL_TLS_HOST:-10.100.0.1}
if [[ "$tls_host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    tls_san="IP:${tls_host}"
elif [[ "$tls_host" =~ ^[A-Za-z0-9.-]+$ ]]; then
    tls_san="DNS:${tls_host}"
else
    printf 'NANPANEL_TLS_HOST must be an IPv4 address or DNS name.\n' >&2
    exit 2
fi

auth_file=$(mktemp)
tls_dir=$(mktemp -d)
chmod 700 "$tls_dir"
trap 'rm -f "$auth_file"; if [[ -d "$tls_dir" ]]; then rm -f "$tls_dir"/*; rmdir "$tls_dir" 2>/dev/null || true; fi' EXIT
ghcr_auth=$(printf '%s:%s' "$ghcr_username" "$ghcr_token" | base64 | tr -d '\n')
printf '{"auths":{"ghcr.io":{"auth":"%s"}}}\n' "$ghcr_auth" > "$auth_file"
chmod 600 "$auth_file"
for image in "ghcr.io/geekcorelab/nanpanel-core:${image_tag}" "ghcr.io/geekcorelab/nanpanel-web:${image_tag}"; do
    REGISTRY_AUTH_FILE="$auth_file" podman manifest inspect "$image" >/dev/null
done
unset ghcr_token

apply_file() {
    sed -e "s|__NANPANEL_IMAGE_TAG__|${image_tag}|g" \
        -e "s|__NANPANEL_TLS_HOST__|${tls_host}|g" "$1" |
        ssh "$ssh_target" kubectl apply -f -
}

apply_file "$script_dir/namespace.yaml"
apply_file "$script_dir/config.yaml"
apply_file "$script_dir/topology-inventory-rbac.yaml"

docker_config=$(printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$ghcr_auth" | base64 | tr -d '\n')
cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: ghcr-pull
  namespace: nanpanel
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: ${docker_config}
EOF
unset GHCR_TOKEN ghcr_username ghcr_auth docker_config

if ! ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-db >/dev/null 2>&1; then
    db_password=$(openssl rand -hex 32)
    admin_password=$(openssl rand -hex 24)
    db_encoded=$(printf '%s' "$db_password" | base64 | tr -d '\n')
    admin_encoded=$(printf '%s' "$admin_password" | base64 | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-db
  namespace: nanpanel
type: Opaque
data:
  DB_PASSWORD: ${db_encoded}
---
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-admin-bootstrap
  namespace: nanpanel
type: Opaque
data:
  NANPANEL_ADMIN_PASSWORD: ${admin_encoded}
EOF
    printf '\nInitial admin login: admin\nInitial admin password (change it at first login): %s\n\n' "$admin_password"
    unset db_password admin_password db_encoded admin_encoded
fi

tls_present=false
tls_rotated=false
if ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-web-tls >/dev/null 2>&1; then
    tls_present=true
    ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-web-tls \
        -o 'jsonpath={.data.tls\\.crt}' | base64 --decode > "$tls_dir/existing-tls.crt"
    if ! openssl x509 -in "$tls_dir/existing-tls.crt" -noout -ext basicConstraints |
        grep -q 'CA:FALSE' ||
        ! openssl x509 -in "$tls_dir/existing-tls.crt" -noout -ext extendedKeyUsage |
            grep -q 'TLS Web Server Authentication'; then
        tls_rotated=true
    fi
fi

if [[ "$tls_present" != true || "$tls_rotated" == true ]]; then
    openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
        -days 825 -keyout "$tls_dir/tls.key" -out "$tls_dir/tls.crt" \
        -subj "/CN=${tls_host}" \
        -addext "basicConstraints=critical,CA:FALSE" \
        -addext "keyUsage=critical,digitalSignature" \
        -addext "extendedKeyUsage=serverAuth" \
        -addext "subjectAltName=${tls_san}" >/dev/null 2>&1
    chmod 600 "$tls_dir/tls.key"
    cert_data=$(base64 < "$tls_dir/tls.crt" | tr -d '\n')
    key_data=$(base64 < "$tls_dir/tls.key" | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-web-tls
  namespace: nanpanel
type: kubernetes.io/tls
data:
  tls.crt: ${cert_data}
  tls.key: ${key_data}
EOF
    unset cert_data key_data tls_san
    tls_rotated=true
fi
unset tls_san

if ! ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-agent-ca >/dev/null 2>&1; then
    openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
        -days 3650 -keyout "$tls_dir/agent-ca.key" -out "$tls_dir/agent-ca.crt" \
        -subj "/CN=NaNPanel Agent CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1
    chmod 600 "$tls_dir/agent-ca.key"
    agent_ca_cert=$(base64 < "$tls_dir/agent-ca.crt" | tr -d '\n')
    agent_ca_key=$(base64 < "$tls_dir/agent-ca.key" | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-agent-ca
  namespace: nanpanel
type: Opaque
data:
  ca.crt: ${agent_ca_cert}
  ca.key: ${agent_ca_key}
EOF
    unset agent_ca_cert agent_ca_key
fi

if ! ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-agent-gateway >/dev/null 2>&1; then
    gateway_token=$(openssl rand -hex 32)
    gateway_token_encoded=$(printf '%s' "$gateway_token" | base64 | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-agent-gateway
  namespace: nanpanel
type: Opaque
data:
  NANPANEL_AGENT_GATEWAY_TOKEN: ${gateway_token_encoded}
EOF
    unset gateway_token gateway_token_encoded
fi

if ! ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-ssh-credentials-key >/dev/null 2>&1; then
    ssh_credentials_key=$(openssl rand -base64 32)
    ssh_credentials_key_encoded=$(printf '%s' "$ssh_credentials_key" | base64 | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-ssh-credentials-key
  namespace: nanpanel
type: Opaque
data:
  NANPANEL_SSH_CREDENTIAL_KEY: ${ssh_credentials_key_encoded}
EOF
    unset ssh_credentials_key ssh_credentials_key_encoded
fi

if ! ssh "$ssh_target" kubectl -n nanpanel get secret nanpanel-kubernetes-credential-key >/dev/null 2>&1; then
    kubernetes_credential_key=$(openssl rand -base64 32)
    kubernetes_credential_key_encoded=$(printf '%s' "$kubernetes_credential_key" | base64 | tr -d '\n')
    cat <<EOF | ssh "$ssh_target" kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: nanpanel-kubernetes-credential-key
  namespace: nanpanel
type: Opaque
data:
  NANPANEL_KUBERNETES_CREDENTIAL_KEY: ${kubernetes_credential_key_encoded}
EOF
    unset kubernetes_credential_key kubernetes_credential_key_encoded
fi

apply_file "$script_dir/postgres.yaml"
ssh "$ssh_target" kubectl -n nanpanel rollout status statefulset/nanpanel-postgres --timeout=180s
apply_file "$script_dir/core.yaml"
apply_file "$script_dir/web.yaml"
if [[ "$tls_rotated" == true ]]; then
    ssh "$ssh_target" kubectl -n nanpanel rollout restart \
        deployment/nanpanel-core deployment/nanpanel-web
fi
ssh "$ssh_target" kubectl -n nanpanel rollout status deployment/nanpanel-core --timeout=240s
ssh "$ssh_target" kubectl -n nanpanel rollout status deployment/nanpanel-web --timeout=180s
if ssh "$ssh_target" systemctl is-enabled nanpanel-agent.service >/dev/null 2>&1; then
    ssh "$ssh_target" bash -s <<'REMOTE'
set -Eeuo pipefail
agent=/usr/local/bin/nanpanel-agent
pod=deployment/nanpanel-core
container=core
image_sha=$(kubectl -n nanpanel exec "$pod" -c "$container" -- sha256sum /work/nanpanel-agent | awk '{print $1}')
current_sha=$(sudo -n sha256sum "$agent" | awk '{print $1}')
if [[ ! "$image_sha" =~ ^[0-9a-f]{64}$ || ! "$current_sha" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'Unable to verify Agent binary hashes.\n' >&2
    exit 1
fi
if [[ "$image_sha" != "$current_sha" ]]; then
    backup="/var/lib/nanpanel-agent/nanpanel-agent.pre-${current_sha}"
    if ! sudo -n test -e "$backup"; then
        sudo -n cp --preserve=mode,ownership "$agent" "$backup"
    fi
    temporary=$(sudo -n mktemp /usr/local/bin/.nanpanel-agent.XXXXXX)
    trap 'sudo -n rm -f -- "$temporary"' EXIT
    kubectl -n nanpanel exec "$pod" -c "$container" -- cat /work/nanpanel-agent |
        sudo -n install -o root -g root -m 0755 /dev/stdin "$temporary"
    downloaded_sha=$(sudo -n sha256sum "$temporary" | awk '{print $1}')
    if [[ "$downloaded_sha" != "$image_sha" ]] ||
        ! sudo -n python3 - "$temporary" <<'PY'
import pathlib
import sys

if pathlib.Path(sys.argv[1]).read_bytes()[:4] != b"\x7fELF":
    raise SystemExit("Agent image artifact is not a Linux executable")
PY
    then
        printf 'Downloaded Agent binary failed verification.\n' >&2
        exit 1
    fi
    sudo -n mv -f -- "$temporary" "$agent"
    trap - EXIT
    if ! sudo -n systemctl restart nanpanel-agent.service; then
        sudo -n cp --preserve=mode,ownership "$backup" "$temporary"
        sudo -n mv -f -- "$temporary" "$agent"
        sudo -n systemctl restart nanpanel-agent.service || true
        exit 1
    fi
    sudo -n systemctl is-active --quiet nanpanel-agent.service
    printf 'Updated nanpanel-agent: image sha256=%s, previous binary backed up at %s\n' "$image_sha" "$backup"
else
    printf 'nanpanel-agent already matches the Core image artifact (sha256=%s)\n' "$image_sha"
fi
REMOTE
fi
if [[ "$tls_rotated" == true ]] &&
    ssh "$ssh_target" systemctl is-enabled nanpanel-agent >/dev/null 2>&1; then
    ssh "$ssh_target" "install -o root -g root -m 0644 /dev/stdin /etc/nanpanel-agent/controller.pem && systemctl restart nanpanel-agent" \
        < "$tls_dir/tls.crt"
fi
ssh "$ssh_target" kubectl -n nanpanel get pods,svc,pvc -o wide
