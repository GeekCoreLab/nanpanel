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
database/bootstrap secrets, a self-signed Web TLS certificate, and the Core/Web
Deployments and Services. The certificate is stored in a Kubernetes Secret and
retained across redeployments. Set `NANPANEL_TLS_HOST` before the first deploy
to use a DNS name instead of the default node IP. The script uses this value
for the certificate and Core's allowed origin. If it changes later, delete the
`nanpanel-web-tls` Secret before redeploying so the certificate matches. Existing
database credentials, TLS certificate, and volumes are retained when the script
is run again.
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

## Verify

```sh
ssh k3s-1 'kubectl -n nanpanel get pods,svc,pvc -o wide'
curl -kfsS https://10.100.0.1:30443/q/health/ready
curl -sSI http://10.100.0.1:30081
```

The Web container proxies `/api/` and `/q/` to Core, so the browser and API
share one origin and the configured CSRF origin.
