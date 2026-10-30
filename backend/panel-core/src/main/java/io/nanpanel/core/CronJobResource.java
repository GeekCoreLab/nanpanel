package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
import java.util.regex.Pattern;

/**
 * 计划任务：面板保存「意图」，显式应用才把受管 cron 文件写到主机（`cron.write`），停用则撤回
 * （`cron.remove`）。状态按最近一次关联 Operation 的终态派生——主机执行失败时不会谎报已生效。
 *
 * <p>任务标识由面板前缀 `nanpanel-` 固定，Helper 只接受这个前缀，因此手工 crontab 不会被碰。
 */
@Path("/api/v1/cron-jobs")
@Produces(MediaType.APPLICATION_JSON)
public class CronJobResource {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,30}[a-z0-9]");
  private static final java.util.Set<String> SERVING = java.util.Set.of("APPLIED", "UNKNOWN");
  private static final String PREFIX = "nanpanel-";

  public record CronRequest(String hostId, String name, String schedule, String command) {}

  @Inject AuthStore store;
  @Inject HostHelperOperationResource helperOperations;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(c -> query(c, """
        SELECT j.host_id, j.job_id, j.schedule, j.command, j.state, j.operation_id,
               j.created_at, j.updated_at, h.hostname, o.status AS operation_status
        FROM cron_job j JOIN host h ON h.id = j.host_id
        LEFT JOIN operation o ON o.id = j.operation_id
        ORDER BY j.updated_at DESC LIMIT 200"""));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (var row : rows) items.add(view(row));
    return Map.of("items", items);
  }

  @POST
  public Response create(CronRequest input) {
    var actor = session();
    if (input == null) throw fail(422, "VALIDATION_FAILED", "定时任务参数缺失");
    UUID hostId = uuid(input.hostId(), "hostId");
    if (host(hostId) == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    String jobId = PREFIX + name(input.name());
    String schedule = input.schedule() == null ? "" : input.schedule().trim();
    String command = input.command() == null ? "" : input.command().trim();
    // The exact dispatch validator decides: a bad schedule never gets stored either.
    helperOperations.validateTargetAndPayload("cron.write", jobId,
        payload(schedule, command));
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      if (!query(c, "SELECT job_id FROM cron_job WHERE host_id=? AND job_id=?", hostId, jobId)
          .isEmpty())
        throw fail(409, "CRON_JOB_EXISTS", "该主机上已存在同名定时任务");
      update(c, "INSERT INTO cron_job (host_id,job_id,schedule,command,state,created_by,"
              + "created_at,updated_at) VALUES (?,?,?,?,'DRAFT',?,?,?)",
          hostId, jobId, schedule, command, actor.get("user_id"), now, now);
      audit(c, String.valueOf(actor.get("user_id")), "cron-job.create", "cron_job", jobId,
          "SUCCEEDED", clientIp(), "登记定时任务 " + jobId);
      return null;
    });
    return Response.status(201).entity(load(hostId, jobId)).build();
  }

  @PUT
  @Path("{hostId}/{jobId}")
  public Response edit(@PathParam("hostId") String hostValue,
      @PathParam("jobId") String jobValue, CronRequest input) {
    var actor = session();
    UUID hostId = uuid(hostValue, "hostId");
    String jobId = jobValue;
    Map<String, Object> job = load(hostId, jobId);
    if (SERVING.contains(String.valueOf(job.get("state"))))
      throw fail(409, "CRON_JOB_APPLIED", "任务已生效或状态未定，需先停用后再修改");
    if (input == null) throw fail(422, "VALIDATION_FAILED", "定时任务参数缺失");
    String schedule = input.schedule() == null ? String.valueOf(job.get("schedule"))
        : input.schedule().trim();
    String command = input.command() == null ? String.valueOf(job.get("command"))
        : input.command().trim();
    helperOperations.validateTargetAndPayload("cron.write", jobId, payload(schedule, command));
    store.transaction(c -> {
      update(c, "UPDATE cron_job SET schedule=?, command=?, updated_at=? "
          + "WHERE host_id=? AND job_id=?", schedule, command,
          System.currentTimeMillis(), hostId, jobId);
      audit(c, String.valueOf(actor.get("user_id")), "cron-job.update", "cron_job", jobId,
          "SUCCEEDED", clientIp(), "修改定时任务 " + jobId);
      return null;
    });
    return Response.ok(load(hostId, jobId)).build();
  }

  @POST
  @Path("{hostId}/{jobId}/apply")
  public Response apply(@PathParam("hostId") String hostValue,
      @PathParam("jobId") String jobValue, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    var actor = session();
    UUID hostId = uuid(hostValue, "hostId");
    Map<String, Object> job = load(hostId, jobValue);
    long generation = hostGeneration(hostId);
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "cron.write", jobValue,
        payload(String.valueOf(job.get("schedule")), String.valueOf(job.get("command"))),
        "应用定时任务 " + jobValue, 60, generation);
    Response submitted;
    try {
      submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
          String.valueOf(actor.get("user_id")), clientIp());
    } catch (RuntimeException failure) {
      if (!"APPLIED".equals(String.valueOf(job.get("state"))))
        store.transaction(c -> update(c,
            "UPDATE cron_job SET state='FAILED', updated_at=? WHERE host_id=? AND job_id=?",
            System.currentTimeMillis(), hostId, jobValue));
      throw failure;
    }
    UUID operationId = operationId(submitted);
    store.transaction(c -> {
      update(c, "UPDATE cron_job SET state='APPLIED', operation_id=?, updated_at=? "
          + "WHERE host_id=? AND job_id=?", operationId, System.currentTimeMillis(), hostId,
          jobValue);
      audit(c, String.valueOf(actor.get("user_id")), "cron-job.apply", "cron_job", jobValue,
          "SUCCEEDED", clientIp(), "下发定时任务 " + jobValue);
      return null;
    });
    return Response.ok(load(hostId, jobValue)).build();
  }

  @POST
  @Path("{hostId}/{jobId}/disable")
  public Response disable(@PathParam("hostId") String hostValue,
      @PathParam("jobId") String jobValue, @HeaderParam("Idempotency-Key") String idempotencyKey) {
    var actor = session();
    UUID hostId = uuid(hostValue, "hostId");
    load(hostId, jobValue);
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "cron.remove", jobValue, null, "停用定时任务 " + jobValue, 60, hostGeneration(hostId));
    // A failed disable changes nothing: the entry may still be scheduled on the host.
    Response submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
        String.valueOf(actor.get("user_id")), clientIp());
    UUID operationId = operationId(submitted);
    store.transaction(c -> {
      update(c, "UPDATE cron_job SET state='DISABLED', operation_id=?, updated_at=? "
          + "WHERE host_id=? AND job_id=?", operationId, System.currentTimeMillis(), hostId,
          jobValue);
      audit(c, String.valueOf(actor.get("user_id")), "cron-job.disable", "cron_job", jobValue,
          "SUCCEEDED", clientIp(), "停用定时任务 " + jobValue);
      return null;
    });
    return Response.ok(load(hostId, jobValue)).build();
  }

  @DELETE
  @Path("{hostId}/{jobId}")
  public Response delete(@PathParam("hostId") String hostValue,
      @PathParam("jobId") String jobValue) {
    var actor = session();
    UUID hostId = uuid(hostValue, "hostId");
    Map<String, Object> job = load(hostId, jobValue);
    if (SERVING.contains(String.valueOf(job.get("state"))))
      throw fail(409, "CRON_JOB_APPLIED", "任务已生效或状态未定，需先停用后再删除");
    store.transaction(c -> {
      update(c, "DELETE FROM cron_job WHERE host_id=? AND job_id=?", hostId, jobValue);
      audit(c, String.valueOf(actor.get("user_id")), "cron-job.delete", "cron_job", jobValue,
          "SUCCEEDED", clientIp(), "删除定时任务 " + jobValue);
      return null;
    });
    return Response.noContent().build();
  }

  private static ObjectNode payload(String schedule, String command) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("schedule", schedule);
    payload.put("command", command);
    return payload;
  }

  private static UUID operationId(Response submitted) {
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null)
      return UUID.fromString(String.valueOf(result.get("id")));
    return null;
  }

  private Map<String, Object> load(UUID hostId, String jobId) {
    var rows = store.transaction(c -> query(c, """
        SELECT j.host_id, j.job_id, j.schedule, j.command, j.state, j.operation_id,
               j.created_at, j.updated_at, h.hostname, o.status AS operation_status
        FROM cron_job j JOIN host h ON h.id = j.host_id
        LEFT JOIN operation o ON o.id = j.operation_id
        WHERE j.host_id=? AND j.job_id=?""", hostId, jobId));
    if (rows.isEmpty()) throw fail(404, "CRON_JOB_NOT_FOUND", "定时任务不存在");
    return view(rows.getFirst());
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    String operationStatus = row.get("operation_status") == null
        ? null : String.valueOf(row.get("operation_status"));
    item.put("hostId", String.valueOf(row.get("host_id")));
    item.put("hostname", row.get("hostname") == null ? null
        : String.valueOf(row.get("hostname")));
    item.put("id", String.valueOf(row.get("job_id")));
    item.put("schedule", String.valueOf(row.get("schedule")));
    item.put("command", String.valueOf(row.get("command")));
    item.put("state", ReverseProxySiteResource.reportedState(
        String.valueOf(row.get("state")), operationStatus));
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

  private static String name(String value) {
    if (value == null) throw fail(422, "VALIDATION_FAILED", "任务名称必填");
    String name = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (!NAME.matcher(name).matches())
      throw fail(422, "VALIDATION_FAILED", "任务名称需为 2–32 位小写字母、数字或连字符，且以字母开头、不以连字符结尾");
    return name;
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
