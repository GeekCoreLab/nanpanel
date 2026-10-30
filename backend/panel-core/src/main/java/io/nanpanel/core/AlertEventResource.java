package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 告警通知历史：只读。实时告警随观测恢复就消失，历史则回答「当时有没有通知出去、成功没有」——
 * 每条记录对应一次实际投递尝试（失败也会留下 FAILED 记录）。
 */
@Path("/api/v1/alert-events")
@Produces(MediaType.APPLICATION_JSON)
public class AlertEventResource {
  private static final Set<String> KINDS = Set.of("FIRING", "RECOVERED");
  private static final int DEFAULT_LIMIT = 30;
  private static final int MAX_LIMIT = 200;

  @Inject AuthStore store;
  @Context ContainerRequestContext request;

  @GET
  public Map<String, Object> list(@QueryParam("kind") String kind,
      @QueryParam("limit") String limit) {
    session();
    String wantedKind = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
    if (wantedKind != null && !KINDS.contains(wantedKind))
      throw fail(422, "VALIDATION_FAILED", "未知的告警事件类型");
    int wantedLimit = DEFAULT_LIMIT;
    if (limit != null && !limit.isBlank()) {
      try {
        wantedLimit = Integer.parseInt(limit.trim());
      } catch (NumberFormatException invalid) {
        throw fail(422, "VALIDATION_FAILED", "无效的数量上限");
      }
      if (wantedLimit < 1 || wantedLimit > MAX_LIMIT)
        throw fail(422, "VALIDATION_FAILED", "数量上限需在 1 到 " + MAX_LIMIT + " 之间");
    }

    StringBuilder sql = new StringBuilder("""
        SELECT event_id, kind, rule_name, channel_name, severity, condition_kind, hostname,
               detail, status, error, created_at
        FROM alert_event WHERE 1=1""");
    List<Object> parameters = new ArrayList<>();
    if (wantedKind != null) {
      sql.append(" AND kind=?");
      parameters.add(wantedKind);
    }
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    parameters.add(wantedLimit);
    final String statement = sql.toString();
    final Object[] bound = parameters.toArray();
    var rows = store.transaction(c -> query(c, statement, bound));

    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (var row : rows) items.add(view(row));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("limit", wantedLimit);
    body.put("kinds", KINDS.stream().sorted().toList());
    return body;
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", String.valueOf(row.get("event_id")));
    item.put("kind", String.valueOf(row.get("kind")));
    item.put("ruleName", String.valueOf(row.get("rule_name")));
    item.put("channelName", row.get("channel_name") == null ? null
        : String.valueOf(row.get("channel_name")));
    item.put("severity", String.valueOf(row.get("severity")));
    item.put("condition", String.valueOf(row.get("condition_kind")));
    item.put("hostname", row.get("hostname") == null ? null
        : String.valueOf(row.get("hostname")));
    item.put("detail", row.get("detail") == null ? null : String.valueOf(row.get("detail")));
    item.put("status", String.valueOf(row.get("status")));
    item.put("error", row.get("error") == null ? null : String.valueOf(row.get("error")));
    item.put("createdAt", ((Number) row.get("created_at")).longValue());
    return item;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return value;
  }
}
