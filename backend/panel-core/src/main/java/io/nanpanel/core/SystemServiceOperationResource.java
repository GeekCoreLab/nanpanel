package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
import java.util.ArrayList;
import java.util.Base64;
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
public class SystemServiceOperationResource {
  private static final HttpClient CONTROL_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).build();
  private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "RUNNING", "UNKNOWN");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject SystemServiceResource services;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-control-url")
  String gatewayControlUrl;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-token")
  String gatewayToken;

  public record RestartRequest(String reason, Integer timeoutSeconds) {}
  public record JournalRequest(String reason) {}
  private record Decision(Map<String, Object> operation, int status,
                          boolean replayed, UUID agentId, String target) {}

  private String actorId() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  @POST
  @Path("hosts/{hostId}/services/{serviceId}/restart")
  public Response restart(@PathParam("hostId") String hostValue,
                          @PathParam("serviceId") String serviceValue,
                          @HeaderParam("Idempotency-Key") String idempotencyKey,
                          @HeaderParam("If-Match") String ifMatch,
                          RestartRequest input) {
    UUID hostId = uuid(hostValue, "hostId");
    UUID serviceId = uuid(serviceValue, "serviceId");
    String actor = actorId();
    String key = validateIdempotencyKey(idempotencyKey);
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    int timeoutSeconds = input == null || input.timeoutSeconds() == null
        ? 60 : input.timeoutSeconds();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl) ||
        timeoutSeconds < 5 || timeoutSeconds > 300)
      throw fail(422, "VALIDATION_FAILED", "操作原因或超时范围无效");
    if (ifMatch == null)
      throw fail(428, "PRECONDITION_REQUIRED", "请先读取服务详情并提供 If-Match");
    if (!ifMatch.matches("\"[A-Za-z0-9._:-]{1,200}\""))
      throw fail(412, "PRECONDITION_FAILED", "If-Match 格式无效或不支持通配符");

    String path = "/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart";
    String requestHash = AuthStore.digest(String.join("\n", "POST", path,
        reason, Integer.toString(timeoutSeconds), ifMatch));
    Instant now = Instant.now();
    Instant deadline = now.plusSeconds(timeoutSeconds);
    UUID operationId = UUID.randomUUID();
    String finalReason = reason;
    Decision decision = store.transaction(c -> {
      // Lock the singleton settings row so concurrent submissions cannot race
      // on a not-yet-existing idempotency key or per-service operation check.
      query(c, "SELECT id FROM platform_settings WHERE id=1 FOR UPDATE");
      var existing = query(c,
          operationSelect() + " WHERE o.idempotency_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (!Objects.equals(String.valueOf(row.get("requested_by")), actor) ||
            !Objects.equals(String.valueOf(row.get("host_id")), hostId.toString()) ||
            !Objects.equals(String.valueOf(row.get("service_id")), serviceId.toString()) ||
            !MessageDigest.isEqual(
                String.valueOf(row.get("request_hash")).getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII)))
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
        String status = String.valueOf(row.get("status"));
        return new Decision(view(row), Set.of("SUCCEEDED", "FAILED", "CANCELLED")
            .contains(status) ? 200 : 202, true,
            row.get("agent_id") == null ? null : (UUID)row.get("agent_id"), null);
      }

      query(c, "SELECT id FROM system_service_observation WHERE host_id=? AND id=? FOR UPDATE",
          hostId, serviceId);
      var observed = query(c,
          "SELECT s.id,s.host_id,s.unit_name,s.display_name,s.active_state,s.sub_state," +
              "s.observed_at,h.generation,h.capabilities" +
              (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
                  ? "::text" : "") + " AS capabilities,a.id AS agent_id,a.helper_status," +
              "a.last_seen,p.host_offline_after_seconds FROM system_service_observation s " +
              "JOIN host h ON h.id=s.host_id CROSS JOIN platform_settings p " +
              "LEFT JOIN agent a ON a.host_id=h.id " +
              "WHERE s.host_id=? AND s.id=? AND p.id=1 " +
              "ORDER BY a.last_seen DESC NULLS LAST LIMIT 1",
          hostId, serviceId);
      if (observed.isEmpty()) throw fail(404, "NOT_FOUND", "systemd 服务不存在");
      var service = observed.getFirst();
      String etag = services.etag(services.service(service,
          ((Number)service.get("host_offline_after_seconds")).intValue(), now));
      if (!MessageDigest.isEqual(
          ("\"" + etag + "\"").getBytes(StandardCharsets.US_ASCII),
          ifMatch.getBytes(StandardCharsets.US_ASCII)))
        throw fail(412, "PRECONDITION_FAILED", "服务观测或支持的操作已变化，请刷新后重试");
      Instant observedAt = instant(service.get("observed_at"));
      if (observedAt == null || observedAt.isBefore(now.minusSeconds(
          ((Number)service.get("host_offline_after_seconds")).intValue())))
        throw fail(503, "SERVICE_OBSERVATION_STALE", "systemd 服务观测已过期，请等待 Agent 更新后重试");
      if (service.get("agent_id") == null)
        throw fail(503, "AGENT_OFFLINE", "该主机尚未接入 Agent");
      Instant lastSeen = instant(service.get("last_seen"));
      int offlineAfter = ((Number)service.get("host_offline_after_seconds")).intValue();
      if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(offlineAfter)))
        throw fail(503, "AGENT_OFFLINE", "Agent 当前不在线");
      if (!"AVAILABLE".equals(service.get("helper_status")))
        throw fail(503, "HELPER_UNAVAILABLE", "Agent Helper 当前不可用");
      JsonNode capabilities = mapper.readTree(String.valueOf(service.get("capabilities")));
      if (!capabilities.isArray() || !contains(capabilities, "systemd.restart"))
        throw fail(422, "OPERATION_UNSUPPORTED", "Agent 未报告 systemd.restart 能力");
      String unitName = String.valueOf(service.get("unit_name"));
      var pending = query(c,
          "SELECT o.id FROM operation o WHERE o.status IN ('PENDING','RUNNING','UNKNOWN') AND " +
              "((o.service_id=?) OR (o.host_id=? AND o.kind='SSH_SYSTEMD_RESTART' " +
              "AND o.target=?)) LIMIT 1",
          serviceId, hostId, unitName);
      if (!pending.isEmpty())
        throw fail(409, "OPERATION_IN_PROGRESS", "该 systemd 服务已有操作等待完成或对账");

      UUID agentId = (UUID)service.get("agent_id");
      long generation = ((Number)service.get("generation")).longValue();
      update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,service_id,requested_by,reason,expected_generation," +
              "started_at,deadline_at,request_hash) " +
              "VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,?,?,?,?,?,?,?,?)",
          operationId, key, "APPLY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
          "SYSTEM_SERVICE_RESTART", hostId, agentId, serviceId, actor, finalReason, generation,
          java.sql.Timestamp.from(now), java.sql.Timestamp.from(deadline), requestHash);
      audit(c, actor, "systemd.restart", "system_service", serviceId.toString(), "ACCEPTED",
          clientIp(), "接受 systemd 服务重启任务：" + finalReason);
      var created = query(c, operationSelect() + " WHERE o.id=?", operationId);
      return new Decision(view(created.getFirst()), 202, false, agentId,
          String.valueOf(service.get("unit_name")));
    });

    if (!decision.replayed()) {
      try {
        var dispatch = Map.of(
            "agentId", decision.agentId().toString(),
            "operationId", String.valueOf(decision.operation().get("id")),
            "idempotencyKey", key,
            "capability", "systemd.restart",
            "target", decision.target(),
            "deadlineEpochMs", deadline.toEpochMilli());
        var body = mapper.writeValueAsBytes(dispatch);
        var gatewayRequest = HttpRequest.newBuilder(URI.create(gatewayControlUrl))
            .timeout(Duration.ofSeconds(5))
            .header("X-NanPanel-Gateway-Token", gatewayToken)
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        var gatewayResponse = CONTROL_CLIENT.send(gatewayRequest,
            HttpResponse.BodyHandlers.discarding());
        if (gatewayResponse.statusCode() != 202) {
          String detail = gatewayResponse.statusCode() == 404
              ? "Agent Gateway has no active mTLS session for this Agent"
              : "Agent Gateway rejected the systemd request (HTTP " + gatewayResponse.statusCode() + ")";
          failDispatch(operationId, actor, serviceId, "systemd.restart", detail,
              gatewayResponse.statusCode() >= 500);
          throw fail(gatewayResponse.statusCode() == 404 ? 503 : 503,
              gatewayResponse.statusCode() == 404 ? "AGENT_OFFLINE" :
                  gatewayResponse.statusCode() >= 500 ? "EXECUTION_OUTCOME_UNKNOWN" : "GATEWAY_UNAVAILABLE",
              gatewayResponse.statusCode() >= 500
                  ? "Gateway 响应未能确认任务是否进入执行队列，请勿更换幂等键重试"
                  : detail);
        }
      } catch (jakarta.ws.rs.WebApplicationException e) {
        throw e;
      } catch (Exception e) {
        String detail = "Agent Gateway response was not received; task execution is uncertain";
        failDispatch(operationId, actor, serviceId, "systemd.restart", detail, true);
        throw fail(503, "EXECUTION_OUTCOME_UNKNOWN",
            "未收到 Gateway 确认，任务可能已进入执行队列；请使用原幂等键查询状态");
      }
    }
    return Response.status(decision.status())
        .entity(decision.operation())
        .header("Location", "/api/v1/operations/" + decision.operation().get("id"))
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed()))
        .build();
  }

  @POST
  @Path("hosts/{hostId}/services/{serviceId}/journal")
  public Response journal(@PathParam("hostId") String hostValue,
                         @PathParam("serviceId") String serviceValue,
                         @HeaderParam("Idempotency-Key") String idempotencyKey,
                         @HeaderParam("If-Match") String ifMatch,
                         JournalRequest input) {
    UUID hostId = uuid(hostValue, "hostId");
    UUID serviceId = uuid(serviceValue, "serviceId");
    String actor = actorId();
    String key = validateIdempotencyKey(idempotencyKey);
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "查询原因无效");
    if (ifMatch == null)
      throw fail(428, "PRECONDITION_REQUIRED", "请先读取服务详情并提供 If-Match");
    if (!ifMatch.matches("\"[A-Za-z0-9._:-]{1,200}\""))
      throw fail(412, "PRECONDITION_FAILED", "If-Match 格式无效或不支持通配符");

    String path = "/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal";
    String requestHash = AuthStore.digest(String.join("\n", "POST", path, reason, ifMatch));
    Instant now = Instant.now();
    Instant deadline = now.plusSeconds(30);
    UUID operationId = UUID.randomUUID();
    Decision decision = store.transaction(c -> {
      query(c, "SELECT id FROM platform_settings WHERE id=1 FOR UPDATE");
      var existing = query(c, operationSelect() + " WHERE o.idempotency_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (!Objects.equals(row.get("requested_by"), actor) ||
            !Objects.equals(row.get("host_id"), hostId) ||
            !Objects.equals(row.get("service_id"), serviceId) ||
            !Objects.equals(row.get("kind"), "SYSTEMD_JOURNAL_READ") ||
            !MessageDigest.isEqual(
                String.valueOf(row.get("request_hash")).getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII)))
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
        String status = String.valueOf(row.get("status"));
        return new Decision(view(row), Set.of("SUCCEEDED", "FAILED", "CANCELLED")
            .contains(status) ? 200 : 202, true,
            row.get("agent_id") == null ? null : (UUID)row.get("agent_id"), null);
      }

      query(c, "SELECT id FROM system_service_observation WHERE host_id=? AND id=? FOR UPDATE",
          hostId, serviceId);
      var observed = query(c,
          "SELECT s.id,s.host_id,s.unit_name,s.display_name,s.active_state,s.sub_state," +
              "s.observed_at,h.generation,h.capabilities" +
              (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
                  ? "::text" : "") + " AS capabilities,a.id AS agent_id,a.helper_status,a.last_seen," +
              "p.host_offline_after_seconds FROM system_service_observation s " +
              "JOIN host h ON h.id=s.host_id CROSS JOIN platform_settings p " +
              "LEFT JOIN agent a ON a.host_id=h.id " +
              "WHERE s.host_id=? AND s.id=? AND p.id=1 " +
              "ORDER BY a.last_seen DESC NULLS LAST LIMIT 1",
          hostId, serviceId);
      if (observed.isEmpty()) throw fail(404, "NOT_FOUND", "systemd 服务不存在");
      var service = observed.getFirst();
      int offlineAfter = ((Number)service.get("host_offline_after_seconds")).intValue();
      String etag = services.etag(services.service(service, offlineAfter, now));
      if (!MessageDigest.isEqual(("\"" + etag + "\"").getBytes(StandardCharsets.US_ASCII),
          ifMatch.getBytes(StandardCharsets.US_ASCII)))
        throw fail(412, "PRECONDITION_FAILED", "服务观测或支持的操作已变化，请刷新后重试");
      Instant observedAt = instant(service.get("observed_at"));
      if (observedAt == null || observedAt.isBefore(now.minusSeconds(offlineAfter)))
        throw fail(503, "SERVICE_OBSERVATION_STALE", "systemd 服务观测已过期，请等待 Agent 更新后重试");
      if (service.get("agent_id") == null)
        throw fail(503, "AGENT_OFFLINE", "该主机尚未接入 Agent");
      Instant lastSeen = instant(service.get("last_seen"));
      if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(offlineAfter)))
        throw fail(503, "AGENT_OFFLINE", "Agent 当前不在线");
      if (!"AVAILABLE".equals(service.get("helper_status")))
        throw fail(503, "HELPER_UNAVAILABLE", "Agent Helper 当前不可用");
      JsonNode capabilities = mapper.readTree(String.valueOf(service.get("capabilities")));
      if (!capabilities.isArray() || !contains(capabilities, "systemd.journal.read"))
        throw fail(422, "OPERATION_UNSUPPORTED", "Agent 未报告 systemd.journal.read 能力");
      var pending = query(c,
          "SELECT id FROM operation WHERE service_id=? AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1",
          serviceId);
      if (!pending.isEmpty())
        throw fail(409, "OPERATION_IN_PROGRESS", "该 systemd 服务已有操作等待完成或对账");

      UUID agentId = (UUID)service.get("agent_id");
      long generation = ((Number)service.get("generation")).longValue();
      update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,service_id,requested_by,reason,expected_generation," +
              "started_at,deadline_at,request_hash) " +
              "VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,?,?,?,?,?,?,?,?)",
          operationId, key, "APPLY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
          "SYSTEMD_JOURNAL_READ", hostId, agentId, serviceId, actor, reason, generation,
          java.sql.Timestamp.from(now), java.sql.Timestamp.from(deadline), requestHash);
      audit(c, actor, "systemd.journal.read", "system_service", serviceId.toString(),
          "ACCEPTED", clientIp(), "接受 systemd journal 查询：" + reason);
      var created = query(c, operationSelect() + " WHERE o.id=?", operationId);
      return new Decision(view(created.getFirst()), 202, false, agentId,
          String.valueOf(service.get("unit_name")));
    });

    if (!decision.replayed()) {
      try {
        var dispatch = Map.of(
            "agentId", decision.agentId().toString(),
            "operationId", String.valueOf(decision.operation().get("id")),
            "idempotencyKey", key,
            "capability", "systemd.journal.read",
            "target", decision.target(),
            "deadlineEpochMs", deadline.toEpochMilli());
        var gatewayRequest = HttpRequest.newBuilder(URI.create(gatewayControlUrl))
            .timeout(Duration.ofSeconds(5))
            .header("X-NanPanel-Gateway-Token", gatewayToken)
            .header("Content-Type", MediaType.APPLICATION_JSON)
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(dispatch)))
            .build();
        var gatewayResponse = CONTROL_CLIENT.send(gatewayRequest,
            HttpResponse.BodyHandlers.discarding());
        if (gatewayResponse.statusCode() != 202) {
          String detail = "Agent Gateway rejected the systemd journal query (HTTP " +
              gatewayResponse.statusCode() + ")";
          failDispatch(operationId, actor, serviceId, "systemd.journal.read", detail,
              gatewayResponse.statusCode() >= 500);
          boolean uncertain = gatewayResponse.statusCode() >= 500;
          throw fail(503, uncertain ? "EXECUTION_OUTCOME_UNKNOWN" : "GATEWAY_UNAVAILABLE",
              uncertain ? "Gateway 未确认查询任务状态，请使用原幂等键查询"
                  : "Gateway 拒绝了查询任务，请检查 Agent 状态后重新创建任务");
        }
      } catch (jakarta.ws.rs.WebApplicationException e) {
        throw e;
      } catch (Exception e) {
        failDispatch(operationId, actor, serviceId, "systemd.journal.read",
            "Agent Gateway response was not received; task execution is uncertain", true);
        throw fail(503, "EXECUTION_OUTCOME_UNKNOWN",
            "未收到 Gateway 确认，请使用原幂等键查询任务状态");
      }
    }
    return Response.status(decision.status()).entity(decision.operation())
        .header("Location", "/api/v1/operations/" + decision.operation().get("id"))
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed())).build();
  }

  @GET
  @Path("operations/{operationId}")
  public Object get(@PathParam("operationId") String operationValue) {
    actorId();
    UUID operationId = uuid(operationValue, "operationId");
    return store.transaction(c -> {
      markExpired(c);
      reconcileSshAgentInstall(c, operationId);
      var rows = query(c, operationSelect() + " WHERE o.id=?", operationId);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "操作任务不存在");
      return view(rows.getFirst());
    });
  }

  @GET
  @Path("operations")
  public Response list(@QueryParam("limit") Integer requestedLimit,
                       @QueryParam("cursor") String cursor,
                       @QueryParam("hostId") String hostValue,
                       @QueryParam("status") String status) {
    actorId();
    int limit = requestedLimit == null ? 20 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    UUID host = hostValue == null ? null : uuid(hostValue, "hostId");
    if (status != null && !Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN").contains(status))
      throw fail(400, "VALIDATION_FAILED", "无效操作状态");
    UUID after = decodeCursor(cursor, host, status);
    return store.transaction(c -> {
      markExpired(c);
      var clauses = new ArrayList<String>();
      var values = new ArrayList<Object>();
      if (host != null) { clauses.add("o.host_id=?"); values.add(host); }
      if (status != null) { clauses.add("o.status=?"); values.add(status); }
      if (after != null) { clauses.add("o.id>?"); values.add(after); }
      String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
      values.add(limit + 1);
      var rows = query(c, operationSelect() + where + " ORDER BY o.id LIMIT ?", values.toArray());
      boolean more = rows.size() > limit;
      var selected = rows.stream().limit(limit).toList();
      var body = new LinkedHashMap<String, Object>();
      body.put("items", selected.stream().map(this::view).toList());
      String next = null;
      if (more) {
        String payload = String.join("|", String.valueOf(selected.getLast().get("id")),
            host == null ? "" : host.toString(), status == null ? "" : status);
        next = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.getBytes(StandardCharsets.UTF_8));
      }
      body.put("nextCursor", next);
      return Response.ok(body).build();
    });
  }

  private String operationSelect() {
    return "SELECT o.*,s.unit_name,u.username AS requested_by_name FROM operation o " +
        "LEFT JOIN system_service_observation s ON s.id=o.service_id " +
        "LEFT JOIN panel_user u ON u.id=o.requested_by";
  }

  private void reconcileSshAgentInstall(java.sql.Connection c, UUID operationId) throws Exception {
    var rows = query(c,
        "SELECT o.requested_by,o.host_id,o.agent_id,o.target,a.last_seen " +
            "FROM operation o JOIN agent a ON a.id=o.agent_id AND a.host_id=o.host_id " +
            "JOIN agent_enrollment e ON e.agent_id=a.id " +
            "WHERE o.id=? AND o.kind='SSH_AGENT_INSTALL' AND o.status='UNKNOWN' " +
            "AND e.consumed_at IS NOT NULL AND a.cert_fingerprint IS NOT NULL " +
            "AND a.last_seen IS NOT NULL AND a.last_seen>=o.created_at FOR UPDATE",
        operationId);
    if (rows.isEmpty()) return;
    Map<String, Object> row = rows.getFirst();
    UUID agentId = (UUID) row.get("agent_id");
    String result = mapper.writeValueAsString(Map.of("agentId", agentId.toString(),
        "status", "ONLINE", "verifiedAt", stamp(row.get("last_seen"))));
    Instant now = Instant.now();
    update(c,
        "UPDATE operation SET status='SUCCEEDED',phase='VERIFY',result_json=?,error_json=NULL," +
            "updated_at=?,finished_at=? WHERE id=? AND status='UNKNOWN'",
        result, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), operationId);
    audit(c, String.valueOf(row.get("requested_by")), "SSH_AGENT_INSTALL", "host",
        String.valueOf(row.get("host_id")), "SUCCEEDED", clientIp(),
        "Agent " + row.get("target") + " 已通过后续 mTLS 心跳完成 SSH 初始化结果对账");
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
    value.put("result", json(row.get("result_json")));
    value.put("error", json(row.get("error_json")));
    return value;
  }

  private Object json(Object value) {
    if (value == null) return null;
    try { return mapper.readValue(String.valueOf(value), Object.class); }
    catch (Exception e) { throw new IllegalStateException("Stored operation JSON is invalid", e); }
  }

  private void failDispatch(UUID operationId, String actor, UUID serviceId, String action,
                            String detail,
                            boolean uncertain) {
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
      audit(c, actor, action, "system_service", serviceId.toString(),
          uncertain ? "UNKNOWN" : "FAILED", clientIp(), detail);
      return null;
    });
  }

  private void markExpired(java.sql.Connection c) throws Exception {
    String error = "{\"code\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"命令下发后尚未取得 Agent 执行结果\",\"retryable\":false,\"executionOutcome\":\"UNCERTAIN\"}";
    update(c,
        "UPDATE operation SET status='UNKNOWN',phase='VERIFY',error_json=?,updated_at=? " +
            "WHERE status='RUNNING' AND deadline_at<?",
        error, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
  }

  private UUID decodeCursor(String cursor, UUID host, String status) {
    if (cursor == null) return null;
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\\|", -1);
      if (parts.length != 3 || !parts[1].equals(host == null ? "" : host.toString()) ||
          !parts[2].equals(status == null ? "" : status)) throw new IllegalArgumentException();
      return uuid(parts[0], "cursor");
    } catch (RuntimeException e) {
      throw fail(400, "INVALID_CURSOR", "分页游标无效或筛选条件已变化");
    }
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

  private boolean contains(JsonNode array, String value) {
    for (JsonNode item : array) if (item.isTextual() && item.asText().equals(value)) return true;
    return false;
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    return Instant.parse(String.valueOf(value));
  }

  private String stamp(Object value) {
    Instant instant = instant(value);
    return instant == null ? null : instant.toString();
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }
}
