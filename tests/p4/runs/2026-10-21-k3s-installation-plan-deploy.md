# K3s installation plan target deployment attempt

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

## Final state

- Core and Web Deployment templates again reference the existing
  `sha-6f146e38d3063c703e0e1da69cfbca21b34814ea` tag.
- Both Deployments rolled out successfully to `1/1`; PostgreSQL stayed running.
- `https://10.100.0.1:30443/` and `/q/health/ready` returned HTTP 200 after the
  rollback. No Core container started from the new image, so its Liquibase
  migration did not run against the target database.
- The installation-plan API and UI are published but are not running on
  `k3s-1`. Target deployment remains open until registry access can reliably
  fetch both images.
