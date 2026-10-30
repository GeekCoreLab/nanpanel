package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 备份记录：面板记住「谁在什么时候对哪台主机的哪个路径做了备份、得到了什么文件」。
 *
 * <p>记录在创建时只带操作 id；**文件、大小、SHA-256 一律来自主机返回的结果**（读取时补齐），
 * 面板不猜文件名。状态按关联操作派生，主机失败时显示失败而不是「已完成」。
 */
@Path("/api/v1/backups")
@Produces(MediaType.APPLICATION_JSON)
public class BackupResource {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final java.util.Set<String> BUSY = java.util.Set.of("PENDING", "RUNNING");

  public record BackupRequest(String hostId, String source, String name) {}

  public record RestoreRequest(String target, String sha256) {}

  @Inject AuthStore store;
  @Inject HostHelperOperationResource helperOperations;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Map<String, Object> list() {
    session();
    harvest();
    var rows = store.transaction(c -> query(c, """
        SELECT b.record_id, b.host_id, b.file_name, b.source, b.size_bytes, b.sha256, b.state,
               b.operation_id, b.created_at, b.updated_at, h.hostname,
               o.status AS operation_status
        FROM backup_record b JOIN host h ON h.id = b.host_id
        LEFT JOIN operation o ON o.id = b.operation_id
        ORDER BY b.created_at DESC LIMIT 200"""));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (var row : rows) items.add(view(row));
    return Map.of("items", items);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey,
      BackupRequest input) {
    var actor = session();
    if (input == null) throw fail(422, "VALIDATION_FAILED", "备份参数缺失");
    UUID hostId = uuid(input.hostId(), "hostId");
    Map<String, Object> host = host(hostId);
    if (host == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    String source = input.source() == null ? "" : input.source().trim();
    String name = input.name() == null ? "" : input.name().trim().toLowerCase(java.util.Locale.ROOT);
    ObjectNode payload = JSON.createObjectNode();
    payload.put("source", source);
    payload.put("name", name);
    helperOperations.validateTargetAndPayload("backup.create", "", payload);

    UUID recordId = UUID.randomUUID();
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "backup.create", "", payload, "备份 " + source, 600,
        ((Number) host.get("generation")).longValue());
    Response submitted;
    try {
      submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
          String.valueOf(actor.get("user_id")), clientIp());
    } catch (RuntimeException failure) {
      throw failure;
    }
    UUID operationId = operationId(submitted);
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      update(c, "INSERT INTO backup_record (record_id,host_id,file_name,source,state,"
              + "operation_id,created_by,created_at,updated_at) VALUES (?,?,?,?,'DRAFT',?,?,?,?)",
          recordId, hostId, "pending-" + recordId, source, operationId,
          actor.get("user_id"), now, now);
      audit(c, String.valueOf(actor.get("user_id")), "backup.create", "backup_record",
          recordId.toString(), "SUCCEEDED", clientIp(), "创建备份 " + name + " ← " + source);
      return null;
    });
    return Response.status(202).entity(record(recordId)).build();
  }

  @POST
  @Path("{recordId}/restore")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response restore(@PathParam("recordId") String recordValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey, RestoreRequest input) {
    var actor = session();
    UUID recordId = uuid(recordValue, "recordId");
    Map<String, Object> record = record(recordId);
    String file = String.valueOf(record.get("file"));
    if (file.startsWith("pending-"))
      throw fail(409, "BACKUP_NOT_READY", "备份文件尚未生成，无法恢复");
    if (input == null || input.target() == null)
      throw fail(422, "VALIDATION_FAILED", "恢复目标目录必填");
    UUID hostId = UUID.fromString(String.valueOf(record.get("hostId")));
    long generation = hostGeneration(hostId);
    ObjectNode payload = JSON.createObjectNode();
    payload.put("file", file);
    payload.put("target", input.target().trim());
    Object sha = record.get("sha256");
    if (input.sha256() != null && !input.sha256().isBlank()) {
      payload.put("sha256", input.sha256().trim());
    } else if (sha != null) {
      // Default to the checksum recorded when the backup was created.
      payload.put("sha256", String.valueOf(sha));
    }
    helperOperations.validateTargetAndPayload("backup.restore", "", payload);
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "backup.restore", "", payload, "恢复备份 " + file, 600, generation);
    Response submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
        String.valueOf(actor.get("user_id")), clientIp());
    store.transaction(c -> {
      audit(c, String.valueOf(actor.get("user_id")), "backup.restore", "backup_record",
          recordId.toString(), "SUCCEEDED", clientIp(),
          "恢复备份 " + file + " 到 " + input.target().trim());
      return null;
    });
    return Response.ok(Map.of("operationId", String.valueOf(operationId(submitted)))).build();
  }

  @DELETE
  @Path("{recordId}")
  public Response delete(@PathParam("recordId") String recordValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey) {
    var actor = session();
    UUID recordId = uuid(recordValue, "recordId");
    Map<String, Object> record = record(recordId);
    String file = String.valueOf(record.get("file"));
    if (!file.startsWith("pending-")) {
      UUID hostId = UUID.fromString(String.valueOf(record.get("hostId")));
      var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
          "backup.delete", file, null, "删除备份 " + file, 60, hostGeneration(hostId));
      helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
          String.valueOf(actor.get("user_id")), clientIp());
    }
    store.transaction(c -> {
      update(c, "DELETE FROM backup_record WHERE record_id=?", recordId);
      audit(c, String.valueOf(actor.get("user_id")), "backup.delete", "backup_record",
          recordId.toString(), "SUCCEEDED", clientIp(), "删除备份记录 " + file);
      return null;
    });
    return Response.noContent().build();
  }

  /** Fills in file name, size and checksum from what the host actually produced. */
  void harvest() {
    var rows = store.transaction(c -> query(c, """
        SELECT o.id, o.host_id, o.result_json, o.status, o.requested_by
        FROM operation o
        WHERE o.capability = 'backup.create' AND o.status = 'SUCCEEDED'
          AND o.result_json IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM backup_record b WHERE b.operation_id = o.id
            AND b.file_name NOT LIKE 'pending-%')
        ORDER BY o.created_at LIMIT 200"""));
    for (var row : rows) {
      JsonNode result = parse(MetricsSampler.resultOf(row.get("result_json")));
      if (result == null) continue;
      UUID operationId = UUID.fromString(String.valueOf(row.get("id")));
      UUID hostId = UUID.fromString(String.valueOf(row.get("host_id")));
      String file = result.path("file").asText("");
      if (file.isBlank()) continue;
      long size = result.path("sizeBytes").asLong(0);
      String sha = result.path("sha256").asText("");
      long now = System.currentTimeMillis();
      String requestedBy = String.valueOf(row.get("requested_by"));
      store.transaction(c -> {
        var existing = query(c, "SELECT record_id FROM backup_record WHERE operation_id=?",
            operationId);
        if (existing.isEmpty()) {
          update(c, "INSERT INTO backup_record (record_id,host_id,file_name,source,size_bytes,"
                  + "sha256,state,operation_id,created_by,created_at,updated_at) "
                  + "VALUES (?,?,?,?,?,?,'APPLIED',?,?,?,?)",
              UUID.randomUUID(), hostId, file, result.path("source").asText(""), size,
              sha.isEmpty() ? null : sha, operationId, requestedBy, now, now);
        } else {
          update(c, "UPDATE backup_record SET file_name=?, size_bytes=?, sha256=?, state='APPLIED',"
              + " updated_at=? WHERE operation_id=?", file, size, sha.isEmpty() ? null : sha,
              now, operationId);
        }
        return null;
      });
    }
  }

  private static JsonNode parse(String output) {
    if (output == null || output.isBlank()) return null;
    try {
      JsonNode node = JSON.readTree(output);
      return node.isObject() ? node : null;
    } catch (Exception invalid) {
      return null;
    }
  }

  private Map<String, Object> record(UUID recordId) {
    var rows = store.transaction(c -> query(c, """
        SELECT b.record_id, b.host_id, b.file_name, b.source, b.size_bytes, b.sha256, b.state,
               b.operation_id, b.created_at, b.updated_at, h.hostname,
               o.status AS operation_status
        FROM backup_record b JOIN host h ON h.id = b.host_id
        LEFT JOIN operation o ON o.id = b.operation_id WHERE b.record_id=?""", recordId));
    if (rows.isEmpty()) throw fail(404, "BACKUP_NOT_FOUND", "备份记录不存在");
    return view(rows.getFirst());
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    String operationStatus = row.get("operation_status") == null
        ? null : String.valueOf(row.get("operation_status"));
    String stored = String.valueOf(row.get("state"));
    item.put("id", String.valueOf(row.get("record_id")));
    item.put("hostId", String.valueOf(row.get("host_id")));
    item.put("hostname", row.get("hostname") == null ? null
        : String.valueOf(row.get("hostname")));
    item.put("file", String.valueOf(row.get("file_name")));
    item.put("source", String.valueOf(row.get("source")));
    item.put("sizeBytes", row.get("size_bytes") == null ? null
        : ((Number) row.get("size_bytes")).longValue());
    item.put("sha256", row.get("sha256") == null ? null : String.valueOf(row.get("sha256")));
    item.put("state", ReverseProxySiteResource.reportedState(stored, operationStatus));
    item.put("operationStatus", operationStatus);
    item.put("operationId", row.get("operation_id") == null ? null
        : String.valueOf(row.get("operation_id")));
    item.put("createdAt", ((Number) row.get("created_at")).longValue());
    item.put("updatedAt", ((Number) row.get("updated_at")).longValue());
    return item;
  }

  private Map<String, Object> host(UUID hostId) {
    var rows = store.transaction(c -> query(c,
        "SELECT id, hostname, generation FROM host WHERE id=?", hostId));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private long hostGeneration(UUID hostId) {
    Map<String, Object> host = host(hostId);
    if (host == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    return ((Number) host.get("generation")).longValue();
  }

  private static UUID operationId(Response submitted) {
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null)
      return UUID.fromString(String.valueOf(result.get("id")));
    return null;
  }

  private static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "无效的 " + field);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return value;
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }
}
