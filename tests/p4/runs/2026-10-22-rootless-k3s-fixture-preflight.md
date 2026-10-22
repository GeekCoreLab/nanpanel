# Rootless Podman K3s fixture preflight

Date: 2026-10-22

## Environment

- `rancher/k3s:v1.33.5-k3s1` was already cached locally.
- No libvirt VMs were running or started. The experiment used a dedicated
  rootless Podman network and a disposable container, separate from Kind and
  the production SSH target `k3s-1`.
- The host exposes cgroup v2 controllers including `cpuset`, but the rootless
  Podman container scope does not receive that controller.

## Attempt and result

A privileged rootless K3s server container was started with temporary test
state and a loopback API port. K3s initialized its datastore and certificates,
then exited before registering a Node:

```text
fatal: failed to find cpuset cgroup (v2)
```

A second attempt explicitly requested the host CPU set, but the OCI runtime
rejected container creation:

```text
controller `cpuset` is not available under
/sys/fs/cgroup/user.slice/user-1000.slice/user@1000.service/user.slice/...
```

No Kubernetes Node became Ready and no P4 acceptance command was run against
this fixture. This is not a Kind substitute and does not close any P4 gate. A
rootful container runtime with delegated cpuset, or an existing disposable
multi-node K3s cluster, is required to run the planned real-kubelet lifecycle
and recovery checks. No production cluster or persistent host data was changed.
