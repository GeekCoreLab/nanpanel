package io.nanpanel.provider.kubeedge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.plugin.api.ClusterProvider;
import io.nanpanel.plugin.api.ClusterProvider.ClusterAccess;
import io.nanpanel.plugin.api.ClusterProvider.ClusterPlan;
import io.nanpanel.plugin.api.ClusterProvider.ClusterReference;
import io.nanpanel.plugin.api.ClusterProvider.ClusterSnapshot;
import io.nanpanel.plugin.api.ClusterProvider.LifecycleRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KubeEdgeClusterProviderTest {
  private final KubeEdgeClusterProvider provider = new KubeEdgeClusterProvider();

  @Test
  void plansAndRestoresCloudCoreInstallOnlyWhileItIsNotObserved() {
    UUID clusterId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 12);
    ClusterSnapshot snapshot = snapshot(clusterId, 12, "NOT_OBSERVED", "HEALTHY");
    ClusterAccess access = () -> java.util.concurrent.CompletableFuture.completedFuture(snapshot);
    var observation = provider.observe(reference, access).toCompletableFuture().join();
    ClusterPlan plan = provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0", null),
        observation).toCompletableFuture().join();

    assertEquals(Set.of("KUBEEDGE"), provider.clusterKinds());
    assertEquals("kubeedge.cloudcore.install", plan.steps().getFirst().capability());
    assertEquals("kubeedge", plan.steps().getFirst().parameters().get("namespace"));
    assertTrue(provider.restorePlan(reference, plan, access).toCompletableFuture().join().successful());
    assertTrue(provider.validate(reference, plan, access).toCompletableFuture().join().successful());
  }

  @Test
  void rejectsExistingUnknownOrMismatchedClusterState() {
    UUID clusterId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 4);
    ClusterSnapshot present = snapshot(clusterId, 4, "HEALTHY", "HEALTHY");
    ClusterAccess access = () -> java.util.concurrent.CompletableFuture.completedFuture(present);
    var observation = provider.observe(reference, access).toCompletableFuture().join();

    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0", null),
        observation).toCompletableFuture().join());
    ClusterAccess absentAccess = () -> java.util.concurrent.CompletableFuture.completedFuture(
        snapshot(clusterId, 4, "NOT_OBSERVED", "HEALTHY"));
    var absentObservation = provider.observe(reference, absentAccess).toCompletableFuture().join();
    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0;bad", null),
        absentObservation).toCompletableFuture().join());
    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0", UUID.randomUUID()),
        absentObservation).toCompletableFuture().join());
    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.observe(
        new ClusterReference(clusterId, "K3S", 4), access).toCompletableFuture().join());
  }

  @Test
  void refusesPlanningFromUnhealthyOrStaleSnapshots() {
    UUID clusterId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 4);
    ClusterSnapshot unhealthy = snapshot(clusterId, 4, "NOT_OBSERVED", "UNAVAILABLE");
    ClusterAccess access = () -> java.util.concurrent.CompletableFuture.completedFuture(unhealthy);
    var observation = provider.observe(reference, access).toCompletableFuture().join();

    assertEquals("UNKNOWN", observation.status());
    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0", null),
        observation).toCompletableFuture().join());

    ClusterSnapshot stale = new ClusterSnapshot(clusterId, 4, "KUBEEDGE", "HEALTHY",
        "NOT_OBSERVED", Instant.now().minusSeconds(121), List.of());
    ClusterAccess staleAccess = () -> java.util.concurrent.CompletableFuture.completedFuture(stale);
    assertEquals("UNKNOWN", provider.observe(reference, staleAccess).toCompletableFuture()
        .join().status());
  }

  @Test
  void restoredPlanDriftsWhenSnapshotChangesOrPlanIsMalformed() {
    UUID clusterId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 4);
    ClusterSnapshot absent = snapshot(clusterId, 4, "NOT_OBSERVED", "HEALTHY");
    ClusterAccess originalAccess = () -> java.util.concurrent.CompletableFuture.completedFuture(absent);
    var observation = provider.observe(reference, originalAccess).toCompletableFuture().join();
    ClusterPlan plan = provider.plan(reference,
        new LifecycleRequest("install_cloudcore", "cloudcore", null, "v1.21.0", null),
        observation).toCompletableFuture().join();
    ClusterAccess changedAccess = () -> java.util.concurrent.CompletableFuture.completedFuture(
        snapshot(clusterId, 5, "NOT_OBSERVED", "HEALTHY"));

    var drift = provider.restorePlan(reference, plan, changedAccess).toCompletableFuture().join();
    assertFalse(drift.successful());
    assertEquals("CLUSTER_STATE_CHANGED", drift.code());
  }

  private static ClusterSnapshot snapshot(UUID clusterId, long generation,
      String cloudCoreStatus, String health) {
    return new ClusterSnapshot(clusterId, generation, "KUBEEDGE", health,
        cloudCoreStatus, Instant.now(), List.of());
  }
}
