package io.nanpanel.core.p3;

import io.nanpanel.plugin.api.ClusterProvider;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class P3FixtureClusterProvider implements ClusterProvider {
  @Override public String id() { return "p3-fixture-cluster-provider"; }
  @Override public Set<String> clusterKinds() { return Set.of("KUBEEDGE"); }
  @Override public Set<String> operations() { return Set.of("OBSERVE"); }

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
    return CompletableFuture.failedFuture(new UnsupportedOperationException("fixture is read-only"));
  }

  @Override
  public CompletionStage<ValidationResult> validate(
      ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
    return CompletableFuture.completedFuture(
        new ValidationResult(false, "READ_ONLY", "fixture is read-only"));
  }
}
