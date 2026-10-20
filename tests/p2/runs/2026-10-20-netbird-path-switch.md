# NetBird path-switch run

- Started: `2026-10-20T07:43:08Z`
- Command: `bash tests/p2/netbird-path-switch.sh`
- Result: interrupted with exit status 130 during the initial P2P wait
- UDP reject rules: not installed yet
- Acceptance: failed; no direct → Relay → direct transition was demonstrated

Sanitized detailed status captured from the temporary peers:

| Field | Docker-host peer | K3s peer |
| --- | --- | --- |
| Management | Connected | Connected |
| Signal | Connected | Connected |
| Connection type | Relayed | Relayed |
| STUN | Checking | Checking |

Cleanup was confirmed after interruption: the temporary K3s namespace, Docker
peer container and state, public endpoint container and state, and temporary
reverse-proxy vhost were removed. No persistent DNS record or production
namespace was changed.
