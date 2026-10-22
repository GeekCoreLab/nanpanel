package io.nanpanel.core.p3;

import io.nanpanel.plugin.api.ClusterProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class P3FixtureClusterProvider implements ClusterProvider {
  @Override public String id() { return "p3-fixture-cluster-provider"; }
  @Override public Set<String> clusterKinds() { return Set.of("KUBEEDGE"); }
  @Override public Set<String> operations() { return Set.of("OBSERVE", "INSTALL_CLOUDCORE"); }

  @Override
  public CompletionStage<ClusterObservation> observe(ClusterReference cluster, ClusterAccess access) {
    return access.snapshot().thenApply(snapshot -> new ClusterObservation(snapshot.clusterId(),
        snapshot.generation(), snapshot.healthStatus(), snapshot.cloudCoreStatus(),
        List.of("nodes=" + snapshot.nodes().size(),
            "edgeNodes=" + snapshot.nodes().stream().filter(NodeSnapshot::edge).count())));
  }

  @Override
  public CompletionStage<ClusterPlan> plan(
      ClusterReference cluster, LifecycleRequest request, ClusterObservation observed) {
    if (!"install_cloudcore".equals(request.operation())
        || !"cloudcore".equals(request.component()) || request.targetVersion() == null) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported fixture plan"));
    }
    List<UUID> credentials = request.credentialReference() == null
        ? List.of() : List.of(request.credentialReference());
    return CompletableFuture.completedFuture(new ClusterPlan(UUID.randomUUID(), cluster.id(),
        cluster.generation(), request.operation(), cluster.id() + "-" + request.targetVersion(),
        List.of(new PlanStep(0, ExecutionTarget.KUBERNETES_API, null, "kubeedge.cloudcore.install",
            Map.of("version", request.targetVersion()), credentials))));
  }

  @Override
  public CompletionStage<ValidationResult> validate(
      ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
    return access.snapshot().thenApply(snapshot -> new ValidationResult(
        snapshot.clusterId().equals(plan.clusterId())
            && snapshot.generation() == plan.generation()
            && cluster.id().equals(plan.clusterId()),
        "VALID", "fixture plan validated against current Core snapshot"));
  }

  @Override
  public CompletionStage<ValidationResult> restorePlan(
      ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
    return CompletableFuture.completedFuture(new ValidationResult(
        cluster.id().equals(plan.clusterId()), "RESTORED", "fixture plan state restored"));
  }
}
