package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 告警通知渠道。规则本身只描述「什么情况算告警」，渠道决定「告警发生时通知谁」——
 * 目前只有 Webhook（POST 一份 JSON），不引入邮件/短信等需要外部凭据的通道。
 *
 * <p>URL 由管理员填写，属于管理员级能力；面板只校验 scheme 与长度，不把它当作可公开的链接。
 */
@Path("/api/v1/alert-channels")
@Produces(MediaType.APPLICATION_JSON)
public class AlertChannelResource {

  public record ChannelRequest(String name, String url, Boolean enabled,
                               Boolean notifyRecovery) {}

  @Inject AuthStore store;
  @Inject AlertNotifier notifier;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(c -> query(c, """
        SELECT ch.channel_id, ch.name, ch.kind, ch.url, ch.enabled, ch.notify_recovery,
               ch.last_status, ch.last_at, ch.last_error, ch.created_at, ch.updated_at,
               (SELECT COUNT(*) FROM alert_rule r WHERE r.channel_id = ch.channel_id) AS rule_count
        FROM alert_channel ch ORDER BY ch.created_at DESC LIMIT 100"""));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (var row : rows) items.add(view(row));
    return Map.of("items", items);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(ChannelRequest input) {
    var actor = session();
    UUID id = UUID.randomUUID();
    long now = System.currentTimeMillis();
    String name = name(input);
    String url = url(input);
    boolean enabled = input.enabled() == null || input.enabled();
    boolean notifyRecovery = input.notifyRecovery() == null || input.notifyRecovery();
    try {
      store.transaction(c -> {
        update(c, "INSERT INTO alert_channel (channel_id,name,kind,url,enabled,created_by,"
            + "created_at,updated_at,notify_recovery) VALUES (?,?,?,?,?,?,?,?,?)",
            id, name, "WEBHOOK", url, enabled, actor.get("user_id"), now, now, notifyRecovery);
        audit(c, String.valueOf(actor.get("user_id")), "alert-channel.create",
            "alert_channel", id.toString(), "SUCCEEDED", clientIp(), "登记告警通知渠道 " + name);
        return null;
      });
    } catch (RuntimeException conflict) {
      if (!uniqueViolation(conflict))
        throw conflict;
      throw fail(409, "CHANNEL_CONFLICT", "同名通知渠道已存在");
    }
    return Response.status(201).entity(load(id)).build();
  }

  @PUT
  @Path("{channelId}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response edit(@PathParam("channelId") String channelValue, ChannelRequest input) {
    var actor = session();
    UUID id = uuid(channelValue);
    load(id);
    String name = input == null || input.name() == null ? null : name(input);
    String url = input == null || input.url() == null ? null : url(input);
    Boolean enabled = input == null ? null : input.enabled();
    Boolean notifyRecovery = input == null ? null : input.notifyRecovery();
    if (name == null && url == null && enabled == null && notifyRecovery == null)
      throw fail(422, "VALIDATION_FAILED", "没有需要更新的字段");
    try {
      store.transaction(c -> {
        update(c, "UPDATE alert_channel SET name=COALESCE(?,name), url=COALESCE(?,url), "
                + "enabled=COALESCE(?,enabled), notify_recovery=COALESCE(?,notify_recovery), "
                + "updated_at=? WHERE channel_id=?",
            name, url, enabled, notifyRecovery, System.currentTimeMillis(), id);
        audit(c, String.valueOf(actor.get("user_id")), "alert-channel.update",
            "alert_channel", id.toString(), "SUCCEEDED", clientIp(), "更新告警通知渠道");
        return null;
      });
    } catch (RuntimeException conflict) {
      if (!uniqueViolation(conflict))
        throw conflict;
      throw fail(409, "CHANNEL_CONFLICT", "同名通知渠道已存在");
    }
    return Response.ok(load(id)).build();
  }

  @DELETE
  @Path("{channelId}")
  public Response delete(@PathParam("channelId") String channelValue) {
    var actor = session();
    UUID id = uuid(channelValue);
    load(id);
    store.transaction(c -> {
      // Rules keep working; they simply stop notifying once their channel is gone.
      update(c, "UPDATE alert_rule SET channel_id=NULL WHERE channel_id=?", id);
      update(c, "DELETE FROM alert_firing_state WHERE channel_id=?", id);
      update(c, "DELETE FROM alert_channel WHERE channel_id=?", id);
      audit(c, String.valueOf(actor.get("user_id")), "alert-channel.delete",
          "alert_channel", id.toString(), "SUCCEEDED", clientIp(), "删除告警通知渠道");
      return null;
    });
    return Response.noContent().build();
  }

  /** Sends a sample payload so an operator can verify the endpoint before trusting it. */
  @POST
  @Path("{channelId}/test")
  public Response test(@PathParam("channelId") String channelValue) {
    var actor = session();
    UUID id = uuid(channelValue);
    Map<String, Object> channel = load(id);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", "alert.test");
    payload.put("channel", channel.get("name"));
    payload.put("severity", "WARNING");
    payload.put("message", "NaNPanel 通知渠道连通性测试");
    payload.put("sentAt", Instant.now().toString());
    payload.put("source", "nanpanel");
    var delivery = notifier.deliver(String.valueOf(channel.get("url")), payload);
    record(id, delivery);
    store.transaction(c -> {
      audit(c, String.valueOf(actor.get("user_id")), "alert-channel.test",
          "alert_channel", id.toString(), delivery.succeeded() ? "SUCCEEDED" : "FAILED",
          clientIp(), "测试通知渠道 " + channel.get("name"));
      return null;
    });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", delivery.succeeded() ? "SUCCEEDED" : "FAILED");
    body.put("error", delivery.error());
    return Response.status(delivery.succeeded() ? 200 : 502).entity(body).build();
  }

  private void record(UUID id, AlertNotifier.Delivery delivery) {
    store.transaction(c -> update(c,
        "UPDATE alert_channel SET last_status=?, last_at=?, last_error=?, updated_at=? "
            + "WHERE channel_id=?", delivery.succeeded() ? "SUCCEEDED" : "FAILED",
        System.currentTimeMillis(), truncate(delivery.error()), System.currentTimeMillis(), id));
  }

  private Map<String, Object> load(UUID id) {
    var rows = store.transaction(c -> query(c, """
        SELECT ch.channel_id, ch.name, ch.kind, ch.url, ch.enabled, ch.notify_recovery,
               ch.last_status, ch.last_at, ch.last_error, ch.created_at, ch.updated_at,
               (SELECT COUNT(*) FROM alert_rule r WHERE r.channel_id = ch.channel_id) AS rule_count
        FROM alert_channel ch WHERE ch.channel_id=?""", id));
    if (rows.isEmpty()) throw fail(404, "CHANNEL_NOT_FOUND", "通知渠道不存在");
    return view(rows.getFirst());
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", String.valueOf(row.get("channel_id")));
    item.put("name", String.valueOf(row.get("name")));
    item.put("kind", String.valueOf(row.get("kind")));
    item.put("url", String.valueOf(row.get("url")));
    item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
    item.put("notifyRecovery", !Boolean.FALSE.equals(row.get("notify_recovery")));
    item.put("lastStatus", row.get("last_status") == null ? null
        : String.valueOf(row.get("last_status")));
    item.put("lastAt", row.get("last_at") == null ? null
        : ((Number) row.get("last_at")).longValue());
    item.put("lastError", row.get("last_error") == null ? null
        : String.valueOf(row.get("last_error")));
    item.put("ruleCount", row.get("rule_count") == null ? 0
        : ((Number) row.get("rule_count")).intValue());
    item.put("createdAt", ((Number) row.get("created_at")).longValue());
    item.put("updatedAt", ((Number) row.get("updated_at")).longValue());
    return item;
  }

  private static String name(ChannelRequest input) {
    if (input == null || input.name() == null)
      throw fail(422, "VALIDATION_FAILED", "渠道名称必填");
    String name = input.name().trim();
    if (name.isEmpty() || name.codePointCount(0, name.length()) > 120)
      throw fail(422, "VALIDATION_FAILED", "渠道名称需为 1–120 字符");
    return name;
  }

  private static String url(ChannelRequest input) {
    if (input == null || input.url() == null)
      throw fail(422, "VALIDATION_FAILED", "Webhook 地址必填");
    String url = input.url().trim();
    if (url.isEmpty() || url.length() > 500)
      throw fail(422, "VALIDATION_FAILED", "Webhook 地址长度需在 1–500 之间");
    if (!url.startsWith("http://") && !url.startsWith("https://"))
      throw fail(422, "VALIDATION_FAILED", "Webhook 地址必须为 http:// 或 https://");
    try {
      URI parsed = URI.create(url);
      if (parsed.getHost() == null || parsed.getHost().isBlank())
        throw fail(422, "VALIDATION_FAILED", "Webhook 地址缺少主机");
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "VALIDATION_FAILED", "Webhook 地址无法解析");
    }
    return url;
  }

  private UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "无效的渠道标识");
    }
  }

  private static boolean uniqueViolation(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql
          && "23505".equals(sql.getSQLState())) return true;
    }
    return false;
  }

  private static String truncate(String value) {
    if (value == null) return null;
    return value.length() <= 300 ? value : value.substring(0, 300);
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
