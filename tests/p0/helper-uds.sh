#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
image=localhost/nanpanel-p0-agent:local
proxy_host=${NANPANEL_P0_PROXY_HOST:-172.16.87.1}
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
podman run --rm --entrypoint /usr/local/bin/helper-smoke "$image"
