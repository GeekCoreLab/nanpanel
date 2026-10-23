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
- The Host Agent may report local kubelet/K3s runtime evidence for discovery.
  Discovery never transfers a host kubeconfig. When the opt-in local K3s
  auto-enrollment is enabled and the reporting Host is a Ready API Server Node
  in Core's own cluster, with a fresh Host network inventory containing that
  Node's InternalIP, Core may register that cluster using its existing
  read-only in-cluster identity and map the Node UID to the Host. The IP check
  supplements the Agent's local API-port observation and hostname lookup; it
  does not treat either as proof of cluster identity. Other
  discovered clusters still require an administrator to import and validate
  credentials; secure credential issuance and rotation for remote Agents are
  not implemented.
- Node mappings are cluster-scoped and do not merge cluster membership or
  authorize cross-cluster resource operations.
- A multi-node K3s first-install plan uses a separately domain-bound AES-GCM
  envelope for its short join token, keyed by the existing Kubernetes
  credential key and plan UUID. The plan installs one Server first and pauses
  until an imported K3s cluster reports that Ready Node mapped to the Server
  Host. It then installs Agents serially and waits for each requested-version
  Ready Node to be mapped to its planned Host before advancing. Plan and audit
  views never return the join token. This does not transfer kubeconfig; an
  administrator must still import and validate the API credentials.

## Consequences

K3s clusters remain independent and may be added, checked, or removed without
joining their control planes. Losing the encryption key makes imported
credentials unusable; deployments must back up that key alongside the database.
