# Agent Kubernetes runtime discovery deployment

- Date: 2026-10-21
- Source commit: `ba85ac183b49b77e6315cff1b670b17af831543c`
- GitHub Actions: [39577241423](https://github.com/GeekCoreLab/nanpanel/actions/runs/39577241423) (`validate` and `publish` succeeded)
- Target: SSH alias `k3s-1`, namespace `nanpanel`
- Images: Core and Web use the immutable `sha-ba85ac183b49b77e6315cff1b670b17af831543c` tags.

## Deployment evidence

- Core and Web Deployments are available at 1/1 with zero pod restarts. Both
  report the expected full-SHA image tags.
- PostgreSQL StatefulSet is Ready. PVC `data-nanpanel-postgres-0` remains Bound
  to `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`.
- The database records Liquibase changeset
  `030-host-kubernetes-runtime-discovery`.
- `nanpanel-agent.service` and `nanpanel-helper.service` are active. The Agent
  binary SHA-256 is
  `c8ba9a1808f6895998424ecec814094bdcebe65afaf052532d0420611e6bb162`; the
  previous Agent binary is backed up at
  `/var/lib/nanpanel-agent/nanpanel-agent.pre-e4e330f74cee2b9394328c24ecfc6771d8158f70cfe44d906f2aedb7e9d86c4a`.
- The persisted runtime observation is `k3s-1|K3S|true|true|true` (runtime
  detected, kubelet reported running, local API port reachable).
- Core readiness returns `UP`; the HTTPS panel returns `200`, HTTP redirects
  with `308`, and unauthenticated discovery API access returns `401`.
- The served Web bundle contains the Agent auto-discovery panel strings.

The first deploy-script invocation reached its four-minute Core rollout wait
before the node finished pulling the new image. K3s retained the previous Core
replica and the database remained available. After the pull completed, the
idempotent deploy script finished successfully and updated the Agent from the
published Core image. No kubeconfig was transferred; discovery records do not
grant Core Kubernetes API access.
