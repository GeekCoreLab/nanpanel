# k3s-1 Linux netns Provider deployment

- Date: 2026-10-26.
- Commit: `d0b9d17946b9a1de63be904f85217b7fa1095a34` (`Add isolated Linux topology provider`).
- GitHub Actions: [Publish NaNPanel containers](https://github.com/GeekCoreLab/nanpanel/actions/runs/40217660339), both `validate` and `publish` jobs succeeded.
- Images: `ghcr.io/geekcorelab/nanpanel-core:sha-d0b9d17946b9a1de63be904f85217b7fa1095a34` and `ghcr.io/geekcorelab/nanpanel-web:sha-d0b9d17946b9a1de63be904f85217b7fa1095a34`. The deployment script verified the remote registry manifests and pulled the images on `k3s-1`; no local image upload was used.

## Deployment evidence

- Before rollout, Core and Web were on immutable tag `sha-59ce7521df4aa7d8ed8708612f24f5dd3f6aaa53`, each `1/1` Ready. PostgreSQL was `1/1` Ready and its 5 GiB PVC was `Bound`.
- `./deploy/k3s/deploy.sh` completed successfully for the full SHA tag. Core and Web reported successful rollouts; afterwards each deployment was `1/1` Ready on the new tag.
- PostgreSQL remained `1/1` Ready and the existing `data-nanpanel-postgres-0` PVC remained `Bound` to the same volume.
- `https://10.100.0.1:30443/` returned HTTP 200. The deployed JavaScript bundle contains the `Linux netns`, `linux-netns`, and `network.topology.reconcile` Provider identifiers. `/q/health/ready` returned HTTP 200 with database connections `UP`.
- `nanpanel-helper.service` and `nanpanel-agent.service` were active after restart. The Helper socket was present at `/run/nanpanel/helper.sock`.
- The deployment script compared binaries from the running Core image with the host artifacts, then replaced the Root Helper and Agent. Root Helper SHA-256: `2af93a5c631cf489e1d70a81f4ff01a9b07251fbddf6a8b16dc21371c9e5335c`; Agent SHA-256: `60e5059fed1e9033c7c04816e97a1085679d8ddff342aefe6a81e03882ad0f15`. Previous binaries were retained under `/var/lib/nanpanel-helper` and `/var/lib/nanpanel-agent`.
- A read-only `helper.capabilities` request over the Helper Unix socket, sent as its configured Agent UID, returned `network.topology.reconcile=true` among 14 capabilities.

## Acceptance boundary

No topology draft, Provider operation, or host network configuration was changed on `k3s-1`. This run verifies registry publication, deployment, runtime binary synchronization, and dynamic Helper capability only. It does not execute Provider Apply/Verify/Compensate against a production topology or prove target-host data-plane connectivity. `k3s.preflight` was not run. Router, Firewall, Packet Tap, OVS, and management-path probes remain open P6 work.
