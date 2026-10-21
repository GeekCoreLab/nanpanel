# K3s installation plan target deployment and recovery

Date: 2026-10-21

## Release

- Commit: `f6c373c2fbf2f45bf88dcfec17c1a5249495e6e4`
- GitHub Actions run: `39606430511` (`Publish NaNPanel containers`)
- Validation job passed: Rust format/check/clippy/tests, Maven verify, and Web
  build.
- Publish job passed for both immutable images:
  `ghcr.io/geekcorelab/nanpanel-core:sha-f6c373c2fbf2f45bf88dcfec17c1a5249495e6e4`
  and
  `ghcr.io/geekcorelab/nanpanel-web:sha-f6c373c2fbf2f45bf88dcfec17c1a5249495e6e4`.

## Target attempt

Before the update, `k3s-1` was Ready on K3s `v1.33.5+k3s1`; Core and Web were
both `1/1` with zero restarts. The running image tag was
`sha-6f146e38d3063c703e0e1da69cfbca21b34814ea`.

`./deploy/k3s/deploy.sh` confirmed both new tags against authenticated GHCR and
applied the new Core/Web Deployment templates. Kubelet then repeatedly failed
to fetch Core image layers from GHCR with IPv6 `connection reset by peer`; the
Core rollout timed out after 240 seconds. The test Web image started, but Core
was still on the old image, so both Deployments were returned to their previous
ReplicaSet revisions before leaving the service in that mixed state.

The user-provided registry proxy was tried with both published image tags.
Core manifest/layers were visible, but a layer repeatedly returned `504 Gateway
Time-out`. The private Web tag returned `403 manifest unknown`. The proxy was
not given the GHCR credential.

## State after the initial attempt

- Core and Web Deployment templates again reference the existing
  `sha-6f146e38d3063c703e0e1da69cfbca21b34814ea` tag.
- Both Deployments rolled out successfully to `1/1`; PostgreSQL stayed running.
- `https://10.100.0.1:30443/` and `/q/health/ready` returned HTTP 200 after the
  rollback. No Core container started from the new image, so its Liquibase
  migration did not run against the target database.
- The installation-plan API and UI are published but are not running on
  `k3s-1`. Target deployment remains open until registry access can reliably
  fetch both images.

## Successful retry through the proxy

The proxy's standard Registry v2 endpoint issued an anonymous, short-lived
pull token for the public Core package. Containerd pulled the complete
Core image from the proxy (50.8 MiB; manifest digest
`sha256:80d22868893b75cc572ec3e97f91208ba6f32542120f9f7ab834c7b1b137948a`)
and the image was tagged under its canonical `ghcr.io` reference on `k3s-1`.
The proxy still returned `not found` for the private Web package. Its matching
immutable Web image had already been pulled during the first deployment
attempt and remained in the node's containerd cache (24.7 MiB); no local build
or image upload was used.

The repository deployment script then completed successfully for both
Deployments. Core and Web run the published `sha-f6c373c2fbf2f45bf88dcfec17c1a5249495e6e4`
images at `1/1`, each with zero pod restarts. PostgreSQL stayed Ready and PVC
`data-nanpanel-postgres-0` remained Bound. Liquibase applied
`032-k3s-installation-plans`. The Agent binary was updated from the Core image
to SHA-256 `9d7d37716789048ef673f0f790533277708e8ba58dfded57f70c1573a011e13b`;
`nanpanel-agent.service` is active with zero systemd restarts, and its prior
binary is backed up under `/var/lib/nanpanel-agent/`.

After rollout, `https://10.100.0.1:30443/q/health/ready` and the HTTPS home
page both returned HTTP 200. The persisted Agent discovery still reports
`k3s-1|K3S|true|true|true`, observed within five minutes. The service update is
complete; the user can continue interactive feature testing.
