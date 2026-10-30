package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HostHelperOperationResource {
  private static final HttpClient CONTROL_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).build();
  private static final Set<String> CAPABILITIES = Set.of(
      "package.install", "file.write.protected", "container.list", "container.logs",
      "container.start", "container.stop", "container.deploy", "container.remove",
      "network.firewall.expose",
      "network.topology.reconcile", "network.topology.capture",
      "file.list", "file.read", "file.write", "file.delete", "k3s.preflight", "k3s.install",
      "k3s.upgrade", "kubeedge.edgecore.join", "nginx.route.set", "nginx.route.remove",
      "nginx.tls.install", "cron.list", "cron.write", "cron.remove", "metrics.read");
  private static final java.util.regex.Pattern CRON_JOB_NAME =
      java.util.regex.Pattern.compile("nanpanel-[a-z]([a-z0-9-]{0,30}[a-z0-9])?");
  private static final java.util.regex.Pattern CRON_FIELD =
      java.util.regex.Pattern.compile("[0-9*/,-]{1,32}");
  private static final java.util.regex.Pattern PACKAGE_NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9+_.:-]{0,127}");
  private static final java.util.regex.Pattern FILE_NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,128}");
  private static final java.util.regex.Pattern CONTAINER_NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");
  private static final java.util.regex.Pattern CONTAINER_IMAGE =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,254}");
  private static final java.util.regex.Pattern NETWORK_INTERFACE =
      java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}");
  private static final java.util.regex.Pattern NETWORK_RULE_NAME =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");
  private static final java.util.regex.Pattern NGINX_ROUTE_NAME =
      java.util.regex.Pattern.compile("nanpanel-[a-z][a-z0-9-]{0,52}");
  private static final java.util.regex.Pattern TLS_NAME =
      java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");
  private static final java.util.regex.Pattern UUID_TEXT = java.util.regex.Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-control-url")
  String gatewayControlUrl;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-token")
  String gatewayToken;

  public record HelperOperationRequest(
      String capability,
      String target,
      JsonNode payload,
      String reason,
      Integer timeoutSeconds,
      Long expectedGeneration) {}

  private record Decision(
      Map<String, Object> operation,
      int status,
      boolean replayed,
      UUID agentId,
      String capability,
      String target,
      String payloadJson,
      long deadlineEpochMs) {}

  private String actorId() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  @POST
  @Path("hosts/{hostId}/operations")
  public Response submit(
      @PathParam("hostId") String hostValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      HelperOperationRequest input) {
    if (input != null && Set.of("network.topology.reconcile", "network.topology.capture")
        .contains(input.capability())) {
      throw fail(403, "NETWORK_PROVIDER_PLAN_REQUIRED",
          "隔离拓扑操作必须经过持久化的 Provider 计划生命周期提交");
    }
    if (input != null && "kubeedge.edgecore.join".equals(input.capability())) {
      throw fail(403, "CLUSTER_PROVIDER_PLAN_REQUIRED",
          "EdgeCore 加入必须通过已验证的 KubeEdge 生命周期计划提交");
    }
    return submitAs(uuid(hostValue, "hostId"), idempotencyKey, input, actorId(), clientIp());
  }

  Response submitPlannedUpgrade(UUID hostId, String idempotencyKey, String role, String version,
                                long expectedGeneration, String reason, String actor) {
    var payload = mapper.createObjectNode().put("role", role).put("version", version);
    return submitAs(hostId, idempotencyKey,
        new HelperOperationRequest("k3s.upgrade", "", payload, reason,
            600, expectedGeneration), actor, null);
  }

  Response submitPlannedInstall(UUID hostId, String idempotencyKey, String role, String version,
                                String serverUrl, String token, long expectedGeneration,
                                String reason, String actor) {
    var payload = mapper.createObjectNode().put("role", role).put("version", version)
        .put("token", token);
    if (serverUrl == null) payload.putNull("serverUrl");
    else payload.put("serverUrl", serverUrl);
    return submitAs(hostId, idempotencyKey,
        new HelperOperationRequest("k3s.install", "", payload, reason, 600,
            expectedGeneration), actor, null);
  }

  Response submitPlannedKubeEdgeJoin(UUID hostId, String idempotencyKey, String version,
      String cloudCoreEndpoint, String token, String reason, String actor) {
    long expectedGeneration = store.transaction(connection -> {
      var rows = query(connection, "SELECT generation FROM host WHERE id=?", hostId);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "计划中的主机资产不存在");
      return ((Number)rows.getFirst().get("generation")).longValue();
    });
    var payload = mapper.createObjectNode().put("version", version)
        .put("cloudCoreEndpoint", cloudCoreEndpoint).put("token", token);
    return submitAs(hostId, idempotencyKey,
        new HelperOperationRequest("kubeedge.edgecore.join", "", payload, reason, 600,
            expectedGeneration), actor, null);
  }

  Response replayPlannedKubeEdgeJoin(UUID hostId, String idempotencyKey, String actor) {
    Map<String, Object> row = store.transaction(connection -> {
      var rows = query(connection, operationSelect()
          + " WHERE o.host_id=? AND o.idempotency_key=? AND o.requested_by=? "
          + "AND o.kind='HOST_HELPER_OPERATION' AND o.capability='kubeedge.edgecore.join'",
          hostId, idempotencyKey, actor);
      return rows.isEmpty() ? null : rows.getFirst();
    });
    return row == null ? null : operationResponse(row, true);
  }

  Response plannedKubeEdgeOperation(UUID operationId, UUID hostId, String actor) {
    Map<String, Object> row = store.transaction(connection -> {
      var rows = query(connection, operationSelect()
          + " WHERE o.id=? AND o.host_id=? AND o.requested_by=? "
          + "AND o.kind='HOST_HELPER_OPERATION' AND o.capability='kubeedge.edgecore.join'",
          operationId, hostId, actor);
      return rows.isEmpty() ? null : rows.getFirst();
    });
    if (row == null) throw fail(404, "NOT_FOUND", "EdgeCore Helper 操作不存在");
    return operationResponse(row, true);
  }

  private Response operationResponse(Map<String, Object> row, boolean replayed) {
    String status = String.valueOf(row.get("status"));
    int responseStatus = Set.of("PENDING", "RUNNING").contains(status) ? 202 : 200;
    return Response.status(responseStatus).entity(view(row))
        .header("Location", "/api/v1/operations/" + row.get("id"))
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(replayed)).build();
  }

  Response submitAs(UUID hostId, String idempotencyKey, HelperOperationRequest input,
                            String actor, String remoteAddress) {
    return submitAs(hostId, idempotencyKey, input, actor, remoteAddress, false);
  }

  Response submitAs(UUID hostId, String idempotencyKey, HelperOperationRequest input,
                    String actor, String remoteAddress, boolean redispatchUnresolvedReplay) {
    String key = validateIdempotencyKey(idempotencyKey);
    if (input == null || !CAPABILITIES.contains(input.capability()))
      throw fail(422, "OPERATION_UNSUPPORTED", "该主机 Helper 操作能力不受支持");
    String target = input.target() == null ? "" : input.target();
    validateTargetAndPayload(input.capability(), target, input.payload());
    String reason = input.reason() == null ? "" : input.reason().trim();
    int timeoutSeconds = input.timeoutSeconds() == null ? defaultTimeout(input.capability())
        : input.timeoutSeconds();
    int maximumTimeoutSeconds = Set.of("k3s.install", "k3s.upgrade", "kubeedge.edgecore.join")
        .contains(input.capability())
        ? 600 : 300;
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl) ||
        timeoutSeconds < 5 || timeoutSeconds > maximumTimeoutSeconds || input.expectedGeneration() == null ||
        input.expectedGeneration() < 1)
      throw fail(422, "VALIDATION_FAILED", "操作原因、超时或主机版本无效");
    String payloadJson = input.payload() == null || input.payload().isNull()
        ? null : compactJson(input.payload());
    String requestHash = AuthStore.digest(String.join("\n", "POST",
        "/api/v1/hosts/" + hostId + "/operations", input.capability(), target,
        Objects.toString(payloadJson, ""), reason, Integer.toString(timeoutSeconds),
        Long.toString(input.expectedGeneration())));
    Instant now = Instant.now();
    Instant deadline = now.plusSeconds(timeoutSeconds);
    UUID operationId = UUID.randomUUID();
    Decision decision = store.transaction(c -> {
      query(c, "SELECT id FROM platform_settings WHERE id=1 FOR UPDATE");
      var existing = query(c, operationSelect() + " WHERE o.idempotency_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (!Objects.equals(row.get("requested_by"), actor) ||
            !Objects.equals(row.get("host_id"), hostId) ||
            !Objects.equals(row.get("kind"), "HOST_HELPER_OPERATION") ||
            !Objects.equals(row.get("capability"), input.capability()) ||
            !Objects.equals(row.get("target"), target) ||
            !MessageDigest.isEqual(
                String.valueOf(row.get("request_hash")).getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII)))
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
        String status = String.valueOf(row.get("status"));
        return new Decision(view(row), Set.of("SUCCEEDED", "FAILED", "CANCELLED")
            .contains(status) ? 200 : 202,
            true, row.get("agent_id") == null ? null : (UUID)row.get("agent_id"),
            input.capability(), target, payloadJson,
            instant(row.get("deadline_at")).toEpochMilli());
      }

      query(c, "SELECT id FROM host WHERE id=? FOR UPDATE", hostId);
      if (input.capability().startsWith("container.")) {
        var owners = query(c, "SELECT cluster_id,node_uid FROM kubernetes_cluster_node "
            + "WHERE host_id=? AND container_owner IN ('KUBERNETES','KUBEEDGE') FOR UPDATE", hostId);
        if (!owners.isEmpty()) {
          throw fail(409, "KUBERNETES_CONTAINER_OWNED",
              "此主机映射到 Kubernetes Node，Pod 和容器由 Kubelet/KubeEdge 管理");
        }
      }
      String capabilitiesColumn = c.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
      var hosts = query(c,
          "SELECT h.id,h.generation," + capabilitiesColumn + " AS capabilities," +
              "(SELECT a.id FROM agent a WHERE a.host_id=h.id " +
              "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS agent_id," +
              "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id " +
              "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status," +
              "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id " +
              "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen " +
              "FROM host h WHERE h.id=?",
          hostId);
      if (hosts.isEmpty()) throw fail(404, "NOT_FOUND", "主机不存在");
      var host = hosts.getFirst();
      long generation = ((Number)host.get("generation")).longValue();
      if (input.expectedGeneration() != generation)
        throw fail(412, "PRECONDITION_FAILED", "主机版本已变化，请刷新后重试");
      if (input.capability().equals("k3s.upgrade")) {
        requireK3sUpgradeGate(c, hostId, now);
      }
      if (input.capability().equals("k3s.install")) {
        requireK3sInstallGate(c, hostId);
      }
      if (host.get("agent_id") == null)
        throw fail(503, "AGENT_OFFLINE", "该主机尚未接入 Agent");
      var settings = query(c, "SELECT host_offline_after_seconds FROM platform_settings WHERE id=1");
      int offlineAfter = settings.isEmpty() ? 90
          : ((Number)settings.getFirst().get("host_offline_after_seconds")).intValue();
      Instant lastSeen = instant(host.get("last_seen"));
      if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(offlineAfter)))
        throw fail(503, "AGENT_OFFLINE", "Agent 当前不在线");
      if (!"AVAILABLE".equals(host.get("helper_status")))
        throw fail(503, "HELPER_UNAVAILABLE", "Agent Helper 当前不可用");
      JsonNode capabilities;
      try {
        capabilities = mapper.readTree(String.valueOf(host.get("capabilities")));
      } catch (Exception e) {
        throw new IllegalStateException("Stored host capabilities are invalid", e);
      }
      if (capabilities == null || !capabilities.isArray() ||
          !contains(capabilities, input.capability()))
        throw fail(422, "OPERATION_UNSUPPORTED", "Agent 未报告该 Helper 能力");
      var pending = query(c,
          "SELECT id FROM operation WHERE host_id=? AND kind='HOST_HELPER_OPERATION' " +
              "AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1",
          hostId);
      if (!pending.isEmpty())
        throw fail(409, "OPERATION_IN_PROGRESS", "该主机已有 Helper 操作等待完成或对账");

      UUID agentId = (UUID)host.get("agent_id");
      update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,requested_by,reason,expected_generation,started_at," +
              "deadline_at,request_hash,capability,target) " +
              "VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          operationId, key, "APPLY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
          "HOST_HELPER_OPERATION", hostId, agentId, actor, reason, generation,
          java.sql.Timestamp.from(now), java.sql.Timestamp.from(deadline), requestHash,
          input.capability(), target);
      audit(c, actor, input.capability(), "host", hostId.toString(), "ACCEPTED",
          remoteAddress, "接受受限 Helper 操作：" + reason);
      var created = query(c, operationSelect() + " WHERE o.id=?", operationId);
      return new Decision(view(created.getFirst()), 202, false, agentId,
          input.capability(), target, payloadJson, deadline.toEpochMilli());
    });

    if (!decision.replayed() || (redispatchUnresolvedReplay
        && Set.of("RUNNING", "UNKNOWN").contains(decision.operation().get("status"))))
      dispatch(decision, actor, hostId, key, remoteAddress);
    return Response.status(decision.status()).entity(decision.operation())
        .header("Location", "/api/v1/operations/" + decision.operation().get("id"))
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed())).build();
  }

  private void requireK3sUpgradeGate(java.sql.Connection connection, UUID hostId, Instant now)
      throws java.sql.SQLException {
    var mappings = query(connection,
        "SELECT c.id AS cluster_id,c.kind,c.health_status,c.observed_at,n.node_present,n.ready "
            + "FROM kubernetes_cluster_node n JOIN kubernetes_cluster c ON c.id=n.cluster_id "
            + "WHERE n.host_id=? FOR UPDATE", hostId);
    if (mappings.isEmpty()) {
      throw fail(409, "K3S_UPGRADE_CLUSTER_UNMAPPED",
          "升级前需将主机映射到已登记的 K3s Node");
    }
    var mapping = mappings.getFirst();
    if (!"K3S".equals(mapping.get("kind"))) {
      throw fail(409, "K3S_UPGRADE_CLUSTER_KIND",
          "该主机映射到的集群不是 K3s，不能执行 K3s 升级");
    }
    Instant observedAt = instant(mapping.get("observed_at"));
    boolean stale = observedAt == null || observedAt.isBefore(now.minusSeconds(120));
    if (!"HEALTHY".equals(mapping.get("health_status")) || stale
        || !Boolean.TRUE.equals(mapping.get("node_present"))
        || !Boolean.TRUE.equals(mapping.get("ready"))) {
      throw fail(409, "K3S_CLUSTER_HEALTH_GATE",
          "K3s 集群或目标 Node 未通过最近 120 秒内的 Ready 健康检查");
    }
    UUID clusterId = (UUID)mapping.get("cluster_id");
    var active = query(connection,
        "SELECT o.id FROM operation o JOIN kubernetes_cluster_node n ON n.host_id=o.host_id "
            + "WHERE n.cluster_id=? AND o.kind='HOST_HELPER_OPERATION' "
            + "AND o.capability IN ('k3s.install','k3s.upgrade') "
            + "AND o.status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1", clusterId);
    if (!active.isEmpty()) {
      throw fail(409, "K3S_UPGRADE_IN_PROGRESS",
          "集群内已有 K3s 安装或升级任务，需等待任务结束并重新检查集群健康");
    }
    var lastMutation = query(connection,
        "SELECT MAX(o.finished_at) AS finished_at FROM operation o "
            + "JOIN kubernetes_cluster_node n ON n.host_id=o.host_id WHERE n.cluster_id=? "
            + "AND o.kind='HOST_HELPER_OPERATION' "
            + "AND o.capability IN ('k3s.install','k3s.upgrade') "
            + "AND o.status IN ('SUCCEEDED','FAILED','CANCELLED')", clusterId);
    Instant finishedAt = lastMutation.isEmpty() ? null
        : instant(lastMutation.getFirst().get("finished_at"));
    if (finishedAt != null && (observedAt == null || observedAt.isBefore(finishedAt))) {
      throw fail(409, "K3S_CLUSTER_HEALTH_GATE",
          "上一次 K3s 操作后尚未刷新集群 Node 健康状态");
    }
  }

  private void requireK3sInstallGate(java.sql.Connection connection, UUID hostId)
      throws java.sql.SQLException {
    var mappings = query(connection,
        "SELECT cluster_id FROM kubernetes_cluster_node WHERE host_id=? FOR UPDATE", hostId);
    if (!mappings.isEmpty()) {
      throw fail(409, "K3S_INSTALL_HOST_ALREADY_MANAGED",
          "已映射到 Kubernetes Node 的主机不能作为首次安装目标");
    }
    var discovery = query(connection,
        "SELECT host_id FROM host_kubernetes_runtime_discovery "
            + "WHERE host_id=? AND detected=TRUE FOR UPDATE", hostId);
    if (!discovery.isEmpty()) {
      throw fail(409, "K3S_INSTALL_HOST_ALREADY_MANAGED",
          "Agent 已发现此主机正在运行 Kubernetes，不能作为首次安装目标");
    }
  }

  private void dispatch(Decision decision, String actor, UUID hostId, String key,
                       String remoteAddress) {
    try {
      var dispatch = new LinkedHashMap<String, Object>();
      dispatch.put("agentId", decision.agentId().toString());
      dispatch.put("operationId", String.valueOf(decision.operation().get("id")));
      dispatch.put("idempotencyKey", key);
      dispatch.put("capability", decision.capability());
      dispatch.put("target", decision.target());
      dispatch.put("deadlineEpochMs", decision.deadlineEpochMs());
      if (decision.payloadJson() != null) dispatch.put("payloadJson", decision.payloadJson());
      var gatewayRequest = HttpRequest.newBuilder(URI.create(gatewayControlUrl))
          .timeout(Duration.ofSeconds(5))
          .header("X-NanPanel-Gateway-Token", gatewayToken)
          .header("Content-Type", MediaType.APPLICATION_JSON)
          .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(dispatch)))
          .build();
      var response = CONTROL_CLIENT.send(gatewayRequest, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 202) {
        boolean uncertain = response.statusCode() >= 500;
        String detail = "Agent Gateway rejected the Helper operation (HTTP " +
            response.statusCode() + ")";
        failDispatch(decision, actor, hostId, detail, uncertain, remoteAddress);
        throw fail(503, uncertain ? "EXECUTION_OUTCOME_UNKNOWN" : "GATEWAY_UNAVAILABLE",
            uncertain ? "Gateway 未确认任务状态，请使用原幂等键查询"
                : "Gateway 拒绝了任务，请检查 Agent 状态后重试");
      }
    } catch (jakarta.ws.rs.WebApplicationException e) {
      throw e;
    } catch (Exception e) {
      failDispatch(decision, actor, hostId,
          "Agent Gateway response was not received; task execution is uncertain", true,
          remoteAddress);
      throw fail(503, "EXECUTION_OUTCOME_UNKNOWN", "未收到 Gateway 确认，请使用原幂等键查询任务状态");
    }
  }

  private void failDispatch(Decision decision, String actor, UUID hostId,
                            String detail, boolean uncertain, String remoteAddress) {
    UUID operationId = UUID.fromString(String.valueOf(decision.operation().get("id")));
    store.transaction(c -> {
      String error = mapper.writeValueAsString(Map.of(
          "code", uncertain ? "EXECUTION_OUTCOME_UNKNOWN" : "GATEWAY_UNAVAILABLE",
          "message", detail, "retryable", false,
          "executionOutcome", uncertain ? "UNCERTAIN" : "NOT_EXECUTED"));
      update(c,
          "UPDATE operation SET status=?,phase=?,error_json=?,updated_at=?,finished_at=? " +
              "WHERE id=? AND status='RUNNING'",
          uncertain ? "UNKNOWN" : "FAILED", uncertain ? "VERIFY" : "APPLY", error,
          java.sql.Timestamp.from(Instant.now()),
          uncertain ? null : java.sql.Timestamp.from(Instant.now()), operationId);
      audit(c, actor, decision.capability(), "host", hostId.toString(),
          uncertain ? "UNKNOWN" : "FAILED", remoteAddress, detail);
      return null;
    });
  }

  void validateTargetAndPayload(String capability, String target, JsonNode payload) {
    boolean validTarget = switch (capability) {
      case "package.install", "container.list", "k3s.preflight", "k3s.install", "k3s.upgrade",
          "kubeedge.edgecore.join", "cron.list", "metrics.read" -> target.isEmpty();
      case "file.write.protected" -> FILE_NAME.matcher(target).matches();
      case "file.list" -> validHostPath(target, true);
      case "file.read", "file.write", "file.delete" -> validHostPath(target, false);
      case "container.logs", "container.start", "container.stop", "container.deploy",
          "container.remove" ->
          CONTAINER_NAME.matcher(target).matches();
      case "network.firewall.expose" -> NETWORK_RULE_NAME.matcher(target).matches();
      case "network.topology.reconcile", "network.topology.capture" -> UUID_TEXT.matcher(target).matches();
      case "nginx.route.set", "nginx.route.remove" -> NGINX_ROUTE_NAME.matcher(target).matches();
      case "nginx.tls.install" -> TLS_NAME.matcher(target).matches();
      case "cron.write", "cron.remove" -> CRON_JOB_NAME.matcher(target).matches();
      default -> false;
    };
    if (!validTarget) throw fail(422, "VALIDATION_FAILED", "操作目标格式无效");
    if (capability.equals("package.install")) {
      if (payload == null || !payload.isObject() || payload.size() != 1 ||
          !payload.path("packages").isArray() || payload.path("packages").isEmpty() ||
          payload.path("packages").size() > 16)
        throw fail(422, "VALIDATION_FAILED", "软件包请求格式无效");
      var names = new java.util.HashSet<String>();
      for (JsonNode packageName : payload.path("packages")) {
        if (!packageName.isTextual() || !PACKAGE_NAME.matcher(packageName.asText()).matches() ||
            !names.add(packageName.asText()))
          throw fail(422, "VALIDATION_FAILED", "软件包名称无效或重复");
      }
      return;
    }
    if (capability.equals("k3s.preflight")) {
      JsonNode role = payload == null ? null : payload.get("role");
      if (payload == null || !payload.isObject() || payload.size() != 1 || role == null ||
          !role.isTextual() || !Set.of("server", "agent").contains(role.asText()))
        throw fail(422, "VALIDATION_FAILED", "K3s 前置校验角色必须为 server 或 agent");
      return;
    }
    if (capability.equals("k3s.install")) {
      JsonNode role = payload == null ? null : payload.get("role");
      JsonNode version = payload == null ? null : payload.get("version");
      JsonNode serverUrl = payload == null ? null : payload.get("serverUrl");
      JsonNode token = payload == null ? null : payload.get("token");
      boolean validVersion = version != null && version.isTextual()
          && version.asText().matches("v[0-9]+\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+");
      boolean validToken = token != null && token.isTextual()
          && token.asText().matches("[A-Za-z0-9_.:-]{16,512}");
      boolean validServer = role != null && role.isTextual() && switch (role.asText()) {
        case "server" -> serverUrl != null && serverUrl.isNull() &&
            !token.asText().startsWith("K10");
        case "agent" -> serverUrl != null && serverUrl.isTextual()
            && validK3sServerUrl(serverUrl.asText());
        default -> false;
      };
      if (payload == null || !payload.isObject() || payload.size() != 4 || !validVersion
          || !validToken || !validServer)
        throw fail(422, "VALIDATION_FAILED", "K3s 安装角色、版本、服务端地址或令牌无效");
      return;
    }
    if (capability.equals("k3s.upgrade")) {
      JsonNode role = payload == null ? null : payload.get("role");
      JsonNode version = payload == null ? null : payload.get("version");
      if (payload == null || !payload.isObject() || payload.size() != 2 || role == null ||
          !role.isTextual() || !Set.of("server", "agent").contains(role.asText()) ||
          version == null || !version.isTextual() ||
          !version.asText().matches("v[0-9]+\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+"))
        throw fail(422, "VALIDATION_FAILED", "K3s 升级角色或固定版本无效");
      return;
    }
    if (capability.equals("kubeedge.edgecore.join")) {
      JsonNode version = payload == null ? null : payload.get("version");
      JsonNode endpoint = payload == null ? null : payload.get("cloudCoreEndpoint");
      JsonNode token = payload == null ? null : payload.get("token");
      boolean validVersion = version != null && version.isTextual()
          && version.asText().matches("v[0-9]+\\.[0-9]+\\.[0-9]+");
      boolean validEndpoint = endpoint != null && endpoint.isTextual()
          && validCloudCoreEndpoint(endpoint.asText());
      boolean validToken = token != null && token.isTextual()
          && token.asText().matches("[A-Za-z0-9_.-]{32,4096}")
          && token.asText().split("\\.").length == 3;
      if (payload == null || !payload.isObject() || payload.size() != 3
          || !validVersion || !validEndpoint || !validToken) {
        throw fail(422, "VALIDATION_FAILED", "KubeEdge EdgeCore 加入参数无效");
      }
      return;
    }
    if (capability.equals("file.write.protected")) {
      JsonNode content = payload == null ? null : payload.get("content");
      if (payload == null || !payload.isObject() || payload.size() != 1 || content == null ||
          !content.isTextual() || content.asText().contains("\0") ||
          content.asText().getBytes(StandardCharsets.UTF_8).length > 32 * 1024)
        throw fail(422, "VALIDATION_FAILED", "受保护文件内容无效或超过 32 KiB");
      return;
    }
    if (capability.equals("file.write")) {
      JsonNode content = payload == null ? null : payload.get("content");
      if (payload == null || !payload.isObject() || payload.size() != 1 || content == null ||
          !content.isTextual() || content.asText().chars().anyMatch(character ->
              Character.isISOControl(character) && character != '\n' && character != '\r' &&
                  character != '\t') ||
          content.asText().getBytes(StandardCharsets.UTF_8).length > 32 * 1024)
        throw fail(422, "VALIDATION_FAILED", "文件内容无效或超过 32 KiB");
      return;
    }
    if (capability.equals("container.deploy") && payload != null && !payload.isNull()) {
      if (!payload.isObject() || fieldSet(payload).stream().anyMatch(field -> !Set.of(
          "image", "hostPort", "containerPort", "environment", "volumes", "memoryMb",
          "stateful").contains(field)) || !payload.has("image"))
        throw fail(422, "VALIDATION_FAILED", "容器部署包含未定义字段");
      JsonNode image = payload.get("image");
      JsonNode hostPort = payload.get("hostPort");
      JsonNode containerPort = payload.get("containerPort");
      boolean hostPortMissing = hostPort == null || hostPort.isNull();
      boolean containerPortMissing = containerPort == null || containerPort.isNull();
      if (image == null || !image.isTextual() || !CONTAINER_IMAGE.matcher(image.asText()).matches()
          || hostPortMissing != containerPortMissing
          || (!hostPortMissing && (!validPort(hostPort) || !validPort(containerPort))))
        throw fail(422, "VALIDATION_FAILED", "容器镜像或端口映射无效");
      JsonNode environment = payload.get("environment");
      if (environment != null && !environment.isNull()) {
        if (!environment.isArray() || environment.isEmpty() || environment.size() > 32
            || !allValidEnvironmentEntries(environment))
          throw fail(422, "VALIDATION_FAILED", "环境变量必须是 1–32 个 KEY=VALUE 条目");
      }
      JsonNode volumes = payload.get("volumes");
      if (volumes != null && !volumes.isNull()) {
        if (!volumes.isArray() || volumes.isEmpty() || volumes.size() > 16
            || !validVolumeMounts(volumes))
          throw fail(422, "VALIDATION_FAILED", "数据卷名称或挂载路径无效");
      }
      JsonNode memoryMb = payload.get("memoryMb");
      if (memoryMb != null && !memoryMb.isNull()
          && (!memoryMb.isIntegralNumber() || !memoryMb.canConvertToInt()
              || memoryMb.intValue() < 64 || memoryMb.intValue() > 2048))
        throw fail(422, "VALIDATION_FAILED", "内存限制必须在 64–2048 MiB");
      JsonNode stateful = payload.get("stateful");
      if (stateful != null && !stateful.isNull() && !stateful.isBoolean())
        throw fail(422, "VALIDATION_FAILED", "stateful 必须是布尔值");
      return;
    }
    if (capability.equals("network.firewall.expose")) {
      JsonNode timeout = payload == null ? null : payload.get("rollbackSeconds");
      JsonNode interfaceName = payload == null ? null : payload.get("interfaceName");
      JsonNode protocol = payload == null ? null : payload.get("protocol");
      JsonNode port = payload == null ? null : payload.get("port");
      if (payload == null || !payload.isObject() || payload.size() != 4 || timeout == null ||
          !timeout.isIntegralNumber() || !timeout.canConvertToInt() ||
          timeout.intValue() < 30 || timeout.intValue() > 300 ||
          interfaceName == null || !interfaceName.isTextual() ||
          !NETWORK_INTERFACE.matcher(interfaceName.asText()).matches() ||
          protocol == null || !protocol.isTextual() ||
          !Set.of("tcp", "udp").contains(protocol.asText()) || !validPort(port))
        throw fail(422, "VALIDATION_FAILED", "网络接口、协议、端口或回滚时间无效");
      return;
    }
    if (capability.equals("network.topology.reconcile")) {
      validateNetworkTopology(target, payload, false);
      return;
    }
    if (capability.equals("network.topology.capture")) {
      validateNetworkTopology(target, payload, true);
      return;
    }
    if (capability.equals("nginx.route.set")) {
      validateNginxRoute(payload);
      return;
    }
    if (capability.equals("nginx.tls.install")) {
      validateTlsMaterial(payload);
      return;
    }
    if (capability.equals("cron.write")) {
      validateCronJob(payload);
      return;
    }
    if (payload != null && !payload.isNull())
      throw fail(422, "VALIDATION_FAILED", "该 Helper 操作不接受附加参数");
  }

  /**
   * A managed cron entry: five schedule fields, a single-line command, and root as the only
   * user (per-user cron files and their privileges are not modelled yet).
   */
  private void validateCronJob(JsonNode payload) {
    if (payload == null || !payload.isObject())
      throw fail(422, "VALIDATION_FAILED", "定时任务参数缺失");
    Set<String> fields = fieldSet(payload);
    if (!Set.of("schedule", "command").containsAll(fields)
        || !Set.of("schedule", "command", "user").containsAll(fields))
      throw fail(422, "VALIDATION_FAILED", "定时任务字段无效");
    JsonNode schedule = payload.get("schedule");
    JsonNode command = payload.get("command");
    if (schedule == null || !schedule.isTextual() || command == null || !command.isTextual())
      throw fail(422, "VALIDATION_FAILED", "计划与命令必须为文本");
    String expression = schedule.asText().trim();
    String[] parts = expression.split("\\s+");
    if (parts.length != 5 || expression.length() > 128
        || !java.util.Arrays.stream(parts).allMatch(part -> CRON_FIELD.matcher(part).matches()))
      throw fail(422, "VALIDATION_FAILED", "计划必须是 5 段 [0-9*,-/] 表达式");
    String commandText = command.asText().trim();
    if (commandText.isEmpty() || commandText.length() > 900
        || commandText.indexOf('\n') >= 0 || commandText.indexOf('\r') >= 0
        || commandText.indexOf('\0') >= 0)
      throw fail(422, "VALIDATION_FAILED", "命令必须是单行且不超过 900 字节");
    JsonNode user = payload.get("user");
    if (user != null && !user.isNull() && (!user.isTextual() || !"root".equals(user.asText())))
      throw fail(422, "VALIDATION_FAILED", "受管定时任务只允许以 root 运行");
  }

  private void validateNetworkTopology(String target, JsonNode payload, boolean capture) {
    JsonNode topologyId = payload == null ? null : payload.get("topologyId");
    JsonNode revisionId = payload == null ? null : payload.get("revisionId");
    JsonNode generation = payload == null ? null : payload.get("generation");
    JsonNode action = payload == null ? null : payload.get("action");
    JsonNode nodes = payload == null ? null : payload.get("nodes");
    JsonNode links = payload == null ? null : payload.get("links");
    JsonNode attachments = payload == null ? null : payload.get("attachments");
    JsonNode captureLinkId = payload == null ? null : payload.get("captureLinkId");
    Set<String> payloadFields = payload != null && payload.isObject() ? fieldSet(payload) : Set.of();
    Set<String> requiredPayloadFields = Set.of("action", "topologyId", "revisionId", "generation",
        "nodes", "links");
    Set<String> allowedPayloadFields = Set.of("action", "topologyId", "revisionId", "generation",
        "nodes", "links", "attachments", "captureLinkId");
    if (payload == null || !payload.isObject() || !allowedPayloadFields.containsAll(payloadFields)
        || !payloadFields.containsAll(requiredPayloadFields)
        || topologyId == null || !topologyId.isTextual() || !target.equals(topologyId.asText())
        || revisionId == null || !revisionId.isTextual() || !UUID_TEXT.matcher(revisionId.asText()).matches()
        || generation == null || !generation.isIntegralNumber() || !generation.canConvertToLong()
        || generation.longValue() < 1 || action == null || !action.isTextual()
        || !(capture ? "CAPTURE".equals(action.asText())
            : Set.of("APPLY", "VERIFY", "COMPENSATE").contains(action.asText()))
        || (capture ? captureLinkId == null || !captureLinkId.isTextual()
            || !UUID_TEXT.matcher(captureLinkId.asText()).matches()
            : captureLinkId != null)
        || nodes == null || !nodes.isArray() || nodes.isEmpty() || nodes.size() > 16
        || links == null || !links.isArray() || links.size() > 64
        || (attachments != null && (!attachments.isArray() || attachments.size() > 64))
        || compactJson(payload).getBytes(StandardCharsets.UTF_8).length > 48 * 1024) {
      throw fail(422, "VALIDATION_FAILED", "隔离拓扑网络操作参数无效");
    }
    var nodeIds = new java.util.HashSet<String>();
    var nodeKinds = new java.util.HashMap<String, String>();
    var nodeBackends = new java.util.HashMap<String, String>();
    for (JsonNode node : nodes) {
      JsonNode id = node == null ? null : node.get("id");
      JsonNode kind = node == null ? null : node.get("kind");
      Set<String> fields = node != null && node.isObject() ? fieldSet(node) : Set.of();
      if (node == null || !node.isObject() || !Set.of("id", "kind", "switchBackend", "firewallPolicy",
          "routerConfiguration").containsAll(fields)
          || !fields.containsAll(Set.of("id", "kind"))
          || id == null || !id.isTextual() || !UUID_TEXT.matcher(id.asText()).matches()
          || kind == null || !kind.isTextual()
          || !Set.of("SWITCH", "PACKET_TAP", "FIREWALL", "ROUTER").contains(kind.asText())
          || !nodeIds.add(id.asText())) {
        throw fail(422, "VALIDATION_FAILED", "隔离 netns Provider 仅接受唯一 SWITCH、ROUTER、FIREWALL 和 PACKET_TAP 设备");
      }
      nodeKinds.put(id.asText(), kind.asText());
      if (node.path("switchBackend").isTextual()) {
        nodeBackends.put(id.asText(), node.path("switchBackend").asText());
      }
      boolean hasFirewall = !node.path("firewallPolicy").isMissingNode()
          && !node.path("firewallPolicy").isNull();
      boolean hasRouter = !node.path("routerConfiguration").isMissingNode()
          && !node.path("routerConfiguration").isNull();
      if (("FIREWALL".equals(kind.asText()) != hasFirewall)
          || ("ROUTER".equals(kind.asText()) != hasRouter)
          || (!"SWITCH".equals(kind.asText()) && !node.path("switchBackend").isNull()
              && !node.path("switchBackend").isMissingNode())
          || ("SWITCH".equals(kind.asText()) && !node.path("switchBackend").isNull()
              && !node.path("switchBackend").isMissingNode()
              && !Set.of("BRIDGE", "OPENVSWITCH").contains(node.path("switchBackend").asText()))) {
        throw fail(422, "VALIDATION_FAILED", "虚拟设备类型与设备配置字段不匹配");
      }
    }
    var linkIds = new java.util.HashSet<String>();
    var occupiedPorts = new java.util.HashSet<String>();
    boolean captureLinkFound = false;
    for (JsonNode link : links) {
      if (!objectWithKeys(link, Set.of("id", "sourceNodeId", "sourcePort", "targetNodeId",
          "targetPort", "layer", "protocol"))) {
        throw fail(422, "VALIDATION_FAILED", "隔离拓扑链路字段无效");
      }
      String id = link.path("id").asText();
      String sourceNode = link.path("sourceNodeId").asText();
      String sourcePort = link.path("sourcePort").asText();
      String targetNode = link.path("targetNodeId").asText();
      String targetPort = link.path("targetPort").asText();
      if (capture && captureLinkId.asText().equals(id)) captureLinkFound = true;
      if (!UUID_TEXT.matcher(id).matches() || !linkIds.add(id)
          || !nodeIds.contains(sourceNode) || !nodeIds.contains(targetNode) || sourceNode.equals(targetNode)
          || !sourcePort.matches("out-([1-9]|1[0-6])")
          || !targetPort.matches("in-([1-9]|1[0-6])")
          || !"L2".equals(link.path("layer").asText())
          || !"ETHERNET".equals(link.path("protocol").asText())
          || !occupiedPorts.add(physicalPortKey(sourceNode, sourcePort, nodeKinds))
          || !occupiedPorts.add(physicalPortKey(targetNode, targetPort, nodeKinds))) {
        throw fail(422, "VALIDATION_FAILED", "Linux Bridge 链路必须连接唯一 L2 Ethernet switch 端口");
      }
    }
    if (capture && !captureLinkFound) {
      throw fail(422, "VALIDATION_FAILED", "抓包选择的链路不属于此拓扑 Revision");
    }
    var attachmentIds = new java.util.HashSet<String>();
    var cidrs = new java.util.HashSet<String>();
    Iterable<JsonNode> attachmentValues = attachments == null ? List.of() : attachments;
    for (JsonNode attachment : attachmentValues) {
      if (!objectWithKeys(attachment, Set.of("id", "nodeId", "devicePort", "networkCidr"))) {
        throw fail(422, "VALIDATION_FAILED", "隔离拓扑工作负载接入字段无效");
      }
      String id = attachment.path("id").asText();
      String nodeId = attachment.path("nodeId").asText();
      String port = attachment.path("devicePort").asText();
      String cidr = attachment.path("networkCidr").asText();
      if (!UUID_TEXT.matcher(id).matches() || !attachmentIds.add(id)
          || !"SWITCH".equals(nodeKinds.get(nodeId))
          || "OPENVSWITCH".equals(nodeBackends.get(nodeId))
          || (!port.matches("in-([1-9]|1[0-6])") && !port.matches("out-([1-9]|1[0-6])"))
          || linkIds.contains(id) || !occupiedPorts.add(physicalPortKey(nodeId, port, nodeKinds))
          || !validAttachmentCidr(cidr) || !cidrs.add(cidr)) {
        throw fail(422, "VALIDATION_FAILED", "工作负载接入必须是唯一 Linux Bridge SWITCH 端口和隔离 /24 网段");
      }
    }
  }

  private Set<String> fieldSet(JsonNode value) {
    var fields = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private String physicalPortKey(String nodeId, String port, Map<String, String> nodeKinds) {
    if ("SWITCH".equals(nodeKinds.get(nodeId))
        && (port.matches("in-([1-9]|1[0-6])") || port.matches("out-([1-9]|1[0-6])"))) {
      return nodeId + ":interface-" + port.substring(port.indexOf('-') + 1);
    }
    return nodeId + ":" + port;
  }

  private boolean validAttachmentCidr(String value) {
    var matcher = java.util.regex.Pattern.compile("^198\\.(18|19)\\.(0|[1-9][0-9]{0,2})\\.0/24$")
        .matcher(value);
    if (!matcher.matches()) return false;
    try {
      return Integer.parseInt(matcher.group(2)) <= 255;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private boolean objectWithKeys(JsonNode value, Set<String> expectedKeys) {
    if (value == null || !value.isObject() || value.size() != expectedKeys.size()) return false;
    var actualKeys = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(actualKeys::add);
    return actualKeys.equals(expectedKeys);
  }

  void validateNginxRoute(JsonNode payload) {
    JsonNode address = payload == null ? null : payload.get("listenAddress");
    JsonNode port = payload == null ? null : payload.get("listenPort");
    JsonNode hostname = payload == null ? null : payload.get("serverName");
    JsonNode healthPath = payload == null ? null : payload.get("healthPath");
    JsonNode backends = payload == null ? null : payload.get("backends");
    JsonNode tls = payload == null ? null : payload.get("tls");
    java.util.Set<String> routeFields = payload != null && payload.isObject()
        ? fieldSet(payload) : java.util.Set.of();
    java.util.Set<String> baseFields = java.util.Set.of(
        "listenAddress", "listenPort", "serverName", "healthPath", "backends");
    boolean validFields = routeFields.equals(baseFields)
        || (routeFields.size() == 6 && routeFields.containsAll(baseFields)
            && routeFields.contains("tls"));
    boolean validTls = tls == null || (tls.isObject() && tls.size() == 2
        && validTlsName(tls.get("certificate")) && validTlsName(tls.get("privateKey")));
    boolean validAddress = address != null && address.isTextual()
        && validHostAddress(address.asText());
    boolean validHostname = hostname != null && hostname.isTextual()
        && hostname.asText().length() <= 253
        && java.util.Arrays.stream(hostname.asText().split("\\.", -1)).allMatch(label ->
            label.length() >= 1 && label.length() <= 63
                && label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"));
    boolean validHealthPath = healthPath != null && healthPath.isTextual()
        && healthPath.asText().startsWith("/") && healthPath.asText().length() <= 256
        && !healthPath.asText().contains("..")
        && healthPath.asText().matches("/[A-Za-z0-9/._~-]*");
    if (payload == null || !payload.isObject() || !validFields || !validAddress
        || !validPort(port) || !validHostname || !validHealthPath || backends == null
        || !backends.isArray() || backends.isEmpty() || backends.size() > 2 || !validTls) {
      throw fail(422, "VALIDATION_FAILED", "Nginx 路由监听地址、主机名或健康检查参数无效");
    }
    var names = new java.util.HashSet<String>();
    var ports = new java.util.HashSet<Integer>();
    int weightSum = 0;
    boolean hasPositiveWeight = false;
    for (JsonNode backend : backends) {
      JsonNode name = backend.get("name");
      JsonNode backendPort = backend.get("port");
      JsonNode weight = backend.get("weight");
      if (!backend.isObject() || backend.size() != 3 || name == null || !name.isTextual()
          || !name.asText().matches("[a-z0-9][a-z0-9-]{0,31}")
          || !names.add(name.asText()) || !validPort(backendPort) || weight == null
          || !weight.isIntegralNumber() || !weight.canConvertToInt()
          || weight.intValue() < 0 || weight.intValue() > 100
          || !ports.add(backendPort.intValue())) {
        throw fail(422, "VALIDATION_FAILED", "Nginx 后端名称、端口或权重无效");
      }
      weightSum += weight.intValue();
      hasPositiveWeight |= weight.intValue() > 0;
    }
    if (weightSum != 100 || !hasPositiveWeight) {
      throw fail(422, "VALIDATION_FAILED", "Nginx 后端权重总和必须为 100");
    }
  }

  /** TLS material payload: exactly the two PEMs, bounded in size and marker-checked. */
  private void validateTlsMaterial(JsonNode payload) {
    if (payload == null || !payload.isObject() || payload.size() != 2
        || !fieldSet(payload).equals(java.util.Set.of("certificatePem", "privateKeyPem"))
        || !validPem(payload.get("certificatePem"), "CERTIFICATE")
        || !validPem(payload.get("privateKeyPem"), "PRIVATE KEY")) {
      throw fail(422, "VALIDATION_FAILED", "TLS 证书或私钥格式无效");
    }
  }

  private static boolean validTlsName(JsonNode value) {
    return value != null && value.isTextual() && TLS_NAME.matcher(value.asText()).matches();
  }

  private static boolean validPem(JsonNode value, String label) {
    if (value == null || !value.isTextual()) return false;
    String pem = value.asText().trim();
    if (pem.isEmpty() || pem.length() > 64 * 1024) return false;
    String begin = "-----BEGIN " + label + "-----";
    String end = "-----END " + label + "-----";
    return pem.startsWith(begin) && pem.endsWith(end)
        && pem.chars().allMatch(ch -> ch >= 0x21 && ch <= 0x7e || ch == '\n' || ch == '\r' || ch == ' ');
  }

  private boolean validHostAddress(String value) {
    if (value == null || value.contains("%")) return false;
    try {
      java.net.InetAddress address;
      if (value.matches("[0-9.]+")) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
          int number = Integer.parseInt(octet);
          if (number < 0 || number > 255) return false;
        }
        address = java.net.InetAddress.getByAddress(new byte[] {
            (byte)Integer.parseInt(octets[0]), (byte)Integer.parseInt(octets[1]),
            (byte)Integer.parseInt(octets[2]), (byte)Integer.parseInt(octets[3])});
      } else if (value.contains(":")) {
        address = java.net.InetAddress.getByName(value);
        if (!(address instanceof java.net.Inet6Address)) return false;
      } else {
        return false;
      }
      return !address.isAnyLocalAddress() && !address.isMulticastAddress();
    } catch (java.net.UnknownHostException | NumberFormatException invalid) {
      return false;
    }
  }

  boolean validK3sServerUrl(String value) {
    if (value.length() > 512 || value.contains("@")) return false;
    try {
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !(uri.getPort() == -1 || uri.getPort() == 6443)
          || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) return false;
      String host = uri.getHost();
      if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")) return false;
      if (host.matches("[0-9.]+") || host.contains(":")) {
        java.net.InetAddress address = java.net.InetAddress.getByName(host);
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress()
            && !address.isLinkLocalAddress() && !address.isMulticastAddress();
      }
      return true;
    } catch (IllegalArgumentException | java.net.UnknownHostException invalid) {
      return false;
    }
  }

  private static boolean validCloudCoreEndpoint(String endpoint) {
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

  private boolean validHostPath(String target, boolean allowRoot) {
    if (target == null || target.length() > 4096 || !target.startsWith("/") ||
        target.chars().anyMatch(Character::isISOControl))
      return false;
    if (target.equals("/")) return allowRoot;
    String[] components = target.substring(1).split("/", -1);
    return components.length > 0 && java.util.Arrays.stream(components).allMatch(component ->
        !component.isEmpty() && !component.equals(".") && !component.equals(".."));
  }

  private boolean allValidEnvironmentEntries(JsonNode array) {
    for (JsonNode entry : array) {
      if (!entry.isTextual() || !validEnvironmentEntry(entry.asText())) return false;
    }
    return true;
  }

  private boolean validEnvironmentEntry(String value) {
    if (value == null || value.isEmpty() || value.length() > 2048 || value.contains("\0"))
      return false;
    int equals = value.indexOf('=');
    if (equals <= 0) return false;
    String name = value.substring(0, equals);
    return name.length() <= 64 && name.matches("[A-Za-z_][A-Za-z0-9_]*");
  }

  private boolean validVolumeMounts(JsonNode array) {
    var keys = new java.util.HashSet<String>();
    for (JsonNode volume : array) {
      if (!volume.isObject() || volume.size() != 2) return false;
      JsonNode name = volume.get("name");
      JsonNode mountPath = volume.get("mountPath");
      if (name == null || !name.isTextual() || !CONTAINER_NAME.matcher(name.asText()).matches()
          || mountPath == null || !mountPath.isTextual()
          || !validHostPath(mountPath.asText(), false)
          || !keys.add(name.asText() + ":" + mountPath.asText()))
        return false;
    }
    return true;
  }

  private boolean validPort(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToInt() &&
        value.intValue() >= 1 && value.intValue() <= 65535;
  }

  private int defaultTimeout(String capability) {
    return Set.of("k3s.install", "k3s.upgrade").contains(capability) ? 600
        : capability.equals("package.install") ? 300 : 60;
  }

  private String operationSelect() {
    return "SELECT o.*,u.username AS requested_by_name FROM operation o " +
        "LEFT JOIN panel_user u ON u.id=o.requested_by";
  }

  /** Shared with {@link OperationsResource} so the platform-wide list and the host list agree. */
  Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", String.valueOf(row.get("id")));
    value.put("kind", row.get("kind"));
    value.put("hostId", row.get("host_id") == null ? null : String.valueOf(row.get("host_id")));
    value.put("agentId", row.get("agent_id") == null ? null : String.valueOf(row.get("agent_id")));
    value.put("serviceId", row.get("service_id") == null ? null : String.valueOf(row.get("service_id")));
    value.put("capability", row.get("capability"));
    value.put("target", row.get("target"));
    value.put("idempotencyKey", row.get("idempotency_key"));
    value.put("requestedBy", row.get("requested_by_name"));
    value.put("reason", row.get("reason"));
    value.put("expectedGeneration", row.get("expected_generation"));
    value.put("phase", row.get("phase"));
    value.put("status", row.get("status"));
    value.put("createdAt", stamp(row.get("created_at")));
    value.put("startedAt", stamp(row.get("started_at")));
    value.put("updatedAt", stamp(row.get("updated_at")));
    value.put("deadlineAt", stamp(row.get("deadline_at")));
    value.put("finishedAt", stamp(row.get("finished_at")));
    value.put("result", parseStoredJson(row.get("result_json")));
    value.put("error", parseStoredJson(row.get("error_json")));
    return value;
  }

  private Object parseStoredJson(Object value) {
    if (value == null) return null;
    try { return mapper.readValue(String.valueOf(value), Object.class); }
    catch (Exception e) { throw new IllegalStateException("Stored operation JSON is invalid", e); }
  }

  private String compactJson(JsonNode value) {
    try { return mapper.writeValueAsString(value); }
    catch (Exception e) { throw new IllegalStateException("Unable to serialize operation payload", e); }
  }

  private boolean contains(JsonNode array, String value) {
    for (JsonNode item : array) if (item.isTextual() && value.equals(item.asText())) return true;
    return false;
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    if (value instanceof Number timestamp) return Instant.ofEpochMilli(timestamp.longValue());
    throw new IllegalStateException("Unsupported timestamp value");
  }

  private String stamp(Object value) {
    Instant timestamp = instant(value);
    return timestamp == null ? null : timestamp.toString();
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private String validateIdempotencyKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
      throw fail(400, "VALIDATION_FAILED", "Idempotency-Key 格式无效");
    return key;
  }

  private UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }
}
