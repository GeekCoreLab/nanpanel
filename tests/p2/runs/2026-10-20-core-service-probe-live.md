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

## Authenticated API acceptance remains open

`tests/p2/core-service-probe-live.sh` reached the Core login endpoint but
received HTTP 401 using the available bootstrap and workspace development
credentials. No credential was reset or recorded. To complete API-level
acceptance, set the current K3s administrator password as
`NANPANEL_P2_ADMIN_PASSWORD` in the ignored local `.env`, then run the script
with the trusted test certificate as documented in `tests/p2/README.md`.

This record does not claim the authenticated inventory and probe API acceptance
gate has passed.
