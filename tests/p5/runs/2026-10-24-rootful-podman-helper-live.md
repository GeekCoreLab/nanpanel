# Rootful Podman Helper and Nginx route acceptance

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

## Follow-up: managed Nginx route

On 2026-10-25, a second disposable-VM run configured the existing trusted
`/usr/sbin/nginx` binary and a root-owned temporary include directory. The
temporary Nginx include listened only on `127.0.0.1:38082`; the existing
default listener on port 80 remained unchanged and returned HTTP 404 after
reload. The Helper advertised `nginx.route.set` alongside the container
operations.

The same fixture ran as UID/GID `1000`, deployed
`nanpanel-p5-rootful-route-7adeedef`, and returned HTTP `200`/`v2` directly on
port `38081`. It then switched the managed Nginx route and returned HTTP
`200`/`v2` through port `38082`, with Host `nanpanel-rootful.test`. The route
result recorded `routedHttpStatus: 200`, listener `127.0.0.1:38082`, and
stable weight 100. The container was stopped successfully afterward.

| Operation | Operation ID | Result |
| --- | --- | --- |
| `container.deploy` | `81cd63dc-0310-4863-bf2e-3b9238a4756e` | Container `487706a93911c89374ee93080c8af1ed9fabc61bf797d8f71652f8547a027339` |
| `nginx.route.set` | `5f8ea0b7-49d0-4e31-9684-d6ff55ee26e3` | HTTP 200, route SHA-256 `262a29d8d95090ca91eb99674adbf5b799a6d1bdf2e2026b0581bd8688f9cda5` |
| `container.stop` | `df1a6178-a1da-489e-b2c3-ecd10632fd7d` | Succeeded |

The exact guest invocation was:

```sh
python3 /tmp/rootful-podman-helper-ops.py \
  --socket /run/nanpanel-p5-rootful-route/socket/helper.sock \
  --image localhost/nanpanel-p5-app:v2 \
  --container-name nanpanel-p5-rootful-route-7adeedef \
  --host-port 38081 \
  --route-port 38082 \
  --server-name nanpanel-rootful.test
```

The temporary container, image, Helper, Nginx route, include file, socket, and
configuration were removed. Nginx remains active with its original port-80
default site. This direct Helper IPC probe closes the rootful Podman container
and managed-route capability check; it does not exercise the complete
Core-to-mTLS-Agent release workflow on a rootful host. Physically independent
host acceptance also remains open. No systemd units, K3s resources, firewall
rules, or host services were changed.
