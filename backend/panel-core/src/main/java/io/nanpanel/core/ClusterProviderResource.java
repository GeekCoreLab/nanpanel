package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import io.nanpanel.plugin.api.ClusterProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/api/v1/cluster-providers")
@Produces(MediaType.APPLICATION_JSON)
public class ClusterProviderResource {
  @Inject NetworkProviderRegistry registry;
  @Inject KubernetesClusterRegistryService clusters;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @GET
  public List<Map<String, Object>> providers() {
    session();
    return registry.clusterProviders().stream().map(provider -> {
      var descriptor = provider.descriptor();
      return Map.<String, Object>of("id", provider.id(), "name", descriptor.name(),
          "version", descriptor.version(), "apiVersion", descriptor.apiVersion(),
          "clusterKinds", provider.clusterKinds(), "operations", provider.operations());
    }).toList();
  }

  @POST
  @Path("{providerId}/clusters/{clusterId}/observations")
  public ClusterProvider.ClusterObservation observe(@PathParam("providerId") String providerId,
      @PathParam("clusterId") String clusterIdValue) {
    session();
    UUID clusterId = uuid(clusterIdValue);
    ClusterProvider provider = registry.clusterProviders().stream()
        .filter(value -> value.id().equals(providerId)).findFirst()
        .orElseThrow(() -> fail(404, "CLUSTER_PROVIDER_UNAVAILABLE", "集群 Provider 未启用或未安装"));
    ClusterProvider.ClusterSnapshot snapshot;
    try {
      snapshot = clusters.providerSnapshot(clusterId);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    }
    if (!provider.clusterKinds().contains(snapshot.kind())) {
      throw fail(422, "CLUSTER_PROVIDER_KIND_UNSUPPORTED", "该 Provider 不支持此集群类型");
    }
    var reference = new ClusterProvider.ClusterReference(snapshot.clusterId(), snapshot.kind(),
        snapshot.generation());
    ClusterProvider.ClusterAccess access = () -> java.util.concurrent.CompletableFuture
        .completedFuture(snapshot);
    ClusterProvider.ClusterObservation observation;
    try {
      observation = await(provider.observe(reference, access));
    } catch (RuntimeException failure) {
      throw fail(409, "CLUSTER_PROVIDER_OBSERVE_FAILED", "集群 Provider 无法观察当前集群状态");
    }
    if (observation == null || !clusterId.equals(observation.clusterId())
        || observation.generation() != snapshot.generation()
        || observation.status().length() > 128
        || (observation.fingerprint() != null && observation.fingerprint().length() > 256)
        || observation.conditions().size() > 128
        || observation.conditions().stream().anyMatch(condition -> condition.length() > 512)) {
      throw fail(502, "CLUSTER_PROVIDER_OBSERVATION_INVALID", "集群 Provider 返回了无效的观察结果");
    }
    return observation;
  }

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException invalid) {
      throw fail(400, "VALIDATION_FAILED", "集群 ID 必须为 UUID");
    }
  }

  private static <T> T await(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      throw new IllegalStateException("Cluster Provider timed out", timeout);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Cluster Provider call interrupted", interrupted);
    } catch (java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("Cluster Provider call failed", failure.getCause());
    }
  }
}
