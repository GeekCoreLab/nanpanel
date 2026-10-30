package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主机指标时间序列。CPU 使用率由相邻两次采样的原始计数器差值算出，因此**第一条样本没有 CPU
 * 百分比**（返回 {@code null} 而不是 0）——单次快照不构成百分比。
 */
@Path("/api/v1/hosts/{hostId}/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class HostMetricsResource {
  private static final int DEFAULT_LIMIT = 60;
  private static final int MAX_LIMIT = 240;

  @Inject AuthStore store;
  @Context ContainerRequestContext request;

  @GET
  public Map<String, Object> series(@PathParam("hostId") String hostValue,
      @QueryParam("limit") String limit) {
    session();
    UUID hostId;
    try {
      hostId = UUID.fromString(hostValue);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "无效的主机标识");
    }
    int wanted = DEFAULT_LIMIT;
    if (limit != null && !limit.isBlank()) {
      try {
        wanted = Integer.parseInt(limit.trim());
      } catch (NumberFormatException invalid) {
        throw fail(422, "VALIDATION_FAILED", "无效的数量上限");
      }
      if (wanted < 2 || wanted > MAX_LIMIT)
        throw fail(422, "VALIDATION_FAILED", "数量上限需在 2 到 " + MAX_LIMIT + " 之间");
    }
    var hostRows = store.transaction(c -> query(c,
        "SELECT hostname FROM host WHERE id=?", hostId));
    if (hostRows.isEmpty()) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    // Fetch one extra older sample so the oldest returned point still has a CPU delta.
    final int fetch = wanted + 1;
    var rows = store.transaction(c -> query(c, """
        SELECT recorded_at, cpu_total, cpu_idle_total, mem_total_kib, mem_available_kib, load1,
               uptime_seconds
        FROM host_metric_sample WHERE host_id=? ORDER BY recorded_at DESC LIMIT ?""",
        hostId, fetch));

    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (int index = rows.size() - 1; index >= 0; index--) {
      var row = rows.get(index);
      // Rows are newest-first, so the previous sample is the next index (never the other way).
      var previous = index + 1 >= rows.size() ? null : rows.get(index + 1);
      long recordedAt = ((Number) row.get("recorded_at")).longValue();
      Double cpuBusy = previous == null ? null : MetricsMath.cpuBusyPercent(
          ((Number) previous.get("cpu_total")).longValue(),
          ((Number) previous.get("cpu_idle_total")).longValue(),
          ((Number) row.get("cpu_total")).longValue(),
          ((Number) row.get("cpu_idle_total")).longValue());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("recordedAt", recordedAt);
      item.put("cpuBusyPercent", cpuBusy);
      item.put("memoryUsedPercent", MetricsMath.memoryUsedPercent(
          ((Number) row.get("mem_total_kib")).longValue(),
          ((Number) row.get("mem_available_kib")).longValue()));
      item.put("load1", ((Number) row.get("load1")).doubleValue());
      item.put("uptimeSeconds", ((Number) row.get("uptime_seconds")).longValue());
      items.add(item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("hostId", hostId.toString());
    body.put("hostname", String.valueOf(hostRows.getFirst().get("hostname")));
    body.put("items", items);
    body.put("limit", wanted);
    body.put("retentionDays", MetricsSampler.RETENTION.toDays());
    return body;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return value;
  }
}
