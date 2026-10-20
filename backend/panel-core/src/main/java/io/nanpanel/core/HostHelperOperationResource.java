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
      "container.start", "container.stop", "container.deploy", "network.firewall.expose");
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
    UUID hostId = uuid(hostValue, "hostId");
    String actor = actorId();
    String key = validateIdempotencyKey(idempotencyKey);
    if (input == null || !CAPABILITIES.contains(input.capability()))
      throw fail(422, "OPERATION_UNSUPPORTED", "该主机 Helper 操作能力不受支持");
    String target = input.target() == null ? "" : input.target();
    validateTargetAndPayload(input.capability(), target, input.payload());
    String reason = input.reason() == null ? "" : input.reason().trim();
    int timeoutSeconds = input.timeoutSeconds() == null ? defaultTimeout(input.capability())
        : input.timeoutSeconds();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl) ||
        timeoutSeconds < 5 || timeoutSeconds > 300 || input.expectedGeneration() == null ||
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
          clientIp(), "接受受限 Helper 操作：" + reason);
      var created = query(c, operationSelect() + " WHERE o.id=?", operationId);
      return new Decision(view(created.getFirst()), 202, false, agentId,
          input.capability(), target, payloadJson, deadline.toEpochMilli());
    });

    if (!decision.replayed()) dispatch(decision, actor, hostId, key);
    return Response.status(decision.status()).entity(decision.operation())
        .header("Location", "/api/v1/operations/" + decision.operation().get("id"))
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed())).build();
  }

  private void dispatch(Decision decision, String actor, UUID hostId, String key) {
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
        failDispatch(decision, actor, hostId, detail, uncertain);
        throw fail(503, uncertain ? "EXECUTION_OUTCOME_UNKNOWN" : "GATEWAY_UNAVAILABLE",
            uncertain ? "Gateway 未确认任务状态，请使用原幂等键查询"
                : "Gateway 拒绝了任务，请检查 Agent 状态后重试");
      }
    } catch (jakarta.ws.rs.WebApplicationException e) {
      throw e;
    } catch (Exception e) {
      failDispatch(decision, actor, hostId,
          "Agent Gateway response was not received; task execution is uncertain", true);
      throw fail(503, "EXECUTION_OUTCOME_UNKNOWN", "未收到 Gateway 确认，请使用原幂等键查询任务状态");
    }
  }

  private void failDispatch(Decision decision, String actor, UUID hostId,
                            String detail, boolean uncertain) {
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
          uncertain ? "UNKNOWN" : "FAILED", clientIp(), detail);
      return null;
    });
  }

  private void validateTargetAndPayload(String capability, String target, JsonNode payload) {
    boolean validTarget = switch (capability) {
      case "package.install", "container.list" -> target.isEmpty();
      case "file.write.protected" -> FILE_NAME.matcher(target).matches();
      case "container.logs", "container.start", "container.stop", "container.deploy" ->
          CONTAINER_NAME.matcher(target).matches();
      case "network.firewall.expose" -> NETWORK_RULE_NAME.matcher(target).matches();
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
    if (capability.equals("file.write.protected")) {
      JsonNode content = payload == null ? null : payload.get("content");
      if (payload == null || !payload.isObject() || payload.size() != 1 || content == null ||
          !content.isTextual() || content.asText().contains("\0") ||
          content.asText().getBytes(StandardCharsets.UTF_8).length > 32 * 1024)
        throw fail(422, "VALIDATION_FAILED", "受保护文件内容无效或超过 32 KiB");
      return;
    }
    if (capability.equals("container.deploy") && payload != null && !payload.isNull()) {
      JsonNode image = payload.get("image");
      JsonNode hostPort = payload.get("hostPort");
      JsonNode containerPort = payload.get("containerPort");
      boolean hostPortMissing = hostPort == null || hostPort.isNull();
      boolean containerPortMissing = containerPort == null || containerPort.isNull();
      if (!payload.isObject() || payload.size() != 3 || image == null || !image.isTextual() ||
          !CONTAINER_IMAGE.matcher(image.asText()).matches() ||
          hostPortMissing != containerPortMissing ||
          (!hostPortMissing && (!validPort(hostPort) || !validPort(containerPort))))
        throw fail(422, "VALIDATION_FAILED", "容器镜像或端口映射无效");
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
    if (payload != null && !payload.isNull())
      throw fail(422, "VALIDATION_FAILED", "该容器操作不接受附加参数");
  }

  private boolean validPort(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToInt() &&
        value.intValue() >= 1 && value.intValue() <= 65535;
  }

  private int defaultTimeout(String capability) {
    return "package.install".equals(capability) ? 300 : 60;
  }

  private String operationSelect() {
    return "SELECT o.*,u.username AS requested_by_name FROM operation o " +
        "LEFT JOIN panel_user u ON u.id=o.requested_by";
  }

  private Map<String, Object> view(Map<String, Object> row) {
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
