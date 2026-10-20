# NetBird path-switch run with STUN enabled

- Started: `2026-10-20T07:56:49Z`
- Command: `bash tests/p2/netbird-path-switch.sh`
- Result: exit status 1 at the initial P2P gate
- Server STUN: `Available` from both peers on UDP/3478
- UDP reject rules: not installed
- Application request: expected fixture body returned while path was Relay
- Acceptance: failed; no direct → Relay → direct transition was demonstrated

Sanitized detailed peer status:

| Field | Docker-host peer | K3s peer |
| --- | --- | --- |
| Management | Connected | Connected |
| Signal | Connected | Connected |
| STUN | Available | Available |
| Connection type | Relayed | Relayed |
| ICE candidate pair | None (`-/-`) | None (`-/-`) |

The temporary peer logs reported exhausted ICE retries. The script printed a
cleanup warning; follow-up checks confirmed the temporary K3s namespace,
containers, state directories, reverse-proxy vhost, and UDP/3478 runtime rule
were gone. Nginx configuration validation passed and the service remained
active. No permanent firewall rule or DNS record was added.
