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

## Live revalidation

Rechecked on 2026-10-21 after subsequent P4 updates:

- Core and Web are still running immutable image
  `sha-29827b84964621b0c11e0cf2b95c57ffd01f6872`; both Deployments are 1/1
  ready, and both current Pods have zero restarts. This image descends from the
  discovery implementation commit `ba85ac183b49b77e6315cff1b670b17af831543c`.
- `nanpanel-agent.service` is active on `k3s-1`. The latest persisted discovery
  is `k3s-1|K3S|true|true|true`, and its observation is less than five minutes
  old. The current Agent binary SHA-256 is
  `7b7bfdebbaf971993f10e954ecbc34abc3232c61d4800c726ef8fe841a3ad200`.
  The managed cluster `k3s-1-k3s` remains `HEALTHY`.
- Core readiness is `UP`; the served Web bundle still contains the discovery
  panel and the kubeconfig import prompt. PostgreSQL PVC remains Bound.
- No deployment was needed during this revalidation: source changes after the
  running image contain only Core test coverage and documentation, not runtime
  code. Automatic discovery records runtime evidence; a newly discovered API
  cluster still requires an administrator to import and verify its kubeconfig
  before resource management is enabled.

## 2026-10-22 auto-enrollment follow-up

The later same-cluster auto-enrollment implementation and its evidence are
recorded separately in
[`2026-10-22-kubernetes-auto-enrollment.md`](2026-10-22-kubernetes-auto-enrollment.md).
That follow-up supersedes the final paragraph above for the existing
`k3s-1` cluster: its Agent observation now claims the matching imported cluster
and maps the Ready Node automatically. The new-cluster branch has isolated
integration coverage; it has not yet been demonstrated on an initially
unimported live K3s cluster.
