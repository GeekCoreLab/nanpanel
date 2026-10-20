# Live Core-to-cluster service probe

- Verified: 2026-10-20 12:25 UTC
- Commit: `f0d804ba3bac19a0e501317865d38129068c72e7`
- CI: [Publish NaNPanel containers](https://github.com/GeekCoreLab/nanpanel/actions/runs/39420584418) passed validation and published the Core and Web images.
- Cluster: `k3s-1`; namespace: `nanpanel-mesh`
- Scope: Core only; the Web Deployment and PostgreSQL StatefulSet were not updated.

## Core deployment

The Core Deployment rolled out from the immutable GHCR tag
`ghcr.io/geekcorelab/nanpanel-core:sha-f0d804ba3bac19a0e501317865d38129068c72e7`.
Rollout completed with one ready replica, the `nanpanel-core` ServiceAccount,
and zero restarts on the new Core and Agent Gateway containers. The Core health
endpoint returned HTTP 200 with the test certificate explicitly trusted.

The `nanpanel-config` ConfigMap enables discovery and probes for the fixed
`k3s-1` / `nanpanel-mesh` source. The namespace RoleBinding allows listing
Services and EndpointSlices only; live impersonation checks returned `yes`,
`yes`, `no`, `no` for Service list, EndpointSlice list, Pod list, and Secret get.

## Persisted probe evidence

A read-only query of Core's PostgreSQL records confirmed that Core discovered
the fixture and successfully connected to its Ready private EndpointSlice Pod:

```text
PASS: persisted source=kubernetes:k3s-1 namespace=nanpanel-mesh service=mesh-probe-target targetPod=mesh-probe-target-5dbc566594-cnb7m protocol=TCP/8080 outcome=SUCCEEDED inventorySnapshotMatched=True; address omitted.
```

The result and matching inventory snapshot were observed at 12:25 UTC. The
endpoint address was not printed. This proves Core-side discovery, TCP
connectivity, and persistence from the live cluster.

## Initial credential probe at 12:25 UTC

`tests/p2/core-service-probe-live.sh` reached the Core login endpoint but
received HTTP 401 using the available bootstrap and workspace development
credentials. No credential was reset or recorded. This was the reason to add
scoped API key authentication for the later API acceptance step.

## API-key release and current deployment

- Verified: 2026-10-20 12:59 UTC
- Commit: `8aa531ef17edf58d0836625d04597ccee46908ba`
- CI: [Publish NaNPanel containers](https://github.com/GeekCoreLab/nanpanel/actions/runs/39424797389) passed Rust format/check/clippy/tests, Maven verify, Web build, and published both images.
- Cluster: `k3s-1`, namespace `nanpanel`; Core and Web were updated from immutable GHCR tags. PostgreSQL was not updated.
- Core runtime image ID: `ghcr.io/geekcorelab/nanpanel-core@sha256:c134e7ed5e2ebbb220fb77238d0f577c02ebd3e8609dc8e03c9a3a3493ee34d6`.
- Web runtime image ID: `ghcr.io/geekcorelab/nanpanel-web@sha256:6d2b9ad5e4c40acaac53c2c189ffd64dc2c650bbd36bb7679626ab77e81e7954`.

Both Deployments have one Ready replica; Core and Agent Gateway each have zero
restarts, and Web has zero restarts. Core readiness returned HTTP 200 and the
Web root returned HTTP 200. The served Web bundle contains the API-key account
page, `/auth/api-keys` calls, and the Kubernetes inventory read scope. A
read-only database query confirmed the `public.api_key` migration is present.

The latest persisted record after rollout was:

```text
kubernetes:k3s-1|mesh-probe-target|TCP/8080|SUCCEEDED|inventorySnapshotMatched=true|2026-10-20 12:58:49 UTC
```

No endpoint address was queried for this report. An unauthenticated request to
the API-key list returned HTTP 401. Authenticated inventory/probe API
acceptance remains open until a current administrator creates a
`kubernetes:inventory:read` key in the deployed console and stores it locally
as `NANPANEL_P2_API_KEY` in ignored `.env`. Then run
`tests/p2/core-service-probe-live.sh` with the trusted test certificate as
documented in `tests/p2/README.md`; never send the key in chat.
