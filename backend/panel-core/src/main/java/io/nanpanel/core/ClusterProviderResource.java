package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.plugin.api.ClusterProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/api/v1/cluster-providers")
@Produces(MediaType.APPLICATION_JSON)
public class ClusterProviderResource {
  private static final Set<String> PLAN_PARAMETER_KEYS = Set.of(
      "version", "namespace", "releaseName", "nodeName", "nodeUid", "component",
      "clusterId", "hostId", "role");
  @Inject NetworkProviderRegistry registry;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record PlanRequest(String operation, String component, String hostId,
                            String targetVersion, String credentialReference, String reason) {}

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

  @POST
  @Path("{providerId}/clusters/{clusterId}/plans")
  public Response plan(@PathParam("providerId") String providerId,
      @PathParam("clusterId") String clusterIdValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey, PlanRequest input) {
    Map<String, Object> actor = session();
    recent(actor);
    UUID clusterId = uuid(clusterIdValue);
    if (input == null || !validIdempotencyKey(idempotencyKey)) {
      throw fail(400, "VALIDATION_FAILED", "生命周期计划参数或幂等键无效");
    }
    if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)
        || (input.targetVersion() != null && (input.targetVersion().length() > 64
            || input.targetVersion().isBlank()))) {
      throw fail(422, "VALIDATION_FAILED", "计划原因或目标版本无效");
    }
    UUID credentialReference = input.credentialReference() == null ? null
        : uuid(input.credentialReference());
    if (credentialReference != null
        && !credentialAvailable(providerId, clusterId, credentialReference)) {
      throw fail(409, "CLUSTER_CREDENTIAL_UNAVAILABLE",
          "凭据不存在、已撤销、已过期或不属于此 Provider 与集群");
    }
    ClusterProvider.LifecycleRequest lifecycle;
    try {
      lifecycle = new ClusterProvider.LifecycleRequest(input.operation(), input.component(),
          input.hostId() == null ? null : uuid(input.hostId()), input.targetVersion(), credentialReference);
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "VALIDATION_FAILED", "生命周期操作参数无效");
    }
    String requestFingerprint = digest(String.join("\n", String.valueOf(actor.get("user_id")),
        clusterId.toString(), lifecycle.operation(), lifecycle.component(),
        String.valueOf(lifecycle.hostId()), String.valueOf(lifecycle.targetVersion()),
        String.valueOf(lifecycle.credentialReference()), input.reason()));
    String storedIdempotencyKey = digest(providerId + "\n" + idempotencyKey);
    ClusterProvider provider = provider(providerId);
    if (!provider.operations().contains(lifecycle.operation().toUpperCase(java.util.Locale.ROOT))) {
      throw fail(422, "CLUSTER_PROVIDER_OPERATION_UNSUPPORTED", "该 Provider 未声明此生命周期操作");
    }
    Map<String, Object> replay = store.transaction(connection -> {
      var existing = query(connection,
          "SELECT * FROM cluster_provider_operation WHERE provider_id=? AND idempotency_key=?",
          providerId, storedIdempotencyKey);
      if (existing.isEmpty()) return null;
      Map<String, Object> row = existing.getFirst();
      if (!MessageDigest.isEqual(String.valueOf(row.get("request_fingerprint"))
          .getBytes(StandardCharsets.US_ASCII), requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
        throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同的集群生命周期请求");
      }
      return row;
    });
    if (replay != null) {
      return Response.status(Response.Status.CREATED).entity(view(replay)).build();
    }
    ClusterProvider.ClusterSnapshot snapshot = snapshot(clusterId);
    ClusterProvider.ClusterReference reference = reference(snapshot, provider);
    ClusterProvider.ClusterAccess access = access(snapshot);
    ClusterProvider.ClusterObservation observed = observe(provider, reference, access);
    ClusterProvider.ClusterPlan plan;
    try {
      plan = await(provider.plan(reference, lifecycle, observed));
    } catch (RuntimeException rejected) {
      throw fail(409, "CLUSTER_PROVIDER_PLAN_REJECTED", "集群 Provider 拒绝了生命周期计划");
    }
    validatePlan(plan, providerId, clusterId, lifecycle.operation(), snapshot.generation(),
        credentialReference);
    String configuration = serialize(plan);
    long now = System.currentTimeMillis();
    Map<String, Object> saved = store.transaction(connection -> {
      var existing = query(connection,
          "SELECT * FROM cluster_provider_operation WHERE provider_id=? AND idempotency_key=?",
          providerId, storedIdempotencyKey);
      if (!existing.isEmpty()) {
        Map<String, Object> row = existing.getFirst();
        if (!MessageDigest.isEqual(String.valueOf(row.get("request_fingerprint"))
            .getBytes(StandardCharsets.US_ASCII), requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同的集群生命周期请求");
        }
        return row;
      }
      if (credentialReference != null
          && !credentialAvailable(connection, providerId, clusterId, credentialReference)) {
        throw fail(409, "CLUSTER_CREDENTIAL_UNAVAILABLE",
            "计划持久化前凭据已撤销或过期");
      }
      update(connection, "INSERT INTO cluster_provider_operation "
          + "(id,provider_id,cluster_id,idempotency_key,request_fingerprint,generation,operation,"
          + "state,plan_configuration,detail,requested_by,created_at,updated_at) "
          + "VALUES (?,?,?,?,?,?,?,'PLANNED',?,?,?,?,?)", plan.id(), providerId, clusterId,
          storedIdempotencyKey, requestFingerprint, plan.generation(), plan.operation(), configuration,
          "Plan persisted; no external changes applied", actor.get("user_id"), now, now);
      audit(connection, String.valueOf(actor.get("user_id")), "cluster-provider.plan",
          "cluster-provider-operation", plan.id().toString(), "SUCCEEDED", remoteAddress(),
          bounded("Persisted " + providerId + " plan for " + clusterId + " ("
              + lifecycle.operation() + "): " + input.reason()));
      return query(connection, "SELECT * FROM cluster_provider_operation WHERE id=?", plan.id())
          .getFirst();
    });
    return Response.status(Response.Status.CREATED).entity(view(saved)).build();
  }

  @GET
  @Path("{providerId}/operations/{operationId}")
  public Map<String, Object> getOperation(@PathParam("providerId") String providerId,
      @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    return store.transaction(connection -> {
      var rows = query(connection,
          "SELECT * FROM cluster_provider_operation WHERE provider_id=? AND id=? AND requested_by=?",
          providerId, uuid(operationId), actor.get("user_id"));
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "集群 Provider 操作不存在");
      return view(rows.getFirst());
    });
  }

  @POST
  @Path("{providerId}/operations/{operationId}/validate")
  public Map<String, Object> validate(@PathParam("providerId") String providerId,
      @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    recent(actor);
    Map<String, Object> row = operation(providerId, operationId, actor);
    ClusterProvider.ClusterPlan plan = deserialize(row.get("plan_configuration"));
    ClusterProvider.ClusterSnapshot current = snapshot((UUID)row.get("cluster_id"));
    ClusterProvider provider = provider(providerId);
    if (current.generation() != plan.generation() || !provider.clusterKinds().contains(current.kind())) {
      return transition(row, actor, "DRIFTED", "Cluster inventory changed after planning");
    }
    if (!credentialReferencesAvailable(plan, providerId, current.clusterId())) {
      return transition(row, actor, "DRIFTED", "A referenced Provider credential is unavailable");
    }
    ClusterProvider.ClusterReference reference = reference(current, provider);
    ClusterProvider.ClusterAccess access = access(current);
    ClusterProvider.ValidationResult restored;
    ClusterProvider.ValidationResult result;
    try {
      restored = await(provider.restorePlan(reference, plan, access));
      result = restored.successful() ? await(provider.validate(reference, plan, access)) : restored;
    } catch (RuntimeException failure) {
      result = new ClusterProvider.ValidationResult(false, "VALIDATION_FAILED",
          "Provider plan could not be safely restored or validated");
    }
    if (result == null) {
      result = new ClusterProvider.ValidationResult(false, "VALIDATION_INVALID",
          "Provider returned an invalid validation result");
    }
    String detail = result.successful()
        ? "Provider restored and validated the lifecycle plan against the current cluster snapshot"
        : "Provider plan validation failed (" + result.code() + ")";
    return transition(row, actor, result.successful() ? "VALIDATED" : "DRIFTED", detail);
  }

  private ClusterProvider provider(String providerId) {
    return registry.clusterProviders().stream().filter(value -> value.id().equals(providerId))
        .findFirst().orElseThrow(
            () -> fail(404, "CLUSTER_PROVIDER_UNAVAILABLE", "集群 Provider 未启用或未安装"));
  }

  private ClusterProvider.ClusterSnapshot snapshot(UUID clusterId) {
    try {
      return clusters.providerSnapshot(clusterId);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    }
  }

  private ClusterProvider.ClusterReference reference(
      ClusterProvider.ClusterSnapshot snapshot, ClusterProvider provider) {
    if (!provider.clusterKinds().contains(snapshot.kind())) {
      throw fail(422, "CLUSTER_PROVIDER_KIND_UNSUPPORTED", "该 Provider 不支持此集群类型");
    }
    return new ClusterProvider.ClusterReference(snapshot.clusterId(), snapshot.kind(),
        snapshot.generation());
  }

  private ClusterProvider.ClusterAccess access(ClusterProvider.ClusterSnapshot snapshot) {
    return () -> java.util.concurrent.CompletableFuture.completedFuture(snapshot);
  }

  private ClusterProvider.ClusterObservation observe(ClusterProvider provider,
      ClusterProvider.ClusterReference reference, ClusterProvider.ClusterAccess access) {
    ClusterProvider.ClusterObservation observed;
    try {
      observed = await(provider.observe(reference, access));
    } catch (RuntimeException failure) {
      throw fail(409, "CLUSTER_PROVIDER_OBSERVE_FAILED", "集群 Provider 无法观察当前集群状态");
    }
    if (observed == null || !reference.id().equals(observed.clusterId())
        || reference.generation() != observed.generation() || observed.status().length() > 128
        || (observed.fingerprint() != null && observed.fingerprint().length() > 256)
        || observed.conditions().size() > 128
        || observed.conditions().stream().anyMatch(condition -> condition.length() > 512)) {
      throw fail(502, "CLUSTER_PROVIDER_OBSERVATION_INVALID", "集群 Provider 返回了无效的观察结果");
    }
    return observed;
  }

  private void validatePlan(ClusterProvider.ClusterPlan plan, String providerId, UUID clusterId,
      String operation, long generation, UUID requestedCredentialReference) {
    List<UUID> credentialReferences = plan == null ? List.of() : plan.steps().stream()
        .flatMap(step -> step.credentialReferences().stream()).distinct().toList();
    boolean credentialsMatch = requestedCredentialReference == null
        ? credentialReferences.isEmpty()
        : !credentialReferences.isEmpty()
            && credentialReferences.stream().allMatch(requestedCredentialReference::equals)
            && credentialAvailable(providerId, clusterId, requestedCredentialReference);
    if (plan == null || plan.id() == null || !clusterId.equals(plan.clusterId())
        || generation != plan.generation() || !operation.equals(plan.operation())
        || !plan.idempotencyKey().matches("[A-Za-z0-9_.:-]{1,128}")
        || plan.steps().isEmpty() || plan.steps().size() > 128
        || !credentialsMatch
        || plan.steps().stream().anyMatch(step -> !(step.capability().startsWith("kubeedge.")
            || step.capability().startsWith("k3s."))
            || !safeParameters(step.parameters()))) {
      throw fail(502, "CLUSTER_PROVIDER_PLAN_INVALID", "集群 Provider 返回了无效的生命周期计划");
    }
    String serialized = serialize(plan);
    if (serialized.length() > 65536) {
      throw fail(502, "CLUSTER_PROVIDER_PLAN_INVALID", "集群 Provider 计划超过大小限制");
    }
  }

  private boolean credentialReferencesAvailable(ClusterProvider.ClusterPlan plan,
      String providerId, UUID clusterId) {
    List<UUID> references = plan.steps().stream()
        .flatMap(step -> step.credentialReferences().stream()).distinct().toList();
    return references.stream().allMatch(reference -> credentialAvailable(providerId, clusterId, reference));
  }

  private boolean credentialAvailable(String providerId, UUID clusterId, UUID credentialId) {
    return store.transaction(connection -> credentialAvailable(connection, providerId, clusterId, credentialId));
  }

  private static boolean credentialAvailable(java.sql.Connection connection, String providerId,
      UUID clusterId, UUID credentialId) throws java.sql.SQLException {
    var rows = query(connection, "SELECT expires_at,revoked_at FROM cluster_provider_credential "
        + "WHERE id=? AND provider_id=? AND cluster_id=?", credentialId, providerId, clusterId);
    if (rows.isEmpty()) return false;
    Map<String, Object> row = rows.getFirst();
    return row.get("revoked_at") == null
        && (row.get("expires_at") == null
            || ((Number)row.get("expires_at")).longValue() > System.currentTimeMillis());
  }

  private static boolean safeParameters(Map<String, String> parameters) {
    if (parameters.size() > PLAN_PARAMETER_KEYS.size()) return false;
    for (var entry : parameters.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (!PLAN_PARAMETER_KEYS.contains(key) || value == null || value.length() > 128
          || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) return false;
      boolean valid = switch (key) {
        case "version" -> value.matches("[vV]?[0-9][A-Za-z0-9.+_-]{0,63}");
        case "namespace", "releaseName", "nodeName" ->
            value.matches("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?");
        case "nodeUid" -> value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
        case "component" -> Set.of("cloudcore", "edgecore").contains(value);
        case "clusterId", "hostId" -> value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        case "role" -> Set.of("server", "agent", "cloudcore", "edgecore").contains(value);
        default -> false;
      };
      if (!valid) return false;
    }
    return true;
  }

  private Map<String, Object> operation(String providerId, String operationId,
      Map<String, Object> actor) {
    return store.transaction(connection -> {
      var rows = query(connection,
          "SELECT * FROM cluster_provider_operation WHERE provider_id=? AND id=? AND requested_by=?",
          providerId, uuid(operationId), actor.get("user_id"));
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "集群 Provider 操作不存在");
      if (!"PLANNED".equals(rows.getFirst().get("state"))) {
        throw fail(409, "INVALID_OPERATION_STATE", "当前集群 Provider 操作不能校验");
      }
      return rows.getFirst();
    });
  }

  private Map<String, Object> transition(Map<String, Object> row, Map<String, Object> actor,
      String state, String detail) {
    long now = System.currentTimeMillis();
    String id = String.valueOf(row.get("id"));
    return store.transaction(connection -> {
      int changed = update(connection, "UPDATE cluster_provider_operation "
          + "SET state=?,detail=?,updated_at=? WHERE id=? AND state=?",
          state, bounded(detail), now, id, row.get("state"));
      if (changed != 1) throw fail(409, "OPERATION_CHANGED", "集群 Provider 操作已被更新");
      audit(connection, String.valueOf(actor.get("user_id")), "cluster-provider.validate",
          "cluster-provider-operation", id, state.equals("VALIDATED") ? "SUCCEEDED" : "FAILED",
          remoteAddress(), bounded(detail));
      return view(query(connection,
          "SELECT * FROM cluster_provider_operation WHERE id=?", id).getFirst());
    });
  }

  private Map<String, Object> view(Map<String, Object> row) {
    return Map.of("id", row.get("id"), "providerId", row.get("provider_id"),
        "clusterId", row.get("cluster_id"), "generation", row.get("generation"),
        "operation", row.get("operation"), "state", row.get("state"),
        "detail", row.get("detail"),
        "createdAt", Instant.ofEpochMilli(((Number)row.get("created_at")).longValue()).toString(),
        "updatedAt", Instant.ofEpochMilli(((Number)row.get("updated_at")).longValue()).toString());
  }

  private void recent(Map<String, Object> actor) {
    if (System.currentTimeMillis() - number(actor, "authenticated_at") > 300000) {
      store.transaction(connection -> {
        audit(connection, String.valueOf(actor.get("user_id")),
            "security.reauthentication.required", "cluster-provider", null, "DENIED",
            remoteAddress(), "集群 Provider 生命周期计划需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED", "请重新验证密码后再创建或校验集群生命周期计划");
    }
  }

  private String remoteAddress() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private String serialize(ClusterProvider.ClusterPlan plan) {
    try {
      return mapper.writeValueAsString(plan);
    } catch (java.io.IOException failure) {
      throw fail(502, "CLUSTER_PROVIDER_PLAN_INVALID", "集群 Provider 计划无法安全序列化");
    }
  }

  private ClusterProvider.ClusterPlan deserialize(Object storedValue) {
    try {
      String value = storedValue instanceof java.sql.Clob clob
          ? clob.getSubString(1, Math.toIntExact(clob.length())) : String.valueOf(storedValue);
      return mapper.readValue(value, ClusterProvider.ClusterPlan.class);
    } catch (java.io.IOException failure) {
      throw fail(500, "OPERATION_CORRUPT", "集群 Provider 计划记录无效");
    } catch (java.sql.SQLException | ArithmeticException failure) {
      throw fail(500, "OPERATION_CORRUPT", "集群 Provider 计划记录无效");
    }
  }

  private static String digest(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static boolean validIdempotencyKey(String value) {
    return value != null && value.matches("[A-Za-z0-9_-]{16,128}");
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "Provider returned no detail";
    return value.length() <= 512 ? value : value.substring(0, 512);
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
