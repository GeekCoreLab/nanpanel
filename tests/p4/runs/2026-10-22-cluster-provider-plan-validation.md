# Cluster Provider plan persistence and validation

## Scope

This is isolated Core API evidence for the KubeEdge lifecycle boundary. It does
not establish a KubeEdge installation, execution, recovery, or live cluster
acceptance. The current target environment has no KubeEdge cluster. The user
also chose to skip the live `k3s.preflight` check for this run.

## Implementation covered

- Core persists Provider plans with an idempotency key, a request fingerprint,
  cluster generation, the sanitized plan configuration, and an audit event.
- Matching idempotent retries return the saved operation without calling the
  Provider again. Reusing a key for a different request returns a conflict.
- Plan parameters are allowlisted before persistence; credential references
  and unsupported parameter keys are rejected.
- Validation restores the persisted plan through the Provider and checks it
  against the current registered cluster snapshot. A changed generation moves
  the operation to `DRIFTED`.
- This API does not dispatch Kubernetes API or Agent Helper steps.

## Evidence

Command:

```text
./mvnw -pl backend/panel-core -am test
```

Result: 46 tests passed, 0 failures, 0 errors, 0 skipped. The focused
`NetworkProviderApiTest` also passed after the final implementation change.
The integration fixture persisted and restored a plan through H2, exercised
idempotent replay and conflicting reuse, validated a current-generation plan,
and rejected validation after the registered cluster generation changed.

The OpenAPI YAML parsed successfully, generated Web API types were refreshed,
and `git diff --check` passed. Web source and the running K3s service were not
changed or deployed as part of this local evidence.

## Remaining boundary

The plan and validation API still needs a remote Core deployment and production
database migration acceptance. KubeEdge Provider behavior, credential
custody, execution, post-execution verification, compensation, and safe
Core/Agent Helper recovery remain unimplemented. No live `k3s.preflight` was
run per user choice.
