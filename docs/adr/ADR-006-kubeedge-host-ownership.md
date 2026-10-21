# ADR-006: Separate Host Agent and KubeEdge resource ownership

- Status: Accepted
- Date: 2026-10-21

## Context

KubeEdge EdgeCore and the Host Agent can run on the same Linux machine. Both
systems observe workload containers, but container lifecycle changes must have
one owner. An unavailable CloudCore or Kubernetes API must not disable local OS
diagnostics or interrupt EdgeCore's offline operation.

## Decision

- The Kubernetes API/KubeEdge owns Pods and their containers on registered
  EdgeCore nodes. Platform operations against such workloads go through the
  cluster API and remain subject to Kubernetes RBAC.
- The Host Agent owns host-level inventory and diagnostics, including CPU,
  memory, disk, network interfaces, routes, systemd, and bounded logs.
- Once a Host is explicitly mapped to a KubeEdge ClusterNode, Core rejects
  Agent `container.*` operations for that Host. It does not disable the Agent,
  its heartbeat, or its host-level capabilities.
- Host-to-EdgeNode mapping and unmapping are explicit, auditable operations.
  They do not install EdgeCore, register or remove an EdgeNode, claim an
  unrelated node, or remove a Host asset.
- For containerized CloudCore, Core reads only matching Pods in the `kubeedge`
  namespace and reports their readiness. A failed observation is `UNKNOWN`;
  no matching Pod is `NOT_OBSERVED`. Binary CloudCore management is not
  implemented.
- EdgeCore's offline workload behavior is observed and reported; Core does not
  try to recreate those containers through the Host Agent.

## Consequences

Cluster API failures affect cluster inventory and Kubernetes resource
operations only. Host Agent diagnostics continue through the independent Agent
channel. Ownership conflicts fail closed and identify the owning cluster.
