# Independent-host rootful Podman live acceptance

## Result

Passed the full browser-to-Core-to-mTLS-Agent-to-Helper host-release workflow
on 2026-10-25. Run ID: **47634ffa**; fixture source was committed as **109e13b**.
Host A ran on the local bare-metal Ubuntu 26.04.1 LTS machine with rootless
Podman VFS. Host B ran rootful Podman **4.3.1** on a separately reached Debian
GNU/Linux 12 (Bookworm) KVM guest. systemd-detect-virt --vm reported none
locally and kvm on Host B. The target was reached over the configured SSH
connection; its address and identity file are intentionally omitted.

Both target Hosts enrolled separate mTLS Agent identities and reported
ONLINE; the rootful Helper reported AVAILABLE with container and Nginx route
capabilities. The real browser used the live Core API without API
interception.

## Invocation

The run used the checked-in live fixture with the isolated Nginx and reverse
Gateway tunnel options. SSH target, key, and known-hosts values came from the
local SSH configuration and are not recorded here.

```sh
python3 -B tests/p5/fixtures/host-release-live-stack.py \
  --runtime podman \
  --rootful-vm-ssh-target "$NANPANEL_P5_SSH_TARGET" \
  --rootful-vm-ssh-port 37663 \
  --rootful-vm-ssh-key "$NANPANEL_P5_SSH_KEY" \
  --rootful-vm-known-hosts "$NANPANEL_P5_KNOWN_HOSTS" \
  --rootful-vm-nginx-mode isolated \
  --rootful-vm-reverse-gateway-tunnel
```

## Observed results

- Release plan c91bc904-dc5e-440e-9131-f30ef7113d91 completed both ordered
  batches. The first pause showed target states SUCCEEDED and BASELINE_DEPLOY;
  after operator approval both targets reached SUCCEEDED. Host A and Host B
  each returned 50/50 HTTP 200 v2 requests.
- The same plan's reverse-order manual rollback ended ROLLED_BACK; both hosts
  returned 25/25 HTTP 200 v1 requests.
- Unhealthy candidate plan df1c63ea-e2f7-416f-a074-f82693e6e557 ended FAILED.
  Automatic compensation restored the rootful Host B route; all 50 requests
  returned HTTP 200 v1.
- The browser reported zero page errors. Host A / Agent
  bf00339a-b025-47aa-a7bd-0b5eb3cbc9fc /
  92f6dce7-6ed8-4887-a0ab-12ab162a2a99; Host B / Agent
  6577a013-90e6-4278-8b4b-29043dad6d0b /
  6dd8137d-3326-4552-b450-2647849233a4.

The successful batch screenshot is
[operator-gated release](../artifacts/host-release-live-47634ffa-succeeded.png);
the final browser state showing automatic compensation and manual rollback is
[compensation and rollback](../artifacts/host-release-live-47634ffa.png).

## Isolation and cleanup

The remote host's existing CNI default used 10.88.0.0/16, which overlaps its
Tailscale 10.0.0.0/8 route and prevented host-to-container health checks.
The fixture created a run-scoped CNI configuration and bridge on
172.31.240.0/24, passed that configuration only to the fixture Helper, and
removed the bridge and configuration during cleanup. It did not edit the
system CNI configuration or add host routes.

The host already had a system OpenResty/Nginx service serving unrelated sites.
The fixture used a separate loopback-only Nginx process and did not edit or
reload the system service. After cleanup, system Nginx remained active and its
original loopback response returned HTTP 200; the rootful Podman default
network remained 10.88.0.0/16 on cni-podman0. The run-specific containers,
P5 image tags, Helper/Agent state, CNI bridge, and temporary files were absent.
No Kubernetes context changed, and the shared k3s-1 cluster was not accessed.

This run closes the independent-host Docker/Podman acceptance gate while
retaining a virtualized remote target: Host B was a KVM guest on a separate
SSH-reached machine.
