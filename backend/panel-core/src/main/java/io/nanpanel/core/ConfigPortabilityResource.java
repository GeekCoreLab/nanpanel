package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 面板配置的导出/导入（站点、告警规则、通知渠道）。
 *
 * <p>导出文档带版本号与被校验的摘要，导入时先验版本与摘要再逐条落地。**不导出任何凭据**：
 * 证书只在主机受管目录、通知渠道不带密钥、主机用主机名而不是 UUID 引用，因此同一份文档可以
 * 在另一台面板上导入（跨机器不重配）。
 *
 * <p>只覆盖「定义」不覆盖「运行时」：站点一律以 `DRAFT` 导入，不会因为导入就向主机下发路由。
 */
@Path("/api/v1/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigPortabilityResource {
  static final int VERSION = 1;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern DOMAIN = Pattern.compile(
      "(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

  @Inject AuthStore store;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  @Path("export")
  public Response export() {
    var actor = session();
    ObjectNode root = JSON.createObjectNode();
    root.put("version", VERSION);
    root.put("exportedAt", Instant.now().toString());
    root.set("alertChannels", store.transaction(c -> channels(c)));
    root.set("alertRules", store.transaction(c -> rules(c)));
    root.set("sites", store.transaction(c -> sites(c)));
    root.put("checksum", checksum(root));
    store.transaction(c -> {
      audit(c, String.valueOf(actor.get("user_id")), "config.export", "panel_config", null,
          "SUCCEEDED", clientIp(), "导出面板配置（站点/告警规则/通知渠道）");
      return null;
    });
    return Response.ok(root.toPrettyString())
        .header("Content-Disposition", "attachment; filename=\"nanpanel-config.json\"")
        .build();
  }

  @POST
  @Path("import")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response importConfig(@QueryParam("strategy") String strategy, JsonNode body) {
    var actor = session();
    String mode = strategy == null || strategy.isBlank() ? "skip"
        : strategy.trim().toLowerCase(java.util.Locale.ROOT);
    if (!mode.equals("skip") && !mode.equals("overwrite"))
      throw fail(422, "VALIDATION_FAILED", "strategy 只能是 skip 或 overwrite");
    if (body == null || !body.isObject())
      throw fail(422, "VALIDATION_FAILED", "导入内容必须是配置文档");
    int version = body.path("version").asInt(0);
    if (version != VERSION)
      throw fail(422, "EXPORT_VERSION_UNSUPPORTED", "不支持的配置文档版本：" + version);
    String expected = body.path("checksum").asText("");
    ObjectNode payload = ((ObjectNode) body).deepCopy();
    payload.remove("checksum");
    if (!checksum(payload).equals(expected))
      throw fail(422, "EXPORT_CHECKSUM_MISMATCH", "配置文档校验和不匹配，可能已被修改");

    String actorId = String.valueOf(actor.get("user_id"));
    long now = System.currentTimeMillis();
    List<Map<String, Object>> failed = new ArrayList<>();
    int imported = 0;
    int skipped = 0;

    // Channels first: rules reference them by name.
    for (JsonNode item : payload.path("alertChannels")) {
      String name = text(item.path("name"));
      String url = text(item.path("url"));
      if (!validChannel(name, url)) {
        failed.add(failure("channel", name, "名称或 Webhook 地址无效"));
        continue;
      }
      boolean exists = !store.transaction(c -> query(c,
          "SELECT channel_id FROM alert_channel WHERE name=?", name)).isEmpty();
      if (exists && mode.equals("skip")) {
        skipped++;
        continue;
      }
      boolean enabled = !item.path("enabled").isBoolean() || item.path("enabled").asBoolean();
      boolean notifyRecovery = !item.path("notifyRecovery").isBoolean()
          || item.path("notifyRecovery").asBoolean();
      store.transaction(c -> {
        if (exists) {
          update(c, "UPDATE alert_channel SET url=?, enabled=?, notify_recovery=?, updated_at=? "
              + "WHERE name=?", url, enabled, notifyRecovery, now, name);
        } else {
          update(c, "INSERT INTO alert_channel (channel_id,name,kind,url,enabled,created_by,"
                  + "created_at,updated_at,notify_recovery) VALUES (?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), name, "WEBHOOK", url, enabled, actorId, now, now, notifyRecovery);
        }
        return null;
      });
      imported++;
    }

    for (JsonNode item : payload.path("sites")) {
      String hostname = text(item.path("hostname"));
      String domain = text(item.path("domain"));
      int listenPort = item.path("listenPort").asInt(0);
      int upstreamPort = item.path("upstreamPort").asInt(0);
      String address = text(item.path("listenAddress"));
      String health = text(item.path("healthPath"));
      if (!validSite(hostname, domain, listenPort, upstreamPort, address, health)) {
        failed.add(failure("site", domain, "站点字段无效或主机缺失"));
        continue;
      }
      var hostRows = store.transaction(c -> query(c, "SELECT id FROM host WHERE hostname=?",
          hostname));
      if (hostRows.isEmpty()) {
        failed.add(failure("site", domain, "主机 " + hostname + " 未在本面板登记"));
        continue;
      }
      UUID hostId = UUID.fromString(String.valueOf(hostRows.getFirst().get("id")));
      UUID existing = store.transaction(c -> {
        var rows = query(c, "SELECT site_id, state FROM reverse_proxy_site "
            + "WHERE host_id=? AND domain=? AND listen_port=?", hostId, domain, listenPort);
        return rows.isEmpty() ? null : UUID.fromString(String.valueOf(rows.getFirst().get("site_id")));
      });
      if (existing != null && mode.equals("skip")) {
        skipped++;
        continue;
      }
      store.transaction(c -> {
        if (existing != null) {
          update(c, "UPDATE reverse_proxy_site SET listen_address=?, upstream_port=?, "
              + "health_path=?, updated_at=? WHERE site_id=?",
              address, upstreamPort, health, now, existing);
        } else {
          update(c, "INSERT INTO reverse_proxy_site (site_id,host_id,domain,listen_address,"
                  + "listen_port,upstream_port,health_path,state,created_by,created_at,updated_at) "
                  + "VALUES (?,?,?,?,?,?,?,'DRAFT',?,?,?)",
              UUID.randomUUID(), hostId, domain, address, listenPort, upstreamPort, health,
              actorId, now, now);
        }
        return null;
      });
      imported++;
    }

    for (JsonNode item : payload.path("alertRules")) {
      String name = text(item.path("name"));
      String condition = text(item.path("condition"));
      String severity = text(item.path("severity"));
      if (!AlertEvaluator.CONDITIONS.contains(condition)
          || !AlertEvaluator.SEVERITIES.contains(severity)
          || name == null || name.isBlank() || name.length() > 120) {
        failed.add(failure("rule", name, "条件、级别或名称无效"));
        continue;
      }
      UUID hostId = null;
      String hostname = text(item.path("hostname"));
      if (hostname != null && !hostname.isBlank()) {
        var hostRows = store.transaction(c -> query(c, "SELECT id FROM host WHERE hostname=?",
            hostname));
        if (hostRows.isEmpty()) {
          failed.add(failure("rule", name, "主机 " + hostname + " 未在本面板登记"));
          continue;
        }
        hostId = UUID.fromString(String.valueOf(hostRows.getFirst().get("id")));
      }
      UUID channelId = null;
      String channelName = text(item.path("channelName"));
      if (channelName != null && !channelName.isBlank()) {
        var channelRows = store.transaction(c -> query(c,
            "SELECT channel_id FROM alert_channel WHERE name=?", channelName));
        if (channelRows.isEmpty()) {
          failed.add(failure("rule", name, "通知渠道 " + channelName + " 不存在"));
          continue;
        }
        channelId = UUID.fromString(String.valueOf(channelRows.getFirst().get("channel_id")));
      }
      String unitName = "SERVICE_INACTIVE".equals(condition) ? text(item.path("unitName")) : null;
      boolean enabled = !item.path("enabled").isBoolean() || item.path("enabled").asBoolean();
      UUID existingRule = store.transaction(c -> {
        var rows = query(c, "SELECT rule_id FROM alert_rule WHERE name=?", name);
        return rows.isEmpty() ? null : UUID.fromString(String.valueOf(rows.getFirst().get("rule_id")));
      });
      if (existingRule != null && mode.equals("skip")) {
        skipped++;
        continue;
      }
      final UUID ruleHost = hostId;
      final UUID ruleChannel = channelId;
      final String ruleUnit = unitName;
      store.transaction(c -> {
        if (existingRule != null) {
          update(c, "UPDATE alert_rule SET condition_kind=?, host_id=?, unit_name=?, severity=?, "
                  + "enabled=?, channel_id=? WHERE rule_id=?",
              condition, ruleHost, ruleUnit, severity, enabled, ruleChannel, existingRule);
        } else {
          update(c, "INSERT INTO alert_rule (rule_id,name,condition_kind,host_id,unit_name,"
                  + "severity,enabled,created_by,created_at,channel_id) "
                  + "VALUES (?,?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(), name, condition, ruleHost, ruleUnit, severity, enabled,
              actorId, now, ruleChannel);
        }
        return null;
      });
      imported++;
    }

    final int importedCount = imported;
    final int skippedCount = skipped;
    final int failedCount = failed.size();
    final String outcome = failedCount == 0 ? "SUCCEEDED" : "FAILED";
    final String summary = "导入面板配置：成功 " + importedCount + "，跳过 " + skippedCount
        + "，失败 " + failedCount + "（策略 " + mode + "）";
    store.transaction(c -> {
      audit(c, actorId, "config.import", "panel_config", null, outcome, clientIp(), summary);
      return null;
    });
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("strategy", mode);
    report.put("imported", imported);
    report.put("skipped", skipped);
    report.put("failed", failed);
    return Response.ok(report).build();
  }

  private ArrayNode channels(java.sql.Connection c) throws Exception {
    ArrayNode items = JSON.createArrayNode();
    for (var row : query(c, "SELECT name, url, enabled, notify_recovery FROM alert_channel "
        + "ORDER BY created_at")) {
      ObjectNode item = items.addObject();
      item.put("name", String.valueOf(row.get("name")));
      item.put("url", String.valueOf(row.get("url")));
      item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
      item.put("notifyRecovery", !Boolean.FALSE.equals(row.get("notify_recovery")));
    }
    return items;
  }

  private ArrayNode rules(java.sql.Connection c) throws Exception {
    ArrayNode items = JSON.createArrayNode();
    for (var row : query(c, "SELECT r.name, r.condition_kind, r.unit_name, r.severity, r.enabled, "
        + "h.hostname, ch.name AS channel_name FROM alert_rule r "
        + "LEFT JOIN host h ON h.id=r.host_id "
        + "LEFT JOIN alert_channel ch ON ch.channel_id=r.channel_id ORDER BY r.created_at")) {
      ObjectNode item = items.addObject();
      item.put("name", String.valueOf(row.get("name")));
      item.put("condition", String.valueOf(row.get("condition_kind")));
      item.put("severity", String.valueOf(row.get("severity")));
      item.put("enabled", Boolean.TRUE.equals(row.get("enabled")));
      item.put("hostname", row.get("hostname") == null ? null : String.valueOf(row.get("hostname")));
      item.put("unitName", row.get("unit_name") == null ? null : String.valueOf(row.get("unit_name")));
      item.put("channelName", row.get("channel_name") == null
          ? null : String.valueOf(row.get("channel_name")));
    }
    return items;
  }

  private ArrayNode sites(java.sql.Connection c) throws Exception {
    ArrayNode items = JSON.createArrayNode();
    // Definitions only: state, TLS material and operation ids stay on the machine they belong to.
    for (var row : query(c, "SELECT h.hostname, s.domain, s.listen_address, s.listen_port, "
        + "s.upstream_port, s.health_path FROM reverse_proxy_site s "
        + "JOIN host h ON h.id=s.host_id ORDER BY s.created_at")) {
      ObjectNode item = items.addObject();
      item.put("hostname", String.valueOf(row.get("hostname")));
      item.put("domain", String.valueOf(row.get("domain")));
      item.put("listenAddress", String.valueOf(row.get("listen_address")));
      item.put("listenPort", ((Number) row.get("listen_port")).intValue());
      item.put("upstreamPort", ((Number) row.get("upstream_port")).intValue());
      item.put("healthPath", String.valueOf(row.get("health_path")));
    }
    return items;
  }

  /** SHA-256 over a key-sorted rendering, so a pretty-printed document keeps verifying. */
  static String checksum(JsonNode payload) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical(payload).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) hex.append(String.format("%02x", value));
      return hex.toString();
    } catch (Exception failure) {
      throw new IllegalStateException("cannot checksum the export document", failure);
    }
  }

  private static String canonical(JsonNode node) throws Exception {
    return JSON.writeValueAsString(sorted(node));
  }

  private static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = JSON.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) sorted.set(name, sorted(node.get(name)));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = JSON.createArrayNode();
      node.forEach(child -> array.add(sorted(child)));
      return array;
    }
    return node;
  }

  private static boolean validChannel(String name, String url) {
    return name != null && !name.isBlank() && name.length() <= 120
        && url != null && (url.startsWith("http://") || url.startsWith("https://"))
        && url.length() <= 500;
  }

  private static boolean validSite(String hostname, String domain, int listenPort, int upstreamPort,
      String address, String health) {
    return hostname != null && !hostname.isBlank()
        && domain != null && domain.length() <= 253 && DOMAIN.matcher(domain).matches()
        && listenPort >= 1 && listenPort <= 65535
        && upstreamPort >= 1 && upstreamPort <= 65535
        && address != null && !address.isBlank() && address.length() <= 64
        && health != null && health.startsWith("/") && health.length() <= 255;
  }

  private static Map<String, Object> failure(String kind, String name, String reason) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", kind);
    item.put("name", name);
    item.put("reason", reason);
    return item;
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
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
