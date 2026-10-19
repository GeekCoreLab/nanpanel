package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/internal/agent-control-results")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InternalAgentControlResource {
  @Inject AuthStore store;

  @ConfigProperty(name = "nanpanel.agent.gateway-token")
  String gatewayToken;

  public record OperationResultReport(
      String agentId,
      String operationId,
      String idempotencyKey,
      String status,
      String detail) {}

  @POST
  public Response operationResult(
      @HeaderParam("X-NanPanel-Gateway-Token") String supplied,
      OperationResultReport report) {
    if (gatewayToken.isBlank() || supplied == null ||
        !MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                               supplied.getBytes(StandardCharsets.UTF_8)))
      throw fail(401, "GATEWAY_UNAUTHORIZED", "Gateway 身份无效");
    if (report == null || report.agentId() == null || report.operationId() == null ||
        report.idempotencyKey() == null || report.status() == null || report.detail() == null ||
        report.idempotencyKey().length() > 128 || report.detail().length() > 2000 ||
        report.detail().chars().anyMatch(Character::isISOControl) ||
        !Set.of("SUCCEEDED", "FAILED").contains(report.status()))
      throw fail(400, "INVALID_OPERATION_RESULT", "Agent 操作结果字段无效");
    UUID agentId = parseUuid(report.agentId(), "Agent ID");
    UUID operationId = parseUuid(report.operationId(), "操作 ID");
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT id FROM agent_control_operation WHERE id=? AND agent_id=? " +
              "AND idempotency_key=? AND status='RUNNING'",
          operationId, agentId, report.idempotencyKey());
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "Agent 更新任务不存在或已结束");
      update(c,
          "UPDATE agent_control_operation SET status=?,detail=?,updated_at=? WHERE id=?",
          report.status(), report.detail(), java.sql.Timestamp.from(Instant.now()), operationId);
      return Response.noContent().build();
    });
  }

  private UUID parseUuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "INVALID_OPERATION_RESULT", field + " 无效");
    }
  }
}
