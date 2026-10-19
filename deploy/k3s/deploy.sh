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

auth_file=$(mktemp)
trap 'rm -f "$auth_file"' EXIT
ghcr_auth=$(printf '%s:%s' "$ghcr_username" "$ghcr_token" | base64 | tr -d '\n')
printf '{"auths":{"ghcr.io":{"auth":"%s"}}}\n' "$ghcr_auth" > "$auth_file"
chmod 600 "$auth_file"
for image in "ghcr.io/geekcorelab/nanpanel-core:${image_tag}" "ghcr.io/geekcorelab/nanpanel-web:${image_tag}"; do
    REGISTRY_AUTH_FILE="$auth_file" podman manifest inspect "$image" >/dev/null
done
unset ghcr_token

apply_file() {
    sed "s/__NANPANEL_IMAGE_TAG__/${image_tag}/g" "$1" |
        ssh "$ssh_target" kubectl apply -f -
}

apply_file "$script_dir/namespace.yaml"
apply_file "$script_dir/config.yaml"

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

apply_file "$script_dir/postgres.yaml"
ssh "$ssh_target" kubectl -n nanpanel rollout status statefulset/nanpanel-postgres --timeout=180s
apply_file "$script_dir/core.yaml"
apply_file "$script_dir/web.yaml"
ssh "$ssh_target" kubectl -n nanpanel rollout status deployment/nanpanel-core --timeout=240s
ssh "$ssh_target" kubectl -n nanpanel rollout status deployment/nanpanel-web --timeout=180s
ssh "$ssh_target" kubectl -n nanpanel get pods,svc,pvc -o wide
