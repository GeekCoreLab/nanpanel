# KubeEdge EdgeCore Helper dispatch code check

Date: 2026-10-22

## Scope

This is code and isolated-test evidence for the single-step `kubeedge-core`
`join_edgecore` plan execution path. It does not claim that an EdgeCore host or
KubeEdge cluster was available, that `keadm join` ran, or that an Edge Node
registered successfully.

## Implemented path

- The authenticated Provider execution route accepts only a persisted,
  validated, one-step EdgeCore join plan.
- Before dispatch, Core checks the current cluster generation, restores and
  validates the plan again, verifies the target Host and scoped
  `EDGECORE_BOOTSTRAP_TOKEN` credential, and then opens the credential.
- The execution idempotency key is derived from the persisted plan ID and step
  ordinal. The response is the existing Host Operation; the Provider plan
  detail records that operation ID for follow-up.
- The general Host Helper endpoint rejects direct requests for
  `kubeedge.edgecore.join`. Agent and gateway payload validation accepts only a
  bounded stable version, CloudCore `host:port`, and JWT-shaped token.
- The root Helper advertises this capability only when a root-owned `keadm`
  binary is available. It requires systemd, refuses an existing
  `edgecore.service`, invokes fixed `keadm join` arguments, verifies the
  service is active, and never returns the token. An uncertain join result is
  surfaced for manual review because `keadm` may have partially changed the
  host.

## Verification

- `./mvnw -pl backend/panel-core -am -Dtest=SystemResourceTest,NetworkProviderApiTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed, 5 tests.
- `cargo fmt --all -- --check` — passed.
- `cargo check --all-targets --all-features` — passed.
- `cargo clippy --all-targets --all-features -- -D warnings` — passed.
- `cargo test --all-features` — passed, including 17 privileged-helper tests.
- OpenAPI YAML parse and `git diff --check` — passed.

## Acceptance still required

There is no KubeEdge target in the available environment. No live Provider
credential, `keadm join`, EdgeCore service, Node registration, compensation, or
restart recovery check was performed. A successful Helper result confirms only
that `edgecore.service` is active; it does not establish that the Kubernetes
API registered the corresponding Edge Node. The only production Kubernetes
target is the user's single-node `k3s-1`, and the selected live `k3s.preflight`
check was skipped.
