# K3s multi-node installation plan code evidence

Date: 2026-10-21

## Implemented

- Core persists explicit K3s installation drafts with one Server followed by
  up to 99 distinct Agent hosts. Creation checks current Host generation,
  online Agent/Helper state, advertised `k3s.install` capability, active
  operations, and existing Kubernetes ownership/runtime discovery.
- The short K3s token is encrypted with AES-GCM using the configured
  Kubernetes credential key and plan-bound associated data. It is absent from
  plan views, operation records, and audit summaries; terminal plans clear the
  encrypted token.
- An explicit start rechecks host generation, Agent freshness, Helper state,
  capability, and pending operations. A persistent worker submits one Helper
  operation at a time with a stable idempotency key.
- Server installation must finish before the plan enters
  `AWAITING_CLUSTER_REGISTRATION`. The administrator must import the K3s API
  and map the Ready Server Node to the selected Server Host before Agent steps
  are unlocked.
- If Core restarts after the Host Helper accepted the operation but before the
  plan saved its operation ID, the `SUBMITTING` step replays with the same
  idempotency key. The scheduler test verifies one Helper dispatch and one
  persisted operation after this recovery window.
- Each Agent step waits for the cluster API to observe a present, Ready Node
  at the requested `kubeletVersion`, mapped to that exact Host. The next Agent
  is not submitted until all present Nodes are Ready and mapped. A failed or
  unknown Helper result stops the plan; temporary API errors leave it waiting.
- The Kubernetes UI exposes draft creation, explicit start, cluster association,
  ordered per-host status, and polling. It instructs operators to map each
  newly joined Node through the existing cluster Node table.

## Code validation

- `./mvnw -pl backend/panel-core -am -Dtest=K3sInstallationApiTest,KubernetesClusterCredentialVaultTest -Dsurefire.failIfNoSpecifiedTests=false test`
  passed: six tests, no failures. Coverage includes validation rejection,
  missing-host rejection without token echo, ordered persistent Server/Agent
  draft creation, encrypted-token persistence, plan-context binding, and
  authenticated-encryption tamper rejection. A fake-Gateway run also proves
  Server-first submission and that Agent steps remain undispatched until
- `./mvnw -pl backend/panel-core -am test` passed with 32 tests, no failures or
  errors; this includes the new scheduler case, direct-install ownership gate,
  and Kubernetes registry credential regression tests. The focused regression
  command covering `SystemResourceTest`, `K3sInstallationApiTest`,
  `HostHelperOperationResourceTest`, and `KubernetesClusterCredentialVaultTest`
  passed all 11 selected tests.
- `npm run build` passed.
- `npm test` passed: 11 tests, no failures.
- OpenAPI parsed successfully with PyYAML; `python3 web/scripts/generate-types.py`
  regenerated the TypeScript DTOs.
- `git diff --check` passed.

The direct single-host `k3s.install` API now rejects any Host that is already
mapped to a Kubernetes Node or has a positive kubelet/K3s runtime discovery
record. The SystemResource integration case reports runtime discovery as absent
before checking the valid first-install path, while a separate mapped-Host case
proves the `409 K3S_INSTALL_HOST_ALREADY_MANAGED` rejection.

## Acceptance still required

This is code-level evidence. No installation plan has been executed on a
disposable multi-node K3s cluster. Successful Server bootstrap, import-and-map
pause/resume, sequential Agent registration, operation failure/UNKNOWN handling,
Core restart recovery, and Kubernetes API interruption still need an isolated
multi-node integration run. Do not use production `k3s-1` for first-install or
failure-injection tests. The broader P4 K3s lifecycle gate remains open until
that run and the separate upgrade-plan fault-recovery run pass.
