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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/internal/agent-control-results")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InternalAgentControlResource {
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject KubernetesApplicationPacketCaptureService packetCaptures;

  @Inject
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
        report.idempotencyKey().length() > 128 || report.detail().length() > 60000 ||
        report.detail().chars().anyMatch(ch -> Character.isISOControl(ch) &&
            ch != '\n' && ch != '\r' && ch != '\t') ||
        !Set.of("SUCCEEDED", "FAILED").contains(report.status()))
      throw fail(400, "INVALID_OPERATION_RESULT", "Agent 操作结果字段无效");
    UUID agentId = parseUuid(report.agentId(), "Agent ID");
    UUID operationId = parseUuid(report.operationId(), "操作 ID");
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT status,detail FROM agent_control_operation WHERE id=? AND agent_id=? " +
              "AND idempotency_key=?",
          operationId, agentId, report.idempotencyKey());
      if (!rows.isEmpty()) {
        if (report.detail().length() > 2000) {
          throw fail(400, "INVALID_OPERATION_RESULT", "普通 Helper 操作结果超过长度限制");
        }
        String existingStatus = String.valueOf(rows.getFirst().get("status"));
        if (!"RUNNING".equals(existingStatus)) {
          if (existingStatus.equals(report.status()) &&
              report.detail().equals(rows.getFirst().get("detail")))
            return Response.noContent().build();
          throw fail(409, "OPERATION_RESULT_CONFLICT", "Agent 更新任务已有不同终态结果");
        }
        update(c,
            "UPDATE agent_control_operation SET status=?,detail=?,updated_at=? WHERE id=?",
            report.status(), report.detail(), java.sql.Timestamp.from(Instant.now()), operationId);
        return Response.noContent().build();
      }
      var operations = query(c,
          "SELECT kind,service_id,host_id,capability,target,requested_by,status,error_json,idempotency_key " +
              "FROM operation WHERE id=? AND agent_id=? " +
              "AND idempotency_key=?",
          operationId, agentId, report.idempotencyKey());
      if (operations.isEmpty()) throw fail(404, "NOT_FOUND", "Agent 操作不存在或已结束");
      var operation = operations.getFirst();
      if (report.detail().length() > 2000
          && !"network.topology.capture".equals(operation.get("capability"))) {
        throw fail(400, "INVALID_OPERATION_RESULT", "普通 Helper 操作结果超过长度限制");
      }
      String existingStatus = String.valueOf(operation.get("status"));
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(existingStatus)) {
        if (existingStatus.equals(report.status())) return acknowledgeResult();
        throw fail(409, "OPERATION_RESULT_CONFLICT", "操作任务已有不同终态结果");
      }
      if ("UNKNOWN".equals(existingStatus) && "FAILED".equals(report.status()) &&
          operation.get("error_json") != null) {
        JsonNode previousError = mapper.readTree(String.valueOf(operation.get("error_json")));
        if ("EXECUTION_OUTCOME_UNKNOWN".equals(previousError.path("code").asText()) &&
            report.detail().equals(previousError.path("agentDetail").asText()))
          return retainUncertainResult();
      }
      if ("SYSTEMD_JOURNAL_READ".equals(operation.get("kind")))
        return journalResult(c, operation, report, operationId);
      if ("HOST_HELPER_OPERATION".equals(operation.get("kind")))
        return helperOperationResult(c, operation, report, operationId);
      UUID serviceId = (UUID)operation.get("service_id");
      var services = query(c,
          "SELECT unit_name FROM system_service_observation WHERE id=?", serviceId);
      if (services.isEmpty()) throw fail(409, "OPERATION_TARGET_REMOVED", "操作目标已不存在，等待人工对账");
      String unitName = String.valueOf(services.getFirst().get("unit_name"));
      Instant now = Instant.now();
      if ("SUCCEEDED".equals(report.status())) {
        JsonNode result = mapper.readTree(report.detail());
        if (!"ACTIVE".equals(result.path("activeState").asText())) {
          String error = mapper.writeValueAsString(Map.of(
              "code", "SERVICE_VERIFICATION_FAILED",
              "message", "systemd 未确认服务处于 active 状态",
              "retryable", false, "executionOutcome", "VERIFIED_FAILED"));
          update(c,
              "UPDATE operation SET status='FAILED',phase='VERIFY',error_json=?," +
                  "updated_at=?,finished_at=? WHERE id=?",
              error, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), operationId);
          audit(c, String.valueOf(operation.get("requested_by")), "systemd.restart",
              "system_service", serviceId.toString(), "FAILED", null,
              "systemd 服务重启后验证未通过");
          return acknowledgeResult();
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("serviceId", serviceId.toString());
        value.put("unitName", unitName);
        value.put("activeState", "ACTIVE");
        value.put("verifiedAt", now.toString());
        String evidence = result.path("evidence").asText();
        value.put("evidence", evidence.isBlank()
            ? "Helper 报告重启成功，systemd 重新观测为 active/running。" : evidence);
        update(c,
            "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,error_json=NULL," +
                "updated_at=?,finished_at=? WHERE id=?",
            mapper.writeValueAsString(value), java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now), operationId);
        audit(c, String.valueOf(operation.get("requested_by")), "systemd.restart",
            "system_service", serviceId.toString(), "SUCCEEDED", null,
            "systemd 服务已重启且状态验证通过");
      } else {
        boolean denied = report.detail().startsWith("DENIED:");
        boolean executionFailed = report.detail().startsWith("FAILED:");
        String status = denied || executionFailed ? "FAILED" : "UNKNOWN";
        String outcome = denied ? "NOT_EXECUTED" : executionFailed ? "VERIFIED_FAILED" : "UNCERTAIN";
        String code = denied ? "HELPER_PERMISSION_DENIED" : executionFailed
            ? "SYSTEMD_OPERATION_FAILED" : "EXECUTION_OUTCOME_UNKNOWN";
        String message = denied ? "本地 Helper 拒绝操作该服务。" : executionFailed
            ? "systemd 重启或状态验证失败。" : "Agent 返回结果不足以确认操作是否执行。";
        String error = mapper.writeValueAsString(Map.of(
            "code", code, "message", message, "retryable", false,
            "executionOutcome", outcome, "agentDetail", report.detail()));
        update(c,
            "UPDATE operation SET status=?,phase=?,error_json=?,updated_at=?,finished_at=? WHERE id=?",
            status, denied ? "APPLY" : "VERIFY", error, java.sql.Timestamp.from(now),
            "UNKNOWN".equals(status) ? null : java.sql.Timestamp.from(now), operationId);
        audit(c, String.valueOf(operation.get("requested_by")), "systemd.restart",
            "system_service", serviceId.toString(), denied ? "DENIED" :
                "UNKNOWN".equals(status) ? "UNKNOWN" : "FAILED", null,
            message);
        return "UNKNOWN".equals(status) ? retainUncertainResult() : acknowledgeResult();
      }
      return acknowledgeResult();
    });
  }

  private Response journalResult(java.sql.Connection c, Map<String, Object> operation,
                                OperationResultReport report, UUID operationId) throws Exception {
    UUID serviceId = (UUID)operation.get("service_id");
    var services = query(c,
        "SELECT unit_name FROM system_service_observation WHERE id=?", serviceId);
    if (services.isEmpty())
      throw fail(409, "OPERATION_TARGET_REMOVED", "操作目标已不存在，等待人工对账");
    String unitName = String.valueOf(services.getFirst().get("unit_name"));
    Instant now = Instant.now();
    if ("SUCCEEDED".equals(report.status())) {
      var result = new LinkedHashMap<String, Object>();
      result.put("serviceId", serviceId.toString());
      result.put("unitName", unitName);
      result.put("journal", report.detail());
      result.put("retrievedAt", now.toString());
      update(c,
          "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,error_json=NULL," +
              "updated_at=?,finished_at=? WHERE id=?",
          mapper.writeValueAsString(result), java.sql.Timestamp.from(now),
          java.sql.Timestamp.from(now), operationId);
      audit(c, String.valueOf(operation.get("requested_by")), "systemd.journal.read",
          "system_service", serviceId.toString(), "SUCCEEDED", null,
          "已读取 systemd journal（最多 12 行）");
      return acknowledgeResult();
    }
    boolean denied = report.detail().startsWith("DENIED:");
    boolean executionFailed = report.detail().startsWith("FAILED:");
    String status = denied || executionFailed ? "FAILED" : "UNKNOWN";
    String outcome = denied ? "NOT_EXECUTED" : executionFailed ? "VERIFIED_FAILED" : "UNCERTAIN";
    String code = denied ? "HELPER_PERMISSION_DENIED" : executionFailed
        ? "SYSTEMD_JOURNAL_QUERY_FAILED" : "EXECUTION_OUTCOME_UNKNOWN";
    String message = denied ? "本地 Helper 拒绝读取该服务日志。" : executionFailed
        ? "systemd journal 查询失败。" : "Agent 返回结果不足以确认查询状态。";
    String error = mapper.writeValueAsString(Map.of(
        "code", code, "message", message, "retryable", false,
        "executionOutcome", outcome, "agentDetail", report.detail()));
    update(c,
        "UPDATE operation SET status=?,phase=?,error_json=?,updated_at=?,finished_at=? WHERE id=?",
        status, denied ? "APPLY" : "VERIFY", error, java.sql.Timestamp.from(now),
        "UNKNOWN".equals(status) ? null : java.sql.Timestamp.from(now), operationId);
    audit(c, String.valueOf(operation.get("requested_by")), "systemd.journal.read",
        "system_service", serviceId.toString(), denied ? "DENIED" :
            "UNKNOWN".equals(status) ? "UNKNOWN" : "FAILED", null, message);
    return "UNKNOWN".equals(status) ? retainUncertainResult() : acknowledgeResult();
  }

  private Response helperOperationResult(java.sql.Connection c, Map<String, Object> operation,
                                         OperationResultReport report, UUID operationId)
      throws Exception {
    UUID hostId = (UUID)operation.get("host_id");
    String capability = String.valueOf(operation.get("capability"));
    String target = String.valueOf(operation.get("target"));
    String operationDetail = report.detail();
    String resultStatus = report.status();
    Instant now = Instant.now();
    if ("network.topology.capture".equals(capability)) {
      var capture = packetCaptures.recordAgentResult(c,
          String.valueOf(operation.get("idempotency_key")), report.status(), report.detail(), operationId);
      if ("SUCCEEDED".equals(report.status()) && capture != null && capture.complete()) {
        operationDetail = "Packet capture artifact stored as " + capture.captureId();
      } else {
        resultStatus = "FAILED";
        operationDetail = "FAILED: bounded packet capture failed or returned invalid data";
      }
    }
    if ("SUCCEEDED".equals(resultStatus)) {
      var result = new LinkedHashMap<String, Object>();
      result.put("capability", capability);
      result.put("target", target);
      result.put("output", operationDetail);
      result.put("completedAt", now.toString());
      update(c,
          "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,error_json=NULL," +
              "updated_at=?,finished_at=? WHERE id=?",
          mapper.writeValueAsString(result), java.sql.Timestamp.from(now),
          java.sql.Timestamp.from(now), operationId);
      audit(c, String.valueOf(operation.get("requested_by")), capability,
          "host", hostId.toString(), "SUCCEEDED", null,
          "受限 Helper 操作已完成");
      return acknowledgeResult();
    }
    boolean denied = operationDetail.startsWith("DENIED:")
        || report.detail().startsWith("DENIED:");
    boolean executionFailed = operationDetail.startsWith("FAILED:")
        || report.detail().startsWith("FAILED:");
    String status = denied || executionFailed ? "FAILED" : "UNKNOWN";
    String outcome = denied ? "NOT_EXECUTED" : executionFailed ? "VERIFIED_FAILED" : "UNCERTAIN";
    String code = denied ? "HELPER_PERMISSION_DENIED" : executionFailed
        ? "HELPER_OPERATION_FAILED" : "EXECUTION_OUTCOME_UNKNOWN";
    String message = denied ? "本地主 Helper 拒绝了该操作。" : executionFailed
        ? "Helper 操作执行或验证失败。" : "Agent 返回结果不足以确认 Helper 操作是否执行。";
    String error = mapper.writeValueAsString(Map.of(
        "code", code, "message", message, "retryable", false,
        "executionOutcome", outcome, "agentDetail", operationDetail));
    update(c,
        "UPDATE operation SET status=?,phase=?,error_json=?,updated_at=?,finished_at=? WHERE id=?",
        status, denied ? "APPLY" : "VERIFY", error,
        java.sql.Timestamp.from(now), "UNKNOWN".equals(status) ? null :
            java.sql.Timestamp.from(now), operationId);
    audit(c, String.valueOf(operation.get("requested_by")), capability,
        "host", hostId.toString(), denied ? "DENIED" :
            "UNKNOWN".equals(status) ? "UNKNOWN" : "FAILED", null, message);
    return "UNKNOWN".equals(status) ? retainUncertainResult() : acknowledgeResult();
  }

  private Response acknowledgeResult() {
    return Response.noContent().header("X-NanPanel-Result-Ack", "true").build();
  }

  private Response retainUncertainResult() {
    return Response.accepted().build();
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
