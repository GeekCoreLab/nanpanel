# Rootful Podman host release live acceptance

## Result

Passed a real browser-to-Core-to-mTLS-Agent-to-Helper release run on
2026-10-25. Host A used isolated rootless Podman VFS on the development host;
Host B used rootful Podman `5.4.2` in the disposable Debian 13 guest, with the
Agent running as UID/GID `1000` and the fixed-operation Helper running as
root. Both hosts enrolled separate mTLS Agent identities. The Core, Gateway,
browser, and both target hosts ran on the same physical machine, so physical
multi-host acceptance remains open.

The guest also contained the disposable K3s fixture used by earlier P5 work.
The product correctly rejected host application release while the Agent
reported an active Kubernetes runtime. For this rootful Podman run only, K3s
was stopped inside that disposable guest; port 6443 was confirmed closed
before release. K3s was restarted after cleanup and its node returned `Ready`.
The shared `k3s-1` cluster was not accessed.

## Run

Run ID: `36ecf105`; fixture commit: `2b5d7e1`

```sh
python3 -B tests/p5/fixtures/host-release-live-stack.py \
  --runtime podman \
  --rootful-vm-ssh-target p5@127.0.0.1 \
  --rootful-vm-ssh-port 2222 \
  --rootful-vm-ssh-key /home/hhjmk/.cache/nanpanel-p5-vm/id_ed25519 \
  --rootful-vm-known-hosts /home/hhjmk/.cache/nanpanel-p5-vm/known_hosts
```

The fixture brought up the Core/Gateway and browser flow, provisioned the
guest's rootful Podman and Nginx route, and enrolled two Agents. The guest was
reached through the QEMU user-network gateway. The browser exercised persisted
operator-gated batches, routed candidate traffic, manual reverse rollback,
and unhealthy-candidate compensation without API interception.

## Observed results

- Both target Hosts reported `ONLINE`; their Helpers reported `AVAILABLE`.
  The rootful target advertised container lifecycle and managed Nginx route
  capabilities.
- The approved ordered batch completed. Each host returned 50/50 HTTP
  `200 v2` requests through its managed route.
- Manual reverse rollback plan
  `56f2449a-a4a0-4600-bf88-04d3954aa9fa` ended `ROLLED_BACK`; each host then
  returned 25/25 HTTP `200 v1` requests.
- Unhealthy candidate plan `4bee8115-fb37-4d9b-bb90-d256ab22b6eb` ended
  `FAILED`; automatic compensation restored the rootful host's stable route,
  which returned 50/50 HTTP `200 v1` requests.
- The browser reported no page errors. Host A / Agent
  `858b7df3-6a34-425c-83e8-54bec9e0ee06` used Host
  `48b3704f-30c1-4503-8f0c-6db7a3cbb63b`; Host B / Agent
  `2df876d7-2558-4669-a317-ccd324f20349` used Host
  `86160c0e-4853-4002-a4d1-7933bd005c97`. The managed route used port `38609`.

The successful browser captures are
[`completed release and rollback`](../artifacts/host-release-live-36ecf105-succeeded.png)
and [`host release run`](../artifacts/host-release-live-36ecf105.png).

## Cleanup and boundary

The fixture removed its generated containers, image tags, Helper and Agent
state, sockets, temporary files, and managed Nginx include. Nginx remained
active and its original port-80 default response returned HTTP 404. K3s was
restored and the disposable node reported `Ready`. The shared `k3s-1` service
and data were untouched.

This run closes the full rootful Core-to-mTLS-Agent host release path for the
disposable guest. Because Host A and Host B shared one physical machine, it
does not close the independent physical-host gate; P5 remains in progress.
