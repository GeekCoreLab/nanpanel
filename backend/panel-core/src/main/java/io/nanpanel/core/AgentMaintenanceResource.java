package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AgentMaintenanceResource {
  private static final HttpClient CONTROL_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2)).build();

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

  private String actor() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
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

  @POST
  @Path("agents/{agentId}/updates")
  public Response update(@PathParam("agentId") String agentId) {
    UUID id = uuid(agentId, "agentId");
    String actorId = actor();
    UUID operationId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    Instant now = Instant.now();
    long deadlineEpochMs = now.plus(Duration.ofMinutes(5)).toEpochMilli();

    store.transaction(c -> {
      var rows = query(c,
          "SELECT a.helper_status,a.last_seen,s.host_offline_after_seconds," +
              "h.capabilities" + (c.getMetaData().getDatabaseProductName()
                  .equalsIgnoreCase("PostgreSQL") ? "::text" : "") + " AS capabilities " +
              "FROM agent a JOIN host h ON h.id=a.host_id CROSS JOIN platform_settings s " +
              "WHERE a.id=? AND s.id=1",
          id);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "Agent 不存在");
      var agent = rows.getFirst();
      if (!"AVAILABLE".equals(agent.get("helper_status")))
        throw fail(409, "HELPER_UNAVAILABLE", "Agent Helper 当前不可用");
      JsonNode capabilities = mapper.readTree(String.valueOf(agent.get("capabilities")));
      boolean supportsUpdate = false;
      if (capabilities.isArray()) {
        for (JsonNode capability : capabilities) {
          if (capability.isTextual() && capability.asText().equals("agent.self_update")) {
            supportsUpdate = true;
            break;
          }
        }
      }
      if (!supportsUpdate)
        throw fail(409, "UPDATE_UNSUPPORTED", "Agent 未报告自更新能力");
      Instant lastSeen = instant(agent.get("last_seen"));
      int offlineAfter = ((Number)agent.get("host_offline_after_seconds")).intValue();
      if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(offlineAfter)))
        throw fail(409, "AGENT_OFFLINE", "Agent 当前不在线，无法下发异步更新");
      var pending = query(c,
          "SELECT id FROM agent_control_operation WHERE agent_id=? " +
              "AND status IN ('PENDING','RUNNING') LIMIT 1", id);
      if (!pending.isEmpty())
        throw fail(409, "AGENT_UPDATE_IN_PROGRESS", "该 Agent 已有更新任务正在执行");
      AuthStore.update(c,
          "INSERT INTO agent_control_operation " +
              "(id,agent_id,idempotency_key,capability,status,created_by,created_at,updated_at) " +
              "VALUES (?,?,?,'agent.update','PENDING',?,?,?)",
          operationId, id, idempotencyKey, actorId,
          java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
      audit(c, actorId, "agent.update", "agent", id.toString(), "ACCEPTED",
          clientIp(), "请求 Agent 经 Helper 异步更新");
      return null;
    });

    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_control_operation SET status='RUNNING',updated_at=? WHERE id=?",
          java.sql.Timestamp.from(Instant.now()), operationId);
      return null;
    });
    try {
      var dispatch = Map.of(
          "agentId", id.toString(),
          "operationId", operationId.toString(),
          "idempotencyKey", idempotencyKey,
          "capability", "agent.update",
          "target", "",
          "deadlineEpochMs", deadlineEpochMs);
      var body = mapper.writeValueAsBytes(dispatch);
      var gatewayRequest = HttpRequest.newBuilder(URI.create(gatewayControlUrl))
          .timeout(Duration.ofSeconds(5))
          .header("X-NanPanel-Gateway-Token", gatewayToken)
          .header("Content-Type", MediaType.APPLICATION_JSON)
          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
          .build();
      var gatewayResponse = CONTROL_CLIENT.send(gatewayRequest, HttpResponse.BodyHandlers.discarding());
      if (gatewayResponse.statusCode() != 202) {
        String detail = gatewayResponse.statusCode() == 404
            ? "Agent Gateway has no active mTLS session for this Agent"
            : "Agent Gateway rejected the update request (HTTP " + gatewayResponse.statusCode() + ")";
        markFailed(operationId, detail);
        throw fail(gatewayResponse.statusCode() == 404 ? 409 : 503,
            gatewayResponse.statusCode() == 404 ? "AGENT_OFFLINE" : "GATEWAY_UNAVAILABLE",
            detail);
      }
    } catch (jakarta.ws.rs.WebApplicationException e) {
      throw e;
    } catch (Exception e) {
      String detail = "Unable to dispatch update through Agent Gateway";
      markFailed(operationId, detail);
      throw fail(503, "GATEWAY_UNAVAILABLE", detail);
    }
    return Response.accepted(operation(operationId, id)).build();
  }

  @GET
  @Path("agents/{agentId}/updates/{operationId}")
  public Object getUpdate(@PathParam("agentId") String agentId,
                          @PathParam("operationId") String operationId) {
    actor();
    UUID agent = uuid(agentId, "agentId");
    UUID operation = uuid(operationId, "operationId");
    return operation(operation, agent);
  }

  private void markFailed(UUID operationId, String detail) {
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE agent_control_operation SET status='FAILED',detail=?,updated_at=? " +
              "WHERE id=? AND status IN ('PENDING','RUNNING')",
          detail, java.sql.Timestamp.from(Instant.now()), operationId);
      return null;
    });
  }

  private Map<String, Object> operation(UUID operationId, UUID agentId) {
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT id,agent_id,status,detail,created_at,updated_at " +
              "FROM agent_control_operation WHERE id=? AND agent_id=?",
          operationId, agentId);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "Agent 更新任务不存在");
      var row = rows.getFirst();
      var value = new LinkedHashMap<String, Object>();
      value.put("id", String.valueOf(row.get("id")));
      value.put("agentId", String.valueOf(row.get("agent_id")));
      value.put("status", row.get("status"));
      value.put("detail", row.get("detail"));
      value.put("createdAt", instant(row.get("created_at")).toString());
      value.put("updatedAt", instant(row.get("updated_at")).toString());
      return value;
    });
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    throw new IllegalStateException("Unsupported timestamp value");
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }
}
