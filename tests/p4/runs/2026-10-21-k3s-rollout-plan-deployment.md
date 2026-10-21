# K3s persistent upgrade-plan service deployment

Date: 2026-10-21

## Release

- Source commit: `6f146e38d3063c703e0e1da69cfbca21b34814ea`
- Branch: `main`
- GitHub Actions run `39594717501` passed validation and published immutable
  Core and Web images for the commit.
- Runtime images: Core `ghcr.io/geekcorelab/nanpanel-core@sha256:efe1bea2e9dcff7156911dbf6f2b38be668a39cf1bb55afd85b7ebd191bae8e9`; Web
  `ghcr.io/geekcorelab/nanpanel-web@sha256:92f79f5a7d13fa57d519ea94001d366dba5993d78fa4461d073074307d96772b`.

## Pull and rollout

- Direct GHCR blob pulls from `k3s-1` initially failed when the GitHub blob
  hostname resolved to IPv6; a ranged IPv4 read of the same blob returned
  HTTP 206.
- The supplied 1ms registry mirror served the Core manifest but its CDN
  returned HTTP 504 for a required blob. Its Web image tag returned 404.
- A temporary `/etc/hosts` entry pinned only
  `pkg-containers.githubusercontent.com` to the IPv4 address that passed the
  ranged read. Core then started from the original GHCR image. The exact
  pre-change `/etc/hosts` backup was restored and byte-compared afterward.
- Core rollout completed with both `core` and `agent-gateway` ready and zero
  restarts. Web is ready with zero restarts. PostgreSQL remained Running and
  its 5 GiB PVC remained Bound. `nanpanel-agent.service` is active with zero
  systemd restarts.
- `https://10.100.0.1:30443/q/health/ready` returned `UP`; the HTTPS home page
  returned `200`.

## Acceptance boundary

This deploys the persistent K3s upgrade-plan API and UI, but no upgrade plan
was created or started on `k3s-1`. It does not satisfy disposable multi-node
upgrade fault/restart acceptance. The production test cluster was not upgraded.
