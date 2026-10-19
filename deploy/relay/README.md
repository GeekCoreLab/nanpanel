# Standalone Control Relay

This service accepts mTLS connections from Agents and the Core Gateway. It stores live connection routes in memory and persists only the highest accepted Core fencing epoch to its state volume; restarting it drops sessions and clients reconnect without allowing an older Core epoch to take over. It does not hold Cloudflare credentials, execute operations, or access a root Helper.

Create `.env` from `.env.example`, replace every placeholder, and place the Relay certificate, private key, and client CA bundle under `certs/`. The client CA bundle must trust Agent and Core client certificates. `NANPANEL_RELAY_CORE_CERT_FINGERPRINTS` is a comma-separated allowlist of lowercase SHA-256 fingerprints for authorized Core client certificates. The Core still validates each Agent fingerprint through its normal heartbeat path before the Relay enables task routing.

Build and run with Podman Compose:

```sh
cp .env.example .env
podman compose -f compose.yaml up -d --build
```

The example publishes TCP/443 and runs the process as UID 10001 with a read-only root filesystem and no Linux capabilities. Rootless Podman may need the host's unprivileged-port setting to publish port 443; otherwise publish an allowed high port and use that port in both Core and Agent endpoint lists.

Run at least two Relays in separate failure domains. Configure each Core Gateway with both HTTPS origins in `NANPANEL_GATEWAY_RELAY_ENDPOINTS`, the shared TLS service name in `NANPANEL_GATEWAY_RELAY_TLS_DOMAIN`, and the Core client identity files. Core obtains a monotonic fencing epoch from its database when the Relay connector starts. `NANPANEL_GATEWAY_CLUSTER_ID` must match the Core Relay configuration. Configure Agents with their trusted Relay origins in `NANPANEL_AGENT_RELAY_ENDPOINTS`; each Agent now keeps two independent control streams active and rotates each worker across its assigned Relay endpoints after a disconnect. Core has a default-off Cloudflare DNS coordinator for zone-scoped CNAME records. Enable it only with a token restricted to DNS editing on the one configured Zone; it uses `NANPANEL_CLOUDFLARE_DNS_TOKEN`, `NANPANEL_CLOUDFLARE_ZONE_ID`, `NANPANEL_CLOUDFLARE_ZONE_NAME`, and `NANPANEL_DNS_MANAGED_SUFFIX`. Core signs the optional fallback Relay list with Ed25519 during enrollment; the installer passes the signed origins to the Agent, which verifies the signature before adding them to its connection set. Configure `NANPANEL_AGENT_RELAY_FALLBACK_ENDPOINTS`, `NANPANEL_AGENT_RELAY_SIGNING_PRIVATE_KEY`, and `NANPANEL_AGENT_RELAY_SIGNING_PUBLIC_KEY` on Core. Keep the PKCS#8 private key root-owned and mounted read-only. The K3s Core runs as UID/GID 10001, so Secret mode `0440` grants it group read access while denying access to other users; create the optional `nanpanel-relay-signing` Secret with key `RELAY_FALLBACK_PRIVATE_KEY` and set the ConfigMap public key to the raw 32-byte public key in lowercase hex. DNS hysteresis, 120-second switch cooldown, and periodic read-back reconciliation are implemented in Core; public DNS propagation and external failure acceptance remain pending.
