# P5 panel deployment to k3s-1

## Release

Deployed NaNPanel commit `b24800f8b8a8b2455e54d778c14a847829198b2d` to the
single-node `k3s-1` test cluster on 2026-10-25. GitHub Actions run
[40155793221](https://github.com/GeekCoreLab/nanpanel/actions/runs/40155793221)
passed Rust formatting, all-target/all-feature check, Clippy, tests, Maven
`verify`, and Web build, then published both immutable GHCR images tagged
`sha-b24800f8b8a8b2455e54d778c14a847829198b2d`.

The release used `deploy/k3s/deploy.sh`, which verifies the private GHCR image
manifests before applying resources. The cluster was running K3s
`v1.33.5+k3s1`; before deployment Core and Web used
`sha-65687945b59975199475ca9860b933ab3402bfbf`.

## Runtime verification

- Core and Web each reached `1/1` Ready on the new immutable image tag. Both
  new application pods had zero restarts; the old Core pod exited after the
  rollout.
- PostgreSQL remained `1/1` Running. The existing 5 GiB PVC stayed Bound to
  volume `pvc-3428a4ec-567f-4678-b44c-fd548a57e5b2`; database, TLS, Agent, and
  credential-key Secrets were retained.
- Liquibase recorded `037-application-releases` and
  `038-host-application-releases`.
- The Agent service was Active with zero restarts and reported `ONLINE` in
  Core. Its host binary SHA-256 matched `/work/nanpanel-agent` in the new Core
  image: `8b9e04ba15389459d284829890bcc68de0fcd32280ef5e9c25dd77fcf66ce012`.
- Core HTTPS readiness returned `UP`, including the database check. HTTP on
  port `30081` returned the configured `308` redirect to HTTPS.
- A headless browser loaded the HTTPS console with status `200`, displayed the
  username/password login form, loaded the JavaScript bundle with status `200`,
  and reported no page errors or failed requests. No login or release plan was
  created during this smoke check.

This updates the NaNPanel panel installation only. The shared cluster was not
used for P5 canary traffic or installation of rollout controllers. Kubernetes
controller/canary acceptance used disposable clusters; host-release acceptance
is recorded separately in its isolated run reports.
