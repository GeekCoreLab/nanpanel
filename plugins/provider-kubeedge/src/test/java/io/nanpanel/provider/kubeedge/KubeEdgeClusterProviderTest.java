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
import io.nanpanel.plugin.api.ClusterProvider.NodeSnapshot;
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

  @Test
  void plansEdgeCoreJoinOnlyForHealthyCloudCoreAndUnregisteredHost() {
    UUID clusterId = UUID.randomUUID();
    UUID hostId = UUID.randomUUID();
    UUID credentialId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 8);
    ClusterSnapshot healthy = snapshot(clusterId, 8, "HEALTHY", "HEALTHY");
    ClusterAccess access = () -> java.util.concurrent.CompletableFuture.completedFuture(healthy);
    var observation = provider.observe(reference, access).toCompletableFuture().join();

    ClusterPlan plan = provider.plan(reference,
        new LifecycleRequest("join_edgecore", "edgecore", hostId, "v1.21.0", credentialId,
            "cloudcore.example.com:10000"), observation).toCompletableFuture().join();
    var step = plan.steps().getFirst();
    assertTrue(provider.operations().contains("JOIN_EDGECORE"));
    assertEquals(ClusterProvider.ExecutionTarget.AGENT_HELPER, step.target());
    assertEquals(hostId, step.hostId());
    assertEquals("kubeedge.edgecore.join", step.capability());
    assertEquals("cloudcore.example.com:10000", step.parameters().get("cloudCoreEndpoint"));
    assertEquals("EDGECORE_BOOTSTRAP_TOKEN", step.parameters().get("credentialPurpose"));
    assertEquals(List.of(credentialId), step.credentialReferences());
    assertTrue(provider.restorePlan(reference, plan, access).toCompletableFuture().join().successful());

    ClusterSnapshot registered = new ClusterSnapshot(clusterId, 8, "KUBEEDGE", "HEALTHY",
        "HEALTHY", Instant.now(), List.of(new NodeSnapshot("node-1", "edge-1", true,
            true, true, "v1.21.0", hostId)));
    ClusterAccess registeredAccess = () -> java.util.concurrent.CompletableFuture
        .completedFuture(registered);
    var drift = provider.validate(reference, plan, registeredAccess).toCompletableFuture().join();
    assertFalse(drift.successful());
    assertEquals("CLUSTER_STATE_CHANGED", drift.code());
  }

  @Test
  void rejectsIncompleteOrMalformedEdgeCoreJoinRequests() {
    UUID clusterId = UUID.randomUUID();
    ClusterReference reference = new ClusterReference(clusterId, "KUBEEDGE", 8);
    ClusterAccess access = () -> java.util.concurrent.CompletableFuture.completedFuture(
        snapshot(clusterId, 8, "HEALTHY", "HEALTHY"));
    var observation = provider.observe(reference, access).toCompletableFuture().join();
    List<LifecycleRequest> invalidRequests = List.of(
        new LifecycleRequest("join_edgecore", "edgecore", UUID.randomUUID(), "v1.21.0",
            UUID.randomUUID()),
        new LifecycleRequest("join_edgecore", "edgecore", UUID.randomUUID(), "v1.21.0",
            UUID.randomUUID(), "https://cloudcore.example.com:10000/path"),
        new LifecycleRequest("join_edgecore", "edgecore", UUID.randomUUID(), "v1.21.0",
            UUID.randomUUID(), "cloudcore.example.com:65536"),
        new LifecycleRequest("join_edgecore", "cloudcore", UUID.randomUUID(), "v1.21.0",
            UUID.randomUUID(), "cloudcore.example.com:10000"),
        new LifecycleRequest("join_edgecore", "edgecore", UUID.randomUUID(), "v1.21.0",
            null, "cloudcore.example.com:10000"));

    for (LifecycleRequest request : invalidRequests) {
      assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(
          reference, request, observation).toCompletableFuture().join());
    }
    var degradedObservation = new ClusterProvider.ClusterObservation(clusterId, 8, "DEGRADED",
        null, List.of("clusterHealth=HEALTHY"));
    assertThrows(java.util.concurrent.CompletionException.class, () -> provider.plan(reference,
        new LifecycleRequest("join_edgecore", "edgecore", UUID.randomUUID(), "v1.21.0",
            UUID.randomUUID(), "cloudcore.example.com:10000"), degradedObservation)
            .toCompletableFuture().join());
  }

  private static ClusterSnapshot snapshot(UUID clusterId, long generation,
      String cloudCoreStatus, String health) {
    return new ClusterSnapshot(clusterId, generation, "KUBEEDGE", health,
        cloudCoreStatus, Instant.now(), List.of());
  }
}
