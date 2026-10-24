# Host release Core restart live acceptance

## Result and boundary

**PASS for an isolated rootless Podman run between operator-gated host batches.**

The run first passed the real browser-to-Core host release flow, including
ordered approval, routed candidate traffic, manual reverse rollback, and
automatic compensation after an unhealthy candidate. It then created a second
two-host plan, let the first host finish, and stopped the real Core JVM while
the plan was persisted as `PAUSED` at the batch boundary. The Nginx routes
continued serving while Core was down. After Core restarted against the same
PostgreSQL database, the plan was still `PAUSED`, both Hosts were online, and
the operator resumed the second host through the Core API. The plan reached
`SUCCEEDED` and both host-success audit events were read back.

This verifies Core process recovery between persisted stages. It does not
interrupt a Helper operation while its result is uncertain, restart a Helper,
or prove duplicate dispatch recovery in a live Docker runtime. The two Agent
sandboxes shared one isolated Podman VFS service on one development machine.
Docker, rootful Helper permissions, physically independent hosts, and K3s were
not exercised.

## Command and observed result

Run `7be3cf01` used commit `6568794` plus the repository working tree and:

```sh
python3 -u tests/p5/fixtures/host-release-live-stack.py --runtime podman
```

The browser acceptance passed with no page errors. Its plan
`faa14472-4640-40b8-90b6-5f6f18c2d272` returned 50 `200 v2` responses through
each host, reverse rollback returned 25 `200 v1` responses through each host,
and unhealthy candidate compensation returned 50 `200 v1` responses.

The restart plan was
`6f9f1b62-dd3d-405c-b7a5-a75612f66905`. Core PID `1199968` was stopped and a
new Core PID `1207088` started against the same database. Immediately before
shutdown, the plan was `PAUSED` with target states `[SUCCEEDED,
BASELINE_DEPLOY]`. The new route on host A returned `200 v2`, and the prior
stable routes on Hosts A and B each returned `200 v1` both while Core was
stopped and after it restarted. Core restored the persisted paused plan and
online Host inventory; resuming through the live API completed both targets.
The new route then returned `200 v2` on both hosts. Audit readback included
`6f9f1b62-dd3d-405c-b7a5-a75612f66905:0` and
`6f9f1b62-dd3d-405c-b7a5-a75612f66905:1` for the two host-success events.

The browser screenshots are [completed candidate rollout](../artifacts/host-release-live-7be3cf01-succeeded.png)
and [failure compensation](../artifacts/host-release-live-7be3cf01.png). The
Core restart and route checks were performed directly against the live Core
API and routed HTTP endpoints by the same harness. No Kubernetes context was
changed.
