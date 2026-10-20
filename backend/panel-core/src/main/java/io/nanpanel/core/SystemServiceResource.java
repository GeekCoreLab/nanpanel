package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class SystemServiceResource {
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private void authenticated() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
  }

  private UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  @GET
  @Path("hosts/{hostId}/services")
  public Response list(@PathParam("hostId") String hostId,
                       @QueryParam("limit") Integer requestedLimit,
                       @QueryParam("cursor") String cursor) {
    authenticated();
    UUID host = uuid(hostId, "hostId");
    int limit = requestedLimit == null ? 20 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    UUID after = decodeCursor(cursor);
    return store.transaction(c -> {
      var hosts = query(c, "SELECT id FROM host WHERE id=?", host);
      if (hosts.isEmpty()) throw fail(404, "NOT_FOUND", "主机不存在");
      String capabilities = "h.capabilities" +
          (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
              ? "::text" : "") + " AS capabilities,";
      var rows = query(c,
          "SELECT s.id,s.host_id,s.unit_name,s.display_name,s.active_state,s.sub_state," +
              "s.observed_at,h.generation," + capabilities +
              "(SELECT MAX(a.last_seen) FROM agent a WHERE a.host_id=h.id) AS last_seen," +
              "p.host_offline_after_seconds " +
              "FROM system_service_observation s JOIN host h ON h.id=s.host_id " +
              "JOIN platform_settings p ON p.id=1 WHERE s.host_id=?" +
              (after == null ? "" : " AND s.id>?") + " ORDER BY s.id LIMIT ?",
          after == null ? new Object[] {host, limit + 1} : new Object[] {host, after, limit + 1});
      int offlineAfter = offlineAfter(c);
      Instant now = Instant.now();
      boolean more = rows.size() > limit;
      var selected = rows.stream().limit(limit).toList();
      var items = selected.stream()
          .map(row -> service(row, offlineAfter, now))
          .toList();
      String nextCursor = null;
      if (more) {
        String id = String.valueOf(selected.getLast().get("id"));
        nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
            id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      var body = new LinkedHashMap<String, Object>();
      body.put("items", items);
      body.put("nextCursor", nextCursor);
      return Response.ok(body).build();
    });
  }

  @GET
  @Path("hosts/{hostId}/services/{serviceId}")
  public Response get(@PathParam("hostId") String hostId,
                      @PathParam("serviceId") String serviceId) {
    authenticated();
    UUID host = uuid(hostId, "hostId");
    UUID service = uuid(serviceId, "serviceId");
    return store.transaction(c -> {
      String capabilities = "h.capabilities" +
          (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
              ? "::text" : "") + " AS capabilities,";
      var rows = query(c,
          "SELECT s.id,s.host_id,s.unit_name,s.display_name,s.active_state,s.sub_state," +
              "s.observed_at,h.generation," + capabilities +
              "(SELECT MAX(a.last_seen) FROM agent a WHERE a.host_id=h.id) AS last_seen," +
              "p.host_offline_after_seconds " +
              "FROM system_service_observation s JOIN host h ON h.id=s.host_id " +
              "JOIN platform_settings p ON p.id=1 WHERE s.host_id=? AND s.id=?",
          host, service);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "systemd 服务不存在");
      var row = rows.getFirst();
      int offlineAfter = ((Number)row.get("host_offline_after_seconds")).intValue();
      var body = service(row, offlineAfter, Instant.now());
      return Response.ok(body).tag(etag(body)).build();
    });
  }

  Map<String, Object> service(Map<String, Object> row, int offlineAfter,
                               Instant now) {
    Instant lastSeen = instant(row.get("last_seen"));
    Instant observedAt = instant(row.get("observed_at"));
    boolean stale = lastSeen == null || lastSeen.isBefore(now.minusSeconds(offlineAfter)) ||
        observedAt == null || observedAt.isBefore(now.minusSeconds(offlineAfter));
    var value = new LinkedHashMap<String, Object>();
    value.put("id", String.valueOf(row.get("id")));
    value.put("hostId", String.valueOf(row.get("host_id")));
    value.put("unitName", row.get("unit_name"));
    value.put("displayName", row.get("display_name"));
    value.put("activeState", stale ? "UNKNOWN" : row.get("active_state"));
    value.put("subState", stale ? null : row.get("sub_state"));
    value.put("generation", ((Number)row.get("generation")).longValue());
    value.put("observedAt", observedAt == null ? null : observedAt.toString());
    var supportedActions = new java.util.ArrayList<String>();
    boolean observed = !stale && !"UNKNOWN".equals(row.get("active_state"));
    if (observed && supports(row, "systemd.restart")) supportedActions.add("RESTART");
    if (observed && supports(row, "systemd.journal.read")) supportedActions.add("READ_JOURNAL");
    value.put("supportedActions", List.copyOf(supportedActions));
    value.put("observationStale", stale);
    return value;
  }

  private boolean supports(Map<String, Object> row, String capability) {
    try {
      var capabilities = mapper.readTree(String.valueOf(row.get("capabilities")));
      if (!capabilities.isArray()) return false;
      for (var value : capabilities)
        if (capability.equals(value.asText())) return true;
      return false;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  String etag(Map<String, Object> body) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body)));
  }

  private int offlineAfter(java.sql.Connection c) throws Exception {
    var rows = query(c,
        "SELECT host_offline_after_seconds FROM platform_settings WHERE id=1");
    return rows.isEmpty() ? 90 : ((Number)rows.getFirst().get("host_offline_after_seconds")).intValue();
  }

  private UUID decodeCursor(String cursor) {
    if (cursor == null) return null;
    try {
      String value = new String(Base64.getUrlDecoder().decode(cursor),
          java.nio.charset.StandardCharsets.UTF_8);
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "INVALID_CURSOR", "分页游标无效");
    }
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    throw new IllegalStateException("Unsupported timestamp value");
  }
}
