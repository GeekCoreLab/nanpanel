# KubeEdge CloudCore plan Provider code acceptance

## Scope

This isolated Provider test covers only creation and restoration of a
CloudCore installation plan. The Provider cannot contact or mutate a cluster.
No KubeEdge target is available, so this is not live lifecycle acceptance.

## Behavior

- PF4J Provider ID is `kubeedge-core`; it accepts only cluster kind `KUBEEDGE`
  and declares only `INSTALL_CLOUDCORE`.
- A plan is produced only for a `NOT_OBSERVED` CloudCore status from a matching,
  healthy snapshot observed within 120 seconds.
- The plan contains one Kubernetes API step with fixed capability
  `kubeedge.cloudcore.install`, fixed `kubeedge` namespace and `cloudcore`
  release name, and a validated target version. It includes no credentials.
- Restore and validation inspect the stored plan shape and current snapshot;
  generation changes, stale/unhealthy observations, malformed steps, or an
  observed CloudCore prevent validation.
- The plugin has no Kubernetes client, kubeconfig, ServiceAccount token, or
  apply/compensate method. Core execution, credential custody, and recovery
  remain unimplemented.

## Evidence

Command:

```text
./mvnw -pl plugins/provider-kubeedge -am test
```

Result: 4 tests passed, 0 failures, 0 errors, 0 skipped. The Provider JAR
contains its PF4J `extensions.idx` entry and plugin manifest. OpenAPI and Web
types are unchanged by this Provider-only change.
