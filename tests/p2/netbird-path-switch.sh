#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NANPANEL_P2_PATH_SWITCH=1 exec bash "$repo_root/tests/p0/netbird-cross-host-udp-blocked.sh"
