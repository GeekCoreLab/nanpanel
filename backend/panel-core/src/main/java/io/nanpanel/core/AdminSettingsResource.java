package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthResource.stamp;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.time.ZoneId;
import java.util.*;

@Path("/api/v1/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
public class AdminSettingsResource {
  private static final Set<String> FIELDS = Set.of(
      "siteName", "defaultTimezone", "hostOfflineAfterSeconds",
      "operationRetentionDays");
  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private Map<String, Object> session() {
    var value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return value;
  }

  private void recent(Map<String, Object> value) {
    if (System.currentTimeMillis() - number(value, "authenticated_at") >
        300000) {
      store.transaction(c -> {
        audit(c, String.valueOf(value.get("user_id")),
              "security.reauthentication.required", "admin", null, "DENIED",
              http.remoteAddress().host(), "管理员写操作需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED",
                 "请重新验证密码后刷新设置，再确认提交");
    }
  }

  private String tag(Map<String, Object> row) {
    return "\"settings-v" + row.get("version") + "\"";
  }

  private Map<String, Object> ensure(java.sql.Connection c,
                                    Map<String, Object> actor)
      throws Exception {
    query(c, "SELECT id FROM auth_bootstrap_lock WHERE id=1 FOR UPDATE");
    var rows = query(c, "SELECT * FROM platform_settings WHERE id=1");
    if (!rows.isEmpty())
      return rows.getFirst();
    long now = System.currentTimeMillis();
    update(c,
           "INSERT INTO platform_settings " +
               "(id,site_name,default_timezone,host_offline_after_seconds," +
               "operation_retention_days,updated_at,updated_by,version) " +
               "VALUES (1,?,?,?,?,?,?,1)",
           "NaNPanel", "Asia/Shanghai", 90, 7, now, actor.get("user_id"));
    return query(c, "SELECT * FROM platform_settings WHERE id=1").getFirst();
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("siteName", row.get("site_name"));
    value.put("defaultTimezone", row.get("default_timezone"));
    value.put("hostOfflineAfterSeconds", row.get("host_offline_after_seconds"));
    value.put("operationRetentionDays", row.get("operation_retention_days"));
    value.put("updatedAt", stamp(row.get("updated_at")));
    value.put("updatedBy", row.get("updated_by"));
    return value;
  }

  @GET
  public Response get() {
    var actor = session();
    return store.transaction(c -> {
      var row = ensure(c, actor);
      return Response.ok(view(row)).header(HttpHeaders.ETAG, tag(row)).build();
    });
  }

  private Map<String, Object> validate(Map<String, Object> input) {
    if (input == null || input.isEmpty())
      throw fail(400, "VALIDATION_FAILED", "至少提供一个设置字段");
    for (var key : input.keySet())
      if (!FIELDS.contains(key))
        throw fail(400, "VALIDATION_FAILED", "不支持的设置字段：" + key);
    var result = new HashMap<String, Object>();
    for (var entry : input.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value == null)
        throw fail(400, "VALIDATION_FAILED", key + " 不能为 null");
      switch (key) {
        case "siteName" -> {
          if (!(value instanceof String text))
            throw fail(400, "VALIDATION_FAILED", "siteName 必须为文本");
          String normalized = text.trim();
          if (normalized.isEmpty() ||
              normalized.codePointCount(0, normalized.length()) > 100)
            throw fail(400, "VALIDATION_FAILED", "siteName 长度须为 1–100");
          result.put(key, normalized);
        }
        case "defaultTimezone" -> {
          if (!(value instanceof String text) ||
              !ZoneId.getAvailableZoneIds().contains(text))
            throw fail(400, "VALIDATION_FAILED", "defaultTimezone 必须为有效 IANA 时区");
          result.put(key, text);
        }
        case "hostOfflineAfterSeconds" ->
            result.put(key, integer(value, key, 30, 600));
        case "operationRetentionDays" ->
            result.put(key, integer(value, key, 7, 365));
        default -> throw fail(400, "VALIDATION_FAILED", "不支持的设置字段");
      }
    }
    return result;
  }

  private int integer(Object value, String field, int minimum, int maximum) {
    if (!(value instanceof Number number) ||
        number.doubleValue() != number.longValue() ||
        number.longValue() < minimum || number.longValue() > maximum)
      throw fail(400, "VALIDATION_FAILED",
                 field + " 必须为 " + minimum + "–" + maximum + " 的整数");
    return number.intValue();
  }

  @PATCH
  @Consumes(MediaType.APPLICATION_JSON)
  public Response patch(Map<String, Object> input) {
    var actor = session();
    recent(actor);
    var changes = validate(input);
    String supplied = request.getHeaderString(HttpHeaders.IF_MATCH);
    if (supplied == null)
      throw fail(428, "PRECONDITION_REQUIRED", "更新设置需要 If-Match");
    return store.transaction(c -> {
      var current = ensure(c, actor);
      if (!supplied.equals(tag(current)))
        throw fail(412, "PRECONDITION_FAILED", "设置已变化，请重新读取后确认");
      String siteName = String.valueOf(current.get("site_name"));
      String timezone = String.valueOf(current.get("default_timezone"));
      int offline = ((Number)current.get("host_offline_after_seconds")).intValue();
      int retention = ((Number)current.get("operation_retention_days")).intValue();
      if (changes.containsKey("siteName"))
        siteName = (String)changes.get("siteName");
      if (changes.containsKey("defaultTimezone"))
        timezone = (String)changes.get("defaultTimezone");
      if (changes.containsKey("hostOfflineAfterSeconds"))
        offline = (Integer)changes.get("hostOfflineAfterSeconds");
      if (changes.containsKey("operationRetentionDays"))
        retention = (Integer)changes.get("operationRetentionDays");
      long now = System.currentTimeMillis();
      update(c,
             "UPDATE platform_settings SET site_name=?,default_timezone=?," +
                 "host_offline_after_seconds=?,operation_retention_days=?," +
                 "updated_at=?,updated_by=?,version=version+1 WHERE id=1",
             siteName, timezone, offline, retention, now, actor.get("user_id"));
      audit(c, String.valueOf(actor.get("user_id")), "admin.settings.update",
            "settings", "1", "SUCCEEDED", http.remoteAddress().host(),
            "管理员更新平台设置");
      var updated = query(c, "SELECT * FROM platform_settings WHERE id=1")
                        .getFirst();
      return Response.ok(view(updated)).header(HttpHeaders.ETAG, tag(updated))
          .build();
    });
  }
}
