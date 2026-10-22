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
  private static final String EDGECORE_OPERATION = "join_edgecore";
  private static final String EDGECORE_COMPONENT = "edgecore";
  private static final String EDGECORE_CREDENTIAL_PURPOSE = "EDGECORE_BOOTSTRAP_TOKEN";
  private static final String NAMESPACE = "kubeedge";
  private static final String RELEASE_NAME = "cloudcore";
  private static final String CAPABILITY = "kubeedge.cloudcore.install";
  private static final String EDGECORE_CAPABILITY = "kubeedge.edgecore.join";

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
    return Set.of(OPERATION.toUpperCase(java.util.Locale.ROOT),
        EDGECORE_OPERATION.toUpperCase(java.util.Locale.ROOT));
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
    if (!validObservation(cluster, observed)) return CompletableFuture.failedFuture(
        new IllegalArgumentException("KubeEdge plan observation is unsupported or unsafe"));
    if (EDGECORE_OPERATION.equals(request.operation())) return planEdgeCoreJoin(cluster, request, observed);
    if (!OPERATION.equals(request.operation()) || !COMPONENT.equals(request.component())
        || request.targetVersion() == null || !validVersion(request.targetVersion())
        || request.hostId() != null || request.credentialReference() != null
        || request.targetEndpoint() != null || !"NOT_OBSERVED".equals(observed.status())) {
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
      if (plan == null || !cluster.id().equals(plan.clusterId())
          || cluster.generation() != plan.generation()
          || cluster.id().equals(snapshot.clusterId()) == false
          || cluster.generation() != snapshot.generation() || !"KUBEEDGE".equals(snapshot.kind())
          || !freshHealthy(snapshot) || !safePlan(plan, cluster.id())) {
        return new ValidationResult(false, "CLUSTER_STATE_CHANGED",
            "KubeEdge plan no longer matches the current cluster snapshot");
      }
      if (OPERATION.equals(plan.operation())) {
        boolean stillAbsent = "NOT_OBSERVED".equals(snapshot.cloudCoreStatus());
        return new ValidationResult(stillAbsent,
            stillAbsent ? "CLOUDCORE_ABSENT" : "CLUSTER_STATE_CHANGED",
            stillAbsent ? "CloudCore remains unobserved in the matching KubeEdge cluster snapshot"
                : "CloudCore plan no longer matches the current KubeEdge cluster snapshot");
      }
      if (EDGECORE_OPERATION.equals(plan.operation())) {
        UUID hostId = plan.steps().getFirst().hostId();
        boolean cloudCoreReady = "HEALTHY".equals(snapshot.cloudCoreStatus());
        boolean hostNotJoined = snapshot.nodes().stream().noneMatch(node -> node.present()
            && hostId.equals(node.hostId()));
        boolean valid = cloudCoreReady && hostNotJoined;
        return new ValidationResult(valid,
            valid ? "EDGE_HOST_NOT_REGISTERED" : "CLUSTER_STATE_CHANGED",
            valid ? "CloudCore is healthy and the target Host has no registered Edge node"
                : "CloudCore or Edge registration state changed after planning");
      }
      return new ValidationResult(false, "CLUSTER_STATE_CHANGED",
          "KubeEdge plan operation is unsupported");
    });
  }

  private static boolean safePlan(ClusterPlan plan, UUID clusterId) {
    if (plan.steps().size() != 1) return false;
    PlanStep step = plan.steps().getFirst();
    String version = step.parameters().get("version");
    boolean common = step.ordinal() == 0
        && step.hostId() == null && CAPABILITY.equals(step.capability())
        && clusterId.toString().equals(step.parameters().get("clusterId"))
        && validVersion(version);
    if (OPERATION.equals(plan.operation())) {
      return common && step.target() == ExecutionTarget.KUBERNETES_API
          && step.credentialReferences().isEmpty()
          && !step.parameters().containsKey("credentialPurpose")
          && !step.parameters().containsKey("cloudCoreEndpoint")
          && COMPONENT.equals(step.parameters().get("component"))
          && NAMESPACE.equals(step.parameters().get("namespace"))
          && RELEASE_NAME.equals(step.parameters().get("releaseName"))
          && plan.idempotencyKey().equals(clusterId + ":cloudcore:" + version);
    }
    if (EDGECORE_OPERATION.equals(plan.operation())) {
      String endpoint = step.parameters().get("cloudCoreEndpoint");
      return step.ordinal() == 0 && step.target() == ExecutionTarget.AGENT_HELPER
          && step.hostId() != null && EDGECORE_CAPABILITY.equals(step.capability())
          && clusterId.toString().equals(step.parameters().get("clusterId"))
          && EDGECORE_COMPONENT.equals(step.parameters().get("component"))
          && "edgecore".equals(step.parameters().get("role"))
          && EDGECORE_CREDENTIAL_PURPOSE.equals(step.parameters().get("credentialPurpose"))
          && endpoint != null && validCloudCoreEndpoint(endpoint)
          && validVersion(version)
          && step.credentialReferences().size() == 1
          && plan.idempotencyKey().equals(clusterId + ":edgecore:" + step.hostId() + ":" + version);
    }
    return false;
  }

  private static boolean validObservation(ClusterReference cluster, ClusterObservation observed) {
    return observed != null && cluster.id().equals(observed.clusterId())
        && cluster.generation() == observed.generation()
        && observed.conditions().contains("clusterHealth=HEALTHY");
  }

  private static CompletionStage<ClusterPlan> planEdgeCoreJoin(
      ClusterReference cluster, LifecycleRequest request, ClusterObservation observed) {
    if (!EDGECORE_COMPONENT.equals(request.component()) || request.hostId() == null
        || request.targetVersion() == null || !validVersion(request.targetVersion())
        || request.credentialReference() == null || !validCloudCoreEndpoint(request.targetEndpoint())
        || !"HEALTHY".equals(observed.status())) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("EdgeCore join plan request is unsupported or unsafe"));
    }
    String version = request.targetVersion();
    String idempotencyKey = cluster.id() + ":edgecore:" + request.hostId() + ":" + version;
    var step = new PlanStep(0, ExecutionTarget.AGENT_HELPER, request.hostId(), EDGECORE_CAPABILITY,
        java.util.Map.of("component", EDGECORE_COMPONENT, "role", EDGECORE_COMPONENT,
            "version", version, "clusterId", cluster.id().toString(),
            "cloudCoreEndpoint", request.targetEndpoint(),
            "credentialPurpose", EDGECORE_CREDENTIAL_PURPOSE),
        List.of(request.credentialReference()));
    return CompletableFuture.completedFuture(new ClusterPlan(UUID.randomUUID(), cluster.id(),
        cluster.generation(), request.operation(), idempotencyKey, List.of(step)));
  }

  private static boolean validCloudCoreEndpoint(String endpoint) {
    if (endpoint == null || endpoint.length() > 260) return false;
    var matcher = java.util.regex.Pattern.compile(
        "^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:]+]):([0-9]{1,5})$")
        .matcher(endpoint);
    if (!matcher.matches()) return false;
    try {
      int port = Integer.parseInt(matcher.group(1));
      return port >= 1 && port <= 65535;
    } catch (NumberFormatException invalid) {
      return false;
    }
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
