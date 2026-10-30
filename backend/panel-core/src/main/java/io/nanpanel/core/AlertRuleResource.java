package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alert rule definitions over observations NaNPanel already collects. Rules are
 * declarative; {@link AlertResource} evaluates them live against host, helper and
 * systemd state, so no alert state machine or notification fan-out is implied here.
 */
@Path("/api/v1/alert-rules")
@Produces(MediaType.APPLICATION_JSON)
public class AlertRuleResource {

  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record RuleRequest(String name, String condition, String hostId, String unitName,
                            String severity, Boolean enabled, String channelId) {}

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(c -> query(c, """
        SELECT r.rule_id, r.name, r.condition_kind, r.host_id, h.hostname, r.unit_name,
               r.severity, r.enabled, r.channel_id, ch.name AS channel_name
        FROM alert_rule r LEFT JOIN host h ON h.id = r.host_id
        LEFT JOIN alert_channel ch ON ch.channel_id = r.channel_id
        ORDER BY r.created_at DESC LIMIT 200"""));
    var items = new ArrayList<Map<String, Object>>(rows.size());
    for (var row : rows) items.add(map(row));
    return Map.of("items", items);
  }

  @POST
  public Response create(RuleRequest input) {
    Map<String, Object> actor = session();
    if (input == null || input.name() == null || input.name().isBlank()
        || input.name().length() > 120 || input.name().chars().anyMatch(Character::isISOControl)
        || !AlertEvaluator.CONDITIONS.contains(input.condition())
        || !AlertEvaluator.SEVERITIES.contains(input.severity())) {
      throw fail(422, "VALIDATION_FAILED", "告警名称、条件或级别无效");
    }
    boolean serviceRule = AlertEvaluator.SERVICE_INACTIVE.equals(input.condition());
    if (serviceRule != (input.unitName() != null && !input.unitName().isBlank())
        || (input.unitName() != null && (input.unitName().length() > 255
            || !input.unitName().matches("[A-Za-z0-9:_.@-]{1,255}")))) {
      throw fail(422, "VALIDATION_FAILED", "SERVICE_INACTIVE 规则必须且只能指定一个 unit 名称");
    }
    UUID hostId = null;
    if (input.hostId() != null && !input.hostId().isBlank()) {
      try {
        hostId = UUID.fromString(input.hostId());
      } catch (RuntimeException invalid) {
        throw fail(422, "VALIDATION_FAILED", "hostId 必须是 UUID");
      }
      final UUID scoped = hostId;
      var hosts = store.transaction(c -> query(c, "SELECT id FROM host WHERE id = ?", scoped));
      if (hosts.isEmpty()) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    }
    final UUID ruleHost = hostId;
    final String unit = serviceRule ? input.unitName() : null;
    UUID channel = null;
    if (input.channelId() != null && !input.channelId().isBlank()) {
      try {
        channel = UUID.fromString(input.channelId());
      } catch (RuntimeException invalid) {
        throw fail(422, "VALIDATION_FAILED", "channelId 必须是 UUID");
      }
      final UUID scope = channel;
      var channels = store.transaction(c -> query(c,
          "SELECT channel_id FROM alert_channel WHERE channel_id = ?", scope));
      if (channels.isEmpty()) throw fail(404, "CHANNEL_NOT_FOUND", "通知渠道不存在");
    }
    final UUID ruleChannel = channel;
    var duplicates = store.transaction(c -> query(c,
        "SELECT rule_id FROM alert_rule WHERE condition_kind = ? AND "
            + "((host_id IS NULL AND ? IS NULL) OR host_id = ?) AND "
            + "((unit_name IS NULL AND ? IS NULL) OR unit_name = ?)",
        input.condition(), ruleHost, ruleHost, unit, unit));
    if (!duplicates.isEmpty()) {
      throw fail(409, "ALERT_RULE_DUPLICATE", "相同条件、主机与 unit 的告警规则已存在");
    }
    UUID ruleId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    String actorId = String.valueOf(actor.get("user_id"));
    store.transaction(c -> {
      update(c, """
          INSERT INTO alert_rule (rule_id, name, condition_kind, host_id, unit_name, severity,
            enabled, created_by, created_at, channel_id) VALUES (?,?,?,?,?,?,?,?,?,?)""",
          ruleId, input.name().trim(), input.condition(), ruleHost, unit, input.severity(),
          input.enabled() == null || input.enabled(), actorId, now, ruleChannel);
      audit(c, actorId, "alert-rule.create", "alert_rule", ruleId.toString(), "SUCCEEDED",
          clientIp(), "创建告警规则 " + input.name().trim());
      return null;
    });
    return Response.status(201).entity(byId(ruleId)).build();
  }

  @DELETE
  @Path("{ruleId}")
  public Response delete(@PathParam("ruleId") String value) {
    Map<String, Object> actor = session();
    UUID ruleId;
    try {
      ruleId = UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "ruleId 必须是 UUID");
    }
    if (byId(ruleId) == null) throw fail(404, "ALERT_RULE_NOT_FOUND", "告警规则不存在");
    String actorId = String.valueOf(actor.get("user_id"));
    store.transaction(c -> {
      update(c, "DELETE FROM alert_rule WHERE rule_id = ?", ruleId);
      audit(c, actorId, "alert-rule.delete", "alert_rule", ruleId.toString(), "SUCCEEDED",
          clientIp(), "删除告警规则");
      return null;
    });
    return Response.noContent().build();
  }

  private Map<String, Object> byId(UUID ruleId) {
    var rows = store.transaction(c -> query(c, """
        SELECT r.rule_id, r.name, r.condition_kind, r.host_id, h.hostname, r.unit_name,
               r.severity, r.enabled, r.channel_id, ch.name AS channel_name
        FROM alert_rule r LEFT JOIN host h ON h.id = r.host_id
        LEFT JOIN alert_channel ch ON ch.channel_id = r.channel_id WHERE r.rule_id = ?""", ruleId));
    return rows.isEmpty() ? null : map(rows.get(0));
  }

  private static Map<String, Object> map(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", String.valueOf(row.get("rule_id")));
    item.put("name", String.valueOf(row.get("name")));
    item.put("condition", String.valueOf(row.get("condition_kind")));
    item.put("hostId", row.get("host_id") == null ? null : String.valueOf(row.get("host_id")));
    item.put("hostname", row.get("hostname") == null ? null : String.valueOf(row.get("hostname")));
    item.put("unitName", row.get("unit_name") == null ? null : String.valueOf(row.get("unit_name")));
    item.put("severity", String.valueOf(row.get("severity")));
    item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
    item.put("channelId", row.get("channel_id") == null ? null
        : String.valueOf(row.get("channel_id")));
    item.put("channelName", row.get("channel_name") == null ? null
        : String.valueOf(row.get("channel_name")));
    return item;
  }
}
