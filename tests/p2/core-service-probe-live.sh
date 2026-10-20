#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

core_url="${NANPANEL_P2_CORE_URL:-}"
username="${NANPANEL_P2_ADMIN_USERNAME:-admin}"
password="${NANPANEL_P2_ADMIN_PASSWORD:-}"
cluster_id="${NANPANEL_P2_CLUSTER_ID:-k3s-1}"
namespace="${NANPANEL_P2_NAMESPACE:-nanpanel-mesh}"
service_name="${NANPANEL_P2_PROBE_SERVICE:-mesh-probe-target}"
target_port="${NANPANEL_P2_PROBE_PORT:-8080}"

[[ "$core_url" == https://* && "$core_url" != */ ]] || {
  echo 'Set NANPANEL_P2_CORE_URL to the HTTPS Core origin without a trailing slash.' >&2
  exit 2
}
[[ -n "$password" ]] || { echo 'Set NANPANEL_P2_ADMIN_PASSWORD.' >&2; exit 2; }
[[ "$cluster_id" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$ ]] || {
  echo 'NANPANEL_P2_CLUSTER_ID is invalid.' >&2
  exit 2
}
[[ "$namespace" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || {
  echo 'NANPANEL_P2_NAMESPACE is invalid.' >&2
  exit 2
}
[[ "$service_name" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || {
  echo 'NANPANEL_P2_PROBE_SERVICE is invalid.' >&2
  exit 2
}
[[ "$target_port" =~ ^[0-9]{1,5}$ ]] && ((target_port >= 1 && target_port <= 65535)) || {
  echo 'NANPANEL_P2_PROBE_PORT must be from 1 to 65535.' >&2
  exit 2
}
for tool in curl jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing required command: $tool" >&2; exit 1; }
done

work="$(mktemp -d "${TMPDIR:-/tmp}/nanpanel-p2-core-probe.XXXXXX")"
chmod 700 "$work"
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

umask 077
python3 - "$work/login.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump({
        "username": os.environ.get("NANPANEL_P2_ADMIN_USERNAME", "admin"),
        "password": os.environ["NANPANEL_P2_ADMIN_PASSWORD"],
    }, handle)
PY
chmod 600 "$work/login.json"

curl --noproxy '*' --silent --show-error --fail --max-time 15 \
  --cookie-jar "$work/cookies" \
  "$core_url/api/v1/auth/csrf" -o "$work/csrf.json"
csrf="$(jq -er '.csrfToken' "$work/csrf.json")"
curl --noproxy '*' --silent --show-error --fail --max-time 15 \
  --cookie "$work/cookies" --cookie-jar "$work/cookies" \
  --header "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data-binary "@$work/login.json" \
  "$core_url/api/v1/auth/login" -o "$work/login-response.json"
csrf="$(jq -er '.csrfToken' "$work/login-response.json")"
chmod 600 "$work/cookies"

inventory_path="$work/inventory.json"
probes_path="$work/probes.json"
target_path="$work/probe-target.json"
expected_source="kubernetes:${cluster_id}"
accepted=0
for attempt in 1 2 3 4 5; do
  curl --noproxy '*' --silent --show-error --fail --max-time 30 \
    --cookie "$work/cookies" --cookie-jar "$work/cookies" \
    --header "X-CSRF-Token: $csrf" -H 'Content-Length: 0' -X POST \
    "$core_url/api/v1/kubernetes/service-inventory/refresh" -o "$inventory_path"
  jq -e --arg source "$expected_source" --arg namespace "$namespace" \
    '.sourceId == $source and .namespace == $namespace and (.observedAt | type == "string")' \
    "$inventory_path" >/dev/null || {
      echo 'Core returned an unexpected inventory source or namespace.' >&2
      exit 1
    }
  jq -ce --arg service "$service_name" --argjson port "$target_port" '
    [.services[] | select(.serviceName == $service) | .endpoints[] |
      select(.ready == true and (.addressType == "IPv4" or .addressType == "IPv6")
        and .targetKind == "Pod" and (.targetName | type == "string")
        and any(.ports[]; .protocol == "TCP" and .port == $port))] | first
  ' "$inventory_path" >"$target_path" || {
    echo 'Core inventory has no Ready private TCP EndpointSlice target for the requested fixture.' >&2
    exit 1
  }
  endpoint_address="$(jq -er '.address' "$target_path")"
  inventory_observed="$(jq -er '.observedAt' "$inventory_path")"

  curl --noproxy '*' --silent --show-error --fail --max-time 15 \
    --cookie "$work/cookies" --cookie-jar "$work/cookies" \
    "$core_url/api/v1/kubernetes/service-probes" -o "$probes_path"
  if jq -e --arg source "$expected_source" --arg service "$service_name" \
    --arg address "$endpoint_address" --argjson port "$target_port" \
    --arg inventory_observed "$inventory_observed" '
      .enabled == true and .sourceId == $source and
      any(.probes[]; .serviceName == $service and .address == $address
        and .port == $port and .protocol == "TCP" and .outcome == "SUCCEEDED"
        and (.observedAt | type == "string")
        and .inventoryObservedAt == $inventory_observed)
    ' "$probes_path" >/dev/null; then
    accepted=1
    break
  fi
  sleep 2
done

if [[ "$accepted" != 1 ]]; then
  echo 'Core did not persist a successful TCP probe matching the Ready inventory endpoint.' >&2
  exit 1
fi

target_pod="$(jq -er '.targetName' "$target_path")"
probe_observed="$(jq -er --arg service "$service_name" --arg address "$endpoint_address" \
  --argjson port "$target_port" '
  .probes[] | select(.serviceName == $service and .address == $address and .port == $port
    and .protocol == "TCP" and .outcome == "SUCCEEDED") | .observedAt
' "$probes_path")"
printf 'PASS: Core source %s discovered %s/%s Pod %s on private TCP/%s and persisted SUCCEEDED; inventoryObservedAt=%s, probeObservedAt=%s. Endpoint IP was checked but omitted from output.\n' \
  "$expected_source" "$namespace" "$service_name" "$target_pod" "$target_port" \
  "$inventory_observed" "$probe_observed"
