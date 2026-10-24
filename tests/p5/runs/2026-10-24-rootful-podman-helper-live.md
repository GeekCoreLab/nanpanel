# Rootful Podman Helper permission probe

## Result

Passed a scoped container-operation probe on 2026-10-24 in the disposable
Debian 13 K3s guest. The guest's rootful Podman server reported version
`5.4.2`. The helper binary from commit `798acaa` ran as root from a temporary
VM path; the requesting process ran as UID/GID `1000` and connected through
the permission-checked Unix socket. The shared host and shared `k3s-1` cluster
were not changed.

## Probe

The fixture imported the already-built
`localhost/nanpanel-p5-app:v2` image into the guest's rootful Podman store,
configured the root-owned managed runtime selector to `podman`, and started
the Helper directly without installing or enabling a systemd unit. The Helper
advertised `container.deploy`, `container.list`, and `container.stop`.

The repeatable fixture `tests/p5/fixtures/rootful-podman-helper-ops.py` ran as
UID `1000` and deployed `nanpanel-p5-rootful-7adeedef` through Helper IPC. The
deploy operation `74de5913-1bb5-4758-8f29-4810dd1fe59d` returned the created
container ID `01dfff2c61c6890d056132ee932dc4e896247b4ada1d103160285771b68f651f`.
A request through loopback published port `38081` returned HTTP `200` with body
`v2`; `container.list` returned the container, and `container.stop` succeeded
with operation `345a98f0-5deb-4fa9-96c0-5a5ad5c53cf3`.

The exact guest invocation was:

```sh
python3 /tmp/rootful-podman-helper-ops.py \
  --socket /run/nanpanel-p5-rootful-7adeedef/socket/helper.sock \
  --image localhost/nanpanel-p5-app:v2 \
  --container-name nanpanel-p5-rootful-7adeedef \
  --host-port 38081
```

The fixture requests `helper.capabilities`, `container.deploy`,
`container.list`, and `container.stop` over the Helper Unix socket as the
configured Agent UID. The live recovery fixture contains the app image recipe
and HTTP health endpoint in
[`application.Containerfile`](../fixtures/application.Containerfile) and
[`http-app.py`](../fixtures/http-app.py).

## Boundary and cleanup

This proves that the root Helper can use the VM's rootful Podman installation
for the fixed container operations while retaining the Agent UID check. It
does not prove a full rootful host release: the guest did not have a trusted
host Nginx binary or a configured managed include directory, so
`nginx.route.set` was not advertised and no route switch was attempted.
Physically independent host acceptance also remains open.

The test container and imported image were removed, the Helper process was
stopped, and temporary binaries, certificates, socket, and configuration were
removed. No systemd units, K3s resources, firewall rules, or host services were
changed.
