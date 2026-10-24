# Host release durable result recovery acceptance

## Result

Passed on 2026-10-24 using the real browser, Quarkus Core, PostgreSQL, two
mTLS-enrolled Rust Agents, the fixed-operation Helper IPC, rootless Podman, a
managed Nginx route, and routed HTTP. The fixture used a disposable rootless
Podman VFS store. Each Agent and Helper ran in its own user-namespace
sandbox. No Kubernetes context was changed, and the shared `k3s-1` cluster
was not accessed.

The complete fixture also repeated the browser release workflow, Helper
restarts, and Core JVM restart acceptance. See the identifiers and traffic
counts below. Code under test was commits `798acaa` (Agent/Helper), `b56b9d7`
(Core/API), and `fd16466` (console and browser acceptance).

## Reproduction

```sh
python3 -u tests/p5/fixtures/host-release-live-stack.py --runtime podman
```

The fixture starts an isolated PostgreSQL instance, Core, Gateway, two Agents,
the browser console, and the Podman VFS service. It drops one selected Agent
result callback and expires only that operation's deadline in this disposable
database to exercise the timeout recovery path.

## Lost callback and Agent restart

The recovery release was `023a57be-722a-4a97-8e86-18d07790c597`. Its baseline
deployment completed in the Helper, and the Agent reported `SUCCEEDED`; the
callback proxy returned HTTP 503 once before forwarding any result to Core.
The operation was `ffaddaf2-b496-48d5-9c45-f580d3822278`, with idempotency key
`np-hostrel:023a57be-722a-4a97-8e86-18d07790c597:0:baseline_deploy`.

The fixture stopped that Agent, expired the operation deadline, then read the
operation through Core. Core reported `UNKNOWN`, and the release plan entered
`UNKNOWN`. Calling the release reconcile endpoint replayed the existing
operation identity and key. After the Agent restarted, it replayed its durable
`SUCCEEDED` result. Core accepted the result and continued the plan to
`SUCCEEDED`.

The final route returned HTTP `200 v2`. The baseline container ID before and
after replay was identical, so Core did not create a second container. The
Agent operation journal no longer contained the key after Core acknowledged
the result. The fixture observed different Agent PIDs before and after restart
(`33` and `346`), `UNKNOWN` after the expired deadline, and `SUCCEEDED` on both
the dropped and replayed result records.

The browser screenshots show the final compensated release view and the
successful host release before rollback: [final run](../artifacts/host-release-live-7adeedef.png),
[successful rollout](../artifacts/host-release-live-7adeedef-succeeded.png).

## Other flows repeated in the same run

- Browser-created two-host release `c1341aaf-6b57-47ac-a599-9100e5e49370`:
  ordered batch gating passed; each host returned 50/50 HTTP `200 v2`; manual
  reverse rollback returned 25/25 HTTP `200 v1` per host. The plan ended in
  the expected `ROLLED_BACK` state.
- Broken candidate plan `a9f6371d-8825-46e0-94c8-56ddbeb1ebc6` ended `FAILED`
  after compensation; all 50 follow-up requests returned HTTP `200 v1`. The
  browser recorded no page errors.
- Core restart plan `c9f31bf8-be79-483b-8eb8-9f4c9dfbd22a` was persisted at
  `PAUSED` between hosts. The route continued serving during the Core outage;
  after restart, both host-success audit resources were present, operator
  resume completed the plan as `SUCCEEDED`, and both routes returned HTTP
  `200 v2`.
- Both Helper sandboxes restarted at the paused batch boundary. Routes stayed
  available and both Agents returned `ONLINE` with `container.deploy` and
  `nginx.route.set` capabilities.

## Boundary

This run proves recovery when the Agent has a durable successful result and
the Core callback was lost. It does not establish a safe automatic resolution
when execution remains genuinely indeterminate and the Agent has no terminal
result to replay; that state remains `UNKNOWN` and is not blindly retried as a
new operation. Rootful Helper permissions and physically independent hosts
also remain separate acceptance gates. The two runtime sandboxes used in this
run shared one physical machine.
