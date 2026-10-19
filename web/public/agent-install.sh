#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

controller=${1:?Usage: agent-install.sh HTTPS_ORIGIN HOST_ID AGENT_ID TOKEN FINGERPRINT GATEWAY_PORT [RELAYS FALLBACK_RELAYS SIGNATURE PUBLIC_KEY]}
host_id=${2:?Missing host ID}
agent_id=${3:?Missing agent ID}
enrollment_token=${4:?Missing one-time enrollment token}
expected_fingerprint=${5:?Missing controller certificate fingerprint}
gateway_port=${6:?Missing Agent Gateway port}
relay_endpoints=${7-}
relay_fallback_endpoints=${8-}
relay_fallback_signature=${9-}
relay_fallback_public_key=${10-}
controller=${controller%/}

if [[ "$EUID" -ne 0 ]]; then
    printf 'Run this installer as root (the Hosts page command uses sudo).\n' >&2
    exit 1
fi
if [[ ! "$controller" =~ ^https://[^/[:space:]]+$ ||
      ! "$host_id" =~ ^[0-9a-fA-F-]{36}$ ||
      ! "$agent_id" =~ ^[0-9a-fA-F-]{36}$ ||
      ! "$enrollment_token" =~ ^[A-Za-z0-9_-]{40,128}$ ||
      ! "$expected_fingerprint" =~ ^[a-fA-F0-9]{64}$ ||
      ! "$gateway_port" =~ ^[0-9]{1,5}$ ]] ; then
    printf 'Invalid controller or enrollment parameters. Create a fresh invite in NaNPanel.\n' >&2
    exit 2
fi
if ((gateway_port < 1 || gateway_port > 65535)); then
    printf 'Invalid Agent Gateway port.\n' >&2
    exit 2
fi
if [[ -z "$relay_fallback_endpoints" ]]; then
    if [[ -n "$relay_fallback_signature" || -n "$relay_fallback_public_key" ]]; then
        printf 'Relay fallback signature material requires a non-empty endpoint list.\n' >&2
        exit 2
    fi
elif [[ ! "$relay_fallback_signature" =~ ^[a-fA-F0-9]{128}$ ||
        ! "$relay_fallback_public_key" =~ ^[a-fA-F0-9]{64}$ ]]; then
    printf 'Relay fallback signature or public key is malformed.\n' >&2
    exit 2
fi
case "$(uname -m)" in
    x86_64|amd64) ;;
    *) printf 'This release currently supports Linux x86_64 only.\n' >&2; exit 2 ;;
esac
for command in curl openssl python3 systemctl systemd-run useradd getent install; do
    if ! command -v "$command" >/dev/null 2>&1; then
        printf 'Required command is missing: %s\n' "$command" >&2
        exit 2
    fi
done

mapfile -t network_config < <(python3 - "$controller" "$gateway_port" "$relay_endpoints" "$relay_fallback_endpoints" <<'PY'
import sys
from urllib.parse import urlsplit, urlunsplit

url = urlsplit(sys.argv[1])
if url.scheme != "https" or not url.hostname or url.username or url.password:
    raise SystemExit("controller must be a plain HTTPS origin")
if url.path not in ("", "/") or url.query or url.fragment:
    raise SystemExit("controller must not contain a path, query, or fragment")
hostname = url.hostname
if any(not (char.isalnum() or char in ".-:%") for char in hostname):
    raise SystemExit("controller hostname contains unsupported characters")
host = f"[{hostname}]" if ":" in hostname else hostname
print(hostname)
print(f"https://{host}:{int(sys.argv[2])}")

def normalize_relays(relay_value, label):
    if not relay_value:
        return ""
    relay_urls = relay_value.split(",")
    if not 2 <= len(relay_urls) <= 8:
        raise SystemExit(f"{label} list must contain two to eight URLs")
    normalized = []
    for relay in relay_urls:
        candidate = urlsplit(relay)
        if (candidate.scheme != "https" or not candidate.hostname or
                candidate.username or candidate.password or candidate.path not in ("", "/") or
                candidate.query or candidate.fragment):
            raise SystemExit(f"{label} endpoints must be HTTPS origins")
        relay_host = candidate.hostname.lower()
        if any(not (char.isalnum() or char in ".-:%") for char in relay_host):
            raise SystemExit(f"{label} hostname contains unsupported characters")
        port = candidate.port
        if port is not None and not 1 <= port <= 65535:
            raise SystemExit(f"{label} port is outside the valid range")
        authority = f"[{relay_host}]" if ":" in relay_host else relay_host
        if port is not None and port != 443:
            authority += f":{port}"
        normalized.append(urlunsplit(("https", authority, "", "", "")))
    if len(set(normalized)) != len(normalized):
        raise SystemExit(f"{label} list contains a duplicate origin")
    return ",".join(normalized)

print(normalize_relays(sys.argv[3], "relay"))
print(normalize_relays(sys.argv[4], "signed fallback relay"))
PY
)
if (( ${#network_config[@]} != 4 )); then
    printf 'Unable to parse the controller address.\n' >&2
    exit 2
fi
tls_domain=${network_config[0]}
gateway_endpoint=${network_config[1]}
relay_endpoints=${network_config[2]}
relay_fallback_endpoints=${network_config[3]}

work_dir=$(mktemp -d /tmp/nanpanel-agent-install.XXXXXX)
trap 'rm -rf -- "$work_dir"' EXIT
controller_cert="$work_dir/controller.pem"
curl --insecure --fail --silent --show-error --location \
    --connect-timeout 10 --max-time 30 \
    "$controller/api/v1/agent-controller-cert" -o "$controller_cert"
actual_fingerprint=$(openssl x509 -in "$controller_cert" -noout -fingerprint -sha256 |
    sed 's/.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')
if [[ "$actual_fingerprint" != "${expected_fingerprint,,}" ]]; then
    printf 'Controller TLS fingerprint mismatch; refusing to send the enrollment token.\n' >&2
    exit 1
fi

agent_user=nanpanel-agent
if ! getent passwd "$agent_user" >/dev/null; then
    useradd --system --user-group --home-dir /var/lib/nanpanel-agent \
        --create-home --shell /usr/sbin/nologin "$agent_user"
fi
install -d -o root -g root -m 0755 /etc/nanpanel-agent
install -d -o "$agent_user" -g "$agent_user" -m 0750 /var/lib/nanpanel-agent
install -d -o root -g "$agent_user" -m 0750 /etc/nanpanel/managed
install -d -o root -g root -m 0700 /etc/nanpanel/managed/files
if [[ -L /etc/nanpanel/managed/systemd-units.allowlist ]]; then
    printf 'Refusing a symlinked systemd allowlist.\n' >&2
    exit 1
fi
if [[ ! -e /etc/nanpanel/managed/systemd-units.allowlist ]]; then
    install -o root -g "$agent_user" -m 0640 /dev/null \
        /etc/nanpanel/managed/systemd-units.allowlist
else
    chown root:"$agent_user" /etc/nanpanel/managed/systemd-units.allowlist
    chmod 0640 /etc/nanpanel/managed/systemd-units.allowlist
fi
if [[ -L /etc/nanpanel/managed/journal-units.allowlist ]]; then
    printf 'Refusing a symlinked systemd journal allowlist.\n' >&2
    exit 1
fi
if [[ ! -e /etc/nanpanel/managed/journal-units.allowlist ]]; then
    install -o root -g "$agent_user" -m 0640 /dev/null \
        /etc/nanpanel/managed/journal-units.allowlist
else
    chown root:"$agent_user" /etc/nanpanel/managed/journal-units.allowlist
    chmod 0640 /etc/nanpanel/managed/journal-units.allowlist
fi
for allowlist in packages.allowlist files.allowlist containers.allowlist network.allowlist container-runtime; do
    path="/etc/nanpanel/managed/$allowlist"
    if [[ -L "$path" ]]; then
        printf 'Refusing a symlinked Helper allowlist: %s\n' "$allowlist" >&2
        exit 1
    fi
    if [[ ! -e "$path" ]]; then
        install -o root -g root -m 0600 /dev/null "$path"
    else
        chown root:root "$path"
        chmod 0600 "$path"
    fi
done

curl --cacert "$controller_cert" --fail --silent --show-error --location \
    --connect-timeout 10 --max-time 120 \
    "$controller/api/v1/agent-download/linux-amd64" -o "$work_dir/nanpanel-agent"
python3 - "$work_dir/nanpanel-agent" <<'PY'
import pathlib
import sys

if pathlib.Path(sys.argv[1]).read_bytes()[:4] != b"\x7fELF":
    raise SystemExit("Agent download is not a Linux executable")
PY
install -o root -g root -m 0755 "$work_dir/nanpanel-agent" /usr/local/bin/nanpanel-agent
curl --cacert "$controller_cert" --fail --silent --show-error --location \
    --connect-timeout 10 --max-time 120 \
    "$controller/api/v1/helper-download/linux-amd64" -o "$work_dir/nanpanel-helper"
python3 - "$work_dir/nanpanel-helper" <<'PY'
import pathlib
import sys

if pathlib.Path(sys.argv[1]).read_bytes()[:4] != b"\x7fELF":
    raise SystemExit("Helper download is not a Linux executable")
PY
install -o root -g root -m 0755 "$work_dir/nanpanel-helper" /usr/local/bin/nanpanel-helper

openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
    -keyout "$work_dir/agent.key" -out "$work_dir/agent.csr" \
    -subj "/CN=$agent_id" >/dev/null 2>&1
python3 - "$work_dir/agent.csr" "$work_dir/redeem.json" <<'PY'
import json
import pathlib
import sys

pathlib.Path(sys.argv[2]).write_text(
    json.dumps({"csrPem": pathlib.Path(sys.argv[1]).read_text()}),
    encoding="utf-8",
)
PY
curl --cacert "$controller_cert" --fail --silent --show-error --location \
    --connect-timeout 10 --max-time 60 \
    -H "Authorization: Bearer $enrollment_token" \
    -H 'Content-Type: application/json' \
    --data-binary "@$work_dir/redeem.json" \
    "$controller/api/v1/agent-enrollments/redeem" -o "$work_dir/enrollment.json"
python3 - "$work_dir/enrollment.json" "$agent_id" "$host_id" "$work_dir/agent.crt" <<'PY'
import json
import pathlib
import sys

response = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if response.get("agentId", "").lower() != sys.argv[2].lower():
    raise SystemExit("Enrollment returned a different Agent ID")
if response.get("hostId", "").lower() != sys.argv[3].lower():
    raise SystemExit("Enrollment returned a different host ID")
certificate = response.get("certificatePem")
if not isinstance(certificate, str) or "BEGIN CERTIFICATE" not in certificate:
    raise SystemExit("Enrollment response did not contain an Agent certificate")
pathlib.Path(sys.argv[4]).write_text(certificate, encoding="ascii")
PY

openssl x509 -in "$work_dir/agent.crt" -noout -pubkey |
    openssl pkey -pubin -outform DER > "$work_dir/cert-public.der"
openssl pkey -in "$work_dir/agent.key" -pubout -outform DER > "$work_dir/key-public.der"
if ! cmp -s "$work_dir/cert-public.der" "$work_dir/key-public.der"; then
    printf 'Issued Agent certificate does not match its private key.\n' >&2
    exit 1
fi

install -o root -g root -m 0644 "$controller_cert" /etc/nanpanel-agent/controller.pem
install -o root -g root -m 0644 "$work_dir/agent.crt" /etc/nanpanel-agent/agent.crt
install -o root -g "$agent_user" -m 0640 "$work_dir/agent.key" /etc/nanpanel-agent/agent.key
agent_uid=$(id -u "$agent_user")
agent_gid=$(id -g "$agent_user")
cat > /etc/nanpanel-agent/agent.env <<EOF
NANPANEL_AGENT_ENDPOINT=$gateway_endpoint
NANPANEL_AGENT_RELAY_ENDPOINTS=$relay_endpoints
NANPANEL_AGENT_RELAY_FALLBACK_ENDPOINTS=$relay_fallback_endpoints
NANPANEL_AGENT_RELAY_FALLBACK_SIGNATURE=$relay_fallback_signature
NANPANEL_AGENT_RELAY_FALLBACK_PUBLIC_KEY=${relay_fallback_public_key,,}
NANPANEL_AGENT_TLS_DOMAIN=$tls_domain
NANPANEL_AGENT_ID=$agent_id
NANPANEL_AGENT_CA=/etc/nanpanel-agent/controller.pem
NANPANEL_AGENT_CERT=/etc/nanpanel-agent/agent.crt
NANPANEL_AGENT_KEY=/etc/nanpanel-agent/agent.key
NANPANEL_AGENT_STATE_DIR=/var/lib/nanpanel-agent
NANPANEL_AGENT_HEARTBEAT_SECS=15
EOF
chown root:"$agent_user" /etc/nanpanel-agent/agent.env
chmod 0640 /etc/nanpanel-agent/agent.env

cat > /etc/systemd/system/nanpanel-agent.service <<'UNIT'
[Unit]
Description=NaNPanel host inventory Agent
Requires=nanpanel-helper.service
After=network-online.target nanpanel-helper.service
Wants=network-online.target

[Service]
Type=simple
User=nanpanel-agent
Group=nanpanel-agent
EnvironmentFile=/etc/nanpanel-agent/agent.env
ExecStart=/usr/local/bin/nanpanel-agent
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
CapabilityBoundingSet=
AmbientCapabilities=
ReadOnlyPaths=/etc/nanpanel-agent
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/nanpanel-agent.service
cat > /etc/systemd/system/nanpanel-helper.service <<UNIT
[Unit]
Description=NaNPanel privileged maintenance Helper
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Group=$agent_user
RuntimeDirectory=nanpanel
RuntimeDirectoryMode=0750
ExecStart=/usr/local/bin/nanpanel-helper /run/nanpanel/helper.sock $agent_uid $agent_gid /etc/nanpanel/managed $controller /etc/nanpanel-agent/controller.pem /usr/local/bin/nanpanel-agent
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/usr/local/bin /run/nanpanel
ReadWritePaths=/etc/nanpanel/managed/files
ReadWritePaths=/usr /etc /var
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
CapabilityBoundingSet=CAP_DAC_OVERRIDE CAP_FOWNER CAP_CHOWN
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/nanpanel-helper.service
systemctl daemon-reload
systemctl enable nanpanel-helper.service nanpanel-agent.service
systemctl restart nanpanel-helper.service
systemctl restart nanpanel-agent.service
systemctl --no-pager --full status nanpanel-helper.service
systemctl --no-pager --full status nanpanel-agent.service
printf '\nNaNPanel Agent installed for host %s (Agent %s).\n' "$host_id" "$agent_id"
