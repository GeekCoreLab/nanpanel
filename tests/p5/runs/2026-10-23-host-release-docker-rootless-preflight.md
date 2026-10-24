# Rootless Docker host-release preflight

## Result and boundary

**Blocked before Docker Engine readiness; this is not Docker release acceptance.**

The preflight attempted the live host-release harness with two separate Helper
sandboxes, each running its own rootless Docker Engine inside the disposable
rootless Podman fixture. The Docker Helper image built and loaded, but both
Helper containers exited before their Agents reported online. Core observed no
Docker Agent capabilities, and the browser/release flow did not start.

RootlessKit failed while creating the nested Docker user namespace. With the
image's default subordinate IDs, `newuidmap` attempted
`[0 1000 1 1 100000 65536]` and the kernel returned `Operation not permitted`.
The follow-up used the UID/GID ranges visible inside the outer rootless user
namespace, producing
`[0 1000 1 1 0 1000 1001 1001 64536]`; `newuidmap` still returned
`Operation not permitted`. A single-UID `unshare --user --map-root-user`
succeeded in the same sandbox, while mapping the subordinate range failed.

This evidence identifies a nested user-namespace restriction in this test
environment. It does not establish that Docker is unsupported on a host with a
working Docker Engine or a different user-namespace policy. No rootful host
Docker daemon or rootful Helper permission boundary was exercised.

## Command and observed result

Run `7d91b0cb` used the repository working tree and the command:

```sh
NANPANEL_P5_KEEP_HELPER_IMAGE_CACHE=1 \
  python3 -u tests/p5/fixtures/host-release-live-stack.py --runtime docker
```

The image used Ubuntu 26.04 packages for Docker, RootlessKit, slirp4netns, and
uidmap. Both isolated Helper containers exited during daemon startup with the
same `newuidmap` error. The Agent inventory stayed `UNKNOWN` with empty
capability lists; no release plan, routed request, browser session, or
screenshot was produced.

The fixture used a disposable Core database and rootless Podman VFS store. No
Kubernetes context or cluster was accessed. The harness removed the disposable
store after the failed run. The resulting Docker Helper image archive was kept
temporarily in `/tmp` for this preflight and removed after evidence collection.

## Remaining gates

The earlier rootless Podman live release remains valid evidence for Podman.
Docker runtime delivery, rootful Helper permissions, independent physical
hosts, active Core/Helper restart reconciliation, and K3s target acceptance
remain open. See [`../README.md`](../README.md),
[`PROJECT_PLAN.md`](../../../PROJECT_PLAN.md), and
[`ADR-014`](../../../docs/adr/ADR-014-progressive-delivery.md).
