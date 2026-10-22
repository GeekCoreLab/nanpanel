package io.nanpanel.provider.kubeedge;

import io.nanpanel.plugin.api.ClusterProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

/** Planning-only KubeEdge CloudCore provider. It never performs cluster writes. */
@Extension
public final class KubeEdgeClusterProvider implements ClusterProvider {
  private static final String OPERATION = "install_cloudcore";
  private static final String COMPONENT = "cloudcore";
  private static final String NAMESPACE = "kubeedge";
  private static final String RELEASE_NAME = "cloudcore";
  private static final String CAPABILITY = "kubeedge.cloudcore.install";

  @Override
  public String id() {
    return "kubeedge-core";
  }

  @Override
  public Set<String> clusterKinds() {
    return Set.of("KUBEEDGE");
  }

  @Override
  public Set<String> operations() {
    return Set.of(OPERATION.toUpperCase(java.util.Locale.ROOT));
  }

  @Override
  public CompletionStage<ClusterObservation> observe(ClusterReference cluster,
      ClusterAccess access) {
    return access.snapshot().thenApply(snapshot -> {
      if (!"KUBEEDGE".equals(cluster.kind()) || !cluster.id().equals(snapshot.clusterId())
          || cluster.generation() != snapshot.generation()
          || !"KUBEEDGE".equals(snapshot.kind())) {
        throw new IllegalArgumentException("KubeEdge snapshot does not match the requested cluster");
      }
      String status = freshHealthy(snapshot) ? snapshot.cloudCoreStatus() : "UNKNOWN";
      return new ClusterObservation(snapshot.clusterId(), snapshot.generation(), status, null,
          List.of("clusterHealth=" + snapshot.healthStatus(),
              "cloudcore=" + snapshot.cloudCoreStatus()));
    });
  }

  @Override
  public CompletionStage<ClusterPlan> plan(ClusterReference cluster, LifecycleRequest request,
      ClusterObservation observed) {
    if (!OPERATION.equals(request.operation()) || !COMPONENT.equals(request.component())
        || request.targetVersion() == null || !validVersion(request.targetVersion())
        || request.hostId() != null || request.credentialReference() != null
        || observed == null || !cluster.id().equals(observed.clusterId())
        || cluster.generation() != observed.generation()
          || !observed.conditions().contains("clusterHealth=HEALTHY")
        || !"NOT_OBSERVED".equals(observed.status())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("CloudCore install plan request is unsupported or unsafe"));
    }
    String version = request.targetVersion();
    String idempotencyKey = cluster.id() + ":cloudcore:" + version;
    var step = new PlanStep(0, ExecutionTarget.KUBERNETES_API, null, CAPABILITY,
        java.util.Map.of("component", COMPONENT, "namespace", NAMESPACE,
            "releaseName", RELEASE_NAME, "version", version,
            "clusterId", cluster.id().toString()), List.of());
    return CompletableFuture.completedFuture(new ClusterPlan(UUID.randomUUID(), cluster.id(),
        cluster.generation(), request.operation(), idempotencyKey, List.of(step)));
  }

  @Override
  public CompletionStage<ValidationResult> validate(ClusterReference cluster, ClusterPlan plan,
      ClusterAccess access) {
    return validateSnapshot(cluster, plan, access);
  }

  @Override
  public CompletionStage<ValidationResult> restorePlan(ClusterReference cluster, ClusterPlan plan,
      ClusterAccess access) {
    return validateSnapshot(cluster, plan, access);
  }

  private CompletionStage<ValidationResult> validateSnapshot(ClusterReference cluster,
      ClusterPlan plan, ClusterAccess access) {
    return access.snapshot().thenApply(snapshot -> {
      boolean identityMatches = plan != null && cluster.id().equals(plan.clusterId())
          && cluster.generation() == plan.generation()
          && OPERATION.equals(plan.operation()) && safePlan(plan, cluster.id());
      boolean stillAbsent = identityMatches && cluster.id().equals(snapshot.clusterId())
          && cluster.generation() == snapshot.generation() && "KUBEEDGE".equals(snapshot.kind())
          && freshHealthy(snapshot)
          && "NOT_OBSERVED".equals(snapshot.cloudCoreStatus());
      return new ValidationResult(stillAbsent,
          stillAbsent ? "CLOUDCORE_ABSENT" : "CLUSTER_STATE_CHANGED",
          stillAbsent ? "CloudCore remains unobserved in the matching KubeEdge cluster snapshot"
              : "CloudCore plan no longer matches the current KubeEdge cluster snapshot");
    });
  }

  private static boolean safePlan(ClusterPlan plan, UUID clusterId) {
    if (plan.steps().size() != 1) return false;
    PlanStep step = plan.steps().getFirst();
    String version = step.parameters().get("version");
    return step.ordinal() == 0 && step.target() == ExecutionTarget.KUBERNETES_API
        && step.hostId() == null && CAPABILITY.equals(step.capability())
        && step.credentialReferences().isEmpty()
        && clusterId.toString().equals(step.parameters().get("clusterId"))
        && COMPONENT.equals(step.parameters().get("component"))
        && NAMESPACE.equals(step.parameters().get("namespace"))
        && RELEASE_NAME.equals(step.parameters().get("releaseName"))
        && validVersion(version)
        && plan.idempotencyKey().equals(clusterId + ":cloudcore:" + version);
  }

  private static boolean freshHealthy(ClusterSnapshot snapshot) {
    if (!"HEALTHY".equals(snapshot.healthStatus()) || snapshot.observedAt() == null) return false;
    Duration age = Duration.between(snapshot.observedAt(), Instant.now());
    return !age.isNegative() && age.compareTo(Duration.ofSeconds(120)) <= 0;
  }

  private static boolean validVersion(String version) {
    return version.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?");
  }
}
