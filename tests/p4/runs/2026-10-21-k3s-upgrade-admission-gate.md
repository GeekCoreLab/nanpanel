# K3s upgrade admission gate

- Date: 2026-10-21
- Source baseline: `b9ab427959a1c342ff6d4f27fd3d9f7ae8d1a5e2`
- Scope: Core admission for per-host `k3s.upgrade` operations targeting hosts
  mapped to registered cluster Nodes.
- Target cluster: no installation or upgrade was run; production `k3s-1` was
  not changed.

## Behavior

- Reject an upgrade when the Host is not mapped to a registered Kubernetes
  Node, or its mapped cluster is not `K3S`.
- Require the mapped Node to be present and Ready and the cluster to be healthy
  with a successful observation no older than 120 seconds.
- Serialize K3s install/upgrade operations across mapped Hosts in the same
  cluster. Pending, running, or unknown operations block a new upgrade.
- After a mapped K3s operation reaches a terminal result, require a newer
  cluster observation before admitting another upgrade, including after a
  failed operation.
- Lock the selected cluster and Node rows while evaluating the gate. Operation
  submissions already serialize through the platform settings row lock.

## Verification

Command:

```text
./mvnw -pl backend/panel-core -Dtest=SystemResourceTest test
```

Result: `SystemResourceTest` passed 4/4. The new assertions cover an unmapped
Host, a Host mapped to KubeEdge, an unavailable/not-Ready K3s cluster, stale
cluster observations, a concurrent cluster operation, the required post-result
refresh, and the path that passes all gates before Agent availability is checked.
`git diff --check` passed.

This is code-level admission evidence only. It does not prove a multi-node
rollout plan, automatic step advancement, K3s installation, or a live upgrade.
No upgrade experiment was run against `k3s-1`.
