# Mixed-runtime Helper and Core restart live acceptance

## Result and boundary

**Passed for rootless Podman and Docker runtime delivery, Helper process restart
at a persisted batch boundary, and Core process restart.** The real browser
release flow also repeated the mixed-runtime candidate, rollback, and failure
compensation checks from the earlier
[`mixed-runtime live run`](2026-10-24-host-release-mixed-rootless-live.md).

Host A used the isolated rootless Podman VFS service. Host B used a separate
rootless Docker 29.9.2 daemon through a Unix socket mounted into its Helper
sandbox. The two runtime daemons and both Helper sandboxes were on the same
physical machine; the Helpers remained rootless. This does not prove rootful
host permissions or physically independent hosts. No Kubernetes context was
changed.

## Command and browser release

Run ID: `15a9ce7d`
Repository HEAD: `6568794` with the P5 harness changes in the working tree.

```sh
python3 -u tests/p5/fixtures/host-release-live-stack.py \
  --runtime both \
  --docker-host-socket /tmp/nanpanel-p5-docker-host-preflight/runtime/docker.sock
```

The browser-created release `68e37841-3df2-4fbd-8ec7-c89dd3495615` paused
after Host A completed, with target states `[SUCCEEDED, BASELINE_DEPLOY]`.
After approval, both targets reached `SUCCEEDED`; 50 requests through each
host route returned `200 v2`. Reverse-order rollback returned 25/25 `200 v1`
responses through each route.

The unhealthy Docker candidate release
`1c165ad6-9adf-422c-a65d-988207000d56` reached `FAILED` after its local HTTP
health check failed. Core compensation restored the stable route, and 50
requests through Host B returned `200 v1`. Playwright reported no page errors.

Screenshots:

- [operator-gated release](../artifacts/host-release-live-15a9ce7d-succeeded.png)
- [failed-candidate compensation](../artifacts/host-release-live-15a9ce7d.png)

## Helper process restart

At a second persisted `PAUSED` batch boundary, the harness sent `SIGTERM` to
each Helper and started a new Helper process in the same Agent sandbox. Host A
used Podman; Host B used Docker. During Helper shutdown, the existing routes
continued serving:

| Host | Runtime | Traffic while Helper was stopped |
| --- | --- | --- |
| `p5-live-15a9ce7d-a` | Podman | New route `200 v2`; existing route `200 v1` |
| `p5-live-15a9ce7d-b` | Docker | Existing route `200 v1` |

After each restart, the Agent reported `ONLINE` and restored both
`container.deploy` and `nginx.route.set`. This verifies Helper restart while
the plan was idle at a batch gate. No operation was interrupted while its
result was uncertain; that recovery case remains open.

## Core process restart and reconciliation

The harness then stopped Core while plan
`7c1c5fe3-3b76-4844-90c2-a39baca61d08` remained paused. Core PID `60698` was
replaced by `73167`. While Core was stopped and after it restarted, Host A's
new route returned `200 v2`; both existing stable routes returned `200 v1`.
The persisted plan restored as `PAUSED` with target states
`[SUCCEEDED, BASELINE_DEPLOY]`. After operator resume, both hosts reached
`SUCCEEDED` and served `200 v2`. Audit readback included:

- `7c1c5fe3-3b76-4844-90c2-a39baca61d08:0`
- `7c1c5fe3-3b76-4844-90c2-a39baca61d08:1`

## Cleanup and remaining gates

The live harness exited successfully and cleaned its disposable Core database,
Podman VFS store, Helper containers, and certificates. All Docker containers
created by the temporary daemon were removed and the daemon was stopped. Its
downloaded packages, VFS image store, and logs remain under
`/tmp/nanpanel-p5-docker-host-preflight`.

Rootless Docker/Podman delivery, batch-boundary Helper restart, and batch-boundary
Core restart are covered by live evidence. In-flight uncertain-result recovery,
rootful Helper permissions, physically independent hosts, and K3s target
acceptance remain open. The shared `k3s-1` cluster was not used.
