# Mixed Docker and Podman host-release live acceptance

## Result and boundary

**Passed for isolated rootless Docker and Podman delivery.** A real browser
created and operated a host release through the live Core API, two separately
enrolled mTLS Agents, two Helper IPC sockets, the isolated Podman VFS service,
an external rootless Docker Engine, managed Nginx routes, and routed HTTP.
The run covered ordered host batches, candidate traffic, reverse-order manual
rollback, unhealthy-candidate compensation, and a Core JVM restart between
operator-gated batches.

Host A used the isolated rootless Podman VFS service. Host B used a separate
Docker 29.9.2 daemon launched by the host user with RootlessKit, slirp4netns,
the builtin port driver, and VFS storage. The Docker Unix socket was mounted
into Host B's Helper sandbox; `--userns=keep-id:uid=0,gid=0` mapped the socket
owner to Helper UID 0 without changing its mode 0660. The Docker daemon and
Podman service were separate runtime daemons, but both ran on the same physical
machine. Both Helpers ran inside rootless Podman sandboxes. This is not evidence
for rootful Helper permissions or physically independent hosts.

Docker 29.9.2 and RootlessKit 3.3.0 came from the official Docker static
archives. The slirp4netns 1.3.4 static binary came from its official release.
SHA256 digests were:

| Artifact | SHA256 |
| --- | --- |
| `docker-29.9.2.tgz` | `9ac225999fd5e863f7694c728ef23d480616b1e5639328fa309befd4243d69eb` |
| `docker-rootless-extras-29.9.2.tgz` | `f2663b230a7afc13dea88fc589685331d01f5f3fc458b92ab40e2388db452ed2` |
| `slirp4netns` 1.3.4 x86_64 | `e8d0440de8d8c87072138883bc27cfa02f8b0e8a504badbf335c41f794788cc2` |

The Docker Engine reported `Driver=vfs` and the `rootless` security option.
Docker images were exported from the existing local Podman store and loaded
into this temporary Docker daemon; release traffic did not depend on a Docker
Hub pull. The host did not have Docker installed and passwordless sudo was
unavailable. The daemon was configured to continue when the test user
namespace lacked `/proc/sys/net/bridge/bridge-nf-call-iptables`; no sysctl or
system service was changed. A standalone smoke container returned `200 v1`
through its published loopback port before the live run.

## Command and source

Run ID: `30a65aa7`
Repository HEAD: `6568794` with the P5 harness changes in the working tree.

```sh
python3 -u tests/p5/fixtures/host-release-live-stack.py \
  --runtime both \
  --docker-host-socket /tmp/nanpanel-p5-docker-host-preflight/runtime/docker.sock
```

The Core/PostgreSQL fixture and Host A Podman runtime used a disposable
rootless Podman VFS store. Host B's Docker runtime used the separate temporary
rootless Docker daemon. `kubernetesContextChanged` was `false`; no Kubernetes
cluster, including the shared `k3s-1`, was used.

## Browser release and routed traffic

The browser created release
`79031e32-6020-4fb4-8d5d-8955568c92a0`. At the operator gate, Host A was
`SUCCEEDED` and Host B was still `BASELINE_DEPLOY`. After approval, both
targets reached `SUCCEEDED`; 50 requests through each Host route returned
`200 v2`. Reverse-order manual rollback returned 25/25 `200 v1` responses
through each route.

The unhealthy Docker candidate release
`97679653-3b87-49ac-b518-10de561e5452` reached `FAILED` after the Helper's local
HTTP health check rejected the candidate. Core compensation restored the
stable route, and 50 requests through Host B returned `200 v1`.

Playwright reported no page errors. Screenshots capture the operator-gated
mixed-runtime release and the failed-candidate compensation:

- [operator-gated release](../artifacts/host-release-live-30a65aa7-succeeded.png)
- [compensation result](../artifacts/host-release-live-30a65aa7.png)

## Core restart acceptance

The harness created a second release plan
`c62db9d5-ec6b-48b2-a245-f481248b2a52` and stopped the Core JVM after Host A
completed and the plan persisted at the `PAUSED` batch boundary. Core PID
`29274` was replaced by PID `43692`. While Core was stopped and after it
restarted, Host A's new route returned `200 v2`, and both pre-existing stable
routes returned `200 v1`. After the operator resumed the plan, both Hosts
reached `SUCCEEDED` and returned `200 v2`. Core readback included both success
audit resources:

- `c62db9d5-ec6b-48b2-a245-f481248b2a52:0`
- `c62db9d5-ec6b-48b2-a245-f481248b2a52:1`

The plan's persisted state after Core restart was `PAUSED`, with target states
`[SUCCEEDED, BASELINE_DEPLOY]`.

## Cleanup and remaining gates

The live harness exited successfully and removed its disposable Core database,
Podman store, Helper containers, and generated certificates. The five
containers created in the temporary Docker daemon were removed and the Docker
daemon was stopped. Its downloaded archives, VFS image store, and logs remain
under `/tmp/nanpanel-p5-docker-host-preflight`; no daemon is running there.

This closes the rootless Docker release traffic gate for this fixture and
demonstrates separate Docker and Podman daemons on one machine. This first
mixed-runtime run did not restart either Helper. The later
[`Helper and Core restart run`](2026-10-24-host-release-mixed-helper-core-restart-live.md)
verifies idle batch-boundary Helper restart and Core restart. In-flight
uncertain-result recovery, physically independent host acceptance, rootful
Helper permissions, and K3s target acceptance remain open. The earlier
nested-Docker preflight still records why the Docker daemon was run outside the
Helper sandbox in this environment:
[`rootless Docker preflight`](2026-10-23-host-release-docker-rootless-preflight.md).
