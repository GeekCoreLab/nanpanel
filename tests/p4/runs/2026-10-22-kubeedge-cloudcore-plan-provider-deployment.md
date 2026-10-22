# KubeEdge CloudCore plan Provider deployment

## Artifact and deployment

- Source commit: `fb6ee0a1ebded95039cbd2834830dcce18b13351`
- Publish workflow: `39752729714`, success
- Core image: `ghcr.io/geekcorelab/nanpanel-core:sha-fb6ee0a1ebded95039cbd2834830dcce18b13351`
- Core image digest: `sha256:8f91c32dbbe6c6bc5a387d17afe14e821957b0db3f58bb492145d7b8e5a21796`
- Target: `k3s-1`; only the Core Deployment was updated.
- The Provider JAR `provider-kubeedge-0.1.0-SNAPSHOT.jar` is present in
  `/work/providers` in the running Core Pod.
- Web remains on `sha-8722235f8ed61e25aa32d407d1c004e0f200d8be`; PostgreSQL
  remains on `postgres:18`.

## Runtime evidence

- Core rollout completed with the new Pod 2/2 Ready and zero restarts.
- `/q/health/ready` returned `UP` with the database connection check `UP`.
- Unauthenticated `GET /api/v1/cluster-providers` returned `401` as expected.
- No Web or PostgreSQL rollout occurred; PostgreSQL retains its earlier restart
  count of 1.

## Acceptance boundary

The admin login was rejected earlier and the user chose not to modify `.env`.
Therefore the authenticated provider listing and plan routes were not tested on
the deployed instance. The deployed JAR presence, image build, local Provider
tests, Core readiness, and API authentication boundary do not establish live
KubeEdge installation or execution. There is no KubeEdge target available.
