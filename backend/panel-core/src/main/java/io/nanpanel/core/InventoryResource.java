package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class InventoryResource {
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private void authenticated() {
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
  }

  private UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value))
        throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  private int offlineAfter(java.sql.Connection c) throws Exception {
    var settings = query(c,
                         "SELECT host_offline_after_seconds FROM " +
                             "platform_settings WHERE id=1");
    return settings.isEmpty()
        ? 90
        : ((Number)settings.getFirst().get("host_offline_after_seconds"))
              .intValue();
  }

  private String hostSelect(java.sql.Connection connection) throws java.sql.SQLException {
    boolean postgres = connection.getMetaData().getDatabaseProductName()
        .equalsIgnoreCase("PostgreSQL");
    String capabilities = postgres ? "h.capabilities::text" : "h.capabilities";
    String host = "SELECT h.id,h.hostname,h.os,h.arch," + capabilities +
        " AS capabilities,h.created_at,";
    if (postgres)
      return host + "a.id AS agent_id,a.version,a.last_seen FROM host h " +
          "LEFT JOIN LATERAL (SELECT id,version,last_seen FROM agent " +
          "WHERE host_id=h.id ORDER BY last_seen DESC NULLS LAST,id LIMIT 1) a " +
          "ON TRUE";
    String latestAgent = " FROM agent a WHERE a.host_id=h.id " +
        "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1)";
    return host + "(SELECT a.id" + latestAgent + " AS agent_id," +
        "(SELECT a.version" + latestAgent + " AS version," +
        "(SELECT a.last_seen" + latestAgent + " AS last_seen FROM host h";
  }

  private Instant instant(Object value) {
    if (value == null)
      return null;
    if (value instanceof java.sql.Timestamp timestamp)
      return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp)
      return timestamp.toInstant();
    if (value instanceof Instant timestamp)
      return timestamp;
    if (value instanceof java.util.Date timestamp)
      return timestamp.toInstant();
    if (value instanceof Number timestamp)
      return Instant.ofEpochMilli(timestamp.longValue());
    throw new IllegalStateException("Unsupported timestamp value");
  }

  private String timestamp(Object value) {
    Instant time = instant(value);
    return time == null ? null : time.toString();
  }

  private String status(Object lastSeen, int offlineAfter, Instant now) {
    Instant seen = instant(lastSeen);
    if (seen == null)
      return "UNKNOWN";
    return seen.isBefore(now.minusSeconds(offlineAfter)) ? "OFFLINE" : "ONLINE";
  }

  private List<String> capabilities(Object value) {
    try {
      JsonNode node = mapper.readTree(String.valueOf(value));
      if (node == null || !node.isArray())
        throw new IllegalStateException("Host capabilities must be a JSON array");
      var result = new ArrayList<String>();
      for (JsonNode capability : node) {
        if (!capability.isTextual() || capability.asText().isBlank())
          throw new IllegalStateException("Invalid host capability value");
        result.add(capability.asText());
      }
      return List.copyOf(result);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Unable to read host capabilities", e);
    }
  }

  private Map<String, Object> host(Map<String, Object> row, int offlineAfter,
                                   Instant now) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", String.valueOf(row.get("id")));
    value.put("hostname", row.get("hostname"));
    value.put("os", row.get("os"));
    value.put("arch", row.get("arch"));
    value.put("status", status(row.get("last_seen"), offlineAfter, now));
    value.put("capabilities", capabilities(row.get("capabilities")));
    value.put("agentId", row.get("agent_id") == null
        ? null
        : String.valueOf(row.get("agent_id")));
    value.put("lastSeenAt", timestamp(row.get("last_seen")));
    value.put("createdAt", timestamp(row.get("created_at")));
    return value;
  }

  @GET
  @Path("hosts")
  public Response listHosts(@QueryParam("limit") Integer requestedLimit,
                            @QueryParam("cursor") String cursor,
                            @QueryParam("status") String filter) {
    authenticated();
    int limit = requestedLimit == null ? 20 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    if (filter != null && !Set.of("UNKNOWN", "ONLINE", "OFFLINE").contains(filter))
      throw fail(400, "VALIDATION_FAILED", "无效主机状态筛选");
    UUID after = null;
    if (cursor != null) {
      try {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                                    java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 2 ||
            !Objects.equals(parts[1], filter == null ? "" : filter))
          throw new IllegalArgumentException();
        after = UUID.fromString(parts[0]);
        if (!after.toString().equalsIgnoreCase(parts[0]))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw fail(400, "INVALID_CURSOR", "分页游标无效或筛选条件已变化");
      }
    }
    final UUID afterId = after;
    final int pageSize = limit;
    final String statusFilter = filter;
    return store.transaction(c -> {
      int threshold = offlineAfter(c);
      Instant now = Instant.now();
      var clauses = new ArrayList<String>();
      var values = new ArrayList<Object>();
      if ("UNKNOWN".equals(statusFilter)) {
        clauses.add("(a.id IS NULL OR a.last_seen IS NULL)");
      } else if ("ONLINE".equals(statusFilter)) {
        clauses.add("a.last_seen>=?");
        values.add(java.sql.Timestamp.from(now.minusSeconds(threshold)));
      } else if ("OFFLINE".equals(statusFilter)) {
        clauses.add("a.last_seen<?");
        values.add(java.sql.Timestamp.from(now.minusSeconds(threshold)));
      }
      if (afterId != null) {
        clauses.add("h.id>?");
        values.add(afterId);
      }
      String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
      values.add(pageSize + 1);
      var rows = query(c, hostSelect(c) + where + " ORDER BY h.id LIMIT ?",
                       values.toArray());
      boolean more = rows.size() > pageSize;
      var selected = rows.stream().limit(pageSize).toList();
      var items = selected.stream().map(row -> host(row, threshold, now)).toList();
      String next = null;
      if (more) {
        String lastId = String.valueOf(selected.getLast().get("id"));
        String payload = lastId + "|" + (statusFilter == null ? "" : statusFilter);
        next = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      var body = new LinkedHashMap<String, Object>();
      body.put("items", items);
      body.put("nextCursor", next);
      return Response.ok(body).build();
    });
  }

  @GET
  @Path("hosts/{hostId}")
  public Object getHost(@PathParam("hostId") String hostId) {
    authenticated();
    UUID id = uuid(hostId, "hostId");
    return store.transaction(c -> {
      var rows = query(c, hostSelect(c) + " WHERE h.id=?", id);
      if (rows.isEmpty())
        throw fail(404, "NOT_FOUND", "主机不存在");
      return host(rows.getFirst(), offlineAfter(c), Instant.now());
    });
  }

  @GET
  @Path("agents/{agentId}")
  public Object getAgent(@PathParam("agentId") String agentId) {
    authenticated();
    UUID id = uuid(agentId, "agentId");
    return store.transaction(c -> {
      var rows = query(
          c,
          "SELECT a.id AS agent_id,a.host_id,a.version,a.last_seen," +
              "h.capabilities" + (c.getMetaData().getDatabaseProductName()
                  .equalsIgnoreCase("PostgreSQL") ? "::text" : "") + " AS capabilities FROM agent a " +
              "JOIN host h ON h.id=a.host_id WHERE a.id=?",
          id);
      if (rows.isEmpty())
        throw fail(404, "NOT_FOUND", "Agent 不存在");
      var row = rows.getFirst();
      int threshold = offlineAfter(c);
      var result = new LinkedHashMap<String, Object>();
      result.put("id", String.valueOf(row.get("agent_id")));
      result.put("hostId", String.valueOf(row.get("host_id")));
      result.put("version", row.get("version"));
      result.put("implementation", "rust");
      result.put("status", status(row.get("last_seen"), threshold, Instant.now()));
      result.put("lastSeenAt", timestamp(row.get("last_seen")));
      result.put("capabilities", capabilities(row.get("capabilities")));
      result.put("helperStatus", "UNKNOWN");
      return result;
    });
  }
}
