# Cluster Provider plan API deployment

## Artifact and deployment

- Commit: `cbda877094c17e6344ba714e277d344d2cad8ffe`
- GitHub Actions publish workflow: `39750216285`, success
- Core image: `ghcr.io/geekcorelab/nanpanel-core:sha-cbda877094c17e6344ba714e277d344d2cad8ffe`
- Core image digest: `sha256:669be1c24693b5e11291b4d77a6a92e1f68f49d35ab3f69f6e2d953d3ff511ce`
- Target: `k3s-1`; only the `nanpanel-core` Deployment image was updated.
- Web remained on `sha-8722235f8ed61e25aa32d407d1c004e0f200d8be`.
- PostgreSQL StatefulSet remained `postgres:18`, 1/1 Ready; its pre-existing
  restart count remained 1.

## Runtime evidence

- Core rollout completed successfully. Both Core Deployment containers were
  Ready with zero restarts on the new image.
- Core logs show Liquibase applied
  `db/changelog/db.changelog-master.xml::034-cluster-provider-operations::nanpanel`
  successfully and reported 34 total changesets.
- A temporary SSH tunnel to the Core service returned `/q/health/ready` with
  status `UP` and the database connection check `UP`.
- Unauthenticated `GET /api/v1/cluster-providers` returned `401` as expected.
- No Web or PostgreSQL rollout occurred.

## Acceptance boundary

The local authenticated integration test covers plan persistence, idempotent
replay and conflict, plan restoration, current-generation validation, and drift
rejection. No authenticated production lifecycle request was created or
validated. The admin login was rejected earlier in this run and the user chose
to skip the live preflight without changing `.env`. This deployment does not
establish KubeEdge execution, recovery, or live target acceptance.
