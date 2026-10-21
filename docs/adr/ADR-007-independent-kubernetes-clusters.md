# ADR-007: Register independent Kubernetes clusters

- Status: Accepted
- Date: 2026-10-21

## Context

One control plane must manage K3s installations in independent networks without
coupling their etcd membership or treating remote clusters as one cluster.
Cluster credentials are sensitive and must not be accepted again on every
resource request.

## Decision

- Each imported cluster has a separate UUID, endpoint, type, health state, and
  credential record. Resource APIs select a registered UUID; they never accept
  a caller-supplied API URL or credential.
- Import accepts one explicit kubeconfig context. Exec credential plugins,
  insecure TLS settings, non-HTTPS endpoints, and unsupported authentication
  forms are rejected. The API validates reachability and the granted read
  permissions before storing the registration.
- Kubeconfig bytes are encrypted at rest with authenticated encryption and
  cluster UUID associated data. Responses and audit events never contain
  kubeconfig material, bearer tokens, client keys, or certificate data.
- The Core uses the imported cluster's Kubernetes RBAC. It does not escalate to
  `cluster-admin`; absent permissions remain visible as capability failures.
- Node mappings are cluster-scoped and do not merge cluster membership or
  authorize cross-cluster resource operations.

## Consequences

K3s clusters remain independent and may be added, checked, or removed without
joining their control planes. Losing the encryption key makes imported
credentials unusable; deployments must back up that key alongside the database.
