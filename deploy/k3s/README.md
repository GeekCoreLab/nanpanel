# K3s test deployment

This deploys a test instance to the single-node `k3s-1` cluster. GitHub Actions
validates the backend and web build, then publishes immutable full-commit tags
to GHCR. The cluster deployment consumes those GHCR images; it does not upload
local images.

## Registry access

The workflow links both images to this repository and publishes them as
`ghcr.io/geekcorelab/nanpanel-core` and `ghcr.io/geekcorelab/nanpanel-web`.
GHCR packages start private. Provide a classic GitHub PAT with only
`read:packages` to create the namespace pull secret. Do not commit the PAT or
place it in a manifest. Publicizing a GHCR package is a separate choice.

## Deploy

Wait for the `Publish NaNPanel containers` workflow to succeed for the commit,
then use its full `sha-<40-character-commit>` tag:

```sh
printf 'GHCR read:packages token: '
read -rs GHCR_TOKEN
echo
export GHCR_TOKEN
export GHCR_USERNAME=your-github-login
export NANPANEL_IMAGE_TAG=sha-<40-character-commit>
./deploy/k3s/deploy.sh
unset GHCR_TOKEN
```

The script requires local `podman`, `openssl`, and the `k3s-1` SSH alias. It
checks both private GHCR tags before changing the cluster. It creates the
`nanpanel` namespace, a persistent 5 GiB PostgreSQL volume, generated
database/bootstrap secrets, a persistent random SSH credential-encryption key,
a self-signed Web TLS certificate, and the Core/Web Deployments and Services.
It also creates a dedicated Agent client-signing CA
and a private Gateway-to-Core callback token. The Core image contains the
static Linux x86_64 Agent and runs its Rust mTLS Gateway sidecar on NodePort
`30444`; the browser panel remains on HTTPS NodePort `30443`. The TLS
certificate and Agent CA are stored in Kubernetes Secrets and retained across
redeployments. Set `NANPANEL_TLS_HOST` before the first deploy
to use a DNS name instead of the default node IP. The script uses this value
for the certificate and Core's allowed origin. If it changes later, delete the
`nanpanel-web-tls` Secret before redeploying so the certificate matches. Existing
database credentials, TLS certificate, and volumes are retained when the script
is run again. The deploy script rotates a legacy serving certificate if it is
marked as a CA or lacks the TLS server-auth usage required by Rustls. It restarts
Core and Web after rotation and refreshes the local Agent's trust certificate if
`nanpanel-agent.service` is installed.

The deployment also binds the Core ServiceAccount to the read-only
`nanpanel-topology-inventory` ClusterRole. It grants `get/list` for Namespace,
Pod, Service, and Ingress metadata across the cluster so the topology editor can
drill into each namespace and Pod. It grants no Secret access and no write
verbs. The hierarchical topology discovery is enabled for this test cluster;
the periodic service inventory remains limited to the configured
`nanpanel-mesh` namespace. The separate Istio fixture Role remains
namespace-scoped for its isolated tests.
The first-run administrator password is printed once; sign in as `admin`,
change it, then remove the one-time bootstrap secret:

```sh
ssh k3s-1 'kubectl -n nanpanel delete secret nanpanel-admin-bootstrap'
```

Open `https://10.100.0.1:30443`. The first visit shows a browser certificate
warning because the certificate is self-signed; proceed to the site to test the
panel. HTTP on port `30081` redirects to HTTPS. To renew the certificate, delete
the `nanpanel-web-tls` Secret and run the deploy script again. Keep this
self-signed certificate on the test network; public access needs a trusted
certificate and a matching origin.

The `nanpanel-ssh-credentials-key` Secret encrypts per-host SSH passwords and
private keys, plus bastion command/terminal recordings. The script creates it
only once and preserves it during redeployments. Protect and back up this Secret
with the PostgreSQL volume: losing it makes saved credentials and recordings
unreadable. Never print its data or commit an exported copy.

## Add the controller host as an asset

Sign in, change the bootstrap password if prompted, then open **主机资产 → 添加
主机**. Create an enrollment and run the copied command on the Linux x86_64
controller host (for this test cluster, `k3s-1`) with sudo access. The command
checks the panel certificate fingerprint before downloading the installer; the
installer creates a `nanpanel-agent` system user, obtains its one-time client
certificate, and enables `nanpanel-agent.service`. The enrollment expires in 15
minutes and can be redeemed once. After the first heartbeat, the asset becomes
ONLINE and reports only host inventory and heartbeat capabilities. It does not
enable systemd operations or the root Helper.

## Verify

```sh
ssh k3s-1 'kubectl -n nanpanel get pods,svc,pvc -o wide'
ssh k3s-1 'systemctl status nanpanel-agent.service'
ssh k3s-1 'journalctl -u nanpanel-agent.service -n 30 --no-pager'
curl -kfsS https://10.100.0.1:30443/q/health/ready
curl -sSI http://10.100.0.1:30081
```

The Web container proxies `/api/` and `/q/` to Core, so the browser and API
share one origin and the configured CSRF origin.
