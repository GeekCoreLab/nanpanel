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
database/bootstrap secrets, and the Core/Web Deployments and Services. Existing
database credentials and volumes are retained when the script is run again.
The first-run administrator password is printed once; sign in as `admin`,
change it, then remove the one-time bootstrap secret:

```sh
ssh k3s-1 'kubectl -n nanpanel delete secret nanpanel-admin-bootstrap'
```

Open `http://10.100.0.1:30081`. The test deployment uses HTTP and disables
Secure Cookies for this private node address. Keep it on the test network;
production access needs HTTPS, a trusted origin, and Secure Cookies enabled.

## Verify

```sh
ssh k3s-1 'kubectl -n nanpanel get pods,svc,pvc -o wide'
curl -fsS http://10.100.0.1:30081/q/health/ready
```

The Web container proxies `/api/` and `/q/` to Core, so the browser and API
share one origin and the configured CSRF origin.
